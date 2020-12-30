package no.nav.soknad.arkivering.arkiveringendtoendtests

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import no.nav.soknad.arkivering.arkiveringendtoendtests.dto.*
import no.nav.soknad.arkivering.arkiveringendtoendtests.kafka.ArkivMockKafkaListener
import no.nav.soknad.arkivering.arkiveringendtoendtests.kafka.KafkaProperties
import no.nav.soknad.arkivering.arkiveringendtoendtests.kafka.KafkaPublisher
import no.nav.soknad.arkivering.arkiveringendtoendtests.locks.VerificationTask
import no.nav.soknad.arkivering.arkiveringendtoendtests.locks.VerificationTaskManager
import no.nav.soknad.arkivering.avroschemas.*
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
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
		it["arkiv-mock"] = 8092
	}
	private val kafkaBrokerPort = 9092
	private val schemaRegistryPort = 8081

	private val useTestcontainers = System.getProperty("useTestcontainers")?.toBoolean() ?: true

	private val attemptsThanSoknadsarkivererWillPerform = 6

	private val mottakerUsername = "avsender"
	private val mottakerPassword = "password"
	private val filleserUsername = "arkiverer"
	private val filleserPassword = "password"
	private val kafkaProperties = KafkaProperties()

	private val restClient = OkHttpClient()
	private val objectMapper = ObjectMapper().also { it.findAndRegisterModules() }
	private lateinit var kafkaPublisher: KafkaPublisher
	private lateinit var arkivMockKafkaListener: ArkivMockKafkaListener

	private lateinit var postgresContainer: KPostgreSQLContainer
	private lateinit var kafkaContainer: KafkaContainer
	private lateinit var schemaRegistryContainer: KGenericContainer
	private lateinit var arkivMockContainer: KGenericContainer
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
		arkivMockKafkaListener = ArkivMockKafkaListener(getPortForKafkaBroker(), getPortForSchemaRegistry())
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
		createTopic(kafkaProperties.entitiesTopic)
		createTopic(kafkaProperties.numberOfCallsTopic)
		createTopic(kafkaProperties.numberOfEntitiesTopic)


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
		arkivMockKafkaListener.close()
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
		val fileId = UUID.randomUUID().toString()
		val dto = createDto(fileId)
		setNormalArchiveBehaviour(dto.innsendingsId)

		sendFilesToFileStorage(fileId)
		sendDataToMottaker(dto)

		assertThatArkivMock()
			.containsData(dto andWasCalled times(1))
			.verify()
		pollAndVerifyDataInFileStorage(fileId, 0)
	}

	@Test
	fun `Poison pill followed by proper message - one file ends up in the archive`() {
		val fileId = UUID.randomUUID().toString()
		val dto = createDto(fileId)
		setNormalArchiveBehaviour(dto.innsendingsId)

		putPoisonPillOnKafkaTopic(UUID.randomUUID().toString())
		sendFilesToFileStorage(fileId)
		sendDataToMottaker(dto)

		assertThatArkivMock()
			.containsData(dto andWasCalled times(1))
			.verify()
		pollAndVerifyDataInFileStorage(fileId, 0)
	}

	@Test
	fun `Happy case - several files in file storage - one file ends up in the archive`() {
		val fileId0 = UUID.randomUUID().toString()
		val fileId1 = UUID.randomUUID().toString()
		val dto = SoknadInnsendtDto(UUID.randomUUID().toString(), false, "personId", "tema", LocalDateTime.now(),
			listOf(
				InnsendtDokumentDto("NAV 10-07.17", true, "Søknad om refusjon av reiseutgifter - bil",
					listOf(InnsendtVariantDto(fileId0, null, "filnavn", "1024", "variantformat", "PDFA"))),

				InnsendtDokumentDto("NAV 10-07.17", false, "Søknad om refusjon av reiseutgifter - bil",
					listOf(InnsendtVariantDto(fileId1, null, "filnavn", "1024", "variantformat", "PDFA")))
			))
		setNormalArchiveBehaviour(dto.innsendingsId)

		sendFilesToFileStorage(fileId0)
		sendFilesToFileStorage(fileId1)
		sendDataToMottaker(dto)

		assertThatArkivMock()
			.containsData(dto andWasCalled times(1))
			.verify()
		pollAndVerifyDataInFileStorage(fileId0, 0)
		pollAndVerifyDataInFileStorage(fileId1, 0)
	}

	@Test
	fun `No files in file storage - Nothing is sent to the archive`() {
		val fileId = UUID.randomUUID().toString()
		val dto = createDto(fileId)
		setNormalArchiveBehaviour(dto.innsendingsId)

		pollAndVerifyDataInFileStorage(fileId, 0)
		sendDataToMottaker(dto)

		assertThatArkivMock()
			.doesNotContainKey(dto.innsendingsId)
			.verify()
		pollAndVerifyDataInFileStorage(fileId, 0)
	}

	@Test
	fun `Several Hovedskjemas - Nothing is sent to the archive`() {
		val fileId0 = UUID.randomUUID().toString()
		val fileId1 = UUID.randomUUID().toString()
		val dto = SoknadInnsendtDto(UUID.randomUUID().toString(), false, "personId", "tema", LocalDateTime.now(),
			listOf(
				InnsendtDokumentDto("NAV 10-07.17", true, "Søknad om refusjon av reiseutgifter - bil",
					listOf(InnsendtVariantDto(fileId0, null, "filnavn", "1024", "variantformat", "PDFA"))),

				InnsendtDokumentDto("NAV 10-07.17", true, "Søknad om refusjon av reiseutgifter - bil",
					listOf(InnsendtVariantDto(fileId1, null, "filnavn", "1024", "variantformat", "PDFA")))
			))
		setNormalArchiveBehaviour(dto.innsendingsId)

		sendFilesToFileStorage(fileId0)
		sendFilesToFileStorage(fileId1)
		sendDataToMottaker(dto)

		assertThatArkivMock()
			.doesNotContainKey(dto.innsendingsId)
			.verify()
		pollAndVerifyDataInFileStorage(fileId0, 1)
		pollAndVerifyDataInFileStorage(fileId1, 1)
	}

	@Test
	fun `Archive responds 404 on first two attempts - Works on third attempt`() {
		val erroneousAttempts = 2
		val fileId = UUID.randomUUID().toString()
		val dto = createDto(fileId)

		sendFilesToFileStorage(fileId)
		mockArchiveRespondsWithCodeForXAttempts(dto.innsendingsId, 404, erroneousAttempts)
		sendDataToMottaker(dto)

		assertThatArkivMock()
			.containsData(dto andWasCalled times(erroneousAttempts + 1))
			.verify()
		pollAndVerifyDataInFileStorage(fileId, 0)
	}

	@Test
	fun `Archive responds 500 on first attempt - Works on second attempt`() {
		val erroneousAttempts = 1
		val fileId = UUID.randomUUID().toString()
		val dto = createDto(fileId)

		sendFilesToFileStorage(fileId)
		mockArchiveRespondsWithCodeForXAttempts(dto.innsendingsId, 500, erroneousAttempts)
		sendDataToMottaker(dto)

		assertThatArkivMock()
			.containsData(dto andWasCalled times(erroneousAttempts + 1))
			.verify()
		pollAndVerifyDataInFileStorage(fileId, 0)
	}

	@Test
	fun `Archive responds 200 but has wrong response body - Will retry`() {
		val erroneousAttempts = 3
		val fileId = UUID.randomUUID().toString()
		val dto = createDto(fileId)

		sendFilesToFileStorage(fileId)
		mockArchiveRespondsWithErroneousBodyForXAttempts(dto.innsendingsId, erroneousAttempts)
		sendDataToMottaker(dto)

		assertThatArkivMock()
			.containsData(dto andWasCalled times(erroneousAttempts + 1))
			.verify()
		pollAndVerifyDataInFileStorage(fileId, 0)
	}

	@Test
	fun `Archive responds 200 but has wrong response body - Will retry until soknadsarkiverer gives up`() {
		val fileId = UUID.randomUUID().toString()
		val dto = createDto(fileId)
		val moreAttemptsThanSoknadsarkivererWillPerform = attemptsThanSoknadsarkivererWillPerform + 1

		sendFilesToFileStorage(fileId)
		mockArchiveRespondsWithErroneousBodyForXAttempts(dto.innsendingsId, moreAttemptsThanSoknadsarkivererWillPerform)
		sendDataToMottaker(dto)

		assertThatArkivMock()
			.containsData(dto andWasCalled times(attemptsThanSoknadsarkivererWillPerform))
			.verify()
		pollAndVerifyDataInFileStorage(fileId, 1)
	}

	@DisabledIfSystemProperty(named = "useTestcontainers", matches = "false")
	@Test
	fun `Put input event on Kafka when Soknadsarkiverer is down - will start up and send to the archive`() {
		val key = UUID.randomUUID().toString()
		val fileId = UUID.randomUUID().toString()
		val innsendingsId = UUID.randomUUID().toString()
		setNormalArchiveBehaviour(innsendingsId)

		sendFilesToFileStorage(fileId)

		shutDownSoknadsarkiverer()
		putInputEventOnKafkaTopic(key, innsendingsId, fileId)
		startUpSoknadsarkiverer()

		assertThatArkivMock()
			.containsData(createDto(fileId, innsendingsId) andWasCalled times(1))
			.verify()
		pollAndVerifyDataInFileStorage(fileId, 0)
	}

	@DisabledIfSystemProperty(named = "useTestcontainers", matches = "false")
	@Test
	fun `Put input event and processing events on Kafka when Soknadsarkiverer is down - will start up and send to the archive`() {
		val key = UUID.randomUUID().toString()
		val fileId = UUID.randomUUID().toString()
		val innsendingsId = UUID.randomUUID().toString()
		setNormalArchiveBehaviour(innsendingsId)

		sendFilesToFileStorage(fileId)

		shutDownSoknadsarkiverer()
		putInputEventOnKafkaTopic(key, innsendingsId, fileId)
		putProcessingEventOnKafkaTopic(key, EventTypes.RECEIVED, EventTypes.STARTED, EventTypes.STARTED)
		startUpSoknadsarkiverer()

		assertThatArkivMock()
			.containsData(createDto(fileId, innsendingsId) andWasCalled times(1))
			.verify()
		pollAndVerifyDataInFileStorage(fileId, 0)
	}

	@DisabledIfSystemProperty(named = "useTestcontainers", matches = "false")
	@Test
	fun `Soknadsarkiverer restarts before finishing to put input event in the archive - will pick event up and send to the archive`() {
		val fileId = UUID.randomUUID().toString()
		val dto = createDto(fileId)

		sendFilesToFileStorage(fileId)
		mockArchiveRespondsWithCodeForXAttempts(dto.innsendingsId, 404, attemptsThanSoknadsarkivererWillPerform + 1)
		sendDataToMottaker(dto)
		assertThatArkivMock()
			.hasBeenCalled(attemptsThanSoknadsarkivererWillPerform timesForKey dto.innsendingsId)
			.verify()
		mockArchiveRespondsWithCodeForXAttempts(dto.innsendingsId, 500, 1)
		TimeUnit.SECONDS.sleep(1)

		shutDownSoknadsarkiverer()
		startUpSoknadsarkiverer()

		assertThatArkivMock()
			.containsData(dto andWasCalled times(attemptsThanSoknadsarkivererWillPerform + 1))
			.verify()
		pollAndVerifyDataInFileStorage(fileId, 0)
	}

	@DisabledIfSystemProperty(named = "useTestcontainers", matches = "false")
	@Test
	fun `Put finished input event on Kafka and send a new input event when Soknadsarkiverer is down - only the new input event ends up in the archive`() {
		val finishedKey = UUID.randomUUID().toString()
		val finishedFileId = UUID.randomUUID().toString()
		val newFileId = UUID.randomUUID().toString()
		val finishedInnsendingsId = UUID.randomUUID().toString()
		val newInnsendingsId = UUID.randomUUID().toString()

		val newDto = createDto(newFileId, newInnsendingsId)
		setNormalArchiveBehaviour(finishedInnsendingsId)
		setNormalArchiveBehaviour(newInnsendingsId)

		sendFilesToFileStorage(finishedFileId)
		sendFilesToFileStorage(newFileId)

		shutDownSoknadsarkiverer()
		putInputEventOnKafkaTopic(finishedKey, finishedInnsendingsId, finishedFileId)
		putProcessingEventOnKafkaTopic(finishedKey, EventTypes.RECEIVED, EventTypes.STARTED, EventTypes.FINISHED)
		sendDataToMottaker(newDto)
		startUpSoknadsarkiverer()

		assertThatArkivMock()
			.containsData(newDto andWasCalled times(1))
			.doesNotContainKey(finishedInnsendingsId)
			.verify()
		pollAndVerifyDataInFileStorage(finishedFileId, 1)
		pollAndVerifyDataInFileStorage(newFileId, 0)
	}

	private fun assertThatArkivMock(): VerificationTaskManager {
		arkivMockKafkaListener.clearVerifiers()
		return VerificationTaskManager()
	}

	private fun VerificationTaskManager.containsData(pair: Pair<SoknadInnsendtDto, Int>): VerificationTaskManager {
		val (countVerifier, valueVerifier) = createVerificationTasksForEntityAndCount(this, pair.first, pair.second)
		return registerVerificationTasks(countVerifier, valueVerifier)
	}

	private fun VerificationTaskManager.hasBeenCalled(pair: Pair<String, Int>): VerificationTaskManager {
		val countVerifier = createVerificationTaskForCount(this, pair.first, pair.second)
		this.registerTasks(listOf(countVerifier))
		arkivMockKafkaListener.addVerifierForNumberOfCalls(countVerifier)
		return this
	}

	private fun VerificationTaskManager.doesNotContainKey(key: String): VerificationTaskManager {
		val (countVerifier, valueVerifier) = createVerificationTaskForAbsenceOfKey(this, key)
		return registerVerificationTasks(countVerifier, valueVerifier)
	}

	private fun VerificationTaskManager.registerVerificationTasks(
		countVerifier: VerificationTask<Int>,
		valueVerifier: VerificationTask<ArkivDbData>
	): VerificationTaskManager {

		this.registerTasks(listOf(countVerifier, valueVerifier))
		arkivMockKafkaListener.addVerifierForEntities(valueVerifier)
		arkivMockKafkaListener.addVerifierForNumberOfCalls(countVerifier)
		return this
	}

	private fun VerificationTaskManager.verify() {
		this.assertAllTasksSucceeds()
	}

	private infix fun SoknadInnsendtDto.andWasCalled(count: Int) = this to count

	private infix fun Int.timesForKey(key: String) = key to this

	/**
	 * Syntactic sugar to make tests read nicer. The function just returns its parameter back
	 */
	private fun times(count: Int) = count


	private fun createVerificationTasksForEntityAndCount(
		manager: VerificationTaskManager,
		dto: SoknadInnsendtDto,
		expectedCount: Int
	): Pair<VerificationTask<Int>, VerificationTask<ArkivDbData>> {

		val countVerifier = createVerificationTaskForCount(manager, dto.innsendingsId, expectedCount)

		val valueVerifier = VerificationTask.Builder<ArkivDbData>()
			.withManager(manager)
			.forKey(dto.innsendingsId)
			.verifyPresence()
			.verifyThat(dto.innsendingsId, { entity -> entity.id }, "Assert correct entity id")
			.verifyThat(dto.tema, { entity -> entity.tema }, "Assert correct entity tema")
			.verifyThat(dto.innsendteDokumenter[0].tittel, { entity -> entity.title }, "Assert correct entity title")
			.build()

		return countVerifier to valueVerifier
	}

	private fun createVerificationTaskForCount(manager: VerificationTaskManager, key: String, expectedCount: Int) =
		VerificationTask.Builder<Int>()
			.withManager(manager)
			.forKey(key)
			.verifyPresence()
			.verifyThat(expectedCount, { count -> count }, "Assert correct number of attempts to save to the Archive")
			.build()

	private fun createVerificationTaskForAbsenceOfKey(
		manager: VerificationTaskManager,
		key: String
	): Pair<VerificationTask<Int>, VerificationTask<ArkivDbData>> {

		val countVerifier = VerificationTask.Builder<Int>()
			.withManager(manager)
			.forKey(key)
			.verifyAbsence()
			.build()

		val valueVerifier = VerificationTask.Builder<ArkivDbData>()
			.withManager(manager)
			.forKey(key)
			.verifyAbsence()
			.build()

		return countVerifier to valueVerifier
	}

	private fun getPortForSoknadsarkiverer() = if (useTestcontainers) soknadsarkivererContainer.firstMappedPort else dependencies["soknadsarkiverer"]
	private fun getPortForSoknadsmottaker() = if (useTestcontainers) soknadsmottakerContainer.firstMappedPort else dependencies["soknadsmottaker"]
	private fun getPortForSoknadsfillager() = if (useTestcontainers) soknadsfillagerContainer.firstMappedPort else dependencies["soknadsfillager"]
	private fun getPortForArkivMock() = if (useTestcontainers) arkivMockContainer.firstMappedPort else dependencies["arkiv-mock"]
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


	private fun setNormalArchiveBehaviour(uuid: String) {
		val url = "http://localhost:${getPortForArkivMock()}/arkiv-mock/response-behaviour/set-normal-behaviour/$uuid"
		performPutCall(url)
	}

	private fun mockArchiveRespondsWithCodeForXAttempts(uuid: String, status: Int, forAttempts: Int) {
		val url = "http://localhost:${getPortForArkivMock()}/arkiv-mock/response-behaviour/mock-response/$uuid/$status/$forAttempts"
		performPutCall(url)
	}

	private fun mockArchiveRespondsWithErroneousBodyForXAttempts(uuid: String, forAttempts: Int) {
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

		restClient.newCall(request).execute().close()
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
