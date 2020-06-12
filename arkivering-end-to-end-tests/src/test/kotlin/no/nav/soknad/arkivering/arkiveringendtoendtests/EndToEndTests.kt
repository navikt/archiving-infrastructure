package no.nav.soknad.arkivering.arkiveringendtoendtests

import no.nav.soknad.arkivering.arkiveringendtoendtests.dto.FilElementDto
import no.nav.soknad.arkivering.arkiveringendtoendtests.dto.InnsendtDokumentDto
import no.nav.soknad.arkivering.arkiveringendtoendtests.dto.InnsendtVariantDto
import no.nav.soknad.arkivering.arkiveringendtoendtests.dto.SoknadInnsendtDto
import org.apache.tomcat.util.codec.binary.Base64.encodeBase64
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.*
import org.springframework.web.client.RestTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.net.URI
import java.time.LocalDateTime
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

	private val mottakerUsername = "avsender"
	private val mottakerPassword = "password"
	private val filleserUsername = "arkiverer"
	private val filleserPassword = "password"

	private val restTemplate = RestTemplate()

	private lateinit var postgresContainer: KPostgreSQLContainer
	private lateinit var kafkaContainer: KafkaContainer
	private lateinit var schemaRegistryContainer: KGenericContainer
	private lateinit var joarkMockContainer: KGenericContainer
	private lateinit var soknadsfillagerContainer: KGenericContainer
	private lateinit var soknadsmottakerContainer: KGenericContainer
	private lateinit var soknadsarkivererContainer: KGenericContainer

	@BeforeAll
	fun startContainer() {
		val postgresUsername = "postgres"
		val databaseName = "soknadsfillager"
		val databaseContainerPort = 5432
		val kafkaBrokerPort = 9092
		val schemaRegistryPort = 8081


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

		createTopic("privat-soknadInnsendt-v1-default")
		createTopic("privat-soknadInnsendt-processingEventLog-v1-default")
		createTopic("privat-soknadInnsendt-messages-v1-default")


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
		val topic =  "/usr/bin/kafka-topics --create --zookeeper localhost:2181 --replication-factor 1 --partitions 1 --topic $topicName"

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
		println("\n\nLogs soknadsfillager:\n${soknadsfillagerContainer.logs}")
		println("\n\nLogs soknadsmottaker:\n${soknadsmottakerContainer.logs}")
		println("\n\nLogs soknadsarkiverer:\n${soknadsarkivererContainer.logs}")
		println("\n\nLogs joark-mock:\n${joarkMockContainer.logs}")

		soknadsfillagerContainer.stop()
		soknadsmottakerContainer.stop()
		soknadsarkivererContainer.stop()
		joarkMockContainer.stop()

		postgresContainer.stop()
		kafkaContainer.stop()
		schemaRegistryContainer.stop()
	}


	@Test
	fun `Happy case - one file in file storage`() {
		val uuid = UUID.randomUUID().toString()
		val dto = createDto(uuid)
		setNormalJoarkBehaviour(dto.innsendingsId)

		sendFilesToFileStorage(uuid)
		sendDataToMottaker(dto)

		verifyDataInJoark(dto)
		verifyNumberOfCallsToJoark(dto.innsendingsId, 1)
		pollAndVerifyDataInFileStorage(uuid, 0)
	}

	@Test
	fun `Happy case - several files in file storage`() {
		val uuid0 = UUID.randomUUID().toString()
		val uuid1 = UUID.randomUUID().toString()
		val dto = SoknadInnsendtDto(UUID.randomUUID().toString(), false, "personId", "tema", LocalDateTime.now(),
			listOf(
				InnsendtDokumentDto("NAV 10-07.17", true, "Søknad om refusjon av reiseutgifter - bil",
					listOf(InnsendtVariantDto(uuid0, null, "filnavn", "1024", "variantformat", "PDFA"))),

				InnsendtDokumentDto("NAV 10-07.17", false, "Søknad om refusjon av reiseutgifter - bil",
					listOf(InnsendtVariantDto(uuid1, null, "filnavn", "1024", "variantformat", "PDFA")))
			))
		setNormalJoarkBehaviour(dto.innsendingsId)

		sendFilesToFileStorage(uuid0)
		sendFilesToFileStorage(uuid1)
		sendDataToMottaker(dto)

		verifyDataInJoark(dto)
		verifyNumberOfCallsToJoark(dto.innsendingsId, 1)
		pollAndVerifyDataInFileStorage(uuid0, 0)
		pollAndVerifyDataInFileStorage(uuid1, 0)
	}

	@Test
	fun `No files in file storage - Nothing is sent to Joark`() {
		val uuid = UUID.randomUUID().toString()
		val dto = createDto(uuid)
		setNormalJoarkBehaviour(dto.innsendingsId)

		pollAndVerifyDataInFileStorage(uuid, 0)
		sendDataToMottaker(dto)

		verifyDataNotInJoark(dto)
		verifyNumberOfCallsToJoark(dto.innsendingsId, 0)
		pollAndVerifyDataInFileStorage(uuid, 0)
	}

	@Test
	fun `Several Hovedskjemas - Nothing is sent to Joark`() {
		val uuid0 = UUID.randomUUID().toString()
		val uuid1 = UUID.randomUUID().toString()
		val dto = SoknadInnsendtDto(UUID.randomUUID().toString(), false, "personId", "tema", LocalDateTime.now(),
			listOf(
				InnsendtDokumentDto("NAV 10-07.17", true, "Søknad om refusjon av reiseutgifter - bil",
					listOf(InnsendtVariantDto(uuid0, null, "filnavn", "1024", "variantformat", "PDFA"))),

				InnsendtDokumentDto("NAV 10-07.17", true, "Søknad om refusjon av reiseutgifter - bil",
					listOf(InnsendtVariantDto(uuid1, null, "filnavn", "1024", "variantformat", "PDFA")))
			))
		setNormalJoarkBehaviour(dto.innsendingsId)

		sendFilesToFileStorage(uuid0)
		sendFilesToFileStorage(uuid1)
		sendDataToMottaker(dto)

		verifyDataNotInJoark(dto)
		verifyNumberOfCallsToJoark(dto.innsendingsId, 0)
		pollAndVerifyDataInFileStorage(uuid0, 1)
		pollAndVerifyDataInFileStorage(uuid1, 1)
	}

	@Test
	fun `Joark responds 404 on first two attempts - Works on third attempt`() {
		val uuid = UUID.randomUUID().toString()
		val dto = createDto(uuid)

		sendFilesToFileStorage(uuid)
		mockJoarkRespondsWithCodeForXAttempts(dto.innsendingsId, 404, 2)
		sendDataToMottaker(dto)

		verifyDataInJoark(dto)
		verifyNumberOfCallsToJoark(dto.innsendingsId, 3)
		pollAndVerifyDataInFileStorage(uuid, 0)
	}

	@Test
	fun `Joark responds 500 on first attempt - Works on second attempt`() {
		val uuid = UUID.randomUUID().toString()
		val dto = createDto(uuid)

		sendFilesToFileStorage(uuid)
		mockJoarkRespondsWithCodeForXAttempts(dto.innsendingsId, 500, 1)
		sendDataToMottaker(dto)

		verifyDataInJoark(dto)
		verifyNumberOfCallsToJoark(dto.innsendingsId, 2)
		pollAndVerifyDataInFileStorage(uuid, 0)
	}

	@Test
	fun `Joark responds 200 but has wrong response body - Will retry`() {
		val uuid = UUID.randomUUID().toString()
		val dto = createDto(uuid)

		sendFilesToFileStorage(uuid)
		mockJoarkRespondsWithErroneousForXAttempts(dto.innsendingsId, 3)
		sendDataToMottaker(dto)

		verifyDataInJoark(dto)
		pollAndVerifyNumberOfCallsToJoark(dto.innsendingsId, 4)
		pollAndVerifyDataInFileStorage(uuid, 0)
	}

	@Test
	fun `Joark responds 200 but has wrong response body - Will retry until soknadsarkiverer gives up`() {
		val uuid = UUID.randomUUID().toString()
		val dto = createDto(uuid)
		val moreAttemptsThanSoknadsarkivererWillPerform = 7

		sendFilesToFileStorage(uuid)
		mockJoarkRespondsWithErroneousForXAttempts(dto.innsendingsId, moreAttemptsThanSoknadsarkivererWillPerform)
		sendDataToMottaker(dto)

		verifyDataInJoark(dto)
		pollAndVerifyNumberOfCallsToJoark(dto.innsendingsId, 6)
		pollAndVerifyDataInFileStorage(uuid, 1)
	}

	private fun setNormalJoarkBehaviour(uuid: String) {
		val url = "http://localhost:${joarkMockContainer.firstMappedPort}/joark/mock/response-behaviour/set-normal-behaviour/$uuid"
		restTemplate.put(url, null)
	}

	private fun mockJoarkRespondsWithCodeForXAttempts(uuid: String, status: Int, forAttempts: Int) {
		val url = "http://localhost:${joarkMockContainer.firstMappedPort}/joark/mock/response-behaviour/mock-response/$uuid/$status/$forAttempts"
		restTemplate.put(url, null)
	}

	private fun mockJoarkRespondsWithErroneousForXAttempts(uuid: String, forAttempts: Int) {
		val url = "http://localhost:${joarkMockContainer.firstMappedPort}/joark/mock/response-behaviour/set-status-ok-with-erroneous-body/$uuid/$forAttempts"
		restTemplate.put(url, null)
	}

	private fun verifyDataNotInJoark(dto: SoknadInnsendtDto) {
		val key = dto.innsendingsId
		val url = "http://localhost:${joarkMockContainer.firstMappedPort}/joark/lookup/$key"

		val responseEntity: ResponseEntity<List<LinkedHashMap<String, String>>> = pollJoarkUntilTimeout(url)

		if (responseEntity.hasBody())
			fail("Expected Joark to not have any results for $key")
	}

	private fun verifyNumberOfCallsToJoark(uuid: String, expectedNumberOfCalls: Int) {
		val numberOfCalls = getNumberOfCallsToJoark(uuid)
		assertEquals(expectedNumberOfCalls, numberOfCalls)
	}

	private fun getNumberOfCallsToJoark(uuid: String): Int {
		val url = "http://localhost:${joarkMockContainer.firstMappedPort}/joark/mock/response-behaviour/number-of-calls/$uuid"
		val responseEntity = restTemplate.getForEntity(url, Int::class.java)
		return responseEntity.body ?: -1
	}

	private fun verifyDataInJoark(dto: SoknadInnsendtDto) {
		val key = dto.innsendingsId
		val url = "http://localhost:${joarkMockContainer.firstMappedPort}/joark/lookup/$key"

		val responseEntity: ResponseEntity<List<LinkedHashMap<String, String>>> = pollJoarkUntilTimeout(url)

		if (!responseEntity.hasBody())
			fail("Failed to get response from Joark")
		assertEquals(dto.tema, responseEntity.body?.get(0)!!["message"])
		assertEquals(dto.innsendingsId, responseEntity.body?.get(0)!!["name"])
	}

	private fun pollAndVerifyNumberOfCallsToJoark(uuid: String, expectedNumberOfCalls: Int) {
		pollAndVerifyResult("calls to Joark", expectedNumberOfCalls) { getNumberOfCallsToJoark(uuid) }
	}

	private fun pollAndVerifyDataInFileStorage(uuid: String, expectedNumberOfHits: Int) {
		val url = "http://localhost:${soknadsfillagerContainer.firstMappedPort}/filer?ids=$uuid"
		pollAndVerifyResult("files in File Storage", expectedNumberOfHits) { getNumberOfFiles(url) }
	}

	private fun pollAndVerifyResult(context: String, expected: Int, function: () -> Int) {
		val startTime = System.currentTimeMillis()
		val timeout = 10 * 1000

		var result = -1
		while (System.currentTimeMillis() < startTime + timeout) {
			result = function.invoke()

			if (result == expected) {
				return
			}
			TimeUnit.MILLISECONDS.sleep(50)
		}
		fail("Expected $expected $context, but saw $result")
	}

	private fun getNumberOfFiles(url: String): Int {
		val headers = createHeaders(filleserUsername, filleserPassword)

		val request = RequestEntity<Any>(headers, HttpMethod.GET, URI(url))

		val response = restTemplate.exchange(request, typeRef<List<FilElementDto>>()).body

		return response?.filter { it.fil != null }?.size ?: 0
	}

	private fun <T> pollJoarkUntilTimeout(url: String): ResponseEntity<List<T>> {

		val respType = object : ParameterizedTypeReference<List<T>>() {}

		val startTime = System.currentTimeMillis()
		val timeout = 10 * 1000

		while (System.currentTimeMillis() < startTime + timeout) {
			val responseEntity = restTemplate.exchange(url, HttpMethod.GET, null, respType)

			if (responseEntity.body != null && responseEntity.body!!.isNotEmpty()) {
				return responseEntity
			}
			TimeUnit.MILLISECONDS.sleep(50)
		}
		return ResponseEntity.of(Optional.empty())
	}

	private fun sendFilesToFileStorage(uuid: String) {
		val files = listOf(FilElementDto(uuid, "apabepa".toByteArray()))
		val url = "http://localhost:${soknadsfillagerContainer.firstMappedPort}/filer"

		performPostRequest(files, url, createHeaders(filleserUsername, filleserPassword))
		pollAndVerifyDataInFileStorage(uuid, 1)
	}

	private fun sendDataToMottaker(dto: SoknadInnsendtDto) {
		val url = "http://localhost:${soknadsmottakerContainer.firstMappedPort}/save"
		performPostRequest(dto, url, createHeaders(mottakerUsername, mottakerPassword))
	}

	private fun createHeaders(username: String, password: String): HttpHeaders {
		return object : HttpHeaders() {
			init {
				val auth = "$username:$password"
				val encodedAuth: ByteArray = encodeBase64(auth.toByteArray())
				val authHeader = "Basic " + String(encodedAuth)
				set("Authorization", authHeader)
			}
		}
	}

	private fun performPostRequest(payload: Any, url: String, headers: HttpHeaders = HttpHeaders()) {
		headers.contentType = MediaType.APPLICATION_JSON
		val request = HttpEntity(payload, headers)
		restTemplate.postForObject(url, request, String::class.java)
	}

	private fun createDto(uuid: String) = SoknadInnsendtDto(UUID.randomUUID().toString(), false, "personId", "tema", LocalDateTime.now(),
		listOf(InnsendtDokumentDto("NAV 10-07.17", true, "Søknad om refusjon av reiseutgifter - bil",
			listOf(InnsendtVariantDto(uuid, null, "filnavn", "1024", "variantformat", "PDFA")))))
}

inline fun <reified T : Any> typeRef(): ParameterizedTypeReference<T> = object : ParameterizedTypeReference<T>() {}

class KGenericContainer(imageName: String) : GenericContainer<KGenericContainer>(imageName)

class KPostgreSQLContainer : PostgreSQLContainer<KPostgreSQLContainer>()
