package no.nav.soknad.arkivering.arkiveringendtoendtests

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.*
import no.nav.soknad.arkivering.arkiveringendtoendtests.dto.FilElementDto
import no.nav.soknad.arkivering.arkiveringendtoendtests.dto.InnsendtDokumentDto
import no.nav.soknad.arkivering.arkiveringendtoendtests.dto.InnsendtVariantDto
import no.nav.soknad.arkivering.arkiveringendtoendtests.dto.SoknadInnsendtDto
import no.nav.soknad.arkivering.arkiveringendtoendtests.kafka.KafkaProperties
import no.nav.soknad.arkivering.arkiveringendtoendtests.kafka.KafkaPublisher
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import java.io.IOException
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

@TestInstance(PER_CLASS)
class Kjellman {

	private val dependencies = HashMap<String, Int>().also {
		it["soknadsmottaker"] = 8090
		it["soknadsarkiverer"] = 8091
		it["soknadsfillager"] = 9042
		it["arkiv-mock"] = 8092
	}
	private val kafkaBrokerPort = 9092
	private val schemaRegistryPort = 8081

	private val useTestcontainers = System.getProperty("useTestcontainers")?.toBoolean() ?: true

	private val mottakerUsername = "avsender"
	private val mottakerPassword = "password"
	private val filleserUsername = "arkiverer"
	private val filleserPassword = "password"
	private val postgresUsername = "postgres"
	private val databaseName = "soknadsfillager"
	private val kafkaProperties = KafkaProperties()

	private val restClient = OkHttpClient()
	private val objectMapper = ObjectMapper().also { it.findAndRegisterModules() }
	private lateinit var kafkaPublisher: KafkaPublisher

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

		arkivMockContainer = KGenericContainer("archiving-infrastructure_arkiv-mock")
			.withNetworkAliases("arkiv-mock")
			.withExposedPorts(dependencies["arkiv-mock"])
			.withNetwork(network)
			.withEnv(hashMapOf("SPRING_PROFILES_ACTIVE" to "docker"))
			.waitingFor(Wait.forHttp("/internal/health").forStatusCode(200))

		schemaRegistryContainer.start()
		soknadsfillagerContainer.start()
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

	private fun preloadDatabase(numberOfEntities: Int) {
		println("Preloading database...")
		val timeTaken = measureTimeMillis {
			fun execQuery(query: String) = postgresContainer.execInContainer("psql", "-h", "localhost", "-U", postgresUsername, "-d", databaseName, "--command", query)

			val result = execQuery("DROP TABLE IF EXISTS documents;" +
				"CREATE TABLE documents \n" +
				"(\n" +
				"    id        VARCHAR(255) NOT NULL,\n" +
				"    document  BYTEA,\n" +
				"    created   TIMESTAMP WITH TIME ZONE NOT NULL default (now() at time zone 'UTC'),\n" +
				"    PRIMARY KEY (id)\n" +
				");")
			if (result.exitCode != 0)
				println("Error when preloading database!\n${result.exitCode}\n${result.stdout}\n${result.stderr}")

			val batchSize = 1000
			val numberOfBatches = numberOfEntities / batchSize
			// This will insert numberOfBatches * batchSize documents into the database.
			for (i in 0 until numberOfBatches) {
				val res = execQuery("INSERT INTO documents(id, document) VALUES " +
					((i * batchSize) until ((i + 1) * batchSize)).joinToString(",") { "('$it','0')" } + ";")

				if (res.exitCode != 0)
					println("Error when preloading database!\n${res.exitCode}\n${res.stdout}\n${res.stderr}")
				else if (i % numberOfBatches == 0)
					println("$i of $numberOfBatches (${100 * i / numberOfBatches}%)")
			}
		}
		println("Preloading database took $timeTaken ms") //Time taken: 185 ms
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


	@Test
	fun `Happy case - one file ends up in the archive`() {
		val numberOfEntities = 10_000
		preloadDatabase(numberOfEntities)

		val dtos = sendDataToMottakerAsync(numberOfEntities)

		TimeUnit.SECONDS.sleep(256)
		dtos.forEach {
			verifyDataInArchive(it)
		}
	}

	private fun sendDataToMottakerAsync(numberOfEntities: Int): List<SoknadInnsendtDto> {
		val atomicInteger = AtomicInteger()
		val startTimeSendingToMottaker = System.currentTimeMillis()
		println("About to send $numberOfEntities entities to Mottaker")
		val deferredDtos = (0 until numberOfEntities).map { sendDataToMottakerAsync(atomicInteger) }
		val dtos = runBlocking { deferredDtos.awaitAll() }
		val finishTimeSendingToMottaker = System.currentTimeMillis()
		println("Sent $numberOfEntities entities to Mottaker in ${finishTimeSendingToMottaker - startTimeSendingToMottaker} ms")
		return dtos
	}

	private fun sendDataToMottakerAsync(atomicInteger: AtomicInteger): Deferred<SoknadInnsendtDto> {
		return GlobalScope.async {

			val dto = createDto(atomicInteger.getAndIncrement().toString())
//		setNormalArchiveBehaviour(dto.innsendingsId)

//		sendFilesToFileStorage(fileId)
			sendDataToMottaker(dto)
			dto
		}
	}


	private fun getPortForSoknadsmottaker() = if (useTestcontainers) soknadsmottakerContainer.firstMappedPort else dependencies["soknadsmottaker"]
	private fun getPortForSoknadsfillager() = if (useTestcontainers) soknadsfillagerContainer.firstMappedPort else dependencies["soknadsfillager"]
	private fun getPortForArkivMock() = if (useTestcontainers) arkivMockContainer.firstMappedPort else dependencies["arkiv-mock"]
	private fun getPortForKafkaBroker() = if (useTestcontainers) kafkaContainer.firstMappedPort else kafkaBrokerPort
	private fun getPortForSchemaRegistry() = if (useTestcontainers) schemaRegistryContainer.firstMappedPort else schemaRegistryPort


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

	private fun verifyNumberOfCallsToArchive(uuid: String, expectedNumberOfCalls: Int) {
		loopAndVerify(expectedNumberOfCalls, { getNumberOfCallsToArchive(uuid) })
	}

	private fun getNumberOfCallsToArchive(uuid: String): Int {
		val url = "http://localhost:${getPortForArkivMock()}/arkiv-mock/response-behaviour/number-of-calls/$uuid"
		val request = Request.Builder().url(url).get().build()
		return restClient.newCall(request).execute().use { response -> response.body?.string()?.toInt() ?: -1 }
	}

	private fun verifyDataInArchive(dto: SoknadInnsendtDto) {
		val key = dto.innsendingsId
		val url = "http://localhost:${getPortForArkivMock()}/rest/journalpostapi/v1/lookup/$key"

		val response: Optional<LinkedHashMap<String, String>> = pollArchiveUntilTimeout(url)

		if (!response.isPresent)
			fail("Failed to get response from the archive for $key")
		assertEquals(dto.innsendingsId, response.get()["id"])
		assertEquals(dto.tema, response.get()["tema"])
		assertTrue((response.get()["title"]).toString().contains(dto.innsendteDokumenter[0].tittel!!))
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

		restClient.newCall(request).execute().use {
			if (it.isSuccessful) {
				val bytes = it.body?.bytes()
				val listOfFiles = objectMapper.readValue(bytes, object : TypeReference<List<FilElementDto>>() {})

				return listOfFiles.filter { file -> file.fil != null }.size
			}
		}
		return 0
	}

	private fun <T> pollArchiveUntilTimeout(url: String): Optional<T> {

		val request = Request.Builder().url(url).get().build()

		val startTime = System.currentTimeMillis()
		val timeout = 10 * 1000

		while (System.currentTimeMillis() < startTime + timeout) {
			restClient.newCall(request).execute().use {

				val responseBody = it.body

				if (it.isSuccessful && responseBody != null) {
					val body = responseBody.string()
					if (body != "[]") {
						val resp = objectMapper.readValue(body, object : TypeReference<T>() {})
						return Optional.of(resp)
					}
				}
				TimeUnit.MILLISECONDS.sleep(50)
			}
		}
		return Optional.empty()
	}

	private fun sendFilesToFileStorage(uuid: String) {
		println("fileUuid is $uuid for test '${Thread.currentThread().stackTrace[2].methodName}'")
		val files = listOf(FilElementDto(uuid, "apabepa".toByteArray(), LocalDateTime.now()))
		val url = "http://localhost:${getPortForSoknadsfillager()}/filer"

		performPostRequest(files, url, createHeaders(filleserUsername, filleserPassword), false)
		pollAndVerifyDataInFileStorage(uuid, 1)
	}

	private fun sendDataToMottaker(dto: SoknadInnsendtDto) {
		val url = "http://localhost:${getPortForSoknadsmottaker()}/save"
		performPostRequest(dto, url, createHeaders(mottakerUsername, mottakerPassword), true)
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
			call.execute()
	}

	private fun createDto(fileId: String, innsendingsId: String = UUID.randomUUID().toString()) =
		SoknadInnsendtDto(innsendingsId, false, "personId", "tema", null,
			listOf(InnsendtDokumentDto("NAV 10-07.17", true, "Søknad om refusjon av reiseutgifter - bil",
				listOf(InnsendtVariantDto(fileId, null, "filnavn", "1024", "variantformat", "PDFA")))))
}
