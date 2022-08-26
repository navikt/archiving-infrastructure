package no.nav.soknad.arkivering.innsending

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import no.nav.security.token.support.client.core.ClientProperties
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
		val okHttpClient  = OkHttpClient()
		val OAuth2AccessTokenService = OAuth2AccessTokenService(null,null, ClientCredentialsTokenClient(DefaultOAuth2HttpClient(okHttpClient)),null)
		val clientProperties = ClientProperties(URI.create(Oauth2Config().tokenEndpointUrl))
		val tokenService = TokenService(OAuth2AccessTokenService)
		soknadApi = SoknadApi(config.soknadsmottakerUrl)
	}

	fun sendDataToSoknadsmottaker(soknad: Soknad) {
		soknadApi.receive(soknad)
	}
}
