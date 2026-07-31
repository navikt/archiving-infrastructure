package no.nav.soknad.arkivering

val kafkaBrokerPort: Int = System.getenv("KAFKA_BROKER_PORT")?.toInt()
	?: run {
		val targetEnv = System.getProperty("targetEnvironment") ?: System.getenv("TARGET_ENVIRONMENT") ?: ""
		if (targetEnv == "embedded") 9093 else 9092
	}

val defaultPorts = mapOf(
	"innsending-api"   to 9064,
	"soknadsmottaker"  to 8090,
	"soknadsarkiverer" to 8091,
	"arkiv-mock"       to 8092,
	"kafka-broker"     to kafkaBrokerPort,
	"schema-registry"  to 8081,
	"database"         to 5432,
	"gotenberg"        to 3000,
	"cloudStorage"	   to 4443
)

val defaultProperties = mapOf(
	"KAFKA_STREAMS_APPLICATION_ID"   to "innsending-system-tests",
	"KAFKA_BROKERS"                  to "localhost:${defaultPorts["kafka-broker"]}",
	"KAFKA_SECURITY"                 to "FALSE",
	"KAFKA_KEYSTORE_PATH"            to "",
	"KAFKA_TRUSTSTORE_PATH"          to "",
	"KAFKA_CREDSTORE_PASSWORD"       to "",

	"KAFKA_LOGGEDIN_SUBMISSION_TOPIC"        to "privat-loggedinsubmission-v1-systemtests",
	"KAFKA_NOLOGIN_SUBMISSION_TOPIC"         to "privat-nologinsubmission-v1-systemtests",
	// Legacy Avro processing-event/metrics topics (referred to as "v2" in the JSON v3 design spec,
	// see navikt/soknadsarkiverer#260). Retained for legacy replay; the default system-test path
	// reads/writes the JSON v3 topics below instead (issue #78).
	"KAFKA_PROCESSING_TOPIC"                 to "privat-soknadinnsending-processingeventlog-v1-dev",
	"KAFKA_MESSAGE_TOPIC"                    to "privat-soknadinnsending-messages-v1-dev",
	"KAFKA_ARKIVERINGSTILBAKEMELDING_TOPIC"  to "privat-soknadinnsending-arkiveringstilbakemeldinger-v1-dev",
	"KAFKA_METRICS_TOPIC"                    to "privat-soknadinnsending-metrics-v1-dev",
	// JSON v3 processing-event/metrics topics (default system-test path, no Schema Registry).
	// Topic names must match the defaults used by soknadsarkiverer/soknadsmottaker.
	"KAFKA_PROCESSING_TOPIC_V3"              to "privat-soknadinnsending-processingeventlog-v3-dev",
	"KAFKA_METRICS_TOPIC_V3"                 to "privat-soknadinnsending-metrics-v3-dev",
	"KAFKA_ENTITIES_TOPIC"                   to "team-soknad.privat-soknadinnsending-systemtests-entities",
	"KAFKA_NUMBER_OF_CALLS_TOPIC"            to "team-soknad.privat-soknadinnsending-systemtests-numberofcalls",
	"KAFKA_BRUKERNOTIFIKASJON_DONE_TOPIC"    to "min-side.aapen-brukervarsel-v1",
	"KAFKA_BRUKERNOTIFIKASJON_BESKJED_TOPIC" to "min-side.aapen-brukervarsel-v1",
	"KAFKA_BRUKERNOTIFIKASJON_OPPGAVE_TOPIC" to "min-side.aapen-brukervarsel-v1",
	"KAFKA_BRUKERNOTIFIKASJON_UTKAST_TOPIC"  to "min-side.aapen-utkast-v1",

	"SOKNADSMOTTAKER_URL" to "http://localhost:${defaultPorts["soknadsmottaker"]}",
	"INNSENDINGAPI_URL" to "http://localhost:${defaultPorts["innsending-api"]}",
	"ARKIVMOCK_URL" to "http://localhost:${defaultPorts["arkiv-mock"]}",
)


fun getProperty(propName: String, defaultValue: String = ""): String =
	System.getenv(propName) ?: (defaultProperties[propName] ?: defaultValue)

data class Config(
	val soknadsmottakerUrl: String = getProperty("SOKNADSMOTTAKER_URL"),
	val innsendingApiUrl:   String = getProperty("INNSENDINGAPI_URL"),
	val arkivMockUrl: String = getProperty("ARKIVMOCK_URL"),
)

data class KafkaConfig(
	val applicationId: String = getProperty("KAFKA_STREAMS_APPLICATION_ID"),
	val brokers: String = getProperty("KAFKA_BROKERS", "localhost:9092"),
	val security: SecurityConfig = SecurityConfig(),
	val topics: Topics = Topics(),
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
	val scopeSoknadsmottaker: String = "api://dev-gcp.team-soknad.soknadsmottaker/.default",
	val scopeInnsendingApi: String = "api://dev-gcp.team-soknad.innsending-api/.default",
	val clientId: String = getProperty("AZURE_APP_CLIENT_ID"),
	val clientSecret: String = getProperty("AZURE_APP_CLIENT_SECRET"),
	val clientAuthMethod: String = "client_secret_basic"
)

data class Topics(
	// Legacy Avro processing-event/metrics topics ("v2" in the design spec). Retained for legacy
	// replay only; not read/written by the default system-test path (issue #78).
	val processingTopic: String = getProperty("KAFKA_PROCESSING_TOPIC"),
	val messageTopic: String = getProperty("KAFKA_MESSAGE_TOPIC"),
	val arkiveringstilbakemeldingerTopic: String = getProperty("KAFKA_ARKIVERINGSTILBAKEMELDING_TOPIC"),
	val metricsTopic: String = getProperty("KAFKA_METRICS_TOPIC"),
	// JSON v3 processing-event/metrics topics: the default system-test path, no Schema Registry.
	val processingTopicV3: String = getProperty("KAFKA_PROCESSING_TOPIC_V3"),
	val metricsTopicV3: String = getProperty("KAFKA_METRICS_TOPIC_V3"),
	val entitiesTopic: String = getProperty("KAFKA_ENTITIES_TOPIC"),
	val numberOfCallsTopic: String = getProperty("KAFKA_NUMBER_OF_CALLS_TOPIC"),
	val brukernotifikasjonDoneTopic: String = getProperty("KAFKA_BRUKERNOTIFIKASJON_DONE_TOPIC"),
	val brukernotifikasjonBeskjedTopic: String = getProperty("KAFKA_BRUKERNOTIFIKASJON_BESKJED_TOPIC"),
	val brukernotifikasjonOppgaveTopic: String = getProperty("KAFKA_BRUKERNOTIFIKASJON_OPPGAVE_TOPIC"),
	val brukernotifikasjonUtkastTopic: String = getProperty("KAFKA_BRUKERNOTIFIKASJON_UTKAST_TOPIC"),
	val loggedinSendInnTopic: String = getProperty("KAFKA_LOGGEDIN_SUBMISSION_TOPIC"),
	val nologinSendInnTopic: String = getProperty("KAFKA_NOLOGIN_SUBMISSION_TOPIC"),
)

const val DEFAULT_LEVETID_OPPRETTET_SOKNAD = 28L // 4 uker inntil ikke innsendt søknad/ettersendingssøknad slettes
