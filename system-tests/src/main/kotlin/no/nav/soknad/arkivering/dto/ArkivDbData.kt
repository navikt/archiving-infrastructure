package no.nav.soknad.arkivering.dto

data class ArkivDbData(
	val id: String,
	val title: String,
	val tema: String,
	val timesaved: Long
)