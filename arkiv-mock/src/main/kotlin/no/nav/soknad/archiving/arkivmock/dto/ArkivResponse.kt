package no.nav.soknad.archiving.arkivmock.dto

data class Dokumenter(val brevkode: String, val dokumentInfoId: String, val tittel: String)

data class OpprettJournalpostResponse(
	val dokumenter: List<Dokumenter>,
	val journalpostId: String,
	val journalpostferdigstilt: Boolean,
	val journalstatus: String,
	val melding: String? = null)
