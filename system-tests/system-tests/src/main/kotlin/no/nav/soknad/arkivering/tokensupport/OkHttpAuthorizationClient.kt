package no.nav.soknad.arkivering.tokensupport

import com.nimbusds.oauth2.sdk.auth.ClientAuthenticationMethod
import no.nav.security.token.support.client.core.ClientAuthenticationProperties
import no.nav.security.token.support.client.core.ClientProperties
import no.nav.security.token.support.client.core.OAuth2GrantType
import no.nav.security.token.support.client.core.oauth2.ClientCredentialsTokenClient
import no.nav.security.token.support.client.core.oauth2.OAuth2AccessTokenService
import no.nav.soknad.arkivering.OAuth2Config
import okhttp3.OkHttpClient
import java.net.URI
import java.util.concurrent.TimeUnit

fun createOkHttpAuthorizationClient(scopesProvider: (OAuth2Config) -> List<String>): OkHttpClient {
	val oauth2Conf = OAuth2Config()
	val oauth2AccessTokenService = OAuth2AccessTokenService(
		null,
		null,
		ClientCredentialsTokenClient(DefaultOAuth2HttpClient(OkHttpClient())),
		null
	)
	val clientProperties = ClientProperties(
		URI.create(oauth2Conf.tokenEndpointUrl),
		null, OAuth2GrantType(oauth2Conf.grantType),
		scopesProvider.invoke(oauth2Conf),
		ClientAuthenticationProperties(
			oauth2Conf.clientId,
			ClientAuthenticationMethod(oauth2Conf.clientAuthMethod),
			oauth2Conf.clientSecret,
			null
		), null, null
	)
	val tokenService = TokenService(clientProperties, oauth2AccessTokenService)

	val okHttpClientTokenService = OkHttpClient().newBuilder()
		.connectTimeout(5, TimeUnit.MINUTES)
		.writeTimeout(5, TimeUnit.MINUTES)
		.readTimeout(5, TimeUnit.MINUTES)
		.callTimeout(5, TimeUnit.MINUTES)
		.addInterceptor {
			val token = tokenService.getToken()
			val bearerRequest = it.request().newBuilder().headers(it.request().headers)
				.header("Authorization", "Bearer ${token.accessToken}").build()

			it.proceed(bearerRequest)
		}.build()

	return okHttpClientTokenService
}
