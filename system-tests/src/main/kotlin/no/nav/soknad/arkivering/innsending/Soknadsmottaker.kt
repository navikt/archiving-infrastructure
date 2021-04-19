package no.nav.soknad.arkivering.innsending

import no.nav.soknad.arkivering.Configuration
import no.nav.soknad.arkivering.dto.SoknadInnsendtDto

fun sendDataToMottaker(dto: SoknadInnsendtDto, async: Boolean, appConfiguration: Configuration) {
	val url = appConfiguration.config.soknadsmottakerUrl + "/save"
	println("APABEPA Soknadsmottaker")
	val headers = createHeaders(appConfiguration.config.soknadsmottakerUsername, appConfiguration.config.soknadsmottakerPassword)
	performPostCall(dto, url, headers, async)
}
