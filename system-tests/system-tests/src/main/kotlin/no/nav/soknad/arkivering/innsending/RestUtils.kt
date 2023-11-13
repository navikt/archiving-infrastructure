package no.nav.soknad.arkivering.innsending

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException

private val restClient = OkHttpClient()


fun performGetCall(url: String): ByteArray? {
	val request = Request.Builder().url(url).get().build()
	restClient.newCall(request).execute().use {
		if (it.isSuccessful && url.contains("/internal/health")) {
			return "UP".toByteArray()
		}
		return it.body?.bytes()
	}
}

fun getStatusCodeForGetCall(url: String): Int {
	val request = Request.Builder().url(url).get().build()
	restClient.newCall(request).execute().use {
		return it.code
	}
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
