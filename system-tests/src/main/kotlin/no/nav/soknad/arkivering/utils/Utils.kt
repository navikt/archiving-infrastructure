package no.nav.soknad.arkivering.utils

import no.nav.soknad.arkivering.dto.InnsendtDokumentDto
import no.nav.soknad.arkivering.dto.InnsendtVariantDto
import no.nav.soknad.arkivering.dto.SoknadInnsendtDto
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.TimeUnit

fun loopAndVerify(expectedCount: Int, getCount: () -> Int,
									finalCheck: () -> Any = { assert(expectedCount == getCount.invoke()) }) {

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


fun createDto(fileId: String, innsendingsId: String = UUID.randomUUID().toString()) =
	SoknadInnsendtDto(innsendingsId, false, "personId", "tema", LocalDateTime.now(),
		listOf(InnsendtDokumentDto("NAV 10-07.17", true, "Søknad om refusjon av reiseutgifter - bil",
			listOf(InnsendtVariantDto(fileId, null, "filnavn", "1024", "variantformat", "PDFA")))))

fun createDto(fileIds: List<String>) =
	SoknadInnsendtDto(UUID.randomUUID().toString(), false, "personId", "tema", LocalDateTime.now(),
		listOf(InnsendtDokumentDto("NAV 10-07.17", true, "Søknad om refusjon av reiseutgifter - bil",
			fileIds.map { InnsendtVariantDto(it, null, "filnavn", "1024", "variantformat", "PDFA") })))
