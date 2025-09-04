package no.nav.soknad.arkivering.dto

data class ArchiveEntity(
	val id: String,
	val title: String,
	val tema: String,
	val kanal: String,
	val timesaved: Long
)
