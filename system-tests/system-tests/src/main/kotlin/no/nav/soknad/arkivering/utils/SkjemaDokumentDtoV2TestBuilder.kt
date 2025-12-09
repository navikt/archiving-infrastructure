package no.nav.soknad.arkivering.utils

import no.nav.soknad.arkivering.innsending.model.Mimetype
import no.nav.soknad.arkivering.innsending.model.OpplastingsStatusDto
import no.nav.soknad.arkivering.innsending.model.SkjemaDokumentDtoV2
import java.util.UUID
import no.nav.soknad.arkivering.utils.Skjema.generateVedleggsnr


class SkjemaDokumentDtoV2TestBuilder(
	var vedleggsnr: String = generateVedleggsnr(),
	var tittel: String = "Forsikring mot ansvar for sykepenger i arbeidsgiverperioden for små bedrifter.",
	var label: String = "Inntektsopplysninger for selvstendig næringsdrivende og frilansere som skal ha foreldrepenger eller svangerskapspenger.",
	var pakrevd: Boolean = true,
	var beskrivelse: String = "Dette er opplysninger som er nødvendig for beregning av utbetaling av foreldrepenger eller svangerskapspenger.",
	var mimetype: Mimetype? = null,
	var document: ByteArray? = null,
	var propertyNavn: String? = null,
	var formioId: String? = UUID.randomUUID().toString()
) {

	var filIdListe: List<String>? = null

	// Hoveddokument uses skjemanr as vedleggsnr
	fun asHovedDokument(skjemanr: String, withFile: Boolean = true): SkjemaDokumentDtoV2TestBuilder {
		if (withFile) {
			document = loadFile("/mellomstor.pdf").readBytes()
			mimetype = Mimetype.applicationSlashPdf
		}
		formioId = null
		vedleggsnr = skjemanr
		return this
	}

	// Hoveddokument uses skjemanr as vedleggsnr
	fun asHovedDokumentVariant(skjemanr: String, withFile: Boolean = true): SkjemaDokumentDtoV2TestBuilder {
		if (withFile) {
			document = loadFile("/innsending.json").readBytes()
			mimetype = Mimetype.applicationSlashJson
		}
		formioId = null
		vedleggsnr = skjemanr
		return this
	}

	fun asVedlegg(skjemanr: String, formioId: String,  document: ByteArray?, mimeType: Mimetype? = Mimetype.applicationSlashPdf ): SkjemaDokumentDtoV2TestBuilder {
		this.document = document
		this.vedleggsnr = skjemanr
		this.tittel = "Vedlegg til $skjemanr"
		this.label = "Vedlegg til $skjemanr"
		this.mimetype = Mimetype.applicationSlashPdf
		this.formioId = formioId
		return this
	}

	fun withFilIdListe(filIdListe: List<String>) = apply {this.filIdListe = filIdListe}

	fun build(): SkjemaDokumentDtoV2 {
		return SkjemaDokumentDtoV2(
			vedleggsnr = vedleggsnr,
			tittel = tittel,
			label = label,
			pakrevd = pakrevd,
			beskrivelse = beskrivelse,
			mimetype = mimetype,
			document = document,
			propertyNavn = propertyNavn,
			fyllutId = formioId,
			opplastingsStatus = OpplastingsStatusDto.lastetOpp,
			opplastingsValgKommentarLedetekst = "Ledetekst for opplastingsvalg",
			opplastingsValgKommentar = "Kommentar for opplastingsvalg",
			filIdListe = filIdListe
		)
	}
}
