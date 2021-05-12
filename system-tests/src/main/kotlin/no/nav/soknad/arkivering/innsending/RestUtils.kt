package no.nav.soknad.arkivering.innsending

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.engine.apache.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import java.util.*

private val client = HttpClient(Apache) {
	expectSuccess = false
}
val objectMapper = ObjectMapper().also {
	it.findAndRegisterModules()
	it.registerModule(JavaTimeModule())
}


fun performGetCall(url: String, usernameAndPassword: Pair<String, String>): ByteArray? {
	return runBlocking {
		client.get(url) {
			headers {
				append("Authorization", createHeaders(usernameAndPassword))
			}
		}
	}
}

fun performGetCall(url: String): ByteArray = runBlocking {
	client.get<HttpStatement>(url).execute().readBytes()
}
fun getStatusCodeForGetCall(url: String): Int = runBlocking {
	client.get<HttpStatement>(url).execute().status.value
}

fun performPostCall(payload: Any, url: String, usernameAndPassword: Pair<String, String>, async: Boolean) {
	runBlocking {
		client.post<Any>(url) {
			contentType(ContentType.Application.Json)
			body = objectMapper.writeValueAsString(payload)
			headers {
				append("Authorization", createHeaders(usernameAndPassword))
			}
		}
	}
}

fun performPutCall(url: String) {
	runBlocking {
		client.put<Any>(url)
	}
}

fun performDeleteCall(url: String) {
	runBlocking {
		client.delete<Any>(url)
	}
}


private fun createHeaders(usernameAndPassword: Pair<String, String>): String {
	val auth = "${usernameAndPassword.first}:${usernameAndPassword.second}"
	return "Basic " + Base64.getEncoder().encodeToString(auth.toByteArray())
}
