package no.nav.soknad.arkivering.arkiveringsystemtests

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import no.nav.soknad.arkivering.arkiveringsystemtests.dto.FilElementDto
import no.nav.soknad.arkivering.arkiveringsystemtests.dto.InnsendtDokumentDto
import no.nav.soknad.arkivering.arkiveringsystemtests.dto.InnsendtVariantDto
import no.nav.soknad.arkivering.arkiveringsystemtests.dto.SoknadInnsendtDto
import no.nav.soknad.arkivering.arkiveringsystemtests.environment.EnvironmentConfig
import no.nav.soknad.arkivering.arkiveringsystemtests.kafka.KafkaListener
import no.nav.soknad.arkivering.arkiveringsystemtests.kafka.KafkaPublisher
import no.nav.soknad.arkivering.arkiveringsystemtests.verification.AssertionHelper
import no.nav.soknad.arkivering.avroschemas.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.fail
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.*
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class SystemTestBase {

	val attemptsThanSoknadsarkivererWillPerform = 6

	val targetEnvironment: String? = System.getProperty("targetEnvironment")
	val isExternalEnvironment = targetEnvironment?.matches(externalEnvironments.toRegex()) ?: false
	val env = EnvironmentConfig()
	private val restClient = OkHttpClient()
	private val objectMapper = ObjectMapper().also { it.findAndRegisterModules() }
	private lateinit var kafkaPublisher: KafkaPublisher
	private lateinit var kafkaListener: KafkaListener


	private val restRequestCallback = object : Callback {
		override fun onResponse(call: Call, response: Response) { }

		override fun onFailure(call: Call, e: IOException) {
			fail("Failed to perform request", e)
		}
	}


	fun setUp() {
		println("Target Environment: $targetEnvironment")

		kafkaPublisher = KafkaPublisher(env.getUrlForKafkaBroker(), env.getUrlForSchemaRegistry())
		kafkaListener = KafkaListener(env.getUrlForKafkaBroker(), env.getUrlForSchemaRegistry())
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

				val request = Request.Builder().url(url).get().build()
				val response = restClient.newCall(request).execute().use { response -> response.body?.string() ?: "" }
				val health = objectMapper.readValue(response, Health::class.java)

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


	fun pollAndVerifyDataInFileStorage(uuid: String, expectedNumberOfHits: Int) {
		val url = env.getUrlForSoknadsfillager() + "/filer?ids=$uuid"
		loopAndVerify(expectedNumberOfHits, { getNumberOfFiles(url) },
			{ assertEquals(expectedNumberOfHits, getNumberOfFiles(url), "Expected $expectedNumberOfHits files in File Storage") })
	}


	private fun getNumberOfFiles(url: String): Int {
		val headers = createHeaders(env.getSoknadsfillagerUsername(), env.getSoknadsfillagerPassword())

		val request = Request.Builder().url(url).headers(headers).get().build()

		restClient.newCall(request).execute().use {
			if (it.isSuccessful) {
				val bytes = it.body?.bytes()
				val listOfFiles = objectMapper.readValue(bytes, object : TypeReference<List<FilElementDto>>() {})

				return listOfFiles.filter { file -> file.fil != null }.size
			}
		}
		return 0
	}


	fun sendFilesToFileStorage(uuid: String) {
		val message = "fileUuid is $uuid for test '${Thread.currentThread().stackTrace[2].methodName}'"
		sendFilesToFileStorage(uuid, "apabepa".toByteArray(), message)
	}

	fun sendFilesToFileStorage(uuid: String, payload: ByteArray, message: String) {
		println(message)
		sendFilesToFileStorageAndVerify(uuid, payload)
	}

	private fun sendFilesToFileStorageAndVerify(uuid: String, payload: ByteArray) {
		val files = listOf(FilElementDto(uuid, payload, LocalDateTime.now()))
		val url = env.getUrlForSoknadsfillager() + "/filer"

		performPostCall(files, url, createHeaders(env.getSoknadsfillagerUsername(), env.getSoknadsfillagerPassword()), false)
		pollAndVerifyDataInFileStorage(uuid, 1)
	}

	fun verifyComponentIsUp(url: String, componentName: String) {
		val healthStatusCode = {
			val request = Request.Builder().url(url).get().build()
			restClient.newCall(request).execute().use { response -> response.code }
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

	fun sendDataToMottaker(dto: SoknadInnsendtDto, async: Boolean = false, verbose: Boolean = true) {
		if (verbose)
			println("innsendingsId is ${dto.innsendingsId} for test '${Thread.currentThread().stackTrace[2].methodName}'")
		val url = env.getUrlForSoknadsmottaker() + "/save"
		performPostCall(dto, url, createHeaders(env.getSoknadsmottakerUsername(), env.getSoknadsmottakerPassword()), async)
	}

	private fun createHeaders(username: String, password: String): Headers {
		val auth = "$username:$password"
		val authHeader = "Basic " + Base64.getEncoder().encodeToString(auth.toByteArray())
		return Headers.headersOf("Authorization", authHeader)
	}

	private fun performPostCall(payload: Any, url: String, headers: Headers, async: Boolean) {
		val requestBody = object : RequestBody() {
			override fun contentType() = "application/json".toMediaType()
			override fun writeTo(sink: BufferedSink) {
				sink.writeUtf8(objectMapper.writeValueAsString(payload))
			}
		}

		val request = Request.Builder().url(url).headers(headers).post(requestBody).build()

		val call = restClient.newCall(request)
		if (async)
			call.enqueue(restRequestCallback)
		else
			call.execute().close()
	}

	fun performPutCall(url: String) {
		val requestBody = object : RequestBody() {
			override fun contentType() = "application/json".toMediaType()
			override fun writeTo(sink: BufferedSink) {}
		}

		val request = Request.Builder().url(url).put(requestBody).build()

		restClient.newCall(request).execute().use { response ->
			if (!response.isSuccessful)
				throw IOException("Unexpected code $response")
		}
	}

	fun performDeleteCall(url: String) {
		val requestBody = object : RequestBody() {
			override fun contentType() = "application/json".toMediaType()
			override fun writeTo(sink: BufferedSink) {}
		}
		val request = Request.Builder().url(url).delete(requestBody).build()
		restClient.newCall(request).execute().close()
	}

	private fun loopAndVerify(expectedCount: Int, getCount: () -> Int,
														finalCheck: () -> Any = { assertEquals(expectedCount, getCount.invoke()) }) {

		val startTime = System.currentTimeMillis()
		val timeout = 30 * 1000

		while (System.currentTimeMillis() < startTime + timeout) {
			val matches = getCount.invoke()

			if (matches == expectedCount) {
				break
			}
			TimeUnit.MILLISECONDS.sleep(50)
		}
		finalCheck.invoke()
	}

	fun createDto(fileId: String, innsendingsId: String = UUID.randomUUID().toString()) =
		SoknadInnsendtDto(innsendingsId, false, "personId", "tema", LocalDateTime.now(),
			listOf(InnsendtDokumentDto("NAV 10-07.17", true, "Søknad om refusjon av reiseutgifter - bil",
				listOf(InnsendtVariantDto(fileId, null, "filnavn", "1024", "variantformat", "PDFA")))))

	fun createDto(fileIds: List<String>) =
		SoknadInnsendtDto(UUID.randomUUID().toString(), false, "personId", "tema", LocalDateTime.now(),
			listOf(InnsendtDokumentDto("NAV 10-07.17", true, "Søknad om refusjon av reiseutgifter - bil",
				fileIds.map { InnsendtVariantDto(it, null, "filnavn", "1024", "variantformat", "PDFA") })))


	fun assertThatArkivMock() = AssertionHelper(kafkaListener).setup()

	fun assertThatFinishedEventsAreCreated(countAndTimeout: Pair<Int, Long>) {
		AssertionHelper(kafkaListener)
			.setup()
			.hasNumberOfFinishedEvents(countAndTimeout)
			.verify()
	}
}

@JsonIgnoreProperties(ignoreUnknown = true)
class Health {
	lateinit var status: String
}


const val externalEnvironments = "docker|q0|q1"
