package no.nav.soknad.arkivering.innsending

import no.nav.soknad.arkivering.Configuration
import no.nav.soknad.arkivering.dto.SoknadInnsendtDto

class Soknadsmottaker(private val restUtils: RestUtils) {

	fun sendDataToMottaker(dto: SoknadInnsendtDto, async: Boolean, appConfiguration: Configuration) {
		val url = appConfiguration.config.soknadsmottakerUrl + "/save"
		val headers = restUtils.createHeaders(appConfiguration.config.soknadsmottakerUsername, appConfiguration.config.soknadsmottakerPassword)
		restUtils.performPostCall(dto, url, headers, async)
	}
}
