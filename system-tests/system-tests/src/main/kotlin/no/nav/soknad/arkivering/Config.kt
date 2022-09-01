package no.nav.soknad.arkivering

const val defaultSoknadsfillagerUsername = "innsending"
const val defaultSoknadsfillagerPassword = "password"
const val defaultSoknadsmottakerUsername = "innsending"
const val defaultSoknadsmottakerPassword = "password"

val defaultPorts = mapOf(
	"soknadsfillager"  to 9042,
	"soknadsmottaker"  to 8090,
	"soknadsarkiverer" to 8091,
	"arkiv-mock"       to 8092,
	"kafka-broker"     to 9092,
	"schema-registry"  to 8081,
	"database"         to 5432
)

val defaultProperties = mapOf(
	"KAFKA_STREAMS_APPLICATION_ID"   to "innsending-system-tests",
	"KAFKA_BROKERS"                  to "localhost:${defaultPorts["kafka-broker"]}",
	"KAFKA_SECURITY"                 to "FALSE",
	"KAFKA_KEYSTORE_PATH"            to "",
	"KAFKA_TRUSTSTORE_PATH"          to "",
	"KAFKA_CREDSTORE_PASSWORD"       to "",
	"KAFKA_SCHEMA_REGISTRY"          to "http://localhost:${defaultPorts["schema-registry"]}",
	"KAFKA_SCHEMA_REGISTRY_USER"     to "",
	"KAFKA_SCHEMA_REGISTRY_PASSWORD" to "",

	"KAFKA_MAIN_TOPIC"                       to "privat-soknadinnsending-v1-dev",
	"KAFKA_PROCESSING_TOPIC"                 to "privat-soknadinnsending-processingeventlog-v1-dev",
	"KAFKA_MESSAGE_TOPIC"                    to "privat-soknadinnsending-messages-v1-dev",
	"KAFKA_METRICS_TOPIC"                    to "privat-soknadinnsending-metrics-v1-dev",
	"KAFKA_ENTITIES_TOPIC"                   to "team-soknad.privat-soknadinnsending-systemtests-entities",
	"KAFKA_NUMBER_OF_CALLS_TOPIC"            to "team-soknad.privat-soknadinnsending-systemtests-numberofcalls",
	"KAFKA_BRUKERNOTIFIKASJON_DONE_TOPIC"    to "min-side.aapen-brukernotifikasjon-done-v1",
	"KAFKA_BRUKERNOTIFIKASJON_BESKJED_TOPIC" to "min-side.aapen-brukernotifikasjon-beskjed-v1",
	"KAFKA_BRUKERNOTIFIKASJON_OPPGAVE_TOPIC" to "min-side.aapen-brukernotifikasjon-oppgave-v1",

	"SOKNADSMOTTAKER_URL"      to "http://localhost:${defaultPorts["soknadsmottaker"]}",
	"SOKNADSFILLAGER_URL"      to "http://localhost:${defaultPorts["soknadsfillager"]}",
	"SOKNADSMOTTAKER_USERNAME" to defaultSoknadsmottakerUsername,
	"SOKNADSFILLAGER_USERNAME" to defaultSoknadsfillagerUsername,
	"SOKNADSMOTTAKER_PASSWORD" to defaultSoknadsmottakerPassword,
	"SOKNADSFILLAGER_PASSWORD" to defaultSoknadsfillagerPassword,
)


fun getProperty(propName: String, defaultValue: String = ""): String =
	System.getenv(propName) ?: (defaultProperties[propName] ?: defaultValue)

data class Config(
	val soknadsmottakerUrl: String = getProperty("SOKNADSMOTTAKER_URL"),
	val soknadsmottakerUsername: String = getProperty("INNSENDING_USERNAME", defaultSoknadsmottakerUsername),
	val soknadsmottakerPassword: String = getProperty("INNSENDING_PASSWORD", defaultSoknadsmottakerPassword),
	val soknadsfillagerUrl: String = getProperty("SOKNADSFILLAGER_URL"),
	val soknadsfillagerUsername: String = getProperty("INNSENDING_USERNAME", defaultSoknadsfillagerUsername),
	val soknadsfillagerPassword: String = getProperty("INNSENDING_PASSWORD", defaultSoknadsfillagerPassword),
)

data class KafkaConfig(
	val applicationId: String = getProperty("KAFKA_STREAMS_APPLICATION_ID"),
	val brokers: String = getProperty("KAFKA_BROKERS"),
	val security: SecurityConfig = SecurityConfig(),
	val topics: Topics = Topics(),
	val schemaRegistry: SchemaRegistry = SchemaRegistry(),
)

data class SecurityConfig(
	val enabled: Boolean = getProperty("KAFKA_SECURITY").toBoolean(),
	val keyStorePath: String = getProperty("KAFKA_KEYSTORE_PATH"),
	val keyStorePassword: String = getProperty("KAFKA_CREDSTORE_PASSWORD"),
	val trustStorePath: String = getProperty("KAFKA_TRUSTSTORE_PATH"),
	val trustStorePassword: String = getProperty("KAFKA_CREDSTORE_PASSWORD"),
)

data class OAuth2Config(
	val tokenEndpointUrl: String = getProperty("AZURE_OPENID_CONFIG_TOKEN_ENDPOINT"),
	val grantType: String = "client_credentials",
	val scopeSoknadsfillager: String = "api://dev-gcp.team-soknad.soknadsfillager-loadtests/.default",
	val scopeSoknadsmottaker: String = "api://dev-gcp.team-soknad.soknadsmottaker-loadtests/.default",
	val clientId: String = getProperty("AZURE_APP_CLIENT_ID"),
	val clientSecret: String = getProperty("AZURE_APP_CLIENT_SECRET"),
	val clientAuthMethod: String = "client_secret_basic"
)

data class Topics(
	val mainTopic: String = getProperty("KAFKA_MAIN_TOPIC"),
	val processingTopic: String = getProperty("KAFKA_PROCESSING_TOPIC"),
	val messageTopic: String = getProperty("KAFKA_MESSAGE_TOPIC"),
	val metricsTopic: String = getProperty("KAFKA_METRICS_TOPIC"),
	val entitiesTopic: String = getProperty("KAFKA_ENTITIES_TOPIC"),
	val numberOfCallsTopic: String = getProperty("KAFKA_NUMBER_OF_CALLS_TOPIC"),
	val brukernotifikasjonDoneTopic: String = getProperty("KAFKA_BRUKERNOTIFIKASJON_DONE_TOPIC"),
	val brukernotifikasjonBeskjedTopic: String = getProperty("KAFKA_BRUKERNOTIFIKASJON_BESKJED_TOPIC"),
	val brukernotifikasjonOppgaveTopic: String = getProperty("KAFKA_BRUKERNOTIFIKASJON_OPPGAVE_TOPIC"),
)

data class SchemaRegistry(
	val url: String = getProperty("KAFKA_SCHEMA_REGISTRY"),
	val username: String = getProperty("KAFKA_SCHEMA_REGISTRY_USER"),
	val password: String = getProperty("KAFKA_SCHEMA_REGISTRY_PASSWORD"),
)
