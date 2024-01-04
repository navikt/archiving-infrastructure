package no.nav.soknad.arkivering.utils

import no.nav.soknad.arkivering.avroschemas.Soknadstyper
import no.nav.soknad.arkivering.soknadsmottaker.model.Soknad
import no.nav.soknad.arkivering.soknadsmottaker.model.DocumentData
import java.time.LocalDateTime
import java.time.ZoneOffset

class SoknadsBuilder() {
	private var behandlingsid: String = "behandlingsid"
	private var fodselsnummer: String = "12345687901"
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

	fun build() =
		Soknad(innsendingId = behandlingsid, erEttersendelse = false, personId = fodselsnummer, tema = arkivtema, dokumenter =  dokumenter)

}
