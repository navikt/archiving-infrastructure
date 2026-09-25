package no.nav.soknad.arkivering.arkiveringsystemtests

import no.nav.soknad.arkivering.Config
import no.nav.soknad.arkivering.arkiveringsystemtests.environment.EmbeddedDockerImages
import no.nav.soknad.arkivering.dto.SafResponses
import no.nav.soknad.arkivering.innsending.*
import no.nav.soknad.arkivering.innsending.model.ArkiveringsStatusDto
import no.nav.soknad.arkivering.innsending.model.AttachmentDto
import no.nav.soknad.arkivering.innsending.model.OpplastingsStatusDto
import no.nav.soknad.arkivering.innsending.model.SoknadsStatusDto
import no.nav.soknad.arkivering.innsending.model.SubmitApplicationRequest
import no.nav.soknad.arkivering.utils.Skjema.generateVedleggsnr
import no.nav.soknad.arkivering.utils.SubmitApplicationRequestBuilder
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
import java.io.File
import java.util.*

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

		try {
			innsendingApi.sendInn(soknadTestdata)
		} catch (e: Exception) {
			throw e
		}

		assertThatArkivMock()
			.hasFinishedEvent(innsendingsId)
			.hasEntityInArchive(innsendingsId)
			.hasCallCountInArchive(innsendingsId, expectedCount = 1)
			.verify()

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

		try {
			innsendingApi.sendInn(soknadTestdata)
		} catch (e: Exception) {
			throw e
		}

		assertThatArkivMock()
			.hasFinishedEvent(innsendingsId)
			.hasEntityInArchive(innsendingsId)
			.hasCallCountInArchive(innsendingsId, expectedCount = 1)
			.verify()

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
		try {
			innsendingApi.sendInn(soknadTestdata)
		} catch (e: Exception) {
			throw e
		}

		assertThatArkivMock()
			.hasFailureEvent(innsendingsId, 350_000L)
			.hasNoEntityInArchive(innsendingsId)
			.verify()

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

		mockArchiveRespondsWithCodeForXAttempts(innsendingsId, 409, -1)
		try {
			innsendingApi.sendInn(soknadTestdata)
		} catch (e: Exception) {
			throw e
		}

		assertThatArkivMock()
			.hasFinishedEvent(innsendingsId)
			.hasEntityInArchive(innsendingsId)
			.hasCallCountInArchive(innsendingsId, expectedCount = 1)
			.verify()

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
		try {
			innsendingApi.sendInn(soknadTestdata)
		} catch (e: Exception) {
			throw e
		}

		assertThatArkivMock()
			.hasFinishedEvent(innsendingsId)
			.verify()

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
		try {
			innsendingApi.sendInn(soknadTestdata)
		} catch (e: Exception) {
			throw e
		}

		assertThatArkivMock()
			.hasCallCountInArchive(innsendingsId, expectedCount = 1)
			.verify()

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

		putPoisonPillOnKafkaTopic(UUID.randomUUID().toString())
		try {
			innsendingApi.sendInn(soknadTestdata)
		} catch (e: Exception) {
			throw e
		}
		assertThatArkivMock()
			.hasEntityInArchive(innsendingsId)
			.hasCallCountInArchive(innsendingsId, expectedCount = 1)
			.verify()

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
		try {
			innsendingApi.sendInn(soknadTestdata)
		} catch (e: Exception) {
			throw e
		}

		assertThatArkivMock()
			.hasEntityInArchive(innsendingsId)
			.hasCallCountInArchive(innsendingsId, expectedCount = erroneousAttempts + 1)
			.verify()

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
		try {
			innsendingApi.sendInn(soknadTestdata)
		} catch (e: Exception) {
			throw e
		}

		assertThatArkivMock()
			.hasEntityInArchive(innsendingsId)
			.hasCallCountInArchive(innsendingsId, expectedCount = erroneousAttempts + 1)
			.verify()

		assertThatSoknad(innsendingsId)
			.hasStatus(ArkiveringsStatusDto.arkivert)
	}

	private val fileOfSize1mb = "/One_MB.pdf"

	@Test
	fun `Happy case - one submission from not logged in user ends up in the archive`() {

		val innsendingsUUID = UUID.randomUUID()
		val nologinSoknad = prepareNoLoginApplication(innsendingsUUID, mapOf(UUID.randomUUID().toString() to listOf(loadFile(fileOfSize1mb))))

		val soknadTestResponse = try {
			innsendingApi.sendInNoLoginApplication(innsendingsUUID, nologinSoknad)
		} catch (e: Exception) {
			throw e
		}

		assertTrue(soknadTestResponse.isSuccess)

		val innsendingsId = innsendingsUUID.toString()

		assertThatArkivMock()
			.hasFinishedEvent(innsendingsId)
			.hasEntityInArchive(innsendingsId)
			.hasCallCountInArchive(innsendingsId, expectedCount = 1)
			.verify()

		assertThatSoknad(innsendingsId)
			.hasStatus(ArkiveringsStatusDto.arkivert)
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

	private fun prepareNoLoginApplication(innsendingsId: UUID, vedleggMap: Map<String, List<File>>): SubmitApplicationRequest {
		val attachments = vedleggMap.map { (attachmentId, files) ->
			val fileIds = files.map { file ->
				innsendingApi.lastOppNoLoginFil(innsendingsId.toString(), attachmentId, file).getOrThrow().id
			}
			AttachmentDto(
				attachmentCode = generateVedleggsnr(),
				label = "Inntektsopplysninger for selvstendig næringsdrivende og frilansere som skal ha foreldrepenger eller svangerskapspenger.",
				uploadStatus = OpplastingsStatusDto.lastetOpp,
				title = "Vedleggseksempel",
				description = "Dette er opplysninger som er nødvendig for beregning av utbetaling av foreldrepenger eller svangerskapspenger.",
				fileIds = fileIds,
			)
		}
		return SubmitApplicationRequestBuilder(
			brukerId = testpersonid,
			status = SoknadsStatusDto.utfylt,
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
