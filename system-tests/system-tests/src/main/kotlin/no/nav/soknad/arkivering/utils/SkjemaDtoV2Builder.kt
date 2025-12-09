package no.nav.soknad.innsending.utils.builders

import no.nav.soknad.arkivering.DEFAULT_LEVETID_OPPRETTET_SOKNAD
import no.nav.soknad.arkivering.innsending.model.AvsenderDto
import no.nav.soknad.arkivering.innsending.model.BrukerDto
import no.nav.soknad.arkivering.innsending.model.SkjemaDokumentDtoV2
import no.nav.soknad.arkivering.innsending.model.SkjemaDtoV2
import no.nav.soknad.arkivering.innsending.model.SoknadsStatusDto
import no.nav.soknad.arkivering.innsending.model.VisningsType
import no.nav.soknad.arkivering.utils.Skjema.createSkjemaPathFromSkjemanr
import no.nav.soknad.arkivering.utils.Skjema.generateSkjemanr
import no.nav.soknad.arkivering.utils.SkjemaDokumentDtoV2TestBuilder
import java.time.OffsetDateTime
import java.util.UUID

class SkjemaDtoV2TestBuilder (

	var brukerId: String = "12128012345",
	var skjemanr: String = generateSkjemanr(),
	var tittel: String = "Forsikring mot ansvar for sykepenger i arbeidsgiverperioden for små bedrifter.",
	var tema: String = "FOS",
	var spraak: String = "nb_NO",
	var hoveddokument: SkjemaDokumentDtoV2 = SkjemaDokumentDtoV2TestBuilder().asHovedDokument(skjemanr).build(),
	var hoveddokumentVariant: SkjemaDokumentDtoV2 = SkjemaDokumentDtoV2TestBuilder().asHovedDokumentVariant(skjemanr).build(),
	var innsendingsId: String? = UUID.randomUUID().toString(),
	var status: SoknadsStatusDto? = SoknadsStatusDto.opprettet,
	var vedleggsListe: List<SkjemaDokumentDtoV2>? = emptyList(),
	var kanLasteOppAnnet: Boolean? = false,
	var fristForEttersendelse: Long? = 14L,
	var skjemaPath: String = createSkjemaPathFromSkjemanr(skjemanr),
	var skalslettesdato: OffsetDateTime? = OffsetDateTime.now().plusDays(DEFAULT_LEVETID_OPPRETTET_SOKNAD),
	var mellomlagringDager: Int? = DEFAULT_LEVETID_OPPRETTET_SOKNAD.toInt(),
	var visningsType: VisningsType? = VisningsType.dokumentinnsending,
) {


	fun medBrukerId(brukerId: String) = apply { this.brukerId = brukerId }
	fun medVedlegg(vedlegg: SkjemaDokumentDtoV2) = apply { vedleggsListe = (vedleggsListe ?: emptyList()) + listOf(vedlegg) }
	fun medVedlegg(vedlegg: List<SkjemaDokumentDtoV2>) = apply { vedleggsListe = vedlegg }
	fun medStatus(status: SoknadsStatusDto) = apply { this.status = status }
	fun medInnsendingsId(innsendingsId: String) = apply { this.innsendingsId = innsendingsId }
	fun medFristForEttersendelse(fristForEttersendelse: Long) = apply { this.fristForEttersendelse = fristForEttersendelse }
	fun medSkjemaPath(skjemaPath: String) = apply { this.skjemaPath = skjemaPath }
	fun medSkalSlettesDato(skalslettesdato: OffsetDateTime) = apply { this.skalslettesdato = skalslettesdato }
	fun medMellomlagringDager(mellomlagringDager: Int?) = apply { this.mellomlagringDager = mellomlagringDager }

	fun build() = SkjemaDtoV2(
		brukerDto = BrukerDto(id = brukerId, idType = BrukerDto.IdType.fNR),
		avsenderId = AvsenderDto(id = brukerId, idType = AvsenderDto.IdType.fNR),
		skjemanr = skjemanr,
		tittel = tittel,
		tema = tema,
		spraak = spraak,
		hoveddokument = hoveddokument,
		hoveddokumentVariant = hoveddokumentVariant,
		innsendingsId = innsendingsId,
		status = status,
		vedleggsListe = vedleggsListe,
		kanLasteOppAnnet = kanLasteOppAnnet,
		fristForEttersendelse = fristForEttersendelse,
		skjemaPath = skjemaPath,
		skalSlettesDato = skalslettesdato,
		mellomlagringDager = mellomlagringDager,
		visningsType = visningsType
	)

}

