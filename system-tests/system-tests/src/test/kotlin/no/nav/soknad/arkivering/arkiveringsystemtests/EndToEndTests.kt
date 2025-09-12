package no.nav.soknad.arkivering.arkiveringsystemtests

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import no.nav.soknad.arkivering.LoadTests
import no.nav.soknad.arkivering.arkiveringsystemtests.environment.EmbeddedDockerImages
import no.nav.soknad.arkivering.dto.SafResponses
import no.nav.soknad.arkivering.innsending.*
import no.nav.soknad.arkivering.innsending.model.ArkiveringsStatusDto
import no.nav.soknad.arkivering.innsending.model.Mimetype
import no.nav.soknad.arkivering.innsending.model.SkjemaDokumentDtoV2
import no.nav.soknad.arkivering.innsending.model.SkjemaDtoV2
import no.nav.soknad.arkivering.innsending.model.SoknadsStatusDto
import no.nav.soknad.arkivering.innsending.model.VisningsType
import no.nav.soknad.arkivering.utils.SkjemaDokumentDtoV2TestBuilder
import no.nav.soknad.arkivering.utils.retry
import no.nav.soknad.innsending.utils.builders.SkjemaDtoV2TestBuilder
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.File
import java.util.*
import kotlin.io.path.createTempFile

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
	fun `Happy case - one file ends up in the archive`() {
		val soknadTestdata = innsendingApi.opprettEttersending()
		val innsendingsId = soknadTestdata.innsendingsId

		soknadTestdata.vedleggsliste()
			.verifyHasSize(1)
			.lastOppFil(0, "OneHundred_KB.pdf")

		innsendingApi.sendInn(soknadTestdata)

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

		innsendingApi.sendInn(soknadTestdata)

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
		innsendingApi.sendInn(soknadTestdata)

		assertThatArkivMock()
			.hasFailureEvent(innsendingsId, 150_000L)
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
		innsendingApi.sendInn(soknadTestdata)

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
		innsendingApi.sendInn(soknadTestdata)

		assertThatArkivMock()
			.hasFinishedEvent(innsendingsId)
			.verify()

		assertThatSoknad(innsendingsId)
			.hasStatus(ArkiveringsStatusDto.ikkeSatt)
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
		innsendingApi.sendInn(soknadTestdata)

		assertThatArkivMock()
			.hasCallCountInArchive(innsendingsId, expectedCount = 1)
			.verify()

		assertThatSoknad(innsendingsId)
			.hasStatus(ArkiveringsStatusDto.ikkeSatt)
	}

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
		innsendingApi.sendInn(soknadTestdata)
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
		innsendingApi.sendInn(soknadTestdata)

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
		innsendingApi.sendInn(soknadTestdata)

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

		val nologinSoknad = prepareNoLoginSoknad(mapOf(UUID.randomUUID().toString() to listOf(loadFile(fileOfSize1mb))))

		val soknadTestResponse = innsendingApi.lagreOgSendInnNoLoginSoknad(nologinSoknad)

		assertTrue(soknadTestResponse.isSuccess)

		val innsendingsId = nologinSoknad.innsendingsId!!

		assertThatArkivMock()
			.hasFinishedEvent(innsendingsId)
			.hasEntityInArchive(innsendingsId)
			.hasCallCountInArchive(innsendingsId, expectedCount = 1)
			.verify()

		assertThatSoknad(innsendingsId)
			.hasStatus(ArkiveringsStatusDto.arkivert)
	}


	@Test
	fun `Happy case - upload one file and then deletes it`() {

		val innsendingsId = UUID.randomUUID().toString()
		val vedleggsId = UUID.randomUUID().toString()

		val uploadResponse = innsendingApi.lastOppNoLoginFil(innsendingsId, vedleggsId, loadFile(fileOfSize1mb))
		assertTrue(uploadResponse.isSuccess)

		val fileId = uploadResponse.getOrThrow().filId
		assertTrue { fileId.toString().isNotEmpty() }

		val deleteResponse = innsendingApi.slettNoLoginFil(innsendingsId, fileId.toString())
		assertTrue(deleteResponse.isSuccess)
	}

	// Lagster opp filer på vedlegg til søknad, og returnerer SkjemaDtoV2 klar for innsending
	private fun prepareNoLoginSoknad(vedleggMap: Map<String, List<File>>): SkjemaDtoV2 {
		val brukerId = testpersonid
		val innsendingsId = UUID.randomUUID().toString()

		val vedleggsListe: List<SkjemaDokumentDtoV2> = lastOppFilerTilSoknad(innsendingsId, vedleggMap) // returnerer map med fyllutVedleggIds til liste med lagringsId for opplastede filer til vedlegg

		val skjemDtoV2 = SkjemaDtoV2TestBuilder(
			brukerId = brukerId,
			innsendingsId = innsendingsId,
			status = SoknadsStatusDto.utfylt,
			visningsType = VisningsType.nologin,
		)
			.medVedlegg(vedleggsListe)
			.build()

		return skjemDtoV2
	}


	private fun lastOppFilerTilSoknad(innsendingsId: String, vedleggMap: Map<String, List<File>>) = runBlocking {
		val vedleggListe = vedleggMap
			.mapValues { (vedleggRef, files) ->
				// Upload all files for this vedleggRef
				lastOppFilerTilVedlegg(innsendingsId, vedleggRef, files)
					.map { it.filId.toString() } // extract just the fileId
			}

		vedleggListe
			.mapKeys {
				SkjemaDokumentDtoV2TestBuilder(
					tittel = "Vedleggseksempel", mimetype = Mimetype.applicationSlashPdf, formioId = it.key
				)
					.withFilIdListe(it.value)
					.build()
			}.keys
	}.toList()

	private fun sendInnSoknader_NoLogin(nologinSoknader: List<SkjemaDtoV2>) = runBlocking {
		nologinSoknader.map{ async { runCatching { sendInnSoknad(it) } }}.awaitAll()
	}

	private fun lastOppFilerTilVedlegg(innsendingsId: String, vedleggRef: String, files: List<File>) = runBlocking {
		files
			.map { file ->
				async {
					lastOppEnFil(
						innsendingsId = innsendingsId,
						vedleggRef = vedleggRef,
						file = file
					).getOrThrow() // unwrap Result or throw
				}
			}
			.awaitAll()
	}

	private suspend fun sendInnSoknad(nologinSoknad: SkjemaDtoV2) {
		return withContext(Dispatchers.IO) {
			retry(3, logThrowable = logThrowableAsWarning("${nologinSoknad.innsendingsId}: Feil ved innsending")) { innsendingApi.lagreOgSendInnNoLoginSoknad(nologinSoknad) }}
	}


	private fun lastOppEnFil(innsendingsId: String, vedleggRef: String, file: File) =
		innsendingApi.lastOppNoLoginFil(innsendingsId, vedleggRef, file)
			.onSuccess { System.out.println("Lastet opp filId=${it.filId} til vedleggRef=$vedleggRef for innsendingsId=$innsendingsId") }
			.onFailure { throw it }

	private fun logThrowableAsWarning(message: String): (Throwable) -> Unit {
		return { t -> System.out.println("$message - ${t.message}") }
	}

	private fun loadFile(fileName: String): File {
		val resource = LoadTests::class.java.getResourceAsStream(fileName) ?: throw Exception("$fileName not found")
		val file = createTempFile().toFile()
		resource.use { input ->
			file.outputStream().use { output ->
				input.copyTo(output)
			}
		}
		return file
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
