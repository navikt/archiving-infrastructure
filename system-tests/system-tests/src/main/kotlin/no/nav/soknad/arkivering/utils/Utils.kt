package no.nav.soknad.arkivering.utils

import no.nav.soknad.arkivering.soknadsmottaker.model.DocumentData
import no.nav.soknad.arkivering.soknadsmottaker.model.Soknad
import no.nav.soknad.arkivering.soknadsmottaker.model.Varianter
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


fun createSoknad(innsendingId: String, fileId: String) = createSoknad(innsendingId, listOf(fileId))
fun createSoknad(innsendingId: String, fileIds: List<String>) = Soknad(
	innsendingId,
	false,
	fnr,
	"BIL",
	mutableListOf(
		createDocuments(fileIds.first(), true)
	).plus(
		fileIds.drop(1).map { createDocuments(it, false) }
	)
)

fun createDocuments(fileId: String, erHovedskjema: Boolean) = DocumentData(
	"NAV 10-07.40",
	erHovedskjema,
	"Søknad om stønad til anskaffelse av motorkjøretøy",
	listOf(createVarianter(fileId))
)

fun createVarianter(fileId: String) = Varianter(
	fileId,
	"application/pdf",
	"innsending.pdf",
	"PDFA"
)
