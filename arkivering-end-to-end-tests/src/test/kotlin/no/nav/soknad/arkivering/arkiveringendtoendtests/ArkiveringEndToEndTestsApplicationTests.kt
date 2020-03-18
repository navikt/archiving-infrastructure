package no.nav.soknad.arkivering.arkiveringendtoendtests

import no.nav.soknad.arkivering.arkiveringendtoendtests.dto.FilElementDto
import no.nav.soknad.arkivering.arkiveringendtoendtests.dto.InnsendtDokumentDto
import no.nav.soknad.arkivering.arkiveringendtoendtests.dto.InnsendtVariantDto
import no.nav.soknad.arkivering.arkiveringendtoendtests.dto.SoknadInnsendtDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.*
import org.springframework.web.client.RestTemplate
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

		sendFilesToFileStorage(uuid)
		sendDataToMottaker(dto)

		verifyDataInJoark(dto)
	}


	private fun verifyDataInJoark(dto: SoknadInnsendtDto) {
		val key = dto.innsendingsId
		val url = "http://localhost:${dependencies["joark-mock"]}/joark/lookup/$key"

		val responseEntity: ResponseEntity<List<LinkedHashMap<String, String>>> = pollJoarkUntilResponds(url)

		assertEquals(dto.tema, responseEntity.body?.get(0)!!["message"])
		assertEquals(dto.innsendingsId, responseEntity.body?.get(0)!!["name"])
	}

	private fun <T> pollJoarkUntilResponds(url: String): ResponseEntity<List<T>> {

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
		fail("Failed to get response from Joark")
	}

	private fun sendFilesToFileStorage(uuid: String) {
		val files = listOf(FilElementDto(uuid, "apabepa".toByteArray()))
		val url = "http://localhost:${dependencies["soknadsfillager"]}/filer"

		performPostRequest(files, url)
	}

	private fun sendDataToMottaker(dto: SoknadInnsendtDto) {
		val url = "http://localhost:${dependencies["soknadsmottaker"]}/save"
		performPostRequest(dto, url)
	}

	private fun performPostRequest(payload: Any, url: String) {
		val headers = HttpHeaders()
		headers.contentType = MediaType.APPLICATION_JSON
		val request = HttpEntity(payload, headers)
		restTemplate.postForObject(url, request, String::class.java)
	}

	private fun createDto(uuid: String) = SoknadInnsendtDto("innsendingId", false, "personId", "tema", LocalDateTime.now(),
		listOf(InnsendtDokumentDto("NAV 10-07.17", true, "Søknad om refusjon av reiseutgifter - bil",
			listOf(InnsendtVariantDto(uuid, null, "filnavn", "1024", "variantformat", "PDFA")))))
}

class Health {
	lateinit var status: String
}
