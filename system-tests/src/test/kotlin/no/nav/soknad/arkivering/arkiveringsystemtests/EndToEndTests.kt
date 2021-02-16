package no.nav.soknad.arkivering.arkiveringsystemtests

import com.fasterxml.jackson.databind.ObjectMapper
import no.nav.soknad.arkivering.arkiveringsystemtests.dto.FilElementDto
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.*

class EndToEndTests {
	private val restClient = OkHttpClient()
	private val objectMapper = ObjectMapper().also { it.findAndRegisterModules() }

	@Test
	fun `Can ping soknadsfillager`() {
		val url = "https://soknadsfillager-q0.dev.adeo.no/filer"
		val username = "srvHenvendelse"
		val password = System.getenv("SRVHENVENDELSE_PASSWORD")
		println("pw len ${password.length}: '$password'")

		val headers = createHeaders(username, password)

		val payload = listOf(FilElementDto("apa", "bepa".toByteArray(), LocalDateTime.now()))
		performPostRequest(payload, url, headers)
	}

	private fun performPostRequest(payload: Any, url: String, headers: Headers) {
		val requestBody = object : RequestBody() {
			override fun contentType() = "application/json".toMediaType()
			override fun writeTo(sink: BufferedSink) {
				sink.writeUtf8(objectMapper.writeValueAsString(payload))
			}
		}

		val request = Request.Builder().url(url).headers(headers).post(requestBody).build()

		val response = restClient.newCall(request).execute()
		val body = response.body
		response.close()

		println(body)
	}

	private fun createHeaders(username: String, password: String): Headers {
		val auth = "$username:$password"
		val authHeader = "Basic " + Base64.getEncoder().encodeToString(auth.toByteArray())
		return Headers.headersOf(
			"Authorization", authHeader,
			"Content-Type", "application/json"
		)
	}
}
