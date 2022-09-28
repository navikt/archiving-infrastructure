package no.nav.soknad.arkivering.innsending

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import no.nav.soknad.arkivering.Config
import no.nav.soknad.arkivering.OAuth2Config
import no.nav.soknad.arkivering.soknadsmottaker.api.SoknadApi
import no.nav.soknad.arkivering.soknadsmottaker.infrastructure.Serializer
import no.nav.soknad.arkivering.soknadsmottaker.model.Soknad
import no.nav.soknad.arkivering.tokensupport.createOkHttpAuthorizationClient

class SoknadsmottakerApi(private val soknadApi: SoknadApi) {
	fun sendDataToSoknadsmottaker(soknad: Soknad) {
		soknadApi.receive(soknad)
	}
}

fun soknadApiWithOAuth2(config: Config): SoknadApi {
	Serializer.jacksonObjectMapper.registerModule(JavaTimeModule())
	val scopesProvider = { oauth2Conf: OAuth2Config -> listOf(oauth2Conf.scopeSoknadsmottaker) }
	return SoknadApi(config.soknadsmottakerUrl, createOkHttpAuthorizationClient(scopesProvider))
}

fun soknadApiWithoutOAuth2(config: Config): SoknadApi {
	Serializer.jacksonObjectMapper.registerModule(JavaTimeModule())
	return SoknadApi(config.soknadsmottakerUrl)
}
