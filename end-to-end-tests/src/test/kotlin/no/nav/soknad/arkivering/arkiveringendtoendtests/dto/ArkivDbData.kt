package no.nav.soknad.arkivering.arkiveringendtoendtests.dto

import java.time.LocalDateTime

data class ArkivDbData(
	val id: String,
	val title: String,
	val tema: String,
	val timesaved: LocalDateTime
)
