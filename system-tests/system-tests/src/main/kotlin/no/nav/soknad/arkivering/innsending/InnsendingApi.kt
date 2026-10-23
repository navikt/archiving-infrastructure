package no.nav.soknad.arkivering.innsending

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import no.nav.soknad.arkivering.Config
import no.nav.soknad.arkivering.innsending.api.*
import no.nav.soknad.arkivering.innsending.infrastructure.ApiClient
import no.nav.soknad.arkivering.innsending.model.*
import no.nav.soknad.arkivering.innsending.infrastructure.Serializer.jacksonObjectMapper
import no.nav.soknad.arkivering.tokensupport.E2eTokens
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID
import kotlin.runCatching

/**
 * Klient mot innsending-api. Med [tokens] sender den samme tokentype som produksjonskonsumentene:
 * TokenX for innbyggerkall og Azure M2M med nologin-access for kall uten innlogging.
 */
class InnsendingApi(config: Config, tokens: E2eTokens = E2eTokens.none) {
	private val logger = LoggerFactory.getLogger(javaClass)

	private val citizenClient = tokens.clientWithBearer(ApiClient.defaultClient, tokens::citizen)
	private val nologinClient = tokens.clientWithBearer(ApiClient.defaultClient, tokens::nologin)

	private val ettersending = EttersendingApi(config.innsendingApiUrl, citizenClient)
	private val sendInnSoknad = SendinnSoknadApi(config.innsendingApiUrl, citizenClient)
	private val sendInnFil = SendinnFilApi(config.innsendingApiUrl, citizenClient)
	private val endtoend = EndtoendApi(config.innsendingApiUrl, citizenClient)

	private val nologinApplicationApi = NologinApplicationApi(config.innsendingApiUrl, nologinClient)


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
		try {
			sendInnSoknad.sendInnSoknad(soknad.innsendingsId)
			logger.info("Sendt inn søknad: ${soknad.innsendingsId}")
		} catch (e: Exception) {
			logger.error("Feil ved innsending av søknad: ${soknad.innsendingsId}", e)
			throw e
		}
	}

	fun sendInn(innsendingsId: String) = runCatching {
		logger.info("Sender inn søknad: ${innsendingsId}")
		try {
			sendInnSoknad.sendInnSoknad(innsendingsId)
			logger.info("Sendt inn søknad: ${innsendingsId}")
		} catch (e: Exception) {
			logger.error("Feil ved innsending av søknad: ${innsendingsId}", e)
			throw e
		}
	}

	fun getArkiveringsstatus(innsendingsId: String): ArkiveringsStatusDto {
		return endtoend.getArkiveringsstatus(innsendingsId)
	}

	fun sendInNoLoginApplication(innsendingsID: UUID, submitApplicationRequest: SubmitApplicationRequest) = runCatching {
		logger.info("Submits not logged in Application ${innsendingsID}")
		val response = nologinApplicationApi.submitNologinApplication(innsendingsID, submitApplicationRequest)
		logger.info("Submitted and sent in not logged in Application ${innsendingsID}")
		response
	}

	fun lastOppNoLoginFil(innsendingId: String, vedleggsId: String, fil: File) = runCatching {
		logger.info("Lagrer og sender inn ikke innlogget søknad: ${innsendingId}")
		nologinApplicationApi.uploadNologinAttachmentFile(
			innsendingsId = UUID.fromString(innsendingId),
			attachmentId = vedleggsId,
			file = fil,
		)
	}

	fun slettNoLoginFil(innsendingId: String, vedleggsId: String, filId: String) = runCatching {
		logger.info("Sletter opplastet fil til ikke innlogget søknad: ${innsendingId}")
		nologinApplicationApi.deleteNologinAttachmentFile(
			innsendingsId = UUID.fromString(innsendingId),
			attachmentId = vedleggsId,
			fileId = UUID.fromString(filId),
		)
	}

}

typealias Vedleggsnummer = String
typealias Vedleggstittel = String
typealias Vedlegg = Pair<Vedleggsnummer, Vedleggstittel>
