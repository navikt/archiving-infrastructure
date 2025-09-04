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
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class EmbeddedDockerImages {
	private val logger = LoggerFactory.getLogger(javaClass)

	private val postgresUsername = "postgres"
	private val databaseName = "postgres"

	private lateinit var gotenbergContainer: KGenericContainer
	private lateinit var postgresInnsendingContainer: KPostgreSQLContainer
	private lateinit var kafkaContainer: KafkaContainer
	private lateinit var schemaRegistryContainer: KGenericContainer
	private lateinit var arkivMockContainer: KGenericContainer
	private lateinit var innsendingApiContainer: KGenericContainer
	private lateinit var soknadsmottakerContainer: KGenericContainer
	private lateinit var soknadsarkivererContainer: KGenericContainer

	private lateinit var cloudStorageInnsendingContainer: KGenericContainer

	private var soknadsarkivererLogs = ""


	@Suppress("HttpUrlsUsage")
	fun startContainers() {
		val network = Network.newNetwork()

		postgresInnsendingContainer = KPostgreSQLContainer()
			.withNetworkAliases("postgres-innsending")
			.withExposedPorts(defaultPorts["database"])
			.withNetwork(network)
			.withUsername(postgresUsername)
			.withPassword(postgresUsername)
			.withDatabaseName(databaseName)
/*

		cloudStorageInnsendingContainer = KGenericContainer("fsouza/fake-gcs-server")
			//.withNetworkAliases("cloudStorage")
			.withNetwork(network)
			.withCreateContainerCmdModifier {
				it.withEntrypoint(
					"/bin/fake-gcs-server",
					"-scheme", "http",
					//"-port", defaultPorts["cloudStorage"].toString(),
					"-data", "/data"
				)
			}
			.withExposedPorts(defaultPorts["cloudStorage"])
*/

		kafkaContainer = KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.9.1"))
			.withNetworkAliases("kafka-broker")
			.withNetwork(network)

		gotenbergContainer = KGenericContainer("gotenberg/gotenberg:8.0.0")
			.withNetworkAliases("gotenberg")
			.withExposedPorts(defaultPorts["gotenberg"])

		postgresInnsendingContainer.start()
		kafkaContainer.start()
		gotenbergContainer.start()

/*
		cloudStorageInnsendingContainer.start()
		updateExternalUrl(cloudStorageInnsendingContainer)
*/

		createTopic(defaultProperties["KAFKA_MAIN_TOPIC"]!!)
		createTopic(defaultProperties["KAFKA_NOLOGIN_SUBMISSION_TOPIC"]!!)
		createTopic(defaultProperties["KAFKA_PROCESSING_TOPIC"]!!)
		createTopic(defaultProperties["KAFKA_MESSAGE_TOPIC"]!!)
		createTopic(defaultProperties["KAFKA_ARKIVERINGSTILBAKEMELDING_TOPIC"]!!)
		createTopic(defaultProperties["KAFKA_METRICS_TOPIC"]!!)
		createTopic(defaultProperties["KAFKA_ENTITIES_TOPIC"]!!)
		createTopic(defaultProperties["KAFKA_NUMBER_OF_CALLS_TOPIC"]!!)
		createTopic(defaultProperties["KAFKA_BRUKERNOTIFIKASJON_OPPGAVE_TOPIC"]!!)
		createTopic(defaultProperties["KAFKA_BRUKERNOTIFIKASJON_UTKAST_TOPIC"]!!)


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

		soknadsmottakerContainer.start()

		innsendingApiContainer = KGenericContainer("archiving-infrastructure-innsending-api")
			.withNetworkAliases("innsending-api")
			.withExposedPorts(defaultPorts["innsending-api"])
			.withNetwork(network)
			.withEnv(
				hashMapOf(
					"SPRING_PROFILES_ACTIVE"                to "endtoend",
					"DATABASE_PORT"                         to defaultPorts["database"].toString(),
					"DATABASE_HOST"                         to postgresInnsendingContainer.networkAliases[0],
					"DATABASE_DATABASE"                     to databaseName,
					"DATABASE_USERNAME"                     to postgresUsername,
					"DATABASE_PASSWORD"                     to postgresUsername,
					"KAFKA_BROKERS"                         to "${kafkaContainer.networkAliases[0]}:${defaultPorts["kafka-broker"]}",
					"KAFKA_ARKIVERINGSTILBAKEMELDING_TOPIC" to defaultProperties["KAFKA_ARKIVERINGSTILBAKEMELDING_TOPIC"],
					"SOKNADSMOTTAKER_HOST"                  to "http://${soknadsmottakerContainer.networkAliases[0]}:${defaultPorts["soknadsmottaker"]}",
					"SAF_URL"								                to "http://${arkivMockContainer.networkAliases[0]}:${defaultPorts["arkiv-mock"]}",
					"SAFSELVBETJENING_URL"								  to "http://${arkivMockContainer.networkAliases[0]}:${defaultPorts["arkiv-mock"]}",
					"AZURE_APP_WELL_KNOWN_URL"              to "http://metadata",
					"AZURE_APP_CLIENT_ID"			              to "aud-localhost",
					"AZURE_OPENID_CONFIG_TOKEN_ENDPOINT"    to "http://metadata",
					"AZURE_APP_CLIENT_SECRET"               to "secret",
					"KONVERTERING_TIL_PDF_URL"							to "http://${gotenbergContainer.networkAliases[0]}:${defaultPorts["gotenberg"]}",
					"FILE_STORAGE_BUCKET_NAME"							to "innsending-api-file-storage-loadtests",
					//"FILE_STORAGE_HOST"											to "http://${cloudStorageInnsendingContainer.networkAliases[0]}:${defaultPorts["cloudStorage"]}"
					//"FILE_STORAGE_HOST"											to "http://localhost:${defaultPorts["cloudStorage"]}",
				)
			)
			.dependsOn(postgresInnsendingContainer, kafkaContainer, soknadsmottakerContainer, arkivMockContainer, gotenbergContainer)
			.waitingFor(Wait.forHttp("/health/isAlive").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(1)))

		try {
			innsendingApiContainer.start()
		} catch (e: Exception) {
			logger.error("Failed to start innsending-api. Logs:\n${innsendingApiContainer.logs}")
			throw e
		}

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
					"JOARK_HOST"              to "http://${arkivMockContainer.networkAliases[0]}:${defaultPorts["arkiv-mock"]}",
					"SEND_TO_JOARK"           to "true",
					"INNSENDING_API_HOST"     to "http://${innsendingApiContainer.networkAliases[0]}:${defaultPorts["innsending-api"]}",
					"SAF_URL"									to "http://${arkivMockContainer.networkAliases[0]}:${defaultPorts["arkiv-mock"]}",
					"AZURE_APP_WELL_KNOWN_URL" to "http://metadata",
					"AZURE_APP_CLIENT_ID"			to "aud-localhost",
					"AZURE_OPENID_CONFIG_TOKEN_ENDPOINT" to "http://metadata",
					"AZURE_APP_CLIENT_SECRET" to "secret",
					"STATUS_LOG_URL"					to "https://logs.adeo.no"
				)
			)
			.dependsOn(kafkaContainer, schemaRegistryContainer, arkivMockContainer, innsendingApiContainer)
			.waitingFor(Wait.forHttp("/internal/health").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)))

		soknadsarkivererContainer.start()

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
		logger.info(createHeader("soknadsmottaker") + soknadsmottakerContainer.logs)
		logger.info(createHeader("soknadsarkiverer") + soknadsarkivererContainer.logs)
		logger.info(createHeader("arkiv-mock") + arkivMockContainer.logs)
		logger.info(createHeader("innsending-api") + innsendingApiContainer.logs)

		innsendingApiContainer.stop()
		soknadsmottakerContainer.stop()
		soknadsarkivererContainer.stop()
		arkivMockContainer.stop()

		postgresInnsendingContainer.stop()
		kafkaContainer.stop()
		gotenbergContainer.stop()
		schemaRegistryContainer.stop()
		//cloudStorageInnsendingContainer.stop()
	}


	fun shutDownSoknadsarkiverer() {
		soknadsarkivererLogs += soknadsarkivererContainer.logs + "\n"
		soknadsarkivererContainer.stop()
	}

	fun startUpSoknadsarkiverer() {
		soknadsarkivererContainer.start()
	}


	fun getUrlForInnsendingApi()    = "http://localhost:" + innsendingApiContainer   .firstMappedPort
	fun getUrlForArkivMock()        = "http://localhost:" + arkivMockContainer       .firstMappedPort
	fun getUrlForSoknadsarkiverer() = "http://localhost:" + soknadsarkivererContainer.firstMappedPort
	fun getUrlForSoknadsmottaker()  = "http://localhost:" + soknadsmottakerContainer .firstMappedPort
	fun getUrlForSchemaRegistry()   = "http://localhost:" + schemaRegistryContainer  .firstMappedPort
	fun getUrlForKafkaBroker()      = "localhost:"        + kafkaContainer           .firstMappedPort
}


private fun updateExternalUrl(cloudStorageInnsendingContainer: KGenericContainer) {
	val url = "http://localhost:${cloudStorageInnsendingContainer.firstMappedPort}"
	val modifyExternalUrlRequestUri = "$url/_internal/config"
	val updateExternalUrlJson = """{"externalUrl": "$url"}"""

	val req = HttpRequest.newBuilder()
		.uri(URI.create(modifyExternalUrlRequestUri))
		.header("Content-Type", "application/json")
		.PUT(HttpRequest.BodyPublishers.ofString(updateExternalUrlJson))
		.build()

	val response = try {
		HttpClient.newBuilder().build()
			.send(req, HttpResponse.BodyHandlers.discarding())
	} catch (e: IOException) {
		throw RuntimeException("Failed to update fake-gcs-server external URL", e)
	} catch (e: InterruptedException) {
		Thread.currentThread().interrupt()
		throw RuntimeException("Thread interrupted while updating fake-gcs-server external URL", e)
	}

	if (response.statusCode() != 200) {
		throw RuntimeException("Error updating fake-gcs-server with external url, response status code ${response.statusCode()} != 200")
	}
}


class KGenericContainer(imageName: String) : GenericContainer<KGenericContainer>(imageName)

class KPostgreSQLContainer : PostgreSQLContainer<KPostgreSQLContainer>(DockerImageName.parse("postgres"))
