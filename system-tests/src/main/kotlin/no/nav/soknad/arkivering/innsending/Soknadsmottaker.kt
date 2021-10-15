package no.nav.soknad.arkivering.innsending

import no.nav.soknad.arkivering.Configuration
import no.nav.soknad.arkivering.dto.SoknadInnsendtDto

fun sendDataToSoknadsmottaker(key: String, dto: SoknadInnsendtDto, async: Boolean, appConfiguration: Configuration) {
	val url = appConfiguration.config.soknadsmottakerUrl + "/save"
	val headers = listOf(
		appConfiguration.config.soknadsmottakerUsername to appConfiguration.config.soknadsmottakerPassword,
		"innsendingKey" to key
	)

	performPostCall(dto, url, headers, async)
}
