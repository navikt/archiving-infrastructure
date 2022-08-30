package no.nav.soknad.arkivering.tokensupport


import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import no.nav.security.token.support.client.core.OAuth2ClientException
import no.nav.security.token.support.client.core.http.OAuth2HttpClient
import no.nav.security.token.support.client.core.http.OAuth2HttpRequest
import no.nav.security.token.support.client.core.oauth2.OAuth2AccessTokenResponse
import okhttp3.*
import org.slf4j.LoggerFactory
import java.net.URL


class DefaultOAuth2HttpClient(private val client: OkHttpClient) : OAuth2HttpClient {

	private val logger = LoggerFactory.getLogger(javaClass)

	override fun post(oAuth2HttpRequest: OAuth2HttpRequest): OAuth2AccessTokenResponse {

		val bodyBuilder = FormBody.Builder()

		oAuth2HttpRequest.formParameters.forEach{ (k, v) -> bodyBuilder.add(k,v)}

		val requestBuilder = Request.Builder().url( oAuth2HttpRequest.tokenEndpointUrl.toURL())

		oAuth2HttpRequest.oAuth2HttpHeaders.headers().forEach{ (k,v) -> requestBuilder.header(k, v.joinToString(separator = ","))}

    val request = requestBuilder.post(bodyBuilder.build()).build()

		val response = client.newCall(request).execute()
		val mapper = ObjectMapper()
		mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false)
		logger.info("Response body from Oauth2 exchange is " + response.body)
		return mapper.readValue(response.body?.bytes(),OAuth2AccessTokenResponse::class.java)

	}



}
