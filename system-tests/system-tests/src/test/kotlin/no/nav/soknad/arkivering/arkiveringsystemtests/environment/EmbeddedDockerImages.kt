package no.nav.soknad.arkivering.arkiveringsystemtests.environment

import no.nav.soknad.arkivering.defaultPorts
import no.nav.soknad.arkivering.defaultProperties
import org.junit.jupiter.api.fail
import org.slf4j.LoggerFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.time.Duration

class EmbeddedDockerImages {
	private val logger = LoggerFactory.getLogger(javaClass)

	private val postgresUsername = "postgres"
	private val databaseName = "postgres"

	private lateinit var postgresContainer: KPostgreSQLContainer
	private lateinit var kafkaContainer: KafkaContainer
	private lateinit var schemaRegistryContainer: KGenericContainer
	private lateinit var arkivMockContainer: KGenericContainer
	private lateinit var soknadsfillagerContainer: KGenericContainer
	private lateinit var soknadsmottakerContainer: KGenericContainer
	private lateinit var soknadsarkivererContainer: KGenericContainer

	private var soknadsarkivererLogs = ""


	@Suppress("HttpUrlsUsage")
	fun startContainers() {
		val network = Network.newNetwork()

		postgresContainer = KPostgreSQLContainer()
			.withNetworkAliases("postgres")
			.withExposedPorts(defaultPorts["database"])
			.withNetwork(network)
			.withUsername(postgresUsername)
			.withPassword(postgresUsername)
			.withDatabaseName(databaseName)

		kafkaContainer = KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka"))
			.withNetworkAliases("kafka-broker")
			.withNetwork(network)

		postgresContainer.start()
		kafkaContainer.start()

		createTopic(defaultProperties["KAFKA_MAIN_TOPIC"]!!)
		createTopic(defaultProperties["KAFKA_PROCESSING_TOPIC"]!!)
		createTopic(defaultProperties["KAFKA_MESSAGE_TOPIC"]!!)
		createTopic(defaultProperties["KAFKA_METRICS_TOPIC"]!!)
		createTopic(defaultProperties["KAFKA_ENTITIES_TOPIC"]!!)
		createTopic(defaultProperties["KAFKA_NUMBER_OF_CALLS_TOPIC"]!!)
		createTopic(defaultProperties["KAFKA_BRUKERNOTIFIKASJON_DONE_TOPIC"]!!)
		createTopic(defaultProperties["KAFKA_BRUKERNOTIFIKASJON_BESKJED_TOPIC"]!!)
		createTopic(defaultProperties["KAFKA_BRUKERNOTIFIKASJON_OPPGAVE_TOPIC"]!!)


		schemaRegistryContainer = KGenericContainer("confluentinc/cp-schema-registry")
			.withNetworkAliases("kafka-schema-registry")
			.withExposedPorts(defaultPorts["schema-registry"])
			.withNetwork(network)
			.withEnv(
				hashMapOf(
					"SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS" to "PLAINTEXT://${kafkaContainer.networkAliases[0]}:${defaultPorts["kafka-broker"]}",
					"SCHEMA_REGISTRY_HOST_NAME" to "localhost",
					"SCHEMA_REGISTRY_LISTENERS" to "http://0.0.0.0:${defaultPorts["schema-registry"]}"
				)
			)
			.dependsOn(kafkaContainer)
			.waitingFor(Wait.forHttp("/subjects").forStatusCode(200))

		schemaRegistryContainer.start()


		soknadsfillagerContainer = KGenericContainer("archiving-infrastructure-soknadsfillager")
			.withNetworkAliases("soknadsfillager")
			.withExposedPorts(defaultPorts["soknadsfillager"])
			.withNetwork(network)
			.withEnv(
				hashMapOf(
					"SPRING_PROFILES_ACTIVE" to "docker",
					"DATABASE_PORT"          to defaultPorts["database"].toString(),
					"DATABASE_HOST"          to postgresContainer.networkAliases[0],
					"DATABASE_DATABASE"      to databaseName,
					"DATABASE_USERNAME"      to postgresUsername,
					"DATABASE_PASSWORD"      to postgresUsername,
					"STATUS_LOG_URL"				 to "https://logs.adeo.no"
				)
			)
			.dependsOn(postgresContainer)
			.waitingFor(Wait.forHttp("/internal/health").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(2)))

		soknadsmottakerContainer = KGenericContainer("archiving-infrastructure-soknadsmottaker")
			.withNetworkAliases("soknadsmottaker")
			.withExposedPorts(defaultPorts["soknadsmottaker"])
			.withNetwork(network)
			.withEnv(
				hashMapOf(
					"SPRING_PROFILES_ACTIVE" to "docker",
					"NAIS_NAMESPACE"         to "team-soknad",
					"KAFKA_SECURITY"         to "FALSE",
					"KAFKA_SCHEMA_REGISTRY"  to "http://${schemaRegistryContainer.networkAliases[0]}:${defaultPorts["schema-registry"]}",
					"KAFKA_BROKERS"          to "${kafkaContainer.networkAliases[0]}:${defaultPorts["kafka-broker"]}",
				)
			)
			.dependsOn(kafkaContainer, schemaRegistryContainer)
			.waitingFor(Wait.forHttp("/internal/health").forStatusCode(200))

		soknadsfillagerContainer.start()
		soknadsmottakerContainer.start()

		soknadsarkivererContainer = KGenericContainer("archiving-infrastructure-soknadsarkiverer")
			.withNetworkAliases("soknadsarkiverer")
			.withExposedPorts(defaultPorts["soknadsarkiverer"])
			.withNetwork(network)
			.withEnv(
				hashMapOf(
					"SPRING_PROFILES_ACTIVE"  to "endtoend",
					"BOOTSTRAPPING_TIMEOUT"   to "60",
					"TASK_STARTUP_INIT_DELAY" to "8",
					"KAFKA_BROKERS"           to "${kafkaContainer.networkAliases[0]}:${defaultPorts["kafka-broker"]}",
					"KAFKA_SCHEMA_REGISTRY"   to "http://${schemaRegistryContainer.networkAliases[0]}:${defaultPorts["schema-registry"]}",
					"FILESTORAGE_HOST"        to "http://${soknadsfillagerContainer.networkAliases[0]}:${defaultPorts["soknadsfillager"]}",
					"JOARK_HOST"              to "http://${arkivMockContainer.networkAliases[0]}:${defaultPorts["arkiv-mock"]}",
					"SEND_TO_JOARK"           to "true",
					"INNSENDING_API_HOST"     to "http://${arkivMockContainer.networkAliases[0]}:${defaultPorts["arkiv-mock"]}",
					"SAF_URL"									to "http://${arkivMockContainer.networkAliases[0]}:${defaultPorts["arkiv-mock"]}",
					"AZURE_APP_WELL_KNOWN_URL" to "http://metadata",
					"AZURE_APP_CLIENT_ID"			to "aud-localhost",
					"AZURE_OPENID_CONFIG_TOKEN_ENDPOINT" to "http://metadata",
					"AZURE_APP_CLIENT_SECRET" to "secret",
					"STATUS_LOG_URL"					to "https://logs.adeo.no"
				)
			)
			.dependsOn(kafkaContainer, schemaRegistryContainer, soknadsfillagerContainer, arkivMockContainer)
			.waitingFor(Wait.forHttp("/internal/health").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)))

		soknadsarkivererContainer.start()

		arkivMockContainer = KGenericContainer("archiving-infrastructure-arkiv-mock")
			.withNetworkAliases("arkiv-mock")
			.withExposedPorts(defaultPorts["arkiv-mock"])
			.withNetwork(network)
			.withEnv(
				hashMapOf(
					"SPRING_PROFILES_ACTIVE" to "docker",
					"KAFKA_SECURITY"         to "FALSE",
					"KAFKA_BROKERS"          to "${kafkaContainer.networkAliases[0]}:${defaultPorts["kafka-broker"]}",
				)
			)
			.dependsOn(kafkaContainer)
			.waitingFor(Wait.forHttp("/internal/health").forStatusCode(200))

		arkivMockContainer.start()

	}

	private fun createTopic(topic: String) {
		val topicCommand =
			"/usr/bin/kafka-topics --create --bootstrap-server=localhost:${defaultPorts["kafka-broker"]} " +
				"--replication-factor 1 --partitions 1 --topic $topic"

		try {
			val result = kafkaContainer.execInContainer("/bin/sh", "-c", topicCommand)
			if (result.exitCode != 0) {
				logger.error("\n\nKafka Container logs:\n${kafkaContainer.logs}")
				fail("Failed to create topic '$topic'. Error:\n${result.stderr}")
			}
		} catch (e: Exception) {
			e.printStackTrace()
			fail("Failed to create topic '$topic'")
		}
	}

	fun stopContainers() {
		fun createHeader(name: String): String {
			val box = "=".repeat(9 + name.length)
			return "\n\n$box\n= Logs $name =\n$box\n"
		}
		logger.info(createHeader("soknadsfillager") + soknadsfillagerContainer.logs)
		logger.info(createHeader("soknadsmottaker") + soknadsmottakerContainer.logs)
		logger.info(createHeader("soknadsarkiverer") + soknadsarkivererContainer.logs)
		logger.info(createHeader("arkiv-mock") + arkivMockContainer.logs)

		soknadsfillagerContainer.stop()
		soknadsmottakerContainer.stop()
		soknadsarkivererContainer.stop()
		arkivMockContainer.stop()

		postgresContainer.stop()
		kafkaContainer.stop()
		schemaRegistryContainer.stop()
	}


	fun shutDownSoknadsarkiverer() {
		soknadsarkivererLogs += soknadsarkivererContainer.logs + "\n"
		soknadsarkivererContainer.stop()
	}

	fun startUpSoknadsarkiverer() {
		soknadsarkivererContainer.start()
	}


	fun getUrlForSoknadsfillager()  = "http://localhost:" + soknadsfillagerContainer .firstMappedPort
	fun getUrlForArkivMock()        = "http://localhost:" + arkivMockContainer       .firstMappedPort
	fun getUrlForSoknadsarkiverer() = "http://localhost:" + soknadsarkivererContainer.firstMappedPort
	fun getUrlForSoknadsmottaker()  = "http://localhost:" + soknadsmottakerContainer .firstMappedPort
	fun getUrlForSchemaRegistry()   = "http://localhost:" + schemaRegistryContainer  .firstMappedPort
	fun getUrlForKafkaBroker()      = "localhost:"        + kafkaContainer           .firstMappedPort
}


class KGenericContainer(imageName: String) : GenericContainer<KGenericContainer>(imageName)

class KPostgreSQLContainer : PostgreSQLContainer<KPostgreSQLContainer>(DockerImageName.parse("postgres"))
