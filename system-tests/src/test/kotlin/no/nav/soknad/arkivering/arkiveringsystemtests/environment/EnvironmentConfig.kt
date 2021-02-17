package no.nav.soknad.arkivering.arkiveringsystemtests.environment

import org.junit.jupiter.api.fail


private val defaultProperties = mapOf(
	"soknadsfillager.url"      to "http://localhost:9042",
	"soknadsmottaker.url"      to "http://localhost:8090",
	"soknadsarkiverer.url"     to "http://localhost:8091",
	"arkiv-mock.url"           to "http://localhost:8092",
	"schemaregistry.url"       to "http://localhost:8081",
	"kafkabroker.url"          to "localhost:9092",
	"soknadsfillager.username" to "arkiverer",
	"soknadsfillager.password" to "password",
	"soknadsmottaker.username" to "avsender",
	"soknadsmottaker.password" to "password"
)
private val q0Properties = mapOf(
	"soknadsfillager.url"      to "https://soknadsfillager-q0.dev.adeo.no",
	"soknadsmottaker.url"      to "https://soknadsmottaker-q0.dev.adeo.no",
	"soknadsfillager.username" to "srvHenvendelse",
	"soknadsfillager.password" to "password",
	"soknadsmottaker.username" to "srvHenvendelse",
	"soknadsmottaker.password" to "password"
)
private val q1Properties = mapOf(
	"soknadsfillager.url"      to "https://soknadsfillager-q1.dev.adeo.no",
	"soknadsmottaker.url"      to "https://soknadsmottaker-q1.dev.adeo.no",
	"soknadsfillager.username" to "srvHenvendelse",
	"soknadsfillager.password" to "password",
	"soknadsmottaker.username" to "srvHenvendelse",
	"soknadsmottaker.password" to "password"
)

enum class Profile {
	EMBEDDED, // Running towards local machine, where application containers are managed by the test suite
	DOCKER, // Running towards local machine, where application containers are managed externally by Docker
	Q0, // Running towards q0 environment in the cloud
	Q1 // Running towards q1 environment in the cloud
}

class EnvironmentConfig(environmentToTarget: String? = null) {

	private var embeddedDockerImages: EmbeddedDockerImages? = null

	fun addEmbeddedDockerImages(embeddedDockerImages: EmbeddedDockerImages): EnvironmentConfig {
		this.embeddedDockerImages = embeddedDockerImages
		return this
	}

	private val targetEnvironment = when (environmentToTarget) {
		"docker" -> Profile.DOCKER
		"q0"     -> Profile.Q0
		"q1"     -> Profile.Q1
		else     -> Profile.EMBEDDED
	}

	private fun getAttribute(attribute: String): String {
		val result = when (targetEnvironment) {
			Profile.DOCKER -> defaultProperties[attribute]

			Profile.Q0 -> when (attribute) {
				"soknadsfillager.password" -> getEnvironmentVariable(attribute)
				"soknadsmottaker.password" -> getEnvironmentVariable(attribute)
				else -> q0Properties[attribute]
			}

			Profile.Q1 -> when (attribute) {
				"soknadsfillager.password" -> getEnvironmentVariable(attribute)
				"soknadsmottaker.password" -> getEnvironmentVariable(attribute)
				else -> q1Properties[attribute]
			}

			Profile.EMBEDDED -> when (attribute) {
				"soknadsfillager.url"  -> embeddedDockerImages?.getUrlForSoknadsfillager()
				"soknadsmottaker.url"  -> embeddedDockerImages?.getUrlForSoknadsmottaker()
				"soknadsarkiverer.url" -> embeddedDockerImages?.getUrlForSoknadsarkiverer()
				"arkiv-mock.url"       -> embeddedDockerImages?.getUrlForArkivMock()
				"kafkabroker.url"      -> embeddedDockerImages?.getUrlForKafkaBroker()
				"schemaregistry.url"   -> embeddedDockerImages?.getUrlForSchemaRegistry()
				else                   -> defaultProperties[attribute]
			}
		}
		if (result != null)
			return result
		else
			throw NotImplementedError("There is no $attribute for environment $targetEnvironment")
	}

	private fun getEnvironmentVariable(attribute: String): String {
		val value = System.getenv(attribute)
		if (value == null) {
			val message = "No value found for environment-variable '$attribute'. " +
				"If you run this locally from the terminal, try running 'export $attribute=VALUE' " +
				"and then running the tests again."
			fail(message)
		}
		return value
	}


	fun getUrlForSoknadsfillager()   = getAttribute("soknadsfillager.url")
	fun getUrlForSoknadsmottaker()   = getAttribute("soknadsmottaker.url")
	fun getUrlForSoknadsarkiverer()  = getAttribute("soknadsarkiverer.url")
	fun getUrlForArkivMock()         = getAttribute("arkiv-mock.url")
	fun getUrlForKafkaBroker()       = getAttribute("kafkabroker.url")
	fun getUrlForSchemaRegistry()    = getAttribute("schemaregistry.url")
	fun getSoknadsfillagerUsername() = getAttribute("soknadsfillager.username")
	fun getSoknadsfillagerPassword() = getAttribute("soknadsfillager.password")
	fun getSoknadsmottakerUsername() = getAttribute("soknadsmottaker.username")
	fun getSoknadsmottakerPassword() = getAttribute("soknadsmottaker.password")
}
