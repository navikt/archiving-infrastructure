package no.nav.soknad.arkivering.utils

import no.nav.soknad.arkivering.innsending.model.AttachmentDto
import no.nav.soknad.arkivering.innsending.model.AvsenderDto
import no.nav.soknad.arkivering.innsending.model.SoknadsStatusDto
import no.nav.soknad.arkivering.innsending.model.SubmitApplicationRequest
import no.nav.soknad.arkivering.utils.Skjema.createSkjemaPathFromSkjemanr
import no.nav.soknad.arkivering.utils.Skjema.generateSkjemanr
import java.util.UUID

class SubmitApplicationRequestBuilder(

	var brukerId: String = "12128012345",
	var skjemanr: String = generateSkjemanr(),
	var tittel: String = "Forsikring mot ansvar for sykepenger i arbeidsgiverperioden for små bedrifter.",
	var tema: String = "FOS",
	var spraak: String = "nb_NO",
	var hoveddokument: ByteArray = "Hoveddokumentet".toByteArray(),
	var hoveddokumentVariant: ByteArray = "{\"Hoveddokumentet\": \"variant\"}".toByteArray(),
	var status: SoknadsStatusDto? = SoknadsStatusDto.opprettet,
	var vedleggsListe: List<AttachmentDto>? = emptyList(),
	var kanLasteOppAnnet: Boolean? = false,
	var skjemaPath: String = createSkjemaPathFromSkjemanr(skjemanr),
	var fileIds: List<UUID>? = emptyList()

	) {

	fun medBrukerId(brukerId: String) = apply { this.brukerId = brukerId }
	fun medVedlegg(vedlegg: AttachmentDto) = apply { vedleggsListe = (vedleggsListe ?: emptyList()) + listOf(vedlegg) }
	fun medVedlegg(vedlegg: List<AttachmentDto>) = apply { vedleggsListe = vedlegg }
	fun medStatus(status: SoknadsStatusDto) = apply { this.status = status }
	fun medSkjemaPath(skjemaPath: String) = apply { this.skjemaPath = skjemaPath }
	fun medFileIds(fileIds: List<UUID>) = apply { this.fileIds = fileIds }
	fun medHoveddokument(hoveddokument: ByteArray) = apply { this.hoveddokument = hoveddokument }
	fun medHoveddokumentVariant(hoveddokumentVariant: ByteArray) = apply { this.hoveddokumentVariant = hoveddokumentVariant }
	fun medKanLasteOppAnnet(kanLasteOppAnnet: Boolean) = apply { this.kanLasteOppAnnet = kanLasteOppAnnet }
	fun medSkjemanr(skjemanr: String) = apply { this.skjemanr = skjemanr }
	fun medTittel(tittel: String) = apply { this.tittel = tittel }
	fun medTema(tema: String) = apply { this.tema = tema }

	fun build() = SubmitApplicationRequest(
		formNumber = skjemanr,
		bruker = brukerId,
		avsender = AvsenderDto(id = brukerId, idType = AvsenderDto.IdType.fNR),
		title = tittel,
		tema = tema,
		language = spraak,
		mainDocument = hoveddokument,
		mainDocumentAlt = hoveddokumentVariant,
		attachments = vedleggsListe,
		otherUploadAvailable = false
	)

}
