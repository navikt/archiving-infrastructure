package no.nav.soknad.arkivering.innsending

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink
import java.io.IOException
import java.util.*


private val restClient = OkHttpClient()
val objectMapper = ObjectMapper().also {
	it.findAndRegisterModules()
	it.registerModule(JavaTimeModule())
}


fun performGetCall(url: String, headers: Headers): ByteArray? {

	val request = Request.Builder().url(url).headers(headers).get().build()

	restClient.newCall(request).execute().use {
		return if (it.isSuccessful) {
			it.body?.bytes()
		} else {
			println(it.networkResponse)
			null
		}
	}
}

fun performPostCall(payload: Any, url: String, headers: Headers, async: Boolean) {
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

fun performPutCall(url: String, headers: Headers? = null) {
	val requestBody = object : RequestBody() {
		override fun contentType() = "application/json".toMediaType()
		override fun writeTo(sink: BufferedSink) {}
	}
	val h = headers ?: Headers.Builder().build()

	val request = Request.Builder().url(url).headers(h).put(requestBody).build()

	restClient.newCall(request).execute().use { response ->
		if (!response.isSuccessful)
			throw IOException("Unexpected code $response")
	}
}

fun performDeleteCall(url: String, headers: Headers? = null) {
	val requestBody = object : RequestBody() {
		override fun contentType() = "application/json".toMediaType()
		override fun writeTo(sink: BufferedSink) {}
	}
	val h = headers ?: Headers.Builder().build()

	val request = Request.Builder().url(url).headers(h).delete(requestBody).build()
	restClient.newCall(request).execute().close()
}

private val restRequestCallback = object : Callback {
	override fun onResponse(call: Call, response: Response) { }

	override fun onFailure(call: Call, e: IOException) {
		throw e
	}
}

fun createHeaders(username: String, password: String): Headers {
	val auth = "$username:$password"
	val authHeader = "Basic " + Base64.getEncoder().encodeToString(auth.toByteArray())
	return Headers.headersOf("Authorization", authHeader)
}
