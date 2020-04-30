package no.nav.soknad.arkivering.arkiveringendtoendtests

import no.nav.soknad.arkivering.arkiveringendtoendtests.dto.FilElementDto
import no.nav.soknad.arkivering.arkiveringendtoendtests.dto.InnsendtDokumentDto
import no.nav.soknad.arkivering.arkiveringendtoendtests.dto.InnsendtVariantDto
import no.nav.soknad.arkivering.arkiveringendtoendtests.dto.SoknadInnsendtDto
import org.apache.tomcat.util.codec.binary.Base64.encodeBase64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.*
import org.springframework.web.client.RestTemplate
import java.net.URI
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.collections.HashMap


class ArkiveringEndToEndTestsApplicationTests {

	private val dependencies = HashMap<String, Int>().also {
		it["soknadsmottaker"] = 8090
		it["soknadsarkiverer"] = 8091
		it["soknadsfillager"] = 9042
		it["joark-mock"] = 8092
	}

	private val healthEndpoints = HashMap<String, String>().also {
		it["soknadsmottaker"] = "http://localhost:8090/internal/health"
		it["soknadsarkiverer"] = "http://localhost:8091/actuator/health"
		it["soknadsfillager"] = "http://localhost:9042/internal/health"
		it["joark-mock"] = "http://localhost:8092/internal/health"
	}

	private val mottakerUsername = "avsender"
	private val mottakerPassword = "password"

	private val restTemplate = RestTemplate()

	@BeforeEach
	fun setup() {
		checkThatDependenciesAreUp()
	}

	private fun checkThatDependenciesAreUp() {
		for (dep in healthEndpoints) {
			try {
				val healthStatusResponse = restTemplate.getForEntity(dep.value, Health::class.java)
				assertEquals("UP", healthStatusResponse.body?.status, "Dependency '${dep.key}' seems to be down")
			} catch (e: Exception) {
				fail("Dependency '${dep.key}' seems to be down")
			}
		}
	}

	@Test
	fun `Happy case - one file in file storage`() {
		val uuid = UUID.randomUUID().toString()
		val dto = createDto(uuid)
		setNormalJoarkBehaviour(dto.innsendingsId)

		sendFilesToFileStorage(uuid)
		sendDataToMottaker(dto)

		verifyDataInJoark(dto)
		verifyNumberOfCallsToJoark(dto.innsendingsId, 1)
		pollAndVerifyDataInFileStorage(uuid, 0)
	}

	@Test
	fun `Happy case - several files in file storage`() {
		val uuid0 = UUID.randomUUID().toString()
		val uuid1 = UUID.randomUUID().toString()
		val dto = SoknadInnsendtDto(UUID.randomUUID().toString(), false, "personId", "tema", LocalDateTime.now(),
			listOf(
				InnsendtDokumentDto("NAV 10-07.17", true, "Søknad om refusjon av reiseutgifter - bil",
					listOf(InnsendtVariantDto(uuid0, null, "filnavn", "1024", "variantformat", "PDFA"))),

				InnsendtDokumentDto("NAV 10-07.17", false, "Søknad om refusjon av reiseutgifter - bil",
					listOf(InnsendtVariantDto(uuid1, null, "filnavn", "1024", "variantformat", "PDFA")))
			))
		setNormalJoarkBehaviour(dto.innsendingsId)

		sendFilesToFileStorage(uuid0)
		sendFilesToFileStorage(uuid1)
		sendDataToMottaker(dto)

		verifyDataInJoark(dto)
		verifyNumberOfCallsToJoark(dto.innsendingsId, 1)
		pollAndVerifyDataInFileStorage(uuid0, 0)
		pollAndVerifyDataInFileStorage(uuid1, 0)
	}

	@Test
	fun `No files in file storage - Nothing is sent to Joark`() {
		val uuid = UUID.randomUUID().toString()
		val dto = createDto(uuid)
		setNormalJoarkBehaviour(dto.innsendingsId)

		pollAndVerifyDataInFileStorage(uuid, 0)
		sendDataToMottaker(dto)

		verifyDataNotInJoark(dto)
		verifyNumberOfCallsToJoark(dto.innsendingsId, 0)
		pollAndVerifyDataInFileStorage(uuid, 0)
	}

	@Test
	fun `Several Hovedskjemas - Nothing is sent to Joark`() {
		val uuid0 = UUID.randomUUID().toString()
		val uuid1 = UUID.randomUUID().toString()
		val dto = SoknadInnsendtDto(UUID.randomUUID().toString(), false, "personId", "tema", LocalDateTime.now(),
			listOf(
				InnsendtDokumentDto("NAV 10-07.17", true, "Søknad om refusjon av reiseutgifter - bil",
					listOf(InnsendtVariantDto(uuid0, null, "filnavn", "1024", "variantformat", "PDFA"))),

				InnsendtDokumentDto("NAV 10-07.17", true, "Søknad om refusjon av reiseutgifter - bil",
					listOf(InnsendtVariantDto(uuid1, null, "filnavn", "1024", "variantformat", "PDFA")))
			))
		setNormalJoarkBehaviour(dto.innsendingsId)

		sendFilesToFileStorage(uuid0)
		sendFilesToFileStorage(uuid1)
		sendDataToMottaker(dto)

		verifyDataNotInJoark(dto)
		verifyNumberOfCallsToJoark(dto.innsendingsId, 0)
		pollAndVerifyDataInFileStorage(uuid0, 1)
		pollAndVerifyDataInFileStorage(uuid1, 1)
	}

	@Test
	fun `Joark responds 404 on first two attempts - Works on third attempt`() {
		val uuid = UUID.randomUUID().toString()
		val dto = createDto(uuid)

		sendFilesToFileStorage(uuid)
		mockJoarkRespondsWithCodeForXAttempts(dto.innsendingsId, 404, 2)
		sendDataToMottaker(dto)

		verifyDataInJoark(dto)
		verifyNumberOfCallsToJoark(dto.innsendingsId, 3)
		pollAndVerifyDataInFileStorage(uuid, 0)
	}

	@Test
	fun `Joark responds 500 on first attempt - Works on second attempt`() {
		val uuid = UUID.randomUUID().toString()
		val dto = createDto(uuid)

		sendFilesToFileStorage(uuid)
		mockJoarkRespondsWithCodeForXAttempts(dto.innsendingsId, 500, 1)
		sendDataToMottaker(dto)

		verifyDataInJoark(dto)
		verifyNumberOfCallsToJoark(dto.innsendingsId, 2)
		pollAndVerifyDataInFileStorage(uuid, 0)
	}

	@Test
	fun `Joark responds 200 but has wrong response body - Will retry`() {
		val uuid = UUID.randomUUID().toString()
		val dto = createDto(uuid)

		sendFilesToFileStorage(uuid)
		mockJoarkRespondsWithErroneousForXAttempts(dto.innsendingsId, 3)
		sendDataToMottaker(dto)

		verifyDataInJoark(dto)
		pollAndVerifyNumberOfCallsToJoark(dto.innsendingsId, 4)
		pollAndVerifyDataInFileStorage(uuid, 0)
	}

	@Test
	fun `Joark responds 200 but has wrong response body - Will retry until soknadsarkiverer gives up`() {
		val uuid = UUID.randomUUID().toString()
		val dto = createDto(uuid)
		val moreAttemptsThanSoknadsarkivererWillPerform = 5

		sendFilesToFileStorage(uuid)
		mockJoarkRespondsWithErroneousForXAttempts(dto.innsendingsId, moreAttemptsThanSoknadsarkivererWillPerform)
		sendDataToMottaker(dto)

		verifyDataInJoark(dto)
		pollAndVerifyNumberOfCallsToJoark(dto.innsendingsId, 5)
		pollAndVerifyDataInFileStorage(uuid, 1)
	}

	private fun setNormalJoarkBehaviour(uuid: String) {
		val url = "http://localhost:${dependencies["joark-mock"]}/joark/mock/response-behaviour/set-normal-behaviour/$uuid"
		restTemplate.put(url, null)
	}

	private fun mockJoarkRespondsWithCodeForXAttempts(uuid: String, status: Int, forAttempts: Int) {
		val url = "http://localhost:${dependencies["joark-mock"]}/joark/mock/response-behaviour/mock-response/$uuid/$status/$forAttempts"
		restTemplate.put(url, null)
	}

	private fun mockJoarkRespondsWithErroneousForXAttempts(uuid: String, forAttempts: Int) {
		val url = "http://localhost:${dependencies["joark-mock"]}/joark/mock/response-behaviour/set-status-ok-with-erroneous-body/$uuid/$forAttempts"
		restTemplate.put(url, null)
	}

	private fun verifyDataNotInJoark(dto: SoknadInnsendtDto) {
		val key = dto.innsendingsId
		val url = "http://localhost:${dependencies["joark-mock"]}/joark/lookup/$key"

		val responseEntity: ResponseEntity<List<LinkedHashMap<String, String>>> = pollJoarkUntilTimeout(url)

		if (responseEntity.hasBody())
			fail("Expected Joark to not have any results for $key")
	}

	private fun verifyNumberOfCallsToJoark(uuid: String, expectedNumberOfCalls: Int) {
		val numberOfCalls = getNumberOfCallsToJoark(uuid)
		assertEquals(expectedNumberOfCalls, numberOfCalls)
	}

	private fun getNumberOfCallsToJoark(uuid: String): Int {
		val url = "http://localhost:${dependencies["joark-mock"]}/joark/mock/response-behaviour/number-of-calls/$uuid"
		val responseEntity = restTemplate.getForEntity(url, Int::class.java)
		return responseEntity.body ?: -1
	}

	private fun verifyDataInJoark(dto: SoknadInnsendtDto) {
		val key = dto.innsendingsId
		val url = "http://localhost:${dependencies["joark-mock"]}/joark/lookup/$key"

		val responseEntity: ResponseEntity<List<LinkedHashMap<String, String>>> = pollJoarkUntilTimeout(url)

		if (!responseEntity.hasBody())
			fail("Failed to get response from Joark")
		assertEquals(dto.tema, responseEntity.body?.get(0)!!["message"])
		assertEquals(dto.innsendingsId, responseEntity.body?.get(0)!!["name"])
	}

	private fun pollAndVerifyNumberOfCallsToJoark(uuid: String, expectedNumberOfCalls: Int) {
		pollAndVerifyResult("calls to Joark", expectedNumberOfCalls) { getNumberOfCallsToJoark(uuid) }
	}

	private fun pollAndVerifyDataInFileStorage(uuid: String, expectedNumberOfHits: Int) {
		val url = "http://localhost:${dependencies["soknadsfillager"]}/filer?ids=$uuid"
		pollAndVerifyResult("files in File Storage", expectedNumberOfHits) { getNumberOfFiles(url) }
	}

	private fun pollAndVerifyResult(context: String, expected: Int, function: () -> Int) {
		val startTime = System.currentTimeMillis()
		val timeout = 10 * 1000

		var result = -1
		while (System.currentTimeMillis() < startTime + timeout) {
			result = function.invoke()

			if (result == expected) {
				return
			}
			TimeUnit.MILLISECONDS.sleep(50)
		}
		fail("Expected $expected $context, but saw $result")
	}

	private fun getNumberOfFiles(url: String): Int {
		val request = RequestEntity<Any>(HttpMethod.GET, URI(url))

		val response = restTemplate.exchange(request, typeRef<List<FilElementDto>>()).body

		return response?.filter { it.fil != null }?.size ?: 0
	}

	private fun <T> pollJoarkUntilTimeout(url: String): ResponseEntity<List<T>> {

		val respType = object : ParameterizedTypeReference<List<T>>() {}

		val startTime = System.currentTimeMillis()
		val timeout = 10 * 1000

		while (System.currentTimeMillis() < startTime + timeout) {
			val responseEntity = restTemplate.exchange(url, HttpMethod.GET, null, respType)

			if (responseEntity.body != null && responseEntity.body!!.isNotEmpty()) {
				return responseEntity
			}
			TimeUnit.MILLISECONDS.sleep(50)
		}
		return ResponseEntity.of(Optional.empty())
	}

	private fun sendFilesToFileStorage(uuid: String) {
		val files = listOf(FilElementDto(uuid, "apabepa".toByteArray()))
		val url = "http://localhost:${dependencies["soknadsfillager"]}/filer"

		performPostRequest(files, url)
		pollAndVerifyDataInFileStorage(uuid, 1)
	}

	private fun sendDataToMottaker(dto: SoknadInnsendtDto) {
		val url = "http://localhost:${dependencies["soknadsmottaker"]}/save"
		performPostRequest(dto, url, createHeaders())
	}

	private fun createHeaders(): HttpHeaders {
		return object : HttpHeaders() {
			init {
				val auth = "$mottakerUsername:$mottakerPassword"
				val encodedAuth: ByteArray = encodeBase64(auth.toByteArray())
				val authHeader = "Basic " + String(encodedAuth)
				set("Authorization", authHeader)
			}
		}
	}

	private fun performPostRequest(payload: Any, url: String, headers: HttpHeaders = HttpHeaders()) {
		headers.contentType = MediaType.APPLICATION_JSON
		val request = HttpEntity(payload, headers)
		restTemplate.postForObject(url, request, String::class.java)
	}

	private fun createDto(uuid: String) = SoknadInnsendtDto(UUID.randomUUID().toString(), false, "personId", "tema", LocalDateTime.now(),
		listOf(InnsendtDokumentDto("NAV 10-07.17", true, "Søknad om refusjon av reiseutgifter - bil",
			listOf(InnsendtVariantDto(uuid, null, "filnavn", "1024", "variantformat", "PDFA")))))
}

class Health {
	lateinit var status: String
}

inline fun <reified T : Any> typeRef(): ParameterizedTypeReference<T> = object : ParameterizedTypeReference<T>() {}
