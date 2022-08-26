package no.nav.soknad.arkivering.innsending

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.nimbusds.oauth2.sdk.auth.ClientAuthenticationMethod
import no.nav.security.token.support.client.core.ClientAuthenticationProperties
import no.nav.security.token.support.client.core.ClientProperties
import no.nav.security.token.support.client.core.OAuth2GrantType
import no.nav.security.token.support.client.core.oauth2.ClientCredentialsTokenClient
import no.nav.security.token.support.client.core.oauth2.OAuth2AccessTokenService
import no.nav.soknad.arkivering.Config
import no.nav.soknad.arkivering.Oauth2Config
import no.nav.soknad.arkivering.soknadsmottaker.api.SoknadApi
import no.nav.soknad.arkivering.soknadsmottaker.infrastructure.ApiClient
import no.nav.soknad.arkivering.soknadsmottaker.infrastructure.Serializer
import no.nav.soknad.arkivering.soknadsmottaker.model.Soknad
import no.nav.soknad.arkivering.tokensupport.DefaultOAuth2HttpClient
import no.nav.soknad.arkivering.tokensupport.TokenService
import okhttp3.OkHttpClient
import java.net.URI

class SoknadsmottakerApi(config: Config) {
	private val soknadApi: SoknadApi

	init {
		Serializer.jacksonObjectMapper.registerModule(JavaTimeModule())
		ApiClient.username = config.soknadsmottakerUsername
		ApiClient.password = config.soknadsmottakerPassword
		val auth2Conf = Oauth2Config()
		val okHttpClientOauth2  = OkHttpClient()
		val OAuth2AccessTokenService = OAuth2AccessTokenService(null,null, ClientCredentialsTokenClient(DefaultOAuth2HttpClient(okHttpClientOauth2)),null)
		val clientProperties = ClientProperties(URI.create(auth2Conf.tokenEndpointUrl),
																						null,OAuth2GrantType(auth2Conf.grantType) ,
																											 listOf(auth2Conf.scopeSoknadsmottaker),
																								       ClientAuthenticationProperties(auth2Conf.clientId,ClientAuthenticationMethod(auth2Conf.clientAuthMethod),auth2Conf.clientSecret,null),null,null)
		val tokenService = TokenService(clientProperties,OAuth2AccessTokenService)

		val okHttpClientTokenService  = OkHttpClient().newBuilder().addInterceptor {
				val token =	tokenService.getToken()
			  val bearerRequest = it.request().newBuilder().headers(it.request().headers).header("Bearer",token).build()

			it.proceed(bearerRequest)
		}.build()


		soknadApi = SoknadApi(config.soknadsmottakerUrl,okHttpClientTokenService)
	}

	fun sendDataToSoknadsmottaker(soknad: Soknad) {
		soknadApi.receive(soknad)
	}
}
