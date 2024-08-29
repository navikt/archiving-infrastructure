package no.nav.soknad.arkivering.arkiveringsystemtests

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import no.nav.soknad.arkivering.Config
import no.nav.soknad.arkivering.KafkaConfig
import no.nav.soknad.arkivering.SchemaRegistry
import no.nav.soknad.arkivering.arkiveringsystemtests.environment.EnvironmentConfig
import no.nav.soknad.arkivering.innsending.InnsendingApi
import no.nav.soknad.arkivering.innsending.performGetCall
import no.nav.soknad.arkivering.kafka.KafkaListener
import no.nav.soknad.arkivering.kafka.KafkaPublisher
import no.nav.soknad.arkivering.verification.AssertionHelper
import no.nav.soknad.arkivering.verification.SoknadAssertionHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.fail
import org.slf4j.LoggerFactory

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class SystemTestBase {
	private val logger = LoggerFactory.getLogger(javaClass)

	val attemptsThanSoknadsarkivererWillPerform = 6

	val targetEnvironment: String? = System.getProperty("targetEnvironment")
	val isExternalEnvironment = targetEnvironment?.matches(externalEnvironments.toRegex()) ?: false
	val env = EnvironmentConfig(targetEnvironment)
	lateinit var config: Config
	private val objectMapper = ObjectMapper().also { it.findAndRegisterModules() }
	private lateinit var kafkaPublisher: KafkaPublisher
	lateinit var kafkaListener: KafkaListener

	fun setUp() {
		logger.info("****Target Environment: $targetEnvironment")
		if (isExternalEnvironment)
			checkThatDependenciesAreUp()

		val dockerImages = env.embeddedDockerImages
		config = if (dockerImages != null) {
			Config(soknadsfillagerUrl = dockerImages.getUrlForSoknadsfillager(), soknadsmottakerUrl = dockerImages.getUrlForSoknadsmottaker(), arkivMockUrl = dockerImages.getUrlForArkivMock(), innsendingApiUrl = dockerImages.getUrlForInnsendingApi())
		} else {
			Config()
		}
		val kafkaConfig = if (dockerImages != null) {
			KafkaConfig(brokers = dockerImages.getUrlForKafkaBroker(), schemaRegistry = SchemaRegistry(url = dockerImages.getUrlForSchemaRegistry()))
		} else {
			KafkaConfig()
		}
		kafkaPublisher = KafkaPublisher(kafkaConfig)
		kafkaListener = KafkaListener(kafkaConfig)
	}

	private fun checkThatDependenciesAreUp() {
		val dependencies = HashMap<String, String>().also {
			it["soknadsmottaker"]  = env.getUrlForSoknadsmottaker()+"/internal/health"
			it["soknadsarkiverer"] = env.getUrlForSoknadsarkiverer()+"/internal/health"
			it["soknadsfillager"]  = env.getUrlForSoknadsfillager()+"/internal/health"
			it["innsendingapi"]    = env.getUrlForInnsendingApi()+"/health/isAlive"
			it["arkiv-mock"]       = env.getUrlForArkivMock()+"/internal/health"
		}
		for (dep in dependencies) {
			try {
				val url = "${dep.value}"

				val bytes = performGetCall(url)
				val health = objectMapper.readValue(bytes, Health::class.java)

				assertEquals("UP", health.status, "Dependency '${dep.key}' seems to be down")
			} catch (e: Exception) {
				fail("Dependency '${dep.key}' seems to be down")
			}
		}
	}

	fun tearDown() = kafkaListener.close()

	fun putPoisonPillOnKafkaTopic(key: String) {
		logger.debug("Poison pill key is $key for test '${Thread.currentThread().stackTrace[2].methodName}'")
		kafkaPublisher.putDataOnTopic(key, "unserializableString")
	}

	fun assertThatArkivMock() = AssertionHelper(kafkaListener)

	fun assertThatSoknad(innsendingsId: String) = SoknadAssertionHelper(InnsendingApi(config), innsendingsId)
}

@JsonIgnoreProperties(ignoreUnknown = true)
class Health {
	lateinit var status: String
}


const val externalEnvironments = "docker|q0|q1"
