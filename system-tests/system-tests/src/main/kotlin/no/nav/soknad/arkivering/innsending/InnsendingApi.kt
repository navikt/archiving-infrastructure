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
import java.io.File
import java.util.UUID
import kotlin.runCatching

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

	private val nologinFillager = if (authClient != null) NologinApi(config.innsendingApiUrl, authClient) else NologinApi(config.innsendingApiUrl)
	private val nologinSoknad = if (authClient != null) NologinSoknadApi(config.innsendingApiUrl, authClient) else NologinSoknadApi(config.innsendingApiUrl)
	private val nologinApplicationApi = if (authClient != null) NologinApplicationApi(config.innsendingApiUrl, authClient) else NologinApplicationApi(config.innsendingApiUrl)


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

	fun lagreOgSendInnNoLoginSoknad(nologinSoknadDto: SkjemaDtoV2) = runCatching {
		logger.info("Lagrer og sender inn ikke innlogget søknad: ${nologinSoknadDto.innsendingsId}")
		nologinSoknad.opprettNologinSoknad(nologinSoknadDto)
		logger.info("Lagret og sendt inn ikke innlogget søknad: ${nologinSoknadDto.innsendingsId}")
	}

	fun sendInNoLoginApplication(innsendingsID: UUID, submitApplicationRequest: SubmitApplicationRequest) = runCatching {
		logger.info("Submits not logged in Application ${innsendingsID}")
		nologinApplicationApi.submitNologinApplication(innsendingsID, submitApplicationRequest)
		logger.info("Submitted and sent in not logged in Application ${innsendingsID}")
	}

	fun lastOppNoLoginFil(innsendingId: String, vedleggsId: String, fil: File) = runCatching {
		logger.info("Lagrer og sender inn ikke innlogget søknad: ${innsendingId}")
		nologinFillager.lastOppFil(vedleggId = vedleggsId,  filinnhold = fil, innsendingId = UUID.fromString(innsendingId))
	}

	fun slettNoLoginFil(innsendingId: String,  filId: String) = runCatching {
		logger.info("Sletter opplastet fil til ikke innlogget søknad: ${innsendingId}")
		nologinFillager.slettFilV2(filId = UUID.fromString(filId), innsendingId = UUID.fromString(innsendingId))
	}

}

typealias Vedleggsnummer = String
typealias Vedleggstittel = String
typealias Vedlegg = Pair<Vedleggsnummer, Vedleggstittel>
