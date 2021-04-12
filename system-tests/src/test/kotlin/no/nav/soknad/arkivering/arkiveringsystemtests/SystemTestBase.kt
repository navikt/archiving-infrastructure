package no.nav.soknad.arkivering.arkiveringsystemtests

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import no.nav.soknad.arkivering.Configuration
import no.nav.soknad.arkivering.arkiveringsystemtests.environment.EnvironmentConfig
import no.nav.soknad.arkivering.avroschemas.*
import no.nav.soknad.arkivering.kafka.KafkaListener
import no.nav.soknad.arkivering.kafka.KafkaPublisher
import no.nav.soknad.arkivering.utils.createDto
import no.nav.soknad.arkivering.utils.loopAndVerify
import no.nav.soknad.arkivering.verification.AssertionHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.fail
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.ZoneOffset
import java.util.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class SystemTestBase {

	val attemptsThanSoknadsarkivererWillPerform = 6

	val targetEnvironment: String? = System.getProperty("targetEnvironment")
	val isExternalEnvironment = targetEnvironment?.matches(externalEnvironments.toRegex()) ?: false
	val env = EnvironmentConfig(targetEnvironment)
	lateinit var config: Configuration
	private lateinit var kafkaPublisher: KafkaPublisher
	private lateinit var kafkaListener: KafkaListener


	fun setUp() {
		println("Target Environment: $targetEnvironment")
		if (isExternalEnvironment)
			checkThatDependenciesAreUp()

		val dockerImages = env.embeddedDockerImages
		config = if (dockerImages != null) {
			val dockerUrls = mapOf(
				"SOKNADSFILLAGER_URL"     to dockerImages.getUrlForSoknadsfillager(),
				"SOKNADSMOTTAKER_URL"     to dockerImages.getUrlForSoknadsmottaker(),
				"SOKNADSARKIVERER_URL"    to dockerImages.getUrlForSoknadsarkiverer(),
				"ARKIV-MOCK_URL"          to dockerImages.getUrlForArkivMock(),
				"SCHEMA_REGISTRY_URL"     to dockerImages.getUrlForSchemaRegistry(),
				"KAFKA_BOOTSTRAP_SERVERS" to dockerImages.getUrlForKafkaBroker()
			)
			Configuration(dockerUrls)
		} else {
			Configuration()
		}
		kafkaPublisher = KafkaPublisher(config)
		kafkaListener = KafkaListener(config)
	}

	private fun checkThatDependenciesAreUp() {
		val dependencies = HashMap<String, String>().also {
			it["soknadsmottaker"] = env.getUrlForSoknadsmottaker()
			it["soknadsarkiverer"] = env.getUrlForSoknadsarkiverer()
			it["soknadsfillager"] = env.getUrlForSoknadsfillager()
			it["arkiv-mock"] = env.getUrlForArkivMock()
		}
		for (dep in dependencies) {
			try {
				val url = "${dep.value}/internal/health"
				val health = makeHealthRequest(url)

				assertEquals("UP", health?.status, "Dependency '${dep.key}' seems to be down")
			} catch (e: Exception) {
				fail("Dependency '${dep.key}' seems to be down")
			}
		}
	}

	private fun makeHealthRequest(url: String) = WebClient.builder().build()
		.method(HttpMethod.GET)
		.uri(url)
		.contentType(MediaType.APPLICATION_JSON)
		.accept(MediaType.APPLICATION_JSON)
		.retrieve()
		.onStatus(
			{ httpStatus -> httpStatus.is4xxClientError || httpStatus.is5xxServerError },
			{ response ->
				response.bodyToMono(String::class.java)
					.map { Exception("Got ${response.statusCode()} when requesting GET $url - response body: '$it'") }
			})
		.bodyToMono(Health::class.java)
		.block()

	fun tearDown() {
		kafkaListener.close()
	}


	fun putPoisonPillOnKafkaTopic(key: String) {
		println("Poison pill key is $key for test '${Thread.currentThread().stackTrace[2].methodName}'")
		kafkaPublisher.putDataOnTopic(key, "unserializableString")
	}

	fun putInputEventOnKafkaTopic(key: String, innsendingsId: String, fileId: String) {
		println("Input Event key is $key for test '${Thread.currentThread().stackTrace[2].methodName}'")
		kafkaPublisher.putDataOnTopic(key, createSoknadarkivschema(innsendingsId, fileId))
	}

	fun putProcessingEventOnKafkaTopic(key: String, vararg eventTypes: EventTypes) {
		println("Processing event key is $key for test '${Thread.currentThread().stackTrace[2].methodName}'")
		eventTypes.forEach { eventType -> kafkaPublisher.putDataOnTopic(key, ProcessingEvent(eventType)) }
	}


	fun verifyComponentIsUp(url: String, componentName: String) {
		val healthStatusCode = {
			WebClient.builder().build()
				.method(HttpMethod.GET)
				.uri(url)
				.exchangeToMono { Mono.just(it) }
				.map { it.statusCode().value() }
				.block()!!
		}
		loopAndVerify(200, healthStatusCode, {
			assertEquals(200, healthStatusCode.invoke(), "$componentName does not seem to be up")
		})
	}

	private fun createSoknadarkivschema(innsendingsId: String, fileId: String): Soknadarkivschema {
		val soknadInnsendtDto = createDto(fileId, innsendingsId)
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
