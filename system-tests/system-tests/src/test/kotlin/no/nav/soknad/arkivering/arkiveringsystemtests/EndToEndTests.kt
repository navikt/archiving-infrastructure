package no.nav.soknad.arkivering.arkiveringsystemtests

import no.nav.soknad.arkivering.Config
import no.nav.soknad.arkivering.arkiveringsystemtests.environment.EmbeddedDockerImages
import no.nav.soknad.arkivering.dto.ArchiveDocument
import no.nav.soknad.arkivering.dto.SafResponses
import no.nav.soknad.arkivering.innsending.*
import no.nav.soknad.arkivering.innsending.model.ArkiveringsStatusDto
import no.nav.soknad.arkivering.innsending.model.AttachmentDto
import no.nav.soknad.arkivering.innsending.model.Mimetype
import no.nav.soknad.arkivering.innsending.model.OpplastingsStatusDto
import no.nav.soknad.arkivering.innsending.model.SkjemaDokumentDto
import no.nav.soknad.arkivering.innsending.model.SkjemaDto
import no.nav.soknad.arkivering.innsending.model.SoknadsStatusDto
import no.nav.soknad.arkivering.innsending.model.SubmitApplicationRequest
import no.nav.soknad.arkivering.utils.SubmitApplicationRequestBuilder
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.*
import javax.imageio.ImageIO

@Execution(ExecutionMode.CONCURRENT)
class EndToEndTests : SystemTestBase() {
	private val embeddedDockerImages = EmbeddedDockerImages()
	private lateinit var soknadsmottakerApi: SoknadsmottakerApi
	private lateinit var innsendingApi: InnsendingApi

	val testpersonid = "19876898104"

	@BeforeAll
	fun setup() {
		if (!isExternalEnvironment) {
			env.addEmbeddedDockerImages(embeddedDockerImages)
			embeddedDockerImages.startContainers()
		}

		setUp()
		soknadsmottakerApi = SoknadsmottakerApi(soknadApiWithoutOAuth2(config))
		innsendingApi = InnsendingApi(config)
	}

	@AfterAll
	fun teardown() {
		tearDown()

		if (!isExternalEnvironment) {
			embeddedDockerImages.stopContainers()
		}
	}
	@Test
	fun `jackson test`() {
		val jsonResponse = "{\"status\":\"UP\", \"ping\":{\"status\":\"UP\"}, \"ssl\":{\"status\":\"UP\",\"details\":{\"validChains\":[]}}}"

		val health = objectMapper.readValue(jsonResponse, Health::class.java)
		assertTrue(health.status == "UP")

	}

	@Test
	fun `Happy case - one file ends up in the archive`() {
		val soknadTestdata = innsendingApi.opprettEttersending()
		val innsendingsId = soknadTestdata.innsendingsId

		soknadTestdata.vedleggsliste()
			.verifyHasSize(1)
			.lastOppFil(0, "OneHundred_KB.pdf")

		assertThatArkivMock()
			.hasFinishedEvent(innsendingsId)
			.hasEntityInArchive(innsendingsId)
			.hasCallCountInArchive(innsendingsId, expectedCount = 1)
			.verify { innsendingApi.sendInn(soknadTestdata).getOrThrow() }

		assertThatSoknad(innsendingsId)
			.hasStatus(ArkiveringsStatusDto.arkivert)
	}

	@Test
	fun `Happy case - large attachment ends up in the archive`() {
		val soknadTestdata = innsendingApi.opprettEttersending()
		val innsendingsId = soknadTestdata.innsendingsId

		soknadTestdata.vedleggsliste()
			.verifyHasSize(1)
			.lastOppFil(0, "Thirty_MB.pdf")

		assertThatArkivMock()
			.hasFinishedEvent(innsendingsId)
			.hasEntityInArchive(innsendingsId)
			.hasCallCountInArchive(innsendingsId, expectedCount = 1)
			.verify { innsendingApi.sendInn(soknadTestdata).getOrThrow() }

		assertThatSoknad(innsendingsId)
			.hasStatus(ArkiveringsStatusDto.arkivert)
	}

	@Test
	fun `Arkivering av ettersending feiler mot arkivet`() {
		val soknadTestdata = innsendingApi.opprettEttersending()
		val innsendingsId = soknadTestdata.innsendingsId

		soknadTestdata.vedleggsliste()
			.verifyHasSize(1)
			.lastOppFil(0, "OneHundred_KB.pdf")

		mockArchiveRespondsWithCodeForXAttempts(innsendingsId, 500, attemptsThanSoknadsarkivererWillPerform + 1)

		assertThatArkivMock()
			.hasFailureEvent(innsendingsId, 350_000L)
			.hasNoEntityInArchive(innsendingsId)
			.verify { innsendingApi.sendInn(soknadTestdata).getOrThrow() }

		assertThatSoknad(innsendingsId)
			.hasStatus(ArkiveringsStatusDto.arkiveringFeilet)
	}

	@Test
	fun `Archive responds 409 - application already archived`() {
		val soknadTestdata = innsendingApi.opprettEttersending(
			vedleggListe = listOf(
				Vedlegg("N6", "Bekreftelse på skoleplass"),
				Vedlegg("A5", "Vitnemål grunnskole"),
				Vedlegg("T1", "Bekreftelse fra fastlege"),
			)
		)
		val innsendingsId = soknadTestdata.innsendingsId

		soknadTestdata.vedleggsliste()
			.verifyHasSize(3)
			.lastOppFil(0, "OneHundred_KB.pdf")
			.lastOppFil(1, "Ten_MB.pdf")
			.lastOppFil(2, "OneHundred_KB.pdf")

		mockArchiveRespondsWithCodeForXAttempts(innsendingsId, 409, 1)

		assertThatArkivMock()
			.hasFinishedEvent(innsendingsId)
			.hasNoEntityInArchive(innsendingsId)
			.hasCallCountInArchive(innsendingsId, expectedCount = 1)
			.verify { innsendingApi.sendInn(soknadTestdata).getOrThrow() }

		assertThatSoknad(innsendingsId)
			.hasStatus(ArkiveringsStatusDto.arkivert)
	}


	@Test
	fun `SAF respond with journalpost - application already archived`() {
		val soknadTestdata = innsendingApi.opprettEttersending(
			vedleggListe = listOf(
				Vedlegg("N6", "Bekreftelse på skoleplass"),
				Vedlegg("A5", "Vitnemål grunnskole"),
				Vedlegg("T1", "Bekreftelse fra fastlege"),
			)
		)
		val innsendingsId = soknadTestdata.innsendingsId

		soknadTestdata.vedleggsliste()
			.verifyHasSize(3)
			.lastOppFil(0, "OneHundred_KB.pdf")
			.lastOppFil(1, "Ten_MB.pdf")
			.lastOppFil(2, "OneHundred_KB.pdf")

		setSafFetchBehaviour(innsendingsId, SafResponses.OK.name, -1)

		assertThatArkivMock()
			.hasFinishedEvent(innsendingsId)
			.hasNoEntityInArchive(innsendingsId)
			.hasNoCallInArchive(innsendingsId)
			.verify { innsendingApi.sendInn(soknadTestdata).getOrThrow() }

		assertThatSoknad(innsendingsId)
			.hasStatus(ArkiveringsStatusDto.arkivert)
	}

	@Test
	fun `Request responds with 408 - second attempt already archived from SAF`() {
		val soknadTestdata = innsendingApi.opprettEttersending(
			vedleggListe = listOf(
				Vedlegg("N6", "Bekreftelse på skoleplass"),
				Vedlegg("A5", "Vitnemål grunnskole"),
				Vedlegg("T1", "Bekreftelse fra fastlege"),
			)
		)
		val innsendingsId = soknadTestdata.innsendingsId

		soknadTestdata.vedleggsliste()
			.verifyHasSize(3)
			.lastOppFil(0, "OneHundred_KB.pdf")
			.lastOppFil(1, "Ten_MB.pdf")
			.lastOppFil(2, "OneHundred_KB.pdf")

		setSafFetchBehaviour(innsendingsId, SafResponses.NOT_FOUND.name, 1)
		mockArchiveRespondsWithCodeForXAttempts(innsendingsId, 408, 1)

		assertThatArkivMock()
			.hasCallCountInArchive(innsendingsId, expectedCount = 1)
			.verify { innsendingApi.sendInn(soknadTestdata).getOrThrow() }

		assertThatSoknad(innsendingsId)
			.hasStatus(ArkiveringsStatusDto.arkivert)
	}

	// The poison pill tests global deserialization robustness and is not tied to a single innsendingsId,
	// so it is kept apart from the other resource heavy tests.
	@ResourceLock(value = heavyTestsResource, mode = ResourceAccessMode.READ_WRITE)
	@Test
	fun `Poison pill followed by proper message - one file ends up in the archive`() {
		val soknadTestdata = innsendingApi.opprettEttersending(
			vedleggListe = listOf(
				Vedlegg("T1", "Bekreftelse fra fastlege"),
			)
		)
		val innsendingsId = soknadTestdata.innsendingsId

		soknadTestdata.vedleggsliste()
			.verifyHasSize(1)
			.lastOppFil(0, "OneHundred_KB.pdf")

		assertThatArkivMock()
			.hasFinishedEvent(innsendingsId)
			.hasEntityInArchive(innsendingsId)
			.hasCallCountInArchive(innsendingsId, expectedCount = 1)
			.verify {
				// Same key means both records use the same partition. The producer waits for the poison
				// pill acknowledgement, so Kafka must process it before the valid submission record.
				putPoisonPillOnKafkaTopic(innsendingsId)
				innsendingApi.sendInn(soknadTestdata).getOrThrow()
			}

		assertThatSoknad(innsendingsId)
			.hasStatus(ArkiveringsStatusDto.arkivert)
	}

	@Test
	fun `Archive responds 404 on first two attempts - Works on third attempt`() {
		val erroneousAttempts = 2

		val soknadTestdata = innsendingApi.opprettEttersending(
			vedleggListe = listOf(
				Vedlegg("T1", "Bekreftelse fra fastlege"),
			)
		)
		val innsendingsId = soknadTestdata.innsendingsId

		soknadTestdata.vedleggsliste()
			.verifyHasSize(1)
			.lastOppFil(0, "OneHundred_KB.pdf")

		mockArchiveRespondsWithCodeForXAttempts(innsendingsId, 404, erroneousAttempts)

		assertThatArkivMock()
			.hasEntityInArchive(innsendingsId)
			.hasCallCountInArchive(innsendingsId, expectedCount = erroneousAttempts + 1)
			.verify { innsendingApi.sendInn(soknadTestdata).getOrThrow() }

		assertThatSoknad(innsendingsId)
			.hasStatus(ArkiveringsStatusDto.arkivert)
	}

	@Test
	fun `Archive responds 200 but has wrong response body - Will retry`() {
		val erroneousAttempts = 3

		val soknadTestdata = innsendingApi.opprettEttersending(
			vedleggListe = listOf(
				Vedlegg("T1", "Bekreftelse fra fastlege"),
			)
		)
		val innsendingsId = soknadTestdata.innsendingsId

		soknadTestdata.vedleggsliste()
			.verifyHasSize(1)
			.lastOppFil(0, "OneHundred_KB.pdf")

		mockArchiveRespondsWithErroneousBodyForXAttempts(innsendingsId, erroneousAttempts)

		assertThatArkivMock()
			.hasEntityInArchive(innsendingsId)
			.hasCallCountInArchive(innsendingsId, expectedCount = erroneousAttempts + 1)
			.verify { innsendingApi.sendInn(soknadTestdata).getOrThrow() }

		assertThatSoknad(innsendingsId)
			.hasStatus(ArkiveringsStatusDto.arkivert)
	}

	private val fileOfSize1mb = "/One_MB.pdf"

	@Test
	fun `Happy case - one submission from not logged in user ends up in the archive`() {

		val innsendingsUUID = UUID.randomUUID()
		val nologinSoknad = prepareNoLoginApplication(innsendingsUUID, mapOf(UUID.randomUUID().toString() to listOf(loadFile(fileOfSize1mb))))

		val innsendingsId = innsendingsUUID.toString()

		assertThatArkivMock()
			.hasFinishedEvent(innsendingsId)
			.hasEntityInArchive(innsendingsId)
			.hasCallCountInArchive(innsendingsId, expectedCount = 1)
			.hasDocumentsInArchive(
				innsendingsId,
				listOf(
					ArchiveDocument("Søknad", "NAV 08-07.04D", listOf("ARKIV", "ORIGINAL")),
					ArchiveDocument("Vedlegg", "T1", listOf("ARKIV")),
				)
			)
			.verify { innsendingApi.sendInNoLoginApplication(innsendingsUUID, nologinSoknad).getOrThrow() }

		assertThatSoknad(innsendingsId)
			.hasStatus(ArkiveringsStatusDto.arkivert)
	}

	@Test
	fun `Happy case - two PDF files for one attachment are archived as one two-page document`() {
		val innsendingsUUID = UUID.randomUUID()
		val attachmentId = UUID.randomUUID().toString()
		val pdf = loadFile("/Synthetic_one_page.pdf")
		val nologinSoknad = prepareNoLoginApplication(innsendingsUUID, mapOf(attachmentId to listOf(pdf, pdf)))
		val attachment = checkNotNull(nologinSoknad.attachments).single()
		val fileIds = checkNotNull(attachment.fileIds)
		assertEquals(2, fileIds.size)
		assertEquals(2, fileIds.toSet().size)

		val innsendingsId = innsendingsUUID.toString()
		assertThatArkivMock()
			.hasFinishedEvent(innsendingsId)
			.hasEntityInArchive(innsendingsId)
			.hasCallCountInArchive(innsendingsId, expectedCount = 1)
			.hasDocumentsInArchive(
				innsendingsId,
				listOf(
					ArchiveDocument("Søknad", "NAV 08-07.04D", listOf("ARKIV", "ORIGINAL")),
					ArchiveDocument("Vedlegg", "T1", listOf("ARKIV"), antallSider = 2),
				)
			)
			.verify { innsendingApi.sendInNoLoginApplication(innsendingsUUID, nologinSoknad).getOrThrow() }

		assertThatSoknad(innsendingsId)
			.hasStatus(ArkiveringsStatusDto.arkivert)
	}

	@Test
	fun `Happy case - uploaded PNG is converted and archived as one-page PDF`() {
		val innsendingsUUID = UUID.randomUUID()
		val imageFile = Files.createTempFile("synthetic-attachment-", ".png").toFile()
		try {
			val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB)
			image.createGraphics().apply {
				color = Color(40, 100, 180)
				fillRect(0, 0, image.width, image.height)
				dispose()
			}
			check(ImageIO.write(image, "png", imageFile)) { "PNG writer is unavailable" }

			val nologinSoknad = prepareNoLoginApplication(
				innsendingsUUID,
				mapOf(UUID.randomUUID().toString() to listOf(imageFile)),
			)
			val innsendingsId = innsendingsUUID.toString()
			assertThatArkivMock()
				.hasFinishedEvent(innsendingsId)
				.hasEntityInArchive(innsendingsId)
				.hasCallCountInArchive(innsendingsId, expectedCount = 1)
				.hasDocumentsInArchive(
					innsendingsId,
					listOf(
						ArchiveDocument("Søknad", "NAV 08-07.04D", listOf("ARKIV", "ORIGINAL")),
						ArchiveDocument("Vedlegg", "T1", listOf("ARKIV"), antallSider = 1),
					)
				)
				.verify { innsendingApi.sendInNoLoginApplication(innsendingsUUID, nologinSoknad).getOrThrow() }

			assertThatSoknad(innsendingsId)
				.hasStatus(ArkiveringsStatusDto.arkivert)
		} finally {
			Files.deleteIfExists(imageFile.toPath())
		}
	}

	@Test
	fun `Happy case - logged in first submission archives PDF JSON variant and attachment`() {
		val formNumber = "NAV 08-07.04D"
		val mainDocumentTitle = "Søknad"
		val attachmentTitle = "Vedlegg"
		val attachmentId = UUID.randomUUID().toString()
		val mainDocument = loadFile("/mellomstor.pdf")
		val requestBuilder = SubmitApplicationRequestBuilder(
			brukerId = testpersonid,
			skjemanr = formNumber,
			tittel = mainDocumentTitle,
			tema = "SYK",
			status = SoknadsStatusDto.utfylt,
			hoveddokument = mainDocument.readBytes(),
		)
		val jsonVariant = requestBuilder.hoveddokumentVariant
		val applicationMetadata = SkjemaDto(
			skjemanr = formNumber,
			tittel = mainDocumentTitle,
			tema = "SYK",
			spraak = "nb_NO",
			hoveddokument = SkjemaDokumentDto(
				vedleggsnr = formNumber,
				tittel = mainDocumentTitle,
				label = mainDocumentTitle,
				pakrevd = true,
				mimetype = Mimetype.applicationSlashPdf,
			),
			hoveddokumentVariant = SkjemaDokumentDto(
				vedleggsnr = formNumber,
				tittel = mainDocumentTitle,
				label = mainDocumentTitle,
				pakrevd = false,
				mimetype = Mimetype.applicationSlashJson,
				document = jsonVariant,
			),
			status = SoknadsStatusDto.opprettet,
			vedleggsListe = listOf(
				SkjemaDokumentDto(
					vedleggsnr = "T1",
					tittel = attachmentTitle,
					label = attachmentTitle,
					pakrevd = false,
					mimetype = Mimetype.applicationSlashPdf,
					formioId = attachmentId,
				)
			),
		)
		val innsendingsUUID = UUID.fromString(innsendingApi.createDigitalApplication(applicationMetadata).getOrThrow())
		val innsendingsId = innsendingsUUID.toString()
		val uploadedAttachment = innsendingApi
			.uploadDigitalAttachmentFile(innsendingsUUID, attachmentId, loadFile(fileOfSize1mb))
			.getOrThrow()
		val attachment = AttachmentDto(
			attachmentCode = "T1",
			label = attachmentTitle,
			uploadStatus = OpplastingsStatusDto.lastetOpp,
			title = attachmentTitle,
			fileIds = listOf(uploadedAttachment.id),
		)
		val request = requestBuilder
			.medVedlegg(attachment)
			.build()

		assertThatArkivMock()
			.hasFinishedEvent(innsendingsId)
			.hasEntityInArchive(innsendingsId)
			.hasCallCountInArchive(innsendingsId, expectedCount = 1)
			.hasDocumentsInArchive(
				innsendingsId,
				listOf(
					ArchiveDocument(mainDocumentTitle, formNumber, listOf("ARKIV", "ORIGINAL")),
					ArchiveDocument(attachmentTitle, "T1", listOf("ARKIV")),
				)
			)
			.verify { innsendingApi.submitDigitalApplication(innsendingsUUID, request).getOrThrow() }

		assertThatSoknad(innsendingsId)
			.hasStatus(ArkiveringsStatusDto.arkivert)
	}

	@Test
	fun `Granting user digital access sets the Joark access override`() {
		val innsendingsUUID = UUID.randomUUID()
		val application = prepareNoLoginApplication(
			innsendingsUUID,
			mapOf(UUID.randomUUID().toString() to listOf(loadFile(fileOfSize1mb))),
			grantUserDigitalAccess = true
		)

		assertThatArkivMock()
			.hasFinishedEvent(innsendingsUUID.toString())
			.hasEntityInArchiveWithOverstyrInnsynsregler(innsendingsUUID.toString(), "VISES_MASKINELT_GODKJENT")
			.verify { innsendingApi.sendInNoLoginApplication(innsendingsUUID, application).getOrThrow() }
	}

	@Test
	fun `Absent user digital access leaves the Joark access override absent`() {
		val innsendingsUUID = UUID.randomUUID()
		val application = prepareNoLoginApplication(
			innsendingsUUID,
			mapOf(UUID.randomUUID().toString() to listOf(loadFile(fileOfSize1mb)))
		)

		assertThatArkivMock()
			.hasFinishedEvent(innsendingsUUID.toString())
			.hasEntityInArchiveWithOverstyrInnsynsregler(innsendingsUUID.toString(), null)
			.verify { innsendingApi.sendInNoLoginApplication(innsendingsUUID, application).getOrThrow() }
	}

	// Ten submissions in a loop put a lot of load on the shared containers, so this test is kept apart
	// from the other resource heavy tests.
	@ResourceLock(value = heavyTestsResource, mode = ResourceAccessMode.READ_WRITE)
	@Test
	fun `Happy case - ten submission from not logged in user ends up in the archive`() {
		repeat(10) {
			`Happy case - one submission from not logged in user ends up in the archive`()
		}
	}

	@Test
	fun `Happy case - upload one file and then deletes it`() {

		val innsendingsId = UUID.randomUUID().toString()
		val vedleggsId = UUID.randomUUID().toString()

		val uploadResponse = innsendingApi.lastOppNoLoginFil(innsendingsId, vedleggsId, loadFile(fileOfSize1mb))
		assertTrue(uploadResponse.isSuccess)

		val fileId = uploadResponse.getOrThrow().id
		assertTrue { fileId.toString().isNotEmpty() }

		val deleteResponse = innsendingApi.slettNoLoginFil(innsendingsId, vedleggsId, fileId.toString())
		assertTrue(deleteResponse.isSuccess)
	}

	private fun prepareNoLoginApplication(
		innsendingsId: UUID,
		vedleggMap: Map<String, List<File>>,
		grantUserDigitalAccess: Boolean? = null
	): SubmitApplicationRequest {
		val attachments = vedleggMap.map { (attachmentId, files) ->
			val fileIds = files.map { file ->
				innsendingApi.lastOppNoLoginFil(innsendingsId.toString(), attachmentId, file).getOrThrow().id
			}
			AttachmentDto(
				attachmentCode = "T1",
				label = "Vedlegg",
				uploadStatus = OpplastingsStatusDto.lastetOpp,
				title = "Vedlegg",
				description = "Dette er opplysninger som er nødvendig for beregning av utbetaling av foreldrepenger eller svangerskapspenger.",
				fileIds = fileIds,
			)
		}
		return SubmitApplicationRequestBuilder(
			brukerId = testpersonid,
			skjemanr = "NAV 08-07.04D",
			tittel = "Søknad",
			tema = "SYK",
			hoveddokument = loadFile("/mellomstor.pdf").readBytes(),
			status = SoknadsStatusDto.utfylt,
			grantUserDigitalAccess = grantUserDigitalAccess
		)
			.medVedlegg(attachments)
			.build()
	}

	private fun loadFile(fileName: String): File {
		val resource = Config::class.java.getResource(fileName) ?: throw Exception("$fileName not found")
		return File(resource.toURI())
	}


	private fun setSafFetchBehaviour(uuid: String, behaviour: String = SafResponses.NOT_FOUND.name, attempts: Int = -1) {
		val url = env.getUrlForArkivMock() + "/arkiv-mock/mock-saf-response/$uuid/$behaviour/$attempts"
		performPutCall(url)
	}

	private fun mockArchiveRespondsWithCodeForXAttempts(uuid: String, status: Int, forAttempts: Int) {
		val url = env.getUrlForArkivMock() + "/arkiv-mock/response-behaviour/mock-response/$uuid/$status/$forAttempts"
		performPutCall(url)
	}

	private fun mockArchiveRespondsWithErroneousBodyForXAttempts(uuid: String, forAttempts: Int) {
		val url =
			env.getUrlForArkivMock() + "/arkiv-mock/response-behaviour/set-status-ok-with-erroneous-body/$uuid/$forAttempts"
		performPutCall(url)
	}
}

/**
 * Resource lock shared by the tests that should not run at the same time as each other. Note that
 * [org.junit.jupiter.api.parallel.Resources.GLOBAL] (and thereby `@Isolated`) must not be used on a test method here:
 * a method level global lock makes JUnit run the whole test run in a single thread.
 */
private const val heavyTestsResource = "end-to-end-heavy-tests"
