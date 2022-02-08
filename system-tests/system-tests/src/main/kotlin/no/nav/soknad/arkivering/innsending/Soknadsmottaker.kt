package no.nav.soknad.arkivering.innsending

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import no.nav.soknad.arkivering.Configuration
import no.nav.soknad.arkivering.soknadsfillager.infrastructure.ApiClient
import no.nav.soknad.arkivering.soknadsfillager.infrastructure.Serializer
import no.nav.soknad.arkivering.soknadsmottaker.api.SoknadApi
import no.nav.soknad.arkivering.soknadsmottaker.model.Soknad

class SoknadsmottakerApi(appConfiguration: Configuration) {
	private val soknadApi: SoknadApi

	init {
		Serializer.jacksonObjectMapper.registerModule(JavaTimeModule())
		ApiClient.username = appConfiguration.config.soknadsmottakerUsername
		ApiClient.password = appConfiguration.config.soknadsmottakerPassword
		soknadApi = SoknadApi(appConfiguration.config.soknadsmottakerUrl)
	}

	fun sendDataToSoknadsmottaker(soknad: Soknad) {
		soknadApi.receive(soknad)
	}
}
