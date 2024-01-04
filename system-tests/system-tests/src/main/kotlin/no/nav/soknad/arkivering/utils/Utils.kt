package no.nav.soknad.arkivering.utils

import no.nav.soknad.arkivering.avroschemas.Soknadstyper
import no.nav.soknad.arkivering.dto.FileResponses
import no.nav.soknad.arkivering.soknadsmottaker.model.DocumentData
import no.nav.soknad.arkivering.soknadsmottaker.model.Soknad
import no.nav.soknad.arkivering.soknadsmottaker.model.Varianter
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


const val fnr = "10108000398" // Not a real fnr
const val kjoreliste: String = "NAV 11-12.10"
const val sykepenger: String = "NAV 08-07.04D"
const val kvittering: String = "L7"
private val titles: Map<String, String> = mapOf(kjoreliste to "Kjøreliste", sykepenger to "Søknad om sykepenger")


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


fun createSoknad(innsendingId: String, skjemanummer: String, tema: String, numberOfAttachments: Int): Soknad {
	val attachments =  (0 until numberOfAttachments).map {
		DocumentData(
			skjemanummer = "O2", erHovedskjema = false, tittel = "Vedlegg$it",
			varianter = listOf(
				Varianter(
					id = UUID.randomUUID().toString(),
					mediaType = "application/pdf",
					filnavn = "O2.pdf",
					filtype = "PDFA"
				)
			)
		)
	}

	val application = SoknadsBuilder()
		.withBehandlingsid(innsendingId)
		.withArkivtema(tema)
		.withFodselsnummer(fnr)
		.withSoknadstype(Soknadstyper.SOKNAD)
		.withInnsendtDato(LocalDateTime.now())
		.withMottatteDokumenter(
			DocumentData(
				skjemanummer = skjemanummer, erHovedskjema = true, tittel = titles.get(sykepenger) ?: "Dummy tittel",
				varianter = listOf(
					Varianter(
						id = UUID.randomUUID().toString(),
						mediaType = "application/pdf",
						filnavn = "$sykepenger.pdf",
						filtype = "PDFA"
					),
					Varianter(
						id = UUID.randomUUID().toString(),
						mediaType = "application/json",
						filnavn = "$sykepenger.json",
						filtype = "json"
					)
				)
			),
			DocumentData(
				skjemanummer = kvittering, erHovedskjema = false, tittel = "Kvittering",
				varianter = listOf(
					Varianter(
						id = UUID.randomUUID().toString(),
						mediaType = "application/pdf",
						filnavn = "$sykepenger.pdf",
						filtype = "PDFA"
					)
				)
			),
			*attachments.toTypedArray()
		)
		.build()

		return application
}


fun createDocuments(fileId: String, erHovedskjema: Boolean) = DocumentData(
	"NAV 11-12.10",
	erHovedskjema,
	"Kjøreliste for godkjent bruk av egen bil",
	listOf(createVarianter(fileId))
)

fun createVarianter(fileId: String) = Varianter(
	fileId,
	"application/pdf",
	"innsending.pdf",
	"PDFA"
)
