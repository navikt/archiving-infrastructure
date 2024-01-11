package no.nav.soknad.arkivering.utils

import no.nav.soknad.arkivering.avroschemas.Soknadstyper
import no.nav.soknad.arkivering.soknadsmottaker.model.Soknad
import no.nav.soknad.arkivering.soknadsmottaker.model.DocumentData
import no.nav.soknad.arkivering.soknadsmottaker.model.Varianter
import java.time.LocalDateTime
import java.time.ZoneOffset

class SoknadsBuilder() {
	private var behandlingsid: String = "behandlingsid"
	private var fodselsnummer: String = fnr
	private var arkivtema: String = "TSO"
	private var innsendtDato: Long = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)
	private var soknadstype: Soknadstyper = Soknadstyper.SOKNAD
	private var dokumenter: MutableList<DocumentData> = mutableListOf()

	fun withBehandlingsid(behandlingsid: String) = apply { this.behandlingsid = behandlingsid }
	fun withFodselsnummer(fodselsnummer: String) = apply { this.fodselsnummer = fodselsnummer }
	fun withArkivtema(arkivtema: String) = apply { this.arkivtema = arkivtema }
	fun withInnsendtDato(innsendtDato: LocalDateTime) =
		apply { this.innsendtDato = innsendtDato.toEpochSecond(ZoneOffset.UTC) }

	fun withSoknadstype(soknadstype: Soknadstyper) = apply { this.soknadstype = soknadstype }
	fun withMottatteDokumenter(vararg mottatteDokumenter: DocumentData) =
		apply { this.dokumenter.addAll(mottatteDokumenter) }

	fun withMottatteDokumenter(mottatteDokumentIder: List<String>) =
		apply { this.dokumenter.addAll(lagDokumenterFraIder(mottatteDokumentIder)) }

	fun build() =
		Soknad(innsendingId = behandlingsid, erEttersendelse = false, personId = fodselsnummer, tema = arkivtema, dokumenter =  dokumenter)

	private fun lagDokumenterFraIder(idList: List<String>): List<DocumentData> {
		val hoveddok =
			DocumentData(
				skjemanummer = "NAV 08-07.04D", erHovedskjema = true, tittel = "Dummy tittel",
				varianter = listOf(Varianter(id = idList[0], mediaType = "application/pdf", filnavn = "hodveddok.pdf", filtype = "PDFA"))
			)

		val vedlegg = if (idList.size > 1) {
			idList.subList(1, idList.size - 1).map {
				DocumentData(
					skjemanummer = "O2", erHovedskjema = false, tittel = "Dummy vedlegg",
					varianter = listOf(
						Varianter(
							id = it,
							mediaType = "application/pdf",
							filnavn = "vedlegg.pdf",
							filtype = "PDFA"
						)
					)
				)
			}
		}	else {
			emptyList<DocumentData>()
			}
		return listOf(hoveddok, *vedlegg.toTypedArray())
	}
}
