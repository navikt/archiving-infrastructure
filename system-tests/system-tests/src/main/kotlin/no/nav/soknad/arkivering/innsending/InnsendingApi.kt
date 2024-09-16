package no.nav.soknad.arkivering.innsending

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import no.nav.soknad.arkivering.Config
import no.nav.soknad.arkivering.OAuth2Config
import no.nav.soknad.arkivering.innsending.api.*
import no.nav.soknad.arkivering.innsending.model.*
import no.nav.soknad.arkivering.innsending.infrastructure.Serializer.jacksonObjectMapper
import no.nav.soknad.arkivering.tokensupport.createOkHttpAuthorizationClient
import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory

fun authorizationClient(): OkHttpClient {
	val scopeProvider = { oauth2Conf: OAuth2Config -> listOf(oauth2Conf.scopeInnsendingApi) }
	return createOkHttpAuthorizationClient(scopeProvider)
}

class InnsendingApi(config: Config, useOauth: Boolean? = false) {
	private val logger = LoggerFactory.getLogger(javaClass)

	private val authClient = if (useOauth == true) {
		authorizationClient()
	} else null

	private val ettersending = if (authClient != null) EttersendingApi(config.innsendingApiUrl, authClient) else EttersendingApi(config.innsendingApiUrl)
	private val sendInnSoknad = if (authClient != null) SendinnSoknadApi(config.innsendingApiUrl, authClient) else SendinnSoknadApi(config.innsendingApiUrl)
	private val sendInnFil = if (authClient != null) SendinnFilApi(config.innsendingApiUrl, authClient) else SendinnFilApi(config.innsendingApiUrl)
	private val endtoend = if (authClient != null) EndtoendApi(config.innsendingApiUrl, authClient) else EndtoendApi(config.innsendingApiUrl)

	init {
		jacksonObjectMapper.registerModule(JavaTimeModule())
	}

	fun opprettEttersending(
		skjemanr: String = "NAV 08-07.04D",
		sprak: String = "nb",
		tema: String = "SYK",
		tittel: String = "Endtoend ettersending",
		vedleggListe: List<Vedlegg> = listOf(Vedlegg("N5", "Endtoend vedlegg"))
	): SoknadTestdata {
		val dto = OpprettEttersending(
			skjemanr = skjemanr,
			sprak = sprak,
			tema = tema,
			tittel = tittel,
			vedleggsListe = vedleggListe.map {
				InnsendtVedleggDto(
					vedleggsnr = it.first,
					tittel = it.second,
				)
			}
		)
		val soknad = ettersending.opprettEttersending(dto)
		logger.info("Opprettet ettersending: $soknad")
		return SoknadTestdata(soknad, sendInnFil)
	}

	fun sendInn(soknad: SoknadTestdata) = runCatching {
		logger.info("Sender inn søknad: ${soknad.innsendingsId}")
		sendInnSoknad.sendInnSoknad(soknad.innsendingsId)
	}

	fun sendInn(innsendingsId: String) {
		logger.info("Sender inn søknad: ${innsendingsId}")
		sendInnSoknad.sendInnSoknad(innsendingsId)
	}

	fun getArkiveringsstatus(innsendingsId: String): ArkiveringsStatusDto {
		return endtoend.getArkiveringsstatus(innsendingsId)
	}

}

typealias Vedleggsnummer = String
typealias Vedleggstittel = String
typealias Vedlegg = Pair<Vedleggsnummer, Vedleggstittel>
