package no.nav.soknad.arkivering

import com.natpryce.konfig.*
import com.natpryce.konfig.ConfigurationProperties.Companion.systemProperties
import java.io.File
import java.util.HashMap

val defaultPorts = HashMap<String, Int>().also {
	it["soknadsfillager"]  = 9042
	it["soknadsmottaker"]  = 8090
	it["soknadsarkiverer"] = 8091
	it["arkiv-mock"]       = 8092
	it["kafka-broker"]     = 9092
	it["schema-registry"]  = 8081
	it["database"]         = 5432
}

private val defaultProperties = ConfigurationMap(mapOf(
	"APP_VERSION"              to "",
	"USERNAME"                 to "arkiverer",
	"PASSWORD"                 to "",
//	"KAFKA_BOOTSTRAP_SERVERS"  to "localhost:29092",
//	"SCHEMA_REGISTRY_URL"      to "http://localhost:${defaultPorts["schema-registry"]}",
	"KAFKA_SECURITY"           to "",
	"KAFKA_SECPROT"            to "",
	"KAFKA_SASLMEC"            to "",
	"KAFKA_INPUT_TOPIC"        to "privat-soknadInnsendt-v1-default",
	"KAFKA_PROCESSING_TOPIC"   to "privat-soknadInnsendt-processingEventLog-v1-default",
	"KAFKA_MESSAGE_TOPIC"      to "privat-soknadInnsendt-messages-v1-default",
	"KAFKA_METRICS_TOPIC"      to "privat-soknadInnsendt-metrics-v1-default",


	"SOKNADSFILLAGER_URL"      to "http://localhost:${defaultPorts["soknadsfillager"]}",
	"SOKNADSMOTTAKER_URL"      to "http://localhost:${defaultPorts["soknadsmottaker"]}",
	"SOKNADSARKIVERER_URL"     to "http://localhost:${defaultPorts["soknadsarkiverer"]}",
	"ARKIV-MOCK_URL"           to "http://localhost:${defaultPorts["arkiv-mock"]}",
	"SCHEMA_REGISTRY_URL"      to "http://localhost:${defaultPorts["schema-registry"]}",
	"KAFKA_BOOTSTRAP_SERVERS"  to "localhost:${defaultPorts["kafka-broker"]}",
	"SOKNADSFILLAGER_USERNAME" to "arkiverer",
	"SOKNADSFILLAGER_PASSWORD" to "password",
	"SOKNADSMOTTAKER_USERNAME" to "avsender",
	"SOKNADSMOTTAKER_PASSWORD" to "password"
))



private val appConfig =
	EnvironmentVariables() overriding
		systemProperties() overriding
//		ConfigurationProperties.fromResource(Configuration::class.java, "/application.yml") overriding
//		ConfigurationProperties.fromResource(Configuration::class.java, "/local.properties") overriding
		defaultProperties

private fun String.configProperty(overridingProperties: Map<String, String>): String =
	overridingProperties[this] ?: appConfig[Key(this, stringType)]

fun readFileAsText(fileName: String, default: String = "") = try { File(fileName).readText(Charsets.UTF_8) } catch (e: Exception) { default }

data class Configuration(val overridingProperties: Map<String, String> = mapOf(),
												 val kafkaConfig: KafkaConfig = KafkaConfig(overridingProperties),
												 val config: Config = Config(overridingProperties)) {

	data class KafkaConfig(
		val overridingProperties: Map<String, String>,

		val version: String = "APP_VERSION".configProperty(overridingProperties),
		val username: String = readFileAsText("/var/run/secrets/nais.io/service_user/username", "USERNAME".configProperty(overridingProperties)),
		val password: String = readFileAsText("/var/run/secrets/nais.io/service_user/password", "PASSWORD".configProperty(overridingProperties)),
		val servers: String = readFileAsText("/var/run/secrets/nais.io/kv/kafkaBootstrapServer", "KAFKA_BOOTSTRAP_SERVERS".configProperty(overridingProperties)),
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
		val soknadsmottakerUsername: String = "SOKNADSMOTTAKER_USERNAME".configProperty(overridingProperties),
		val soknadsmottakerPassword: String = "SOKNADSMOTTAKER_PASSWORD".configProperty(overridingProperties),
		val soknadsfillagerUrl: String = "SOKNADSFILLAGER_URL".configProperty(overridingProperties),
		val soknadsfillagerUsername: String = "SOKNADSFILLAGER_USERNAME".configProperty(overridingProperties),
		val soknadsfillagerPassword: String = "SOKNADSFILLAGER_PASSWORD".configProperty(overridingProperties),
		val arkivMockUrl: String = "ARKIV-MOCK_URL".configProperty(overridingProperties)
/*
		val username: String = readFileAsText("/var/run/secrets/nais.io/serviceuser/username", "SOKNADSARKIVERER_USERNAME".configProperty()),
		val sharedPassword: String = readFileAsText("/var/run/secrets/nais.io/kv/SHARED_PASSWORD", "SHARED_PASSWORD".configProperty()),
		val clientsecret: String = readFileAsText("/var/run/secrets/nais.io/serviceuser/password", "CLIENTSECRET".configProperty()),

		val profile: String = "SPRING_PROFILES_ACTIVE".configProperty(),
		val maxMessageSize: Int = "MAX_MESSAGE_SIZE".configProperty().toInt(),
		val adminUser: String = readFileAsText("/var/run/secrets/nais.io/kv/ADMIN_USER", "ADMIN_USER".configProperty()),
		val adminUserPassword: String = readFileAsText("/var/run/secrets/nais.io/kv/ADMIN_USER_PASSWORD", "ADMIN_USER_PASSWORD".configProperty()),
 */
	)
}
