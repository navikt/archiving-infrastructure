package no.nav.soknad.arkivering.arkiveringsystemtests.environment

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import no.nav.soknad.arkivering.defaultPorts
import no.nav.soknad.arkivering.defaultProperties
import org.junit.jupiter.api.fail
import org.slf4j.LoggerFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.time.Duration

// Pinned for reproducibility and to avoid unnecessary pulls (issue #85). These matched `latest`
// when pinned. Keep in sync with the pre-pulled images in the GitHub workflows.
private const val KAFKA_IMAGE = "confluentinc/cp-kafka:8.3.1"
private const val SCHEMA_REGISTRY_IMAGE = "confluentinc/cp-schema-registry:8.3.1"

class EmbeddedDockerImages {
	private val logger = LoggerFactory.getLogger(javaClass)

	private val postgresUsername = "postgres"
	private val databaseName = "postgres"

	private lateinit var authServerContainer: GenericContainer<*>
	private lateinit var gotenbergContainer: GenericContainer<*>
	private lateinit var postgresInnsendingContainer: PostgreSQLContainer<*>
	private lateinit var kafkaContainer: GenericContainer<ConfluentKafkaContainer>
	private lateinit var schemaRegistryContainer: GenericContainer<*>
	private lateinit var arkivMockContainer: GenericContainer<*>
	private lateinit var innsendingApiContainer: GenericContainer<*>
	private lateinit var soknadsmottakerContainer: GenericContainer<*>
	private lateinit var soknadsarkivererContainer: GenericContainer<*>

	// Starts the containers in dependency-ordered phases, starting the mutually independent
	// containers of each phase in parallel (issue #85):
	// 1. postgres-innsending, gotenberg, authserver and kafka (no dependencies on each other)
	// 2. Kafka topics, then schema-registry and arkiv-mock (depend only on kafka)
	// 3. soknadsmottaker (depends on kafka and schema-registry)
	// 4. innsending-api (depends on postgres, kafka, soknadsmottaker, arkiv-mock and gotenberg)
	// 5. soknadsarkiverer (depends on authserver, kafka, schema-registry, arkiv-mock, innsending-api)
	fun startContainers() = runBlocking {
		val network = Network.newNetwork()

		coroutineScope {
			val postgres = async(Dispatchers.IO) { createPostgresContainer(network).also { it.start() } }
			val gotenberg = async(Dispatchers.IO) { createGotenbergContainer(network).also { it.start() } }
			val authServer = async(Dispatchers.IO) { createAuthServerContainer(network).also { it.start() } }
			val kafka = async(Dispatchers.IO) { createKafkaContainer(network).also { it.start() } }
			postgresInnsendingContainer = postgres.await()
			gotenbergContainer = gotenberg.await()
			authServerContainer = authServer.await()
			kafkaContainer = kafka.await()
		}

		createTopics()

		coroutineScope {
			val schemaRegistry = async(Dispatchers.IO) { createSchemaRegistryContainer(network).also { it.start() } }
			val arkivMock = async(Dispatchers.IO) { createArkivMockContainer(network).also { it.start() } }
			schemaRegistryContainer = schemaRegistry.await()
			arkivMockContainer = arkivMock.await()
		}

		soknadsmottakerContainer = createSoknadsmottakerContainer(network)
		soknadsmottakerContainer.start()

		innsendingApiContainer = createInnsendingApiContainer(network)
		try {
			innsendingApiContainer.start()
		} catch (e: Exception) {
			logger.error("Failed to start innsending-api. Logs:\n${innsendingApiContainer.logs}")
			throw e
		}

		soknadsarkivererContainer = createSoknadsarkivererContainer(network)
		try {
			soknadsarkivererContainer.start()
		} catch (e: Exception) {
			logger.error("Failed to start soknadsarkiverer. Logs:\n${soknadsarkivererContainer.logs}")
			throw e
		}

		logger.info("Containers started")
	}

	private fun createPostgresContainer(network: Network) =
		PostgreSQLContainer(DockerImageName.parse("postgres:15.6"))
			.withNetworkAliases("postgres-innsending")
			.withExposedPorts(defaultPorts["database"]!!)
			.withNetwork(network)
			.withUsername(postgresUsername)
			.withPassword(postgresUsername)
			.withDatabaseName(databaseName)

	private fun createGotenbergContainer(network: Network) =
		GenericContainer(DockerImageName.parse("gotenberg/gotenberg:8.25.1"))
			.withNetworkAliases("gotenberg")
			.withExposedPorts(defaultPorts["gotenberg"]!!)
			.withNetwork(network)

	@Suppress("HttpUrlsUsage")
	private fun createAuthServerContainer(network: Network) =
		GenericContainer(DockerImageName.parse("ghcr.io/navikt/mock-oauth2-server:0.5.5"))
			.withNetworkAliases("authserver")
			.withExposedPorts(6969)
			.withNetwork(network)
			.withEnv(
				hashMapOf(
					"SERVER_PORT" to "6969",
					"JSON_CONFIG" to """{"interactiveLogin":true,"httpServer":"NettyWrapper"}"""
				)
			)
			.waitingFor(Wait.forHttp("/azuread/.well-known/openid-configuration").forStatusCode(200))

	private fun createKafkaContainer(network: Network) =
		ConfluentKafkaContainer(DockerImageName.parse(KAFKA_IMAGE))
			.withNetworkAliases("kafka-broker")
			.withNetwork(network)

	@Suppress("HttpUrlsUsage")
	private fun createSchemaRegistryContainer(network: Network) =
		// Retained for soknadsmottaker/soknadsarkiverer legacy Avro processing-event/metrics replay
		// and production during the JSON v3 transition (issue #78). The system-test suite's own
		// Kafka listener/publisher reads/writes plain JSON on the v3 topics without Schema Registry.
		GenericContainer(SCHEMA_REGISTRY_IMAGE)
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

	private fun createArkivMockContainer(network: Network) =
		GenericContainer("archiving-infrastructure-arkiv-mock")
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

	@Suppress("HttpUrlsUsage")
	private fun createSoknadsmottakerContainer(network: Network) =
		GenericContainer("archiving-infrastructure-soknadsmottaker")
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
			.waitingFor(Wait.forHttp("/health/status").forStatusCode(200))

	@Suppress("HttpUrlsUsage")
	private fun createInnsendingApiContainer(network: Network) =
		GenericContainer("archiving-infrastructure-innsending-api")
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
					"FILE_STORAGE_BUCKET_NAME"							to "innsending-api-file-storage-systemtests",
				)
			)
			.dependsOn(postgresInnsendingContainer, kafkaContainer, soknadsmottakerContainer, arkivMockContainer, gotenbergContainer)
			.waitingFor(Wait.forHttp("/internal/health").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(1)))

	@Suppress("HttpUrlsUsage")
	private fun createSoknadsarkivererContainer(network: Network) =
		GenericContainer("archiving-infrastructure-soknadsarkiverer")
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
					"AZURE_OPENID_CONFIG_ISSUER" to "http://authserver:6969/azuread",
					"AZURE_OPENID_CONFIG_JWKS_URI" to "http://authserver:6969/azuread/jwks",
					"AZURE_OPENID_CONFIG_TOKEN_ENDPOINT" to "http://authserver:6969/azuread/token",
					"AZURE_APP_CLIENT_SECRET" to "secret",
					"DOKARKIV_SCOPE"					to "scope",
					"SAF_SCOPE"								to "scope",
					"INNSENDING_API_SCOPE"			to "scope",
					"STATUS_LOG_URL"					to "https://logs.adeo.no"
				)
			)
			.dependsOn(authServerContainer, kafkaContainer, schemaRegistryContainer, arkivMockContainer, innsendingApiContainer)
			.waitingFor(Wait.forHttp("/internal/health").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)))

	private suspend fun createTopics() = coroutineScope {
		listOf(
			defaultProperties["KAFKA_LOGGEDIN_SUBMISSION_TOPIC"]!!,
			defaultProperties["KAFKA_NOLOGIN_SUBMISSION_TOPIC"]!!,
			// Legacy Avro processing-event/metrics topics ("v2" in the design spec). Retained so legacy
			// replay keeps working while it exists (issue #78).
			defaultProperties["KAFKA_PROCESSING_TOPIC"]!!,
			defaultProperties["KAFKA_MESSAGE_TOPIC"]!!,
			defaultProperties["KAFKA_ARKIVERINGSTILBAKEMELDING_TOPIC"]!!,
			defaultProperties["KAFKA_METRICS_TOPIC"]!!,
			// JSON v3 processing-event/metrics topics: the default system-test path (issue #78).
			defaultProperties["KAFKA_PROCESSING_TOPIC_V3"]!!,
			defaultProperties["KAFKA_METRICS_TOPIC_V3"]!!,
			defaultProperties["KAFKA_ENTITIES_TOPIC"]!!,
			defaultProperties["KAFKA_NUMBER_OF_CALLS_TOPIC"]!!,
			defaultProperties["KAFKA_BRUKERNOTIFIKASJON_OPPGAVE_TOPIC"]!!,
			defaultProperties["KAFKA_BRUKERNOTIFIKASJON_UTKAST_TOPIC"]!!,
		).map { topic -> async(Dispatchers.IO) { createTopic(topic) } }.awaitAll()
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
		// collect logs for debugging
		try { logger.info("soknadsmottaker logs:\n${soknadsmottakerContainer.logs}") } catch (_: Exception) {}
		try { logger.info("soknadsarkiverer logs:\n${soknadsarkivererContainer.logs}") } catch (_: Exception) {}
		try { logger.info("arkiv-mock logs:\n${arkivMockContainer.logs}") } catch (_: Exception) {}
		try { logger.info("innsending-api logs:\n${innsendingApiContainer.logs}") } catch (_: Exception) {}
		try { innsendingApiContainer.stop() } catch (_: Exception) {}
		try { soknadsmottakerContainer.stop() } catch (_: Exception) {}
		try { soknadsarkivererContainer.stop() } catch (_: Exception) {}
		try { arkivMockContainer.stop() } catch (_: Exception) {}
		try { kafkaContainer.stop() } catch (_: Exception) {}
		try { schemaRegistryContainer.stop() } catch (_: Exception) {}
		try { authServerContainer.stop() } catch (_: Exception) {}
		try { gotenbergContainer.stop() } catch (_: Exception) {}
		try { postgresInnsendingContainer.stop() } catch (_: Exception) {}
	}

	fun getUrlForInnsendingApi()    = "http://localhost:" + innsendingApiContainer   .firstMappedPort
	fun getUrlForArkivMock()        = "http://localhost:" + arkivMockContainer       .firstMappedPort
	fun getUrlForSoknadsarkiverer() = "http://localhost:" + soknadsarkivererContainer.firstMappedPort
	fun getUrlForSoknadsmottaker()  = "http://localhost:" + soknadsmottakerContainer .firstMappedPort
	fun getUrlForSchemaRegistry()   = "http://localhost:" + schemaRegistryContainer  .firstMappedPort
	fun getUrlForKafkaBroker()      = "localhost:"        + kafkaContainer           .firstMappedPort
}
