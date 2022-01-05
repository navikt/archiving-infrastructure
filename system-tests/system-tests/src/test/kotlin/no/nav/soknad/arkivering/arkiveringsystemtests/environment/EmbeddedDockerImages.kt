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

		createTopic(defaultProperties["KAFKA_INPUT_TOPIC"]!!)
		createTopic(defaultProperties["KAFKA_PROCESSING_TOPIC"]!!)
		createTopic(defaultProperties["KAFKA_MESSAGE_TOPIC"]!!)
		createTopic(defaultProperties["KAFKA_METRICS_TOPIC"]!!)
		createTopic(defaultProperties["KAFKA_ENTITIES_TOPIC"]!!)
		createTopic(defaultProperties["KAFKA_NUMBER_OF_CALLS_TOPIC"]!!)


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

		soknadsfillagerContainer = KGenericContainer("archiving-infrastructure_soknadsfillager")
			.withNetworkAliases("soknadsfillager")
			.withExposedPorts(defaultPorts["soknadsfillager"])
			.withNetwork(network)
			.withEnv(
				hashMapOf(
					"APPLICATION_PROFILE" to "docker",
					"DATABASE_JDBC_URL" to "jdbc:postgresql://${postgresContainer.networkAliases[0]}:${defaultPorts["database"]}/$databaseName",
					"DATABASE_NAME" to databaseName,
					"APPLICATION_USERNAME" to postgresUsername,
					"APPLICATION_PASSWORD" to postgresUsername
				)
			)
			.dependsOn(postgresContainer)
			.waitingFor(Wait.forHttp("/internal/health").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(2)))

		soknadsmottakerContainer = KGenericContainer("archiving-infrastructure_soknadsmottaker")
			.withNetworkAliases("soknadsmottaker")
			.withExposedPorts(defaultPorts["soknadsmottaker"])
			.withNetwork(network)
			.withEnv(
				hashMapOf(
					"KAFKA_BOOTSTRAP_SERVERS" to "${kafkaContainer.networkAliases[0]}:${defaultPorts["kafka-broker"]}",
					"SCHEMA_REGISTRY_URL" to "http://${schemaRegistryContainer.networkAliases[0]}:${defaultPorts["schema-registry"]}"
				)
			)
			.dependsOn(kafkaContainer, schemaRegistryContainer)
			.waitingFor(Wait.forHttp("/internal/health").forStatusCode(200))

		soknadsfillagerContainer.start()
		soknadsmottakerContainer.start()

		arkivMockContainer = KGenericContainer("archiving-infrastructure_arkiv-mock")
			.withNetworkAliases("arkiv-mock")
			.withExposedPorts(defaultPorts["arkiv-mock"])
			.withNetwork(network)
			.withEnv(
				hashMapOf(
					"APPLICATION_PROFILE" to "docker",
					"KAFKA_BOOTSTRAP_SERVERS" to "${kafkaContainer.networkAliases[0]}:${defaultPorts["kafka-broker"]}",
					"DATABASE_JDBC_URL" to "jdbc:postgresql://${postgresContainer.networkAliases[0]}:${defaultPorts["database"]}/$databaseName",
					"DATABASE_NAME" to databaseName,
					"APPLICATION_USERNAME" to postgresUsername,
					"APPLICATION_PASSWORD" to postgresUsername
				)
			)
			.dependsOn(postgresContainer, kafkaContainer, schemaRegistryContainer, soknadsfillagerContainer)
			.waitingFor(Wait.forHttp("/internal/health").forStatusCode(200))

		arkivMockContainer.start()

		soknadsarkivererContainer = KGenericContainer("archiving-infrastructure_soknadsarkiverer")
			.withNetworkAliases("soknadsarkiverer")
			.withExposedPorts(defaultPorts["soknadsarkiverer"])
			.withNetwork(network)
			.withEnv(
				hashMapOf(
					"SPRING_PROFILES_ACTIVE" to "test",
					"BOOTSTRAPPING_TIMEOUT" to "60",
					"KAFKA_BOOTSTRAP_SERVERS" to "${kafkaContainer.networkAliases[0]}:${defaultPorts["kafka-broker"]}",
					"SCHEMA_REGISTRY_URL" to "http://${schemaRegistryContainer.networkAliases[0]}:${defaultPorts["schema-registry"]}",
					"FILESTORAGE_HOST" to "http://${soknadsfillagerContainer.networkAliases[0]}:${defaultPorts["soknadsfillager"]}",
					"JOARK_HOST" to "http://${arkivMockContainer.networkAliases[0]}:${defaultPorts["arkiv-mock"]}"
				)
			)
			.dependsOn(kafkaContainer, schemaRegistryContainer, soknadsfillagerContainer, arkivMockContainer)
			.waitingFor(Wait.forHttp("/internal/health").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)))

		soknadsarkivererContainer.start()
	}

	private fun createTopic(topic: String) {
		val topicCommand =
			"/usr/bin/kafka-topics --create --bootstrap-server=localhost:9092 --replication-factor 1 --partitions 1 --topic $topic"

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