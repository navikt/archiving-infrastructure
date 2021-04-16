package no.nav.soknad.arkivering.innsending

import no.nav.soknad.arkivering.dto.FilElementDto
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient

@Service
class RestUtils(private val webClient: WebClient) {

	fun performGetCall(uri: String, headers: String): MutableList<FilElementDto>? {
		val method = HttpMethod.GET
		val webClient = setupWebClient(uri, method, headers)

		return webClient
			.retrieve()
			.onStatus(
				{ httpStatus -> httpStatus.is4xxClientError || httpStatus.is5xxServerError },
				{ response ->
					response.bodyToMono(String::class.java)
						.map { Exception("Got ${response.statusCode()} when requesting $method $uri - response body: '$it'") }
				})
			.bodyToFlux(FilElementDto::class.java)
			.collectList()
			.block()
	}

	fun performPostCall(payload: Any, uri: String, headers: String, async: Boolean) {
		val method = HttpMethod.POST
		val webClient = setupWebClient(uri, method, headers)

		val mono = webClient
			.body(BodyInserters.fromValue(payload))
			.retrieve()
			.onStatus(
				{ httpStatus -> httpStatus.is4xxClientError || httpStatus.is5xxServerError },
				{ response ->
					response.bodyToMono(String::class.java)
						.map { Exception("Got ${response.statusCode()} when requesting $method $uri - response body: '$it'") }
				})
			.bodyToMono(String::class.java)

		if (!async)
			mono.block()
	}

	fun performPutCall(uri: String, headers: String? = null) {
		val method = HttpMethod.PUT
		val webClient = setupWebClient(uri, method, headers)

		webClient
			.body(BodyInserters.empty<String>())
			.retrieve()
			.bodyToMono(String::class.java)
			.block()
	}

	fun performDeleteCall(uri: String, headers: String? = null) {
		val method = HttpMethod.DELETE
		val webClient = setupWebClient(uri, method, headers)

		webClient
			.retrieve()
			.onStatus(
				{ httpStatus -> httpStatus.is4xxClientError || httpStatus.is5xxServerError },
				{ response ->
					response.bodyToMono(String::class.java)
						.map { Exception("Got ${response.statusCode()} when requesting $method $uri - response body: '$it'") }
				})
			.bodyToMono(String::class.java)
			.block()
	}

	fun createHeaders(username: String, password: String): String {
		val auth = "$username:$password"
		val encodedAuth: ByteArray = org.apache.tomcat.util.codec.binary.Base64.encodeBase64(auth.toByteArray())
		return "Basic " + String(encodedAuth)
	}

	private fun setupWebClient(uri: String, method: HttpMethod, authHeader: String?): WebClient.RequestBodySpec {

		val spec = webClient
			.method(method)
			.uri(uri)
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.APPLICATION_JSON)

		return if (authHeader != null)
			spec.header("Authorization", authHeader)
		else
			spec
	}
}
