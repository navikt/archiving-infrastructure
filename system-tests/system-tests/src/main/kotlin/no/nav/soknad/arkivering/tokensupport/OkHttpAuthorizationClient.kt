package no.nav.soknad.arkivering.tokensupport

import no.nav.security.token.support.client.core.http.OAuth2HttpRequest
import no.nav.security.token.support.client.core.http.SimpleOAuth2HttpClient
import no.nav.soknad.arkivering.OAuth2Config
import okhttp3.OkHttpClient
import java.net.URI
import java.util.concurrent.TimeUnit

fun createOkHttpAuthorizationClient(scopesProvider: (OAuth2Config) -> List<String>): OkHttpClient {
	val oauth2Conf = OAuth2Config()

	val formParams = mapOf(
		"grant-type" to oauth2Conf.grantType,
		"client-auth-method" to oauth2Conf.clientAuthMethod,
		"client-id" to oauth2Conf.clientId,
		"client-secret" to oauth2Conf.clientSecret,
		"scope" to scopesProvider.invoke(oauth2Conf).joinToString(" ")
	)

	val tokenService = createSimpleOAuth2HttpClient()

	val okHttpClientTokenService = OkHttpClient().newBuilder()
		.connectTimeout(5, TimeUnit.MINUTES)
		.writeTimeout(5, TimeUnit.MINUTES)
		.readTimeout(5, TimeUnit.MINUTES)
		.callTimeout(5, TimeUnit.MINUTES)
		.addInterceptor {
			val token = tokenService.post(createOAuth2HttpRequest(URI.create(oauth2Conf.tokenEndpointUrl),formParams ))
			val bearerRequest = it.request().newBuilder().headers(it.request().headers)
				.header("Authorization", "Bearer ${token.access_token}").build()

			it.proceed(bearerRequest)
		}.build()

	return okHttpClientTokenService
}

fun createSimpleOAuth2HttpClient() = SimpleOAuth2HttpClient()

fun createOAuth2HttpRequest(tokenEndpointUrl: URI, formParameters: Map<String, String>) =
	OAuth2HttpRequest(tokenEndpointUrl = tokenEndpointUrl, formParameters = formParameters )
