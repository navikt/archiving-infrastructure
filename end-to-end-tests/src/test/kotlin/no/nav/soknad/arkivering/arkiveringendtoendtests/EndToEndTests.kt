package no.nav.soknad.arkivering.arkiveringendtoendtests

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import no.nav.soknad.arkivering.arkiveringendtoendtests.dto.FilElementDto
import no.nav.soknad.arkivering.arkiveringendtoendtests.dto.InnsendtDokumentDto
import no.nav.soknad.arkivering.arkiveringendtoendtests.dto.InnsendtVariantDto
import no.nav.soknad.arkivering.arkiveringendtoendtests.dto.SoknadInnsendtDto
import no.nav.soknad.arkivering.arkiveringendtoendtests.kafka.KafkaProperties
import no.nav.soknad.arkivering.arkiveringendtoendtests.kafka.KafkaPublisher
import no.nav.soknad.arkivering.avroschemas.*
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS
import org.junit.jupiter.api.condition.DisabledIfSystemProperty
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.*
import java.util.concurrent.TimeUnit

@TestInstance(PER_CLASS)
class EndToEndTests {

	private val dependencies = HashMap<String, Int>().also {
		it["soknadsmottaker"] = 8090
		it["soknadsarkiverer"] = 8091
		it["soknadsfillager"] = 9042
		it["joark-mock"] = 8092
	}
	private val kafkaBrokerPort = 9092
	private val schemaRegistryPort = 8081

	private val useTestcontainers = System.getProperty("useTestcontainers")?.toBoolean() ?: true

	private val mottakerUsername = "avsender"
	private val mottakerPassword = "password"
	private val filleserUsername = "arkiverer"
	private val filleserPassword = "password"
	private val kafkaProperties = KafkaProperties()

	private val restClient = OkHttpClient()
	private val objectMapper = ObjectMapper().also { it.findAndRegisterModules() }
	private lateinit var kafkaPublisher: KafkaPublisher

	private lateinit var postgresContainer: KPostgreSQLContainer
	private lateinit var kafkaContainer: KafkaContainer
	private lateinit var schemaRegistryContainer: KGenericContainer
	private lateinit var joarkMockContainer: KGenericContainer
	private lateinit var soknadsfillagerContainer: KGenericContainer
	private lateinit var soknadsmottakerContainer: KGenericContainer
	private lateinit var soknadsarkivererContainer: KGenericContainer

	private var soknadsarkivererLogs = ""


	@BeforeAll
	fun setup() {
		println("useTestcontainers: $useTestcontainers")

		if (useTestcontainers)
			startContainers()
		else
			checkThatDependenciesAreUp()

		kafkaPublisher = KafkaPublisher(getPortForKafkaBroker(), getPortForSchemaRegistry())
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
		val postgresUsername = "postgres"
		val databaseName = "soknadsfillager"
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

		joarkMockContainer = KGenericContainer("archiving-infrastructure_joark-mock")
			.withNetworkAliases("joark-mock")
			.withExposedPorts(dependencies["joark-mock"])
			.withNetwork(network)
			.withEnv(hashMapOf("SPRING_PROFILES_ACTIVE" to "docker"))
			.waitingFor(Wait.forHttp("/internal/health").forStatusCode(200))

		schemaRegistryContainer.start()
		soknadsfillagerContainer.start()
		joarkMockContainer.start()

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
				"SPRING_PROFILES_ACTIVE" to "docker",
				"KAFKA_BOOTSTRAP_SERVERS" to "${kafkaContainer.networkAliases[0]}:$kafkaBrokerPort",
				"SCHEMA_REGISTRY_URL" to "http://${schemaRegistryContainer.networkAliases[0]}:$schemaRegistryPort",
				"FILESTORAGE_HOST" to "http://${soknadsfillagerContainer.networkAliases[0]}:${dependencies["soknadsfillager"]}",
				"JOARK_HOST" to "http://${joarkMockContainer.networkAliases[0]}:${dependencies["joark-mock"]}"))
			.dependsOn(kafkaContainer, schemaRegistryContainer, soknadsfillagerContainer, joarkMockContainer)
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
		if (useTestcontainers) {
			println("\n\nLogs soknadsfillager:\n${soknadsfillagerContainer.logs}")
			println("\n\nLogs soknadsmottaker:\n${soknadsmottakerContainer.logs}")
			println("\n\nLogs soknadsarkiverer:\n${soknadsarkivererLogs + soknadsarkivererContainer.logs}")
			println("\n\nLogs joark-mock:\n${joarkMockContainer.logs}")

			soknadsfillagerContainer.stop()
			soknadsmottakerContainer.stop()
			soknadsarkivererContainer.stop()
			joarkMockContainer.stop()

			postgresContainer.stop()
			kafkaContainer.stop()
			schemaRegistryContainer.stop()
		}
	}


	@Test
	fun `Happy case - one file ends up in Joark`() {
		val fileId = UUID.randomUUID().toString()
		val dto = createDto(fileId)
		setNormalJoarkBehaviour(dto.innsendingsId)

		sendFilesToFileStorage(fileId)
		sendDataToMottaker(dto)

		verifyDataInJoark(dto)
		verifyNumberOfCallsToJoark(dto.innsendingsId, 1)
		pollAndVerifyDataInFileStorage(fileId, 0)
	}

	@Test
	fun `Poison pill followed by proper message - one file ends up in Joark`() {
		val fileId = UUID.randomUUID().toString()
		val dto = createDto(fileId)
		setNormalJoarkBehaviour(dto.innsendingsId)

		putPoisonPillOnKafkaTopic(UUID.randomUUID().toString())
		sendFilesToFileStorage(fileId)
		sendDataToMottaker(dto)

		verifyDataInJoark(dto)
		verifyNumberOfCallsToJoark(dto.innsendingsId, 1)
		pollAndVerifyDataInFileStorage(fileId, 0)
	}

	@Test
	fun `Happy case - several files in file storage - one file ends up in Joark`() {
		val fileId0 = UUID.randomUUID().toString()
		val fileId1 = UUID.randomUUID().toString()
		val dto = SoknadInnsendtDto(UUID.randomUUID().toString(), false, "personId", "tema", LocalDateTime.now(),
			listOf(
				InnsendtDokumentDto("NAV 10-07.17", true, "Søknad om refusjon av reiseutgifter - bil",
					listOf(InnsendtVariantDto(fileId0, null, "filnavn", "1024", "variantformat", "PDFA"))),

				InnsendtDokumentDto("NAV 10-07.17", false, "Søknad om refusjon av reiseutgifter - bil",
					listOf(InnsendtVariantDto(fileId1, null, "filnavn", "1024", "variantformat", "PDFA")))
			))
		setNormalJoarkBehaviour(dto.innsendingsId)

		sendFilesToFileStorage(fileId0)
		sendFilesToFileStorage(fileId1)
		sendDataToMottaker(dto)

		verifyDataInJoark(dto)
		verifyNumberOfCallsToJoark(dto.innsendingsId, 1)
		pollAndVerifyDataInFileStorage(fileId0, 0)
		pollAndVerifyDataInFileStorage(fileId1, 0)
	}

	@Test
	fun `No files in file storage - Nothing is sent to Joark`() {
		val fileId = UUID.randomUUID().toString()
		val dto = createDto(fileId)
		setNormalJoarkBehaviour(dto.innsendingsId)

		pollAndVerifyDataInFileStorage(fileId, 0)
		sendDataToMottaker(dto)

		verifyDataNotInJoark(dto)
		verifyNumberOfCallsToJoark(dto.innsendingsId, 0)
		pollAndVerifyDataInFileStorage(fileId, 0)
	}

	@Test
	fun `Several Hovedskjemas - Nothing is sent to Joark`() {
		val fileId0 = UUID.randomUUID().toString()
		val fileId1 = UUID.randomUUID().toString()
		val dto = SoknadInnsendtDto(UUID.randomUUID().toString(), false, "personId", "tema", LocalDateTime.now(),
			listOf(
				InnsendtDokumentDto("NAV 10-07.17", true, "Søknad om refusjon av reiseutgifter - bil",
					listOf(InnsendtVariantDto(fileId0, null, "filnavn", "1024", "variantformat", "PDFA"))),

				InnsendtDokumentDto("NAV 10-07.17", true, "Søknad om refusjon av reiseutgifter - bil",
					listOf(InnsendtVariantDto(fileId1, null, "filnavn", "1024", "variantformat", "PDFA")))
			))
		setNormalJoarkBehaviour(dto.innsendingsId)

		sendFilesToFileStorage(fileId0)
		sendFilesToFileStorage(fileId1)
		sendDataToMottaker(dto)

		verifyDataNotInJoark(dto)
		verifyNumberOfCallsToJoark(dto.innsendingsId, 0)
		pollAndVerifyDataInFileStorage(fileId0, 1)
		pollAndVerifyDataInFileStorage(fileId1, 1)
	}

	@Test
	fun `Joark responds 404 on first two attempts - Works on third attempt`() {
		val fileId = UUID.randomUUID().toString()
		val dto = createDto(fileId)

		sendFilesToFileStorage(fileId)
		mockJoarkRespondsWithCodeForXAttempts(dto.innsendingsId, 404, 2)
		sendDataToMottaker(dto)

		verifyDataInJoark(dto)
		verifyNumberOfCallsToJoark(dto.innsendingsId, 3)
		pollAndVerifyDataInFileStorage(fileId, 0)
	}

	@Test
	fun `Joark responds 500 on first attempt - Works on second attempt`() {
		val fileId = UUID.randomUUID().toString()
		val dto = createDto(fileId)

		sendFilesToFileStorage(fileId)
		mockJoarkRespondsWithCodeForXAttempts(dto.innsendingsId, 500, 1)
		sendDataToMottaker(dto)

		verifyDataInJoark(dto)
		verifyNumberOfCallsToJoark(dto.innsendingsId, 2)
		pollAndVerifyDataInFileStorage(fileId, 0)
	}

	@Test
	fun `Joark responds 200 but has wrong response body - Will retry`() {
		val fileId = UUID.randomUUID().toString()
		val dto = createDto(fileId)

		sendFilesToFileStorage(fileId)
		mockJoarkRespondsWithErroneousForXAttempts(dto.innsendingsId, 3)
		sendDataToMottaker(dto)

		verifyDataInJoark(dto)
		pollAndVerifyNumberOfCallsToJoark(dto.innsendingsId, 4)
		pollAndVerifyDataInFileStorage(fileId, 0)
	}

	@Test
	fun `Joark responds 200 but has wrong response body - Will retry until soknadsarkiverer gives up`() {
		val fileId = UUID.randomUUID().toString()
		val dto = createDto(fileId)
		val moreAttemptsThanSoknadsarkivererWillPerform = 6

		sendFilesToFileStorage(fileId)
		mockJoarkRespondsWithErroneousForXAttempts(dto.innsendingsId, moreAttemptsThanSoknadsarkivererWillPerform)
		sendDataToMottaker(dto)

		verifyDataInJoark(dto)
		pollAndVerifyNumberOfCallsToJoark(dto.innsendingsId, 5)
		pollAndVerifyDataInFileStorage(fileId, 1)
	}

	@DisabledIfSystemProperty(named = "useTestcontainers", matches = "false")
	@Test
	fun `Put input event on Kafka when Soknadsarkiverer is down - will start up and send to Joark`() {
		val key = UUID.randomUUID().toString()
		val fileId = UUID.randomUUID().toString()
		val innsendingsId = UUID.randomUUID().toString()
		setNormalJoarkBehaviour(innsendingsId)

		sendFilesToFileStorage(fileId)

		shutDownSoknadsarkiverer()
		putInputEventOnKafkaTopic(key, innsendingsId, fileId)
		startUpSoknadsarkiverer()

		verifyDataInJoark(createDto(fileId, innsendingsId))
		verifyNumberOfCallsToJoark(innsendingsId, 1)
		pollAndVerifyDataInFileStorage(fileId, 0)
	}

	@DisabledIfSystemProperty(named = "useTestcontainers", matches = "false")
	@Test
	fun `Put input event and processing events on Kafka when Soknadsarkiverer is down - will start up and send to Joark`() {
		val key = UUID.randomUUID().toString()
		val fileId = UUID.randomUUID().toString()
		val innsendingsId = UUID.randomUUID().toString()
		setNormalJoarkBehaviour(innsendingsId)

		sendFilesToFileStorage(fileId)

		shutDownSoknadsarkiverer()
		putInputEventOnKafkaTopic(key, innsendingsId, fileId)
		putProcessingEventOnKafkaTopic(key, EventTypes.RECEIVED, EventTypes.STARTED, EventTypes.STARTED)
		startUpSoknadsarkiverer()

		verifyDataInJoark(createDto(fileId, innsendingsId))
		verifyNumberOfCallsToJoark(innsendingsId, 1)
		pollAndVerifyDataInFileStorage(fileId, 0)
	}

	@DisabledIfSystemProperty(named = "useTestcontainers", matches = "false")
	@Test
	fun `Soknadsarkiverer restarts before finishing to put input event in Joark - will pick event up and send to Joark`() {
		val attemptsThanSoknadsarkivererWillPerform = 5
		val fileId = UUID.randomUUID().toString()
		val dto = createDto(fileId)

		sendFilesToFileStorage(fileId)
		mockJoarkRespondsWithCodeForXAttempts(dto.innsendingsId, 404, attemptsThanSoknadsarkivererWillPerform + 1)
		sendDataToMottaker(dto)
		verifyNumberOfCallsToJoark(dto.innsendingsId, attemptsThanSoknadsarkivererWillPerform)
		mockJoarkRespondsWithCodeForXAttempts(dto.innsendingsId, 500, 1)
		TimeUnit.SECONDS.sleep(1)

		shutDownSoknadsarkiverer()
		startUpSoknadsarkiverer()

		verifyDataInJoark(dto)
		verifyNumberOfCallsToJoark(dto.innsendingsId, 6)
		pollAndVerifyDataInFileStorage(fileId, 0)
	}

	@DisabledIfSystemProperty(named = "useTestcontainers", matches = "false")
	@Test
	fun `Put finished input event on Kafka and send a new input event when Soknadsarkiverer is down - only the new input event ends up in Joark`() {
		val finishedKey = UUID.randomUUID().toString()
		val finishedFileId = UUID.randomUUID().toString()
		val newFileId = UUID.randomUUID().toString()
		val finishedInnsendingsId = UUID.randomUUID().toString()
		val newInnsendingsId = UUID.randomUUID().toString()

		val finishedDto = createDto(finishedFileId, finishedInnsendingsId)
		val newDto = createDto(newFileId, newInnsendingsId)
		setNormalJoarkBehaviour(finishedInnsendingsId)
		setNormalJoarkBehaviour(newInnsendingsId)

		sendFilesToFileStorage(finishedFileId)
		sendFilesToFileStorage(newFileId)

		shutDownSoknadsarkiverer()
		putInputEventOnKafkaTopic(finishedKey, finishedInnsendingsId, finishedFileId)
		putProcessingEventOnKafkaTopic(finishedKey, EventTypes.RECEIVED, EventTypes.STARTED, EventTypes.FINISHED)
		sendDataToMottaker(newDto)
		startUpSoknadsarkiverer()

		verifyDataInJoark(newDto)
		verifyDataNotInJoark(finishedDto)
		verifyNumberOfCallsToJoark(newInnsendingsId, 1)
		verifyNumberOfCallsToJoark(finishedInnsendingsId, 0)
		pollAndVerifyDataInFileStorage(newFileId, 0)
		pollAndVerifyDataInFileStorage(finishedFileId, 1)
	}


	private fun getPortForSoknadsarkiverer() = if (useTestcontainers) soknadsarkivererContainer.firstMappedPort else dependencies["soknadsarkiverer"]
	private fun getPortForSoknadsmottaker() = if (useTestcontainers) soknadsmottakerContainer.firstMappedPort else dependencies["soknadsmottaker"]
	private fun getPortForSoknadsfillager() = if (useTestcontainers) soknadsfillagerContainer.firstMappedPort else dependencies["soknadsfillager"]
	private fun getPortForJoarkMock() = if (useTestcontainers) joarkMockContainer.firstMappedPort else dependencies["joark-mock"]
	private fun getPortForKafkaBroker() = if (useTestcontainers) kafkaContainer.firstMappedPort else kafkaBrokerPort
	private fun getPortForSchemaRegistry() = if (useTestcontainers) schemaRegistryContainer.firstMappedPort else schemaRegistryPort

	private fun shutDownSoknadsarkiverer() {
		soknadsarkivererLogs += soknadsarkivererContainer.logs + "\n"
		soknadsarkivererContainer.stop()
	}

	private fun startUpSoknadsarkiverer() {
		soknadsarkivererContainer.start()

		val url = "http://localhost:${getPortForSoknadsarkiverer()}/internal/health"
		val healthStatusCode = {
			val request = Request.Builder().url(url).get().build()
			restClient.newCall(request).execute().use { response -> response.code }
		}
		loopAndVerify(200, healthStatusCode, { assertEquals(200, healthStatusCode.invoke(), "Soknadsarkiverer does not seem to be up") })
	}

	private fun putPoisonPillOnKafkaTopic(key: String) {
		println("Poison pill key is $key for test '${Thread.currentThread().stackTrace[2].methodName}'")
		kafkaPublisher.putDataOnTopic(key, "unserializableString")
	}

	private fun putInputEventOnKafkaTopic(key: String, innsendingsId: String, fileId: String) {
		println("Input Event key is $key for test '${Thread.currentThread().stackTrace[2].methodName}'")
		kafkaPublisher.putDataOnTopic(key, createSoknadarkivschema(innsendingsId, fileId))
	}

	private fun putProcessingEventOnKafkaTopic(key: String, vararg eventTypes: EventTypes) {
		println("Processing event key is $key for test '${Thread.currentThread().stackTrace[2].methodName}'")
		eventTypes.forEach { eventType -> kafkaPublisher.putDataOnTopic(key, ProcessingEvent(eventType)) }
	}


	private fun setNormalJoarkBehaviour(uuid: String) {
		val url = "http://localhost:${getPortForJoarkMock()}/joark/mock/response-behaviour/set-normal-behaviour/$uuid"
		performPutCall(url)
	}

	private fun mockJoarkRespondsWithCodeForXAttempts(uuid: String, status: Int, forAttempts: Int) {
		val url = "http://localhost:${getPortForJoarkMock()}/joark/mock/response-behaviour/mock-response/$uuid/$status/$forAttempts"
		performPutCall(url)
	}

	private fun mockJoarkRespondsWithErroneousForXAttempts(uuid: String, forAttempts: Int) {
		val url = "http://localhost:${getPortForJoarkMock()}/joark/mock/response-behaviour/set-status-ok-with-erroneous-body/$uuid/$forAttempts"
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

	private fun verifyDataNotInJoark(dto: SoknadInnsendtDto) {
		val key = dto.innsendingsId
		val url = "http://localhost:${getPortForJoarkMock()}/rest/journalpostapi/v1/lookup/$key"

		val response: Optional<LinkedHashMap<String, String>> = pollJoarkUntilTimeout(url)

		if (response.isPresent)
			fail("Expected Joark to not have any results for $key")
	}

	private fun verifyNumberOfCallsToJoark(uuid: String, expectedNumberOfCalls: Int) {
		loopAndVerify(expectedNumberOfCalls, { getNumberOfCallsToJoark(uuid) })
	}

	private fun getNumberOfCallsToJoark(uuid: String): Int {
		val url = "http://localhost:${getPortForJoarkMock()}/joark/mock/response-behaviour/number-of-calls/$uuid"
		val request = Request.Builder().url(url).get().build()
		return restClient.newCall(request).execute().use { response -> response.body?.string()?.toInt() ?: -1 }
	}

	private fun verifyDataInJoark(dto: SoknadInnsendtDto) {
		val key = dto.innsendingsId
		val url = "http://localhost:${getPortForJoarkMock()}/rest/journalpostapi/v1/lookup/$key"

		val response: Optional<LinkedHashMap<String, String>> = pollJoarkUntilTimeout(url)

		if (!response.isPresent)
			fail("Failed to get response from Joark")
		assertEquals(dto.innsendingsId, response.get()["id"])
		assertEquals(dto.tema, response.get()["tema"])
		assertTrue((response.get()["title"]).toString().contains(dto.innsendteDokumenter[0].tittel!!))
	}

	private fun pollAndVerifyNumberOfCallsToJoark(uuid: String, expectedNumberOfCalls: Int) {
		loopAndVerify(expectedNumberOfCalls, { getNumberOfCallsToJoark(uuid) },
			{ assertEquals(expectedNumberOfCalls, getNumberOfCallsToJoark(uuid), "Expected $expectedNumberOfCalls calls to Joark") })
	}

	private fun pollAndVerifyDataInFileStorage(uuid: String, expectedNumberOfHits: Int) {
		val url = "http://localhost:${getPortForSoknadsfillager()}/filer?ids=$uuid"
		loopAndVerify(expectedNumberOfHits, { getNumberOfFiles(url) },
			{ assertEquals(expectedNumberOfHits, getNumberOfFiles(url), "Expected $expectedNumberOfHits files in File Storage") })
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

	private fun getNumberOfFiles(url: String): Int {
		val headers = createHeaders(filleserUsername, filleserPassword)

		val request = Request.Builder().url(url).headers(headers).get().build()
		val response = restClient.newCall(request).execute()
		if (response.isSuccessful) {
			val bytes = response.body?.bytes()
			val listOfFiles = objectMapper.readValue(bytes, object : TypeReference<List<FilElementDto>>() {})

			return listOfFiles.filter { it.fil != null }.size
		}
		return 0
	}

	private fun <T> pollJoarkUntilTimeout(url: String): Optional<T> {

		val request = Request.Builder().url(url).get().build()

		val startTime = System.currentTimeMillis()
		val timeout = 10 * 1000

		while (System.currentTimeMillis() < startTime + timeout) {
			val response = restClient.newCall(request).execute()
			val responseBody = response.body

			if (response.isSuccessful && responseBody != null) {
				val body = responseBody.string()
				if (body != "[]") {
					val resp = objectMapper.readValue(body, object : TypeReference<T>() {})
					return Optional.of(resp)
				}
			}
			TimeUnit.MILLISECONDS.sleep(50)
		}
		return Optional.empty()
	}

	private fun sendFilesToFileStorage(uuid: String) {
		println("fileUuid is $uuid for test '${Thread.currentThread().stackTrace[2].methodName}'")
		val files = listOf(FilElementDto(uuid, "apabepa".toByteArray(), LocalDateTime.now()))
		val url = "http://localhost:${getPortForSoknadsfillager()}/filer"

		performPostRequest(files, url, createHeaders(filleserUsername, filleserPassword))
		pollAndVerifyDataInFileStorage(uuid, 1)
	}

	private fun sendDataToMottaker(dto: SoknadInnsendtDto) {
		println("innsendingsId is ${dto.innsendingsId} for test '${Thread.currentThread().stackTrace[2].methodName}'")
		val url = "http://localhost:${getPortForSoknadsmottaker()}/save"
		performPostRequest(dto, url, createHeaders(mottakerUsername, mottakerPassword))
	}

	private fun createHeaders(username: String, password: String): Headers {

		val auth = "$username:$password"
		val authHeader = "Basic " + Base64.getEncoder().encodeToString(auth.toByteArray())
		return Headers.headersOf("Authorization", authHeader)
	}

	private fun performPostRequest(payload: Any, url: String, headers: Headers) {
		val requestBody = object : RequestBody() {
			override fun contentType() = "application/json".toMediaType()
			override fun writeTo(sink: BufferedSink) {
				sink.writeUtf8(objectMapper.writeValueAsString(payload))
			}
		}

		val request = Request.Builder().url(url).headers(headers).post(requestBody).build()

		restClient.newCall(request).execute()
	}

	private fun createDto(fileId: String, innsendingsId: String = UUID.randomUUID().toString()) =
		SoknadInnsendtDto(innsendingsId, false, "personId", "tema", LocalDateTime.now(),
			listOf(InnsendtDokumentDto("NAV 10-07.17", true, "Søknad om refusjon av reiseutgifter - bil",
				listOf(InnsendtVariantDto(fileId, null, "filnavn", "1024", "variantformat", "PDFA")))))

	private fun createSoknadarkivschema(innsendingsId: String, fileId: String): Soknadarkivschema {
		val soknadInnsendtDto = createDto(fileId, innsendingsId)
		val innsendtDokumentDto = soknadInnsendtDto.innsendteDokumenter[0]
		val innsendtVariantDto = innsendtDokumentDto.varianter[0]

		val mottattVariant = listOf(MottattVariant(innsendtVariantDto.uuid, innsendtVariantDto.filNavn, innsendtVariantDto.filtype, innsendtVariantDto.variantformat))

		val mottattDokument = listOf(MottattDokument(innsendtDokumentDto.skjemaNummer, innsendtDokumentDto.erHovedSkjema, innsendtDokumentDto.tittel, mottattVariant))

		return Soknadarkivschema(innsendingsId, soknadInnsendtDto.personId, soknadInnsendtDto.tema, soknadInnsendtDto.innsendtDato.toEpochSecond(ZoneOffset.UTC), Soknadstyper.SOKNAD, mottattDokument)
	}
}

class KGenericContainer(imageName: String) : GenericContainer<KGenericContainer>(imageName)

class KPostgreSQLContainer : PostgreSQLContainer<KPostgreSQLContainer>()

@JsonIgnoreProperties(ignoreUnknown = true)
class Health {
	lateinit var status: String
}
