package no.nav.soknad.arkivering.utils

import no.nav.soknad.arkivering.dto.InnsendtDokumentDto
import no.nav.soknad.arkivering.dto.InnsendtVariantDto
import no.nav.soknad.arkivering.dto.SoknadInnsendtDto
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.TimeUnit

fun loopAndVerify(
	expectedCount: Int,
	getCount: () -> Int,
	finalCheck: () -> Any = { assert(expectedCount == getCount.invoke()) }
) {

	val startTime = System.currentTimeMillis()
	val timeout = 30 * 1000

	while (System.currentTimeMillis() < startTime + timeout) {
		val matches = getCount.invoke()

		if (matches == expectedCount) {
			break
		}
		TimeUnit.MILLISECONDS.sleep(50)
	}
	finalCheck.invoke()
}


private const val fnr = "10108000398" // Not a real fnr

fun createDto(innsendingsId: String, fileId: String) =
	createDto(innsendingsId, listOf(fileId))

fun createDto(innsendingsId: String, fileIds: List<String>) =
	SoknadInnsendtDto(innsendingsId, false, fnr, "BIL", LocalDateTime.now(), createInnsendtDokumentDtos(fileIds))

private fun createInnsendtDokumentDtos(fileIds: List<String>): List<InnsendtDokumentDto> =
	mutableListOf(
		createInnsendtDokumentDto(fileIds.first(), true)
	).plus(
		fileIds.drop(1).map { createInnsendtDokumentDto(it, false) }
	)

private fun createInnsendtDokumentDto(id: String, erHovedskjema: Boolean) =
	InnsendtDokumentDto("NAV 10-07.17", erHovedskjema, "Søknad fra lasttest",
		listOf(InnsendtVariantDto(id, null, "filnavn", "1024", "ARKIV", "PDFA")))
