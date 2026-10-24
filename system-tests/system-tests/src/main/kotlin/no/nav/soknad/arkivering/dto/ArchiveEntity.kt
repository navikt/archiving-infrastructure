package no.nav.soknad.arkivering.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class ArchiveEntity(
	val id: String,
	val title: String,
	val tema: String,
	val kanal: String,
	val timesaved: Long,
	val overstyrInnsynsregler: String?,
	val dokumenter: List<ArchiveDocument> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ArchiveDocument(
	val tittel: String,
	val brevkode: String?,
	val variantformater: List<String>
)
