package no.nav.soknad.arkivering.arkiveringendtoendtests

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import no.nav.soknad.arkivering.arkiveringendtoendtests.dto.FilElementDto
import no.nav.soknad.arkivering.arkiveringendtoendtests.dto.InnsendtDokumentDto
import no.nav.soknad.arkivering.arkiveringendtoendtests.dto.InnsendtVariantDto
import no.nav.soknad.arkivering.arkiveringendtoendtests.dto.SoknadInnsendtDto
import no.nav.soknad.arkivering.arkiveringendtoendtests.kafka.KafkaListener
import no.nav.soknad.arkivering.arkiveringendtoendtests.kafka.KafkaProperties
import no.nav.soknad.arkivering.arkiveringendtoendtests.kafka.KafkaPublisher
import no.nav.soknad.arkivering.arkiveringendtoendtests.verification.AssertionHelper
import no.nav.soknad.arkivering.avroschemas.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.fail
import org.testcontainers.containers.*
import org.testcontainers.containers.wait.strategy.Wait
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.*
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class BaseTests {

	private val dependencies = HashMap<String, Int>().also {
		it["soknadsmottaker"] = 8090
		it["soknadsarkiverer"] = 8091
		it["soknadsfillager"] = 9042
		it["arkiv-mock"] = 8092
	}
	private val kafkaBrokerPort = 9092
	private val schemaRegistryPort = 8081

	val useTestcontainers = System.getProperty("useTestcontainers")?.toBoolean() ?: true

	val attemptsThanSoknadsarkivererWillPerform = 6

	private val filleserUsername = "arkiverer"
	private val filleserPassword = "password"
	private val mottakerUsername = "avsender"
	private val mottakerPassword = "password"
	private val postgresUsername = "postgres"
	private val databaseName = "soknadsfillager"
	private val kafkaProperties = KafkaProperties()

	private val restClient = OkHttpClient()
	private val objectMapper = ObjectMapper().also { it.findAndRegisterModules() }
	private lateinit var kafkaPublisher: KafkaPublisher
	private lateinit var kafkaListener: KafkaListener

	private lateinit var postgresContainer: KPostgreSQLContainer
	private lateinit var kafkaContainer: KafkaContainer
	private lateinit var schemaRegistryContainer: KGenericContainer
	private lateinit var arkivMockContainer: KGenericContainer
	private lateinit var soknadsfillagerContainer: KGenericContainer
	private lateinit var soknadsmottakerContainer: KGenericContainer
	private lateinit var soknadsarkivererContainer: KGenericContainer

	private var soknadsarkivererLogs = ""

	private val restRequestCallback = object : Callback {
		override fun onResponse(call: Call, response: Response) { }

		override fun onFailure(call: Call, e: IOException) {
			fail("Failed to perform request", e)
		}
	}


	@BeforeAll
	fun setup() {
		println("useTestcontainers: $useTestcontainers")

		if (useTestcontainers)
			startContainers()
		else
			checkThatDependenciesAreUp()

		kafkaPublisher = KafkaPublisher(getPortForKafkaBroker(), getPortForSchemaRegistry())
		kafkaListener = KafkaListener(getPortForKafkaBroker(), getPortForSchemaRegistry())
	}

	private fun checkThatDependenciesAreUp() {
		for (dep in dependencies) {
			try {
				val url = "http://localhost:${dep.value}/internal/health"

				val request = Request.Builder().url(url).get().build()
				val response = restClient.newCall(request).execute().use { response -> response.body?.string() ?: "" }
				val health = objectMapper.readValue(response, Health::class.java)

				assertEquals("UP", health.status, "Dependency '${dep.key}' seems to be down")
			} catch (e: Exception) {
				fail("Dependency '${dep.key}' seems to be down")
			}
		}
	}

	private fun startContainers() {
		val databaseContainerPort = 5432

		val network = Network.newNetwork()

		postgresContainer = KPostgreSQLContainer()
			.withNetworkAliases("postgres")
			.withExposedPorts(databaseContainerPort)
			.withNetwork(network)
			.withUsername(postgresUsername)
			.withPassword(postgresUsername)
			.withDatabaseName(databaseName)

		kafkaContainer = KafkaContainer()
			.withNetworkAliases("kafka-broker")
			.withNetwork(network)

		postgresContainer.start()
		kafkaContainer.start()

		createTopic(kafkaProperties.inputTopic)
		createTopic(kafkaProperties.processingEventLogTopic)
		createTopic(kafkaProperties.messageTopic)
		createTopic(kafkaProperties.entitiesTopic)
		createTopic(kafkaProperties.numberOfCallsTopic)
		createTopic(kafkaProperties.numberOfEntitiesTopic)
		createTopic(kafkaProperties.metricsTopic)


		schemaRegistryContainer = KGenericContainer("confluentinc/cp-schema-registry")
			.withNetworkAliases("kafka-schema-registry")
			.withExposedPorts(schemaRegistryPort)
			.withNetwork(network)
			.withEnv(hashMapOf(
				"SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS" to "PLAINTEXT://${kafkaContainer.networkAliases[0]}:$kafkaBrokerPort",
				"SCHEMA_REGISTRY_HOST_NAME" to "localhost",
				"SCHEMA_REGISTRY_LISTENERS" to "http://0.0.0.0:$schemaRegistryPort"))
			.dependsOn(kafkaContainer)
			.waitingFor(Wait.forHttp("/subjects").forStatusCode(200))

		soknadsfillagerContainer = KGenericContainer("archiving-infrastructure_soknadsfillager")
			.withNetworkAliases("soknadsfillager")
			.withExposedPorts(dependencies["soknadsfillager"])
			.withNetwork(network)
			.withEnv(hashMapOf(
				"SPRING_PROFILES_ACTIVE" to "docker",
				"DATABASE_JDBC_URL" to "jdbc:postgresql://${postgresContainer.networkAliases[0]}:$databaseContainerPort/$databaseName",
				"DATABASE_NAME" to databaseName,
				"APPLICATION_USERNAME" to postgresUsername,
				"APPLICATION_PASSWORD" to postgresUsername))
			.dependsOn(postgresContainer)
			.waitingFor(Wait.forHttp("/internal/health").forStatusCode(200))

		schemaRegistryContainer.start()
		soknadsfillagerContainer.start()

		arkivMockContainer = KGenericContainer("archiving-infrastructure_arkiv-mock")
			.withNetworkAliases("arkiv-mock")
			.withExposedPorts(dependencies["arkiv-mock"])
			.withNetwork(network)
			.withEnv(hashMapOf(
				"SPRING_PROFILES_ACTIVE" to "docker",
				"KAFKA_BOOTSTRAP_SERVERS" to "${kafkaContainer.networkAliases[0]}:$kafkaBrokerPort",
				"SCHEMA_REGISTRY_URL" to "http://${schemaRegistryContainer.networkAliases[0]}:$schemaRegistryPort"))
			.dependsOn(kafkaContainer, schemaRegistryContainer)
			.waitingFor(Wait.forHttp("/internal/health").forStatusCode(200))

		arkivMockContainer.start()

		soknadsmottakerContainer = KGenericContainer("archiving-infrastructure_soknadsmottaker")
			.withNetworkAliases("soknadsmottaker")
			.withExposedPorts(dependencies["soknadsmottaker"])
			.withNetwork(network)
			.withEnv(hashMapOf(
				"SPRING_PROFILES_ACTIVE" to "docker",
				"KAFKA_BOOTSTRAP_SERVERS" to "${kafkaContainer.networkAliases[0]}:$kafkaBrokerPort",
				"SCHEMA_REGISTRY_URL" to "http://${schemaRegistryContainer.networkAliases[0]}:$schemaRegistryPort"))
			.dependsOn(kafkaContainer, schemaRegistryContainer)
			.waitingFor(Wait.forHttp("/internal/health").forStatusCode(200))

		soknadsarkivererContainer = KGenericContainer("archiving-infrastructure_soknadsarkiverer")
			.withNetworkAliases("soknadsarkiverer")
			.withExposedPorts(dependencies["soknadsarkiverer"])
			.withNetwork(network)
			.withEnv(hashMapOf(
				"SPRING_PROFILES_ACTIVE" to "test",
				"KAFKA_BOOTSTRAP_SERVERS" to "${kafkaContainer.networkAliases[0]}:$kafkaBrokerPort",
				"SCHEMA_REGISTRY_URL" to "http://${schemaRegistryContainer.networkAliases[0]}:$schemaRegistryPort",
				"FILESTORAGE_HOST" to "http://${soknadsfillagerContainer.networkAliases[0]}:${dependencies["soknadsfillager"]}",
				"JOARK_HOST" to "http://${arkivMockContainer.networkAliases[0]}:${dependencies["arkiv-mock"]}"))
			.dependsOn(kafkaContainer, schemaRegistryContainer, soknadsfillagerContainer, arkivMockContainer)
			.waitingFor(Wait.forHttp("/internal/health").forStatusCode(200))

		soknadsmottakerContainer.start()
		soknadsarkivererContainer.start()
	}

	private fun createTopic(topicName: String) {
		// kafka container uses with embedded zookeeper
		// confluent platform and Kafka compatibility 5.1.x <-> kafka 2.1.x
		// kafka 2.1.x require option --zookeeper, later versions use --bootstrap-servers instead
		val topic = "/usr/bin/kafka-topics --create --zookeeper localhost:2181 --replication-factor 1 --partitions 1 --topic $topicName"

		try {
			val result = kafkaContainer.execInContainer("/bin/sh", "-c", topic)
			if (result.exitCode != 0) {
				println("\n\nKafka Container logs:\n${kafkaContainer.logs}")
				fail("Failed to create topic '$topicName'. Error:\n${result.stderr}")
			}
		} catch (e: Exception) {
			e.printStackTrace()
			fail("Failed to create topic '$topicName'")
		}
	}

	@AfterAll
	fun teardown() {
		kafkaListener.close()
		if (useTestcontainers) {
			println("\n\nLogs soknadsfillager:\n${soknadsfillagerContainer.logs}")
			println("\n\nLogs soknadsmottaker:\n${soknadsmottakerContainer.logs}")
			println("\n\nLogs soknadsarkiverer:\n${soknadsarkivererLogs + soknadsarkivererContainer.logs}")
			println("\n\nLogs arkiv-mock:\n${arkivMockContainer.logs}")

			soknadsfillagerContainer.stop()
			soknadsmottakerContainer.stop()
			soknadsarkivererContainer.stop()
			arkivMockContainer.stop()

			postgresContainer.stop()
			kafkaContainer.stop()
			schemaRegistryContainer.stop()
		}
	}

	fun executeQueryInPostgres(query: String): Container.ExecResult = postgresContainer.execInContainer("psql", "-h", "localhost", "-U", postgresUsername, "-d", databaseName, "--command", query)


	private fun getPortForSoknadsfillager() = if (useTestcontainers) soknadsfillagerContainer.firstMappedPort else dependencies["soknadsfillager"]
	private fun getPortForArkivMock() = if (useTestcontainers) arkivMockContainer.firstMappedPort else dependencies["arkiv-mock"]
	private fun getPortForSoknadsarkiverer() = if (useTestcontainers) soknadsarkivererContainer.firstMappedPort else dependencies["soknadsarkiverer"]
	private fun getPortForSoknadsmottaker() = if (useTestcontainers) soknadsmottakerContainer.firstMappedPort else dependencies["soknadsmottaker"]
	private fun getPortForKafkaBroker() = if (useTestcontainers) kafkaContainer.firstMappedPort else kafkaBrokerPort
	private fun getPortForSchemaRegistry() = if (useTestcontainers) schemaRegistryContainer.firstMappedPort else schemaRegistryPort


	fun shutDownSoknadsarkiverer() {
		soknadsarkivererLogs += soknadsarkivererContainer.logs + "\n"
		soknadsarkivererContainer.stop()
	}

	fun startUpSoknadsarkiverer() {
		soknadsarkivererContainer.start()

		val url = "http://localhost:${getPortForSoknadsarkiverer()}/internal/health"
		val healthStatusCode = {
			val request = Request.Builder().url(url).get().build()
			restClient.newCall(request).execute().use { response -> response.code }
		}
		loopAndVerify(200, healthStatusCode, { assertEquals(200, healthStatusCode.invoke(), "Soknadsarkiverer does not seem to be up") })
	}


	fun putPoisonPillOnKafkaTopic(key: String) {
		println("Poison pill key is $key for test '${Thread.currentThread().stackTrace[2].methodName}'")
		kafkaPublisher.putDataOnTopic(key, "unserializableString")
	}

	fun putInputEventOnKafkaTopic(key: String, innsendingsId: String, fileId: String) {
		println("Input Event key is $key for test '${Thread.currentThread().stackTrace[2].methodName}'")
		kafkaPublisher.putDataOnTopic(key, createSoknadarkivschema(innsendingsId, fileId))
	}

	fun putProcessingEventOnKafkaTopic(key: String, vararg eventTypes: EventTypes) {
		println("Processing event key is $key for test '${Thread.currentThread().stackTrace[2].methodName}'")
		eventTypes.forEach { eventType -> kafkaPublisher.putDataOnTopic(key, ProcessingEvent(eventType)) }
	}


	fun resetArchiveDatabase() {
		val url = "http://localhost:${getPortForArkivMock()}/rest/journalpostapi/v1/reset"

		val requestBody = object : RequestBody() {
			override fun contentType() = "application/json".toMediaType()
			override fun writeTo(sink: BufferedSink) {}
		}
		val request = Request.Builder().url(url).delete(requestBody).build()
		restClient.newCall(request).execute().close()
	}

	fun setNormalArchiveBehaviour(uuid: String) {
		val url = "http://localhost:${getPortForArkivMock()}/arkiv-mock/response-behaviour/set-normal-behaviour/$uuid"
		performPutCall(url)
	}

	fun mockArchiveRespondsWithCodeForXAttempts(uuid: String, status: Int, forAttempts: Int) {
		val url = "http://localhost:${getPortForArkivMock()}/arkiv-mock/response-behaviour/mock-response/$uuid/$status/$forAttempts"
		performPutCall(url)
	}

	fun mockArchiveRespondsWithErroneousBodyForXAttempts(uuid: String, forAttempts: Int) {
		val url = "http://localhost:${getPortForArkivMock()}/arkiv-mock/response-behaviour/set-status-ok-with-erroneous-body/$uuid/$forAttempts"
		performPutCall(url)
	}

	private fun performPutCall(url: String) {
		val requestBody = object : RequestBody() {
			override fun contentType() = "application/json".toMediaType()
			override fun writeTo(sink: BufferedSink) {}
		}

		val request = Request.Builder().url(url).put(requestBody).build()

		restClient.newCall(request).execute().use { response ->
			if (!response.isSuccessful)
				throw IOException("Unexpected code $response")
		}
	}

	fun pollAndVerifyDataInFileStorage(uuid: String, expectedNumberOfHits: Int) {
		val url = "http://localhost:${getPortForSoknadsfillager()}/filer?ids=$uuid"
		loopAndVerify(expectedNumberOfHits, { getNumberOfFiles(url) },
			{ assertEquals(expectedNumberOfHits, getNumberOfFiles(url), "Expected $expectedNumberOfHits files in File Storage") })
	}


	private fun getNumberOfFiles(url: String): Int {
		val headers = createHeaders(filleserUsername, filleserPassword)

		val request = Request.Builder().url(url).headers(headers).get().build()

		restClient.newCall(request).execute().use {
			if (it.isSuccessful) {
				val bytes = it.body?.bytes()
				val listOfFiles = objectMapper.readValue(bytes, object : TypeReference<List<FilElementDto>>() {})

				return listOfFiles.filter { file -> file.fil != null }.size
			}
		}
		return 0
	}


	fun sendFilesToFileStorage(uuid: String) {
		println("fileUuid is $uuid for test '${Thread.currentThread().stackTrace[2].methodName}'")
		sendFilesToFileStorageAndVerify(uuid, "apabepa".toByteArray())
	}

	fun sendFilesToFileStorage(uuid: String, payload: ByteArray) {
		println("fileUuid is $uuid for test '${Thread.currentThread().stackTrace[2].methodName}'")
		sendFilesToFileStorageAndVerify(uuid, payload)
	}

	private fun sendFilesToFileStorageAndVerify(uuid: String, payload: ByteArray) {
		val files = listOf(FilElementDto(uuid, payload, LocalDateTime.now()))
		val url = "http://localhost:${getPortForSoknadsfillager()}/filer"

		performPostRequest(files, url, createHeaders(filleserUsername, filleserPassword), false)
		pollAndVerifyDataInFileStorage(uuid, 1)
	}


	private fun createSoknadarkivschema(innsendingsId: String, fileId: String): Soknadarkivschema {
		val soknadInnsendtDto = createDto(fileId, innsendingsId)
		val innsendtDokumentDto = soknadInnsendtDto.innsendteDokumenter[0]
		val innsendtVariantDto = innsendtDokumentDto.varianter[0]

		val mottattVariant = listOf(MottattVariant(innsendtVariantDto.uuid, innsendtVariantDto.filNavn, innsendtVariantDto.filtype, innsendtVariantDto.variantformat))

		val mottattDokument = listOf(MottattDokument(innsendtDokumentDto.skjemaNummer, innsendtDokumentDto.erHovedSkjema, innsendtDokumentDto.tittel, mottattVariant))

		return Soknadarkivschema(innsendingsId, soknadInnsendtDto.personId, soknadInnsendtDto.tema,
			soknadInnsendtDto.innsendtDato.toEpochSecond(ZoneOffset.UTC), Soknadstyper.SOKNAD, mottattDokument)
	}

	fun sendDataToMottaker(dto: SoknadInnsendtDto, async: Boolean = false, verbose: Boolean = true) {
		if (verbose)
			println("innsendingsId is ${dto.innsendingsId} for test '${Thread.currentThread().stackTrace[2].methodName}'")
		val url = "http://localhost:${getPortForSoknadsmottaker()}/save"
		performPostRequest(dto, url, createHeaders(mottakerUsername, mottakerPassword), async)
	}

	private fun createHeaders(username: String, password: String): Headers {
		val auth = "$username:$password"
		val authHeader = "Basic " + Base64.getEncoder().encodeToString(auth.toByteArray())
		return Headers.headersOf("Authorization", authHeader)
	}

	private fun performPostRequest(payload: Any, url: String, headers: Headers, async: Boolean) {
		val requestBody = object : RequestBody() {
			override fun contentType() = "application/json".toMediaType()
			override fun writeTo(sink: BufferedSink) {
				sink.writeUtf8(objectMapper.writeValueAsString(payload))
			}
		}

		val request = Request.Builder().url(url).headers(headers).post(requestBody).build()

		val call = restClient.newCall(request)
		if (async)
			call.enqueue(restRequestCallback)
		else
			call.execute().close()
	}


	private fun loopAndVerify(expectedCount: Int, getCount: () -> Int,
														finalCheck: () -> Any = { assertEquals(expectedCount, getCount.invoke()) }) {

		val startTime = System.currentTimeMillis()
		val timeout = 30 * 1000

		while (System.currentTimeMillis() < startTime + timeout) {
			val matches = getCount.invoke()

			if (matches == expectedCount) {
				break
			}
			TimeUnit.MILLISECONDS.sleep(50)
		}
		finalCheck.invoke()
	}

	fun createDto(fileId: String, innsendingsId: String = UUID.randomUUID().toString()) =
		SoknadInnsendtDto(innsendingsId, false, "personId", "tema", LocalDateTime.now(),
			listOf(InnsendtDokumentDto("NAV 10-07.17", true, "Søknad om refusjon av reiseutgifter - bil",
				listOf(InnsendtVariantDto(fileId, null, "filnavn", "1024", "variantformat", "PDFA")))))

	fun createDto(fileIds: List<String>) =
		SoknadInnsendtDto(UUID.randomUUID().toString(), false, "personId", "tema", LocalDateTime.now(),
			listOf(InnsendtDokumentDto("NAV 10-07.17", true, "Søknad om refusjon av reiseutgifter - bil",
				fileIds.map { InnsendtVariantDto(it, null, "filnavn", "1024", "variantformat", "PDFA") })))


	fun assertThatArkivMock() = AssertionHelper(kafkaListener).assertThatArkivMock()
}


class KGenericContainer(imageName: String) : GenericContainer<KGenericContainer>(imageName)

class KPostgreSQLContainer : PostgreSQLContainer<KPostgreSQLContainer>()

@JsonIgnoreProperties(ignoreUnknown = true)
class Health {
	lateinit var status: String
}
