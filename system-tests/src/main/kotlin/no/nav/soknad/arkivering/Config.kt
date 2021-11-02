package no.nav.soknad.arkivering

import com.natpryce.konfig.*
import com.natpryce.konfig.ConfigurationProperties.Companion.systemProperties
import java.io.File

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
	"USERNAME"                 to "arkiverer",
	"PASSWORD"                 to "",
	"KAFKA_SECURITY"           to "",
	"KAFKA_SECPROT"            to "",
	"KAFKA_SASLMEC"            to "",
	"KAFKA_INPUT_TOPIC"        to "privat-soknadInnsendt-v1-teamsoknad",
	"KAFKA_PROCESSING_TOPIC"   to "privat-soknadInnsendt-processingEventLog-v1-teamsoknad",
	"KAFKA_MESSAGE_TOPIC"      to "privat-soknadInnsendt-messages-v1-teamsoknad",
	"KAFKA_METRICS_TOPIC"      to "privat-soknadInnsendt-metrics-v1-teamsoknad",

	"SOKNADSFILLAGER_URL"      to "http://localhost:${defaultPorts["soknadsfillager"]}",
	"SOKNADSMOTTAKER_URL"      to "http://localhost:${defaultPorts["soknadsmottaker"]}",
	"SOKNADSARKIVERER_URL"     to "http://localhost:${defaultPorts["soknadsarkiverer"]}",
	"ARKIVMOCK_URL"            to "http://localhost:${defaultPorts["arkiv-mock"]}",
	"SCHEMA_REGISTRY_URL"      to "http://localhost:${defaultPorts["schema-registry"]}",
	"KAFKA_BOOTSTRAP_SERVERS"  to "localhost:${defaultPorts["kafka-broker"]}",
	"SOKNADSFILLAGER_USERNAME" to "arkiverer",
	"SOKNADSFILLAGER_PASSWORD" to "password",
	"SOKNADSMOTTAKER_USERNAME" to "avsender",
	"SOKNADSMOTTAKER_PASSWORD" to "password"
)



private val appConfig =
	EnvironmentVariables() overriding
		systemProperties() overriding
		ConfigurationMap(defaultProperties)

private fun String.configProperty(overridingProperties: Map<String, String>): String =
	overridingProperties[this] ?: appConfig[Key(this, stringType)]

fun readFileAsText(fileName: String, default: String = "") = try { File(fileName).readText(Charsets.UTF_8) } catch (e: Exception) { default }

data class Configuration(val overridingProperties: Map<String, String> = mapOf(),
												 val kafkaConfig: KafkaConfig = KafkaConfig(overridingProperties),
												 val config: Config = Config(overridingProperties)) {

	data class KafkaConfig(
		val overridingProperties: Map<String, String>,

		val username: String = readFileAsText("/var/run/secrets/nais.io/srvinnsendingtests/username", "USERNAME".configProperty(overridingProperties)),
		val password: String = readFileAsText("/var/run/secrets/nais.io/srvinnsendingtests/password", "PASSWORD".configProperty(overridingProperties)),
		val servers: String = "KAFKA_BOOTSTRAP_SERVERS".configProperty(overridingProperties),
		val schemaRegistryUrl: String = "SCHEMA_REGISTRY_URL".configProperty(overridingProperties),
		val secure: String = "KAFKA_SECURITY".configProperty(overridingProperties),
		val protocol: String = "KAFKA_SECPROT".configProperty(overridingProperties),
		val salsmec: String = "KAFKA_SASLMEC".configProperty(overridingProperties),
		val inputTopic: String = "KAFKA_INPUT_TOPIC".configProperty(overridingProperties),
		val processingTopic: String = "KAFKA_PROCESSING_TOPIC".configProperty(overridingProperties),
		val messageTopic: String = "KAFKA_MESSAGE_TOPIC".configProperty(overridingProperties),
		val metricsTopic: String = "KAFKA_METRICS_TOPIC".configProperty(overridingProperties),
		val saslJaasConfig: String = "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"$username\" password=\"$password\";"
	)

	data class Config(
		val overridingProperties: Map<String, String>,

		val soknadsmottakerUrl: String = "SOKNADSMOTTAKER_URL".configProperty(overridingProperties),
		val soknadsmottakerUsername: String = readFileAsText("/var/run/secrets/nais.io/innsending-system-tests/soknadsmottaker_username", "SOKNADSMOTTAKER_USERNAME".configProperty(overridingProperties)),
		val soknadsmottakerPassword: String = readFileAsText("/var/run/secrets/nais.io/innsending-system-tests/soknadsmottaker_password", "SOKNADSMOTTAKER_PASSWORD".configProperty(overridingProperties)),
		val soknadsfillagerUrl: String = "SOKNADSFILLAGER_URL".configProperty(overridingProperties),
		val soknadsfillagerUsername: String = readFileAsText("/var/run/secrets/nais.io/innsending-system-tests/soknadsfillager_username", "SOKNADSFILLAGER_USERNAME".configProperty(overridingProperties)),
		val soknadsfillagerPassword: String = readFileAsText("/var/run/secrets/nais.io/innsending-system-tests/soknadsfillager_password", "SOKNADSFILLAGER_PASSWORD".configProperty(overridingProperties)),
		val arkivMockUrl: String = "ARKIVMOCK_URL".configProperty(overridingProperties),
	)
}
