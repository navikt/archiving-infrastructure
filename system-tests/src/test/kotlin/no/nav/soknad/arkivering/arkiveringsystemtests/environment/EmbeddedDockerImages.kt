package no.nav.soknad.arkivering.arkiveringsystemtests.environment

import no.nav.soknad.arkivering.kafka.KafkaProperties
import org.junit.jupiter.api.fail
import org.testcontainers.containers.*
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.time.Duration

class EmbeddedDockerImages {
	private val postgresUsername = "postgres"
	private val databaseName = "soknadsfillager"
	private val kafkaProperties = KafkaProperties()

	private lateinit var postgresContainer: KPostgreSQLContainer
	private lateinit var kafkaContainer: KafkaContainer
	private lateinit var schemaRegistryContainer: KGenericContainer
	private lateinit var arkivMockContainer: KGenericContainer
	private lateinit var soknadsfillagerContainer: KGenericContainer
	private lateinit var soknadsmottakerContainer: KGenericContainer
	private lateinit var soknadsarkivererContainer: KGenericContainer

	private var soknadsarkivererLogs = ""


	fun startContainers() {
		val network = Network.newNetwork()

		postgresContainer = KPostgreSQLContainer()
			.withNetworkAliases("postgres")
			.withExposedPorts(defaultPorts["database"])
			.withNetwork(network)
			.withUsername(postgresUsername)
			.withPassword(postgresUsername)
			.withDatabaseName(databaseName)

		kafkaContainer = KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:5.4.3"))
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
			.withExposedPorts(defaultPorts["schema-registry"])
			.withNetwork(network)
			.withEnv(hashMapOf(
				"SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS" to "PLAINTEXT://${kafkaContainer.networkAliases[0]}:${defaultPorts["kafka-broker"]}",
				"SCHEMA_REGISTRY_HOST_NAME" to "localhost",
				"SCHEMA_REGISTRY_LISTENERS" to "http://0.0.0.0:${defaultPorts["schema-registry"]}"))
			.dependsOn(kafkaContainer)
			.waitingFor(Wait.forHttp("/subjects").forStatusCode(200))

		soknadsfillagerContainer = KGenericContainer("archiving-infrastructure_soknadsfillager")
			.withNetworkAliases("soknadsfillager")
			.withExposedPorts(defaultPorts["soknadsfillager"])
			.withNetwork(network)
			.withEnv(hashMapOf(
				"SPRING_PROFILES_ACTIVE" to "docker",
				"DATABASE_JDBC_URL" to "jdbc:postgresql://${postgresContainer.networkAliases[0]}:${defaultPorts["database"]}/$databaseName",
				"DATABASE_NAME" to databaseName,
				"APPLICATION_USERNAME" to postgresUsername,
				"APPLICATION_PASSWORD" to postgresUsername))
			.dependsOn(postgresContainer)
			.waitingFor(Wait.forHttp("/internal/health").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(2)))

		schemaRegistryContainer.start()
		soknadsfillagerContainer.start()

		arkivMockContainer = KGenericContainer("archiving-infrastructure_arkiv-mock")
			.withNetworkAliases("arkiv-mock")
			.withExposedPorts(defaultPorts["arkiv-mock"])
			.withNetwork(network)
			.withEnv(hashMapOf(
				"SPRING_PROFILES_ACTIVE" to "docker",
				"KAFKA_BOOTSTRAP_SERVERS" to "${kafkaContainer.networkAliases[0]}:${defaultPorts["kafka-broker"]}",
				"SCHEMA_REGISTRY_URL" to "http://${schemaRegistryContainer.networkAliases[0]}:${defaultPorts["schema-registry"]}"))
			.dependsOn(kafkaContainer, schemaRegistryContainer)
			.waitingFor(Wait.forHttp("/internal/health").forStatusCode(200))

		arkivMockContainer.start()

		soknadsmottakerContainer = KGenericContainer("archiving-infrastructure_soknadsmottaker")
			.withNetworkAliases("soknadsmottaker")
			.withExposedPorts(defaultPorts["soknadsmottaker"])
			.withNetwork(network)
			.withEnv(hashMapOf(
				"SPRING_PROFILES_ACTIVE" to "docker",
				"KAFKA_BOOTSTRAP_SERVERS" to "${kafkaContainer.networkAliases[0]}:${defaultPorts["kafka-broker"]}",
				"SCHEMA_REGISTRY_URL" to "http://${schemaRegistryContainer.networkAliases[0]}:${defaultPorts["schema-registry"]}"))
			.dependsOn(kafkaContainer, schemaRegistryContainer)
			.waitingFor(Wait.forHttp("/internal/health").forStatusCode(200))

		soknadsarkivererContainer = KGenericContainer("archiving-infrastructure_soknadsarkiverer")
			.withNetworkAliases("soknadsarkiverer")
			.withExposedPorts(defaultPorts["soknadsarkiverer"])
			.withNetwork(network)
			.withEnv(hashMapOf(
				"SPRING_PROFILES_ACTIVE" to "test",
				"BOOTSTRAPPING_TIMEOUT" to "10",
				"KAFKA_BOOTSTRAP_SERVERS" to "${kafkaContainer.networkAliases[0]}:${defaultPorts["kafka-broker"]}",
				"SCHEMA_REGISTRY_URL" to "http://${schemaRegistryContainer.networkAliases[0]}:${defaultPorts["schema-registry"]}",
				"FILESTORAGE_HOST" to "http://${soknadsfillagerContainer.networkAliases[0]}:${defaultPorts["soknadsfillager"]}",
				"JOARK_HOST" to "http://${arkivMockContainer.networkAliases[0]}:${defaultPorts["arkiv-mock"]}"))
			.dependsOn(kafkaContainer, schemaRegistryContainer, soknadsfillagerContainer, arkivMockContainer)
			.waitingFor(Wait.forHttp("/internal/health").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)))

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

	fun stopContainers() {
		fun createHeader(name: String): String {
			val box = "=".repeat(9 + name.length)
			return "\n\n$box\n= Logs $name =\n$box\n"
		}
		println(createHeader("soknadsfillager") + soknadsfillagerContainer.logs)
		println(createHeader("soknadsmottaker") + soknadsmottakerContainer.logs)
		println(createHeader("soknadsarkiverer") + soknadsarkivererContainer.logs)
		println(createHeader("arkiv-mock") + arkivMockContainer.logs)

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
