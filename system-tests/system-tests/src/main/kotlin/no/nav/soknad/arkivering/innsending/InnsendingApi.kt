package no.nav.soknad.arkivering.innsending

import no.nav.soknad.arkivering.Config
import no.nav.soknad.arkivering.innsending.api.*
import no.nav.soknad.arkivering.innsending.model.*
import org.slf4j.LoggerFactory

class InnsendingApi(config: Config) {
	private val logger = LoggerFactory.getLogger(javaClass)

	private val ettersending = EttersendingApi(config.innsendingApiUrl)
	private val sendInnSoknad = SendinnSoknadApi(config.innsendingApiUrl)
	private val sendInnFil = SendinnFilApi(config.innsendingApiUrl)
	private val endtoend = EndtoendApi(config.innsendingApiUrl)

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
		val innsendingsId = soknad.innsendingsId!!
		return SoknadTestdata(innsendingsId, sendInnSoknad, sendInnFil)
	}

	fun sendInn(soknad: SoknadTestdata) = runCatching {
		logger.info("Sender inn søknad: ${soknad.innsendingsId}")
		sendInnSoknad.sendInnSoknad(soknad.innsendingsId)
	}

	fun getArkiveringsstatus(innsendingsId: String): ArkiveringsStatusDto {
		return endtoend.getArkiveringsstatus(innsendingsId)
	}

}

typealias Vedleggsnummer = String
typealias Vedleggstittel = String
typealias Vedlegg = Pair<Vedleggsnummer, Vedleggstittel>
