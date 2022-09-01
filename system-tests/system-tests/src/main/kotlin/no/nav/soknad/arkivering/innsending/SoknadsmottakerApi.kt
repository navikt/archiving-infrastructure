package no.nav.soknad.arkivering.innsending

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import no.nav.soknad.arkivering.Config
import no.nav.soknad.arkivering.OAuth2Config
import no.nav.soknad.arkivering.soknadsmottaker.api.SoknadApi
import no.nav.soknad.arkivering.soknadsmottaker.infrastructure.ApiClient
import no.nav.soknad.arkivering.soknadsmottaker.infrastructure.Serializer
import no.nav.soknad.arkivering.soknadsmottaker.model.Soknad
import no.nav.soknad.arkivering.tokensupport.createOkHttpAuthorizationClient

class SoknadsmottakerApi(config: Config) {
	private val soknadApi: SoknadApi

	init {
		Serializer.jacksonObjectMapper.registerModule(JavaTimeModule())
		ApiClient.username = config.soknadsmottakerUsername
		ApiClient.password = config.soknadsmottakerPassword

		val scopesProvider = { oauth2Conf: OAuth2Config -> listOf(oauth2Conf.scopeSoknadsmottaker) }

		soknadApi = SoknadApi(config.soknadsmottakerUrl, createOkHttpAuthorizationClient(scopesProvider))
	}

	fun sendDataToSoknadsmottaker(soknad: Soknad) {
		soknadApi.receive(soknad)
	}
}
