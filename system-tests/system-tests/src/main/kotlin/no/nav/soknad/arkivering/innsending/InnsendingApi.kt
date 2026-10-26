package no.nav.soknad.arkivering.innsending

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import no.nav.soknad.arkivering.Config
import no.nav.soknad.arkivering.innsending.api.*
import no.nav.soknad.arkivering.innsending.model.*
import no.nav.soknad.arkivering.innsending.infrastructure.Serializer.jacksonObjectMapper
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID
import kotlin.runCatching

class InnsendingApi(config: Config) {
	private val logger = LoggerFactory.getLogger(javaClass)

	private val ettersending = EttersendingApi(config.innsendingApiUrl)
	private val sendInnSoknad = SendinnSoknadApi(config.innsendingApiUrl)
	private val sendInnFil = SendinnFilApi(config.innsendingApiUrl)
	private val fyllut = FyllutApi(config.innsendingApiUrl)
	private val endtoend = EndtoendApi(config.innsendingApiUrl)
	private val nologinApplicationApi = NologinApplicationApi(config.innsendingApiUrl)

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

	fun getArkiveringsstatus(innsendingsId: String): ArkiveringsStatusDto {
		return endtoend.getArkiveringsstatus(innsendingsId)
	}

	fun sendInNoLoginApplication(innsendingsID: UUID, submitApplicationRequest: SubmitApplicationRequest) = runCatching {
		logger.info("Submits not logged in Application ${innsendingsID}")
		val response = nologinApplicationApi.submitNologinApplication(innsendingsID, submitApplicationRequest)
		logger.info("Submitted and sent in not logged in Application ${innsendingsID}")
		response
	}

	fun createDigitalApplication(skjemaDto: SkjemaDto) = runCatching {
		val response = fyllut.fyllUtOpprettSoknad(skjemaDto, force = true)
		jacksonObjectMapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(response)
			.path("innsendingsId")
			.takeIf { it.isTextual }
			?.asText()
			?.takeIf { it.isNotBlank() }
			?: error("Created application did not return an innsendingsId")
	}

	fun uploadDigitalAttachmentFile(innsendingsId: UUID, attachmentId: String, file: File) = runCatching {
		sendInnFil.uploadAttachmentFile(innsendingsId, attachmentId, file)
	}

	fun submitDigitalApplication(innsendingsId: UUID, request: SubmitApplicationRequest) = runCatching {
		fyllut.submitDigitalApplication(innsendingsId, request)
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
