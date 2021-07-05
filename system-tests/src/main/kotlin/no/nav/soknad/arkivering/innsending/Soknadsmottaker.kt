package no.nav.soknad.arkivering.innsending

import no.nav.soknad.arkivering.Configuration
import no.nav.soknad.arkivering.dto.SoknadInnsendtDto

fun sendDataToMottaker(dto: SoknadInnsendtDto, async: Boolean, appConfiguration: Configuration) {
	val url = appConfiguration.config.soknadsmottakerUrl + "/save"
	val headers = appConfiguration.config.soknadsmottakerUsername to appConfiguration.config.soknadsmottakerPassword
	performPostCall(dto, url, headers, async)
}
