package no.nav.soknad.arkivering.arkiveringsystemtests.environment

import no.nav.soknad.arkivering.defaultPorts

private val defaultProperties = mapOf(
	"soknadsmottaker.url"      to "http://localhost:${defaultPorts["soknadsmottaker"]}",
	"soknadsarkiverer.url"     to "http://localhost:${defaultPorts["soknadsarkiverer"]}",
	"arkiv-mock.url"           to "http://localhost:${defaultPorts["arkiv-mock"]}",
	"schema-registry.url"      to "http://localhost:${defaultPorts["schema-registry"]}",
	"kafka-broker.url"         to "localhost:${defaultPorts["kafka-broker"]}",
)

enum class Profile {
	EMBEDDED, // Running towards local machine, where application containers are managed by the test suite
	DOCKER, // Running towards local machine, where application containers are managed externally by Docker
}

class EnvironmentConfig(environmentToTarget: String? = null) {

	var embeddedDockerImages: EmbeddedDockerImages? = null

	fun addEmbeddedDockerImages(embeddedDockerImages: EmbeddedDockerImages): EnvironmentConfig {
		this.embeddedDockerImages = embeddedDockerImages
		return this
	}

	private val targetEnvironment = when (environmentToTarget) {
		"docker" -> Profile.DOCKER
		else     -> Profile.EMBEDDED
	}

	private fun getAttribute(attribute: String): String {
		val result = when (targetEnvironment) {
			Profile.DOCKER -> defaultProperties[attribute]

			Profile.EMBEDDED -> when (attribute) {
				"innsendingapi.url"    -> embeddedDockerImages?.getUrlForInnsendingApi()
				"soknadsmottaker.url"  -> embeddedDockerImages?.getUrlForSoknadsmottaker()
				"soknadsarkiverer.url" -> embeddedDockerImages?.getUrlForSoknadsarkiverer()
				"arkiv-mock.url"       -> embeddedDockerImages?.getUrlForArkivMock()
				"kafka-broker.url"     -> embeddedDockerImages?.getUrlForKafkaBroker()
				"schema-registry.url"  -> embeddedDockerImages?.getUrlForSchemaRegistry()
				else                   -> defaultProperties[attribute]
			}
		}
		if (result != null)
			return result
		else
			throw NotImplementedError("There is no $attribute for environment $targetEnvironment")
	}


	fun getUrlForInnsendingApi()    = getAttribute("innsendingapi.url")
	fun getUrlForSoknadsmottaker()  = getAttribute("soknadsmottaker.url")
	fun getUrlForSoknadsarkiverer() = getAttribute("soknadsarkiverer.url")
	fun getUrlForArkivMock()        = getAttribute("arkiv-mock.url")
}
