package no.nav.soknad.arkivering.arkiveringsystemtests

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import no.nav.soknad.arkivering.Configuration
import no.nav.soknad.arkivering.arkiveringsystemtests.environment.EnvironmentConfig
import no.nav.soknad.arkivering.avroschemas.*
import no.nav.soknad.arkivering.innsending.getStatusCodeForGetCall
import no.nav.soknad.arkivering.innsending.performGetCall
import no.nav.soknad.arkivering.kafka.KafkaListener
import no.nav.soknad.arkivering.kafka.KafkaPublisher
import no.nav.soknad.arkivering.utils.createDto
import no.nav.soknad.arkivering.utils.loopAndVerify
import no.nav.soknad.arkivering.verification.AssertionHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.fail
import org.slf4j.LoggerFactory
import java.time.ZoneOffset

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class SystemTestBase {
	private val logger = LoggerFactory.getLogger(javaClass)

	val attemptsThanSoknadsarkivererWillPerform = 6

	val targetEnvironment: String? = System.getProperty("targetEnvironment")
	val isExternalEnvironment = targetEnvironment?.matches(externalEnvironments.toRegex()) ?: false
	val env = EnvironmentConfig(targetEnvironment)
	lateinit var config: Configuration
	private val objectMapper = ObjectMapper().also { it.findAndRegisterModules() }
	private lateinit var kafkaPublisher: KafkaPublisher
	private lateinit var kafkaListener: KafkaListener


	fun setUp() {
		logger.info("Target Environment: $targetEnvironment")
		if (isExternalEnvironment)
			checkThatDependenciesAreUp()

		val dockerImages = env.embeddedDockerImages
		config = if (dockerImages != null) {
			val dockerUrls = mapOf(
				"SOKNADSFILLAGER_URL"     to dockerImages.getUrlForSoknadsfillager(),
				"SOKNADSMOTTAKER_URL"     to dockerImages.getUrlForSoknadsmottaker(),
				"SOKNADSARKIVERER_URL"    to dockerImages.getUrlForSoknadsarkiverer(),
				"ARKIVMOCK_URL"           to dockerImages.getUrlForArkivMock(),
				"SCHEMA_REGISTRY_URL"     to dockerImages.getUrlForSchemaRegistry(),
				"KAFKA_BOOTSTRAP_SERVERS" to dockerImages.getUrlForKafkaBroker()
			)
			Configuration(dockerUrls)
		} else {
			Configuration()
		}
		kafkaPublisher = KafkaPublisher(config)
		kafkaListener = KafkaListener(config.kafkaConfig)
	}

	private fun checkThatDependenciesAreUp() {
		val dependencies = HashMap<String, String>().also {
			it["soknadsmottaker"]  = env.getUrlForSoknadsmottaker()
			it["soknadsarkiverer"] = env.getUrlForSoknadsarkiverer()
			it["soknadsfillager"]  = env.getUrlForSoknadsfillager()
			it["arkiv-mock"]       = env.getUrlForArkivMock()
		}
		for (dep in dependencies) {
			try {
				val url = "${dep.value}/internal/health"

				val bytes = performGetCall(url)
				val health = objectMapper.readValue(bytes, Health::class.java)

				assertEquals("UP", health.status, "Dependency '${dep.key}' seems to be down")
			} catch (e: Exception) {
				fail("Dependency '${dep.key}' seems to be down")
			}
		}
	}


	fun tearDown() {
		kafkaListener.close()
	}


	fun putPoisonPillOnKafkaTopic(key: String) {
		logger.debug("Poison pill key is $key for test '${Thread.currentThread().stackTrace[2].methodName}'")
		kafkaPublisher.putDataOnTopic(key, "unserializableString")
	}

	fun putInputEventOnKafkaTopic(key: String, fileId: String) {
		logger.debug("Input Event key is $key for test '${Thread.currentThread().stackTrace[2].methodName}'")
		kafkaPublisher.putDataOnTopic(key, createSoknadarkivschema(key, fileId))
	}

	fun putProcessingEventOnKafkaTopic(key: String, vararg eventTypes: EventTypes) {
		logger.debug("Processing event key is $key for test '${Thread.currentThread().stackTrace[2].methodName}'")
		eventTypes.forEach { eventType -> kafkaPublisher.putDataOnTopic(key, ProcessingEvent(eventType)) }
	}


	fun verifyComponentIsUp(url: String, componentName: String) {
		val healthStatusCode = {
			getStatusCodeForGetCall(url)
		}
		loopAndVerify(200, healthStatusCode) {
			assertEquals(200, healthStatusCode.invoke(), "$componentName does not seem to be up")
		}
	}

	private fun createSoknadarkivschema(innsendingsId: String, fileId: String): Soknadarkivschema {
		val soknadInnsendtDto = createDto(innsendingsId, fileId)
		val innsendtDokumentDto = soknadInnsendtDto.innsendteDokumenter[0]
		val innsendtVariantDto = innsendtDokumentDto.varianter[0]

		val mottattVariant = listOf(MottattVariant(innsendtVariantDto.uuid, innsendtVariantDto.filNavn, innsendtVariantDto.filtype, innsendtVariantDto.variantformat))

		val mottattDokument = listOf(MottattDokument(innsendtDokumentDto.skjemaNummer, innsendtDokumentDto.erHovedSkjema, innsendtDokumentDto.tittel, mottattVariant))

		return Soknadarkivschema(innsendingsId, soknadInnsendtDto.personId, soknadInnsendtDto.tema,
			soknadInnsendtDto.innsendtDato.toEpochSecond(ZoneOffset.UTC), Soknadstyper.SOKNAD, mottattDokument)
	}


	fun assertThatArkivMock() = AssertionHelper(kafkaListener)
}

@JsonIgnoreProperties(ignoreUnknown = true)
class Health {
	lateinit var status: String
}


const val externalEnvironments = "docker|q0|q1"
