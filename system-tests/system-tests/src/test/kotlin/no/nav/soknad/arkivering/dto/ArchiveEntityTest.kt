package no.nav.soknad.arkivering.dto

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ArchiveEntityTest {
	private val mapper = jacksonObjectMapper()

	@Test
	fun `reads an archive event without document metadata`() {
		val entity = mapper.readValue<ArchiveEntity>(
			"""{"id":"test-id","title":"Test","tema":"SYK","kanal":"NAV_NO","timesaved":0,"overstyrInnsynsregler":null}"""
		)

		assertEquals(emptyList<ArchiveDocument>(), entity.dokumenter)
	}

	@Test
	fun `reads ordered documents and tolerates additional metadata`() {
		val entity = mapper.readValue<ArchiveEntity>(
			"""
			{
			  "id": "test-id", "title": "Test", "tema": "SYK", "kanal": "NAV_NO", "timesaved": 0,
			  "overstyrInnsynsregler": null, "additionalMetadata": true,
			  "dokumenter": [
			    {"tittel":"Hoveddokument","brevkode":"TEST","variantformater":["ARKIV","ORIGINAL"],"antallSider":2,"additionalMetadata":true},
			    {"tittel":"Vedlegg","brevkode":null,"variantformater":["ARKIV"]}
			  ]
			}
			""".trimIndent()
		)

		assertEquals(
			listOf(
				ArchiveDocument("Hoveddokument", "TEST", listOf("ARKIV", "ORIGINAL"), antallSider = 2),
				ArchiveDocument("Vedlegg", null, listOf("ARKIV"))
			),
			entity.dokumenter
		)
		assertEquals(entity, mapper.readValue<ArchiveEntity>(mapper.writeValueAsString(entity)))
	}
}
