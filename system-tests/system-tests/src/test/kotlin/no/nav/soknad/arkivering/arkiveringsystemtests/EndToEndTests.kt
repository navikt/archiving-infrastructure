package no.nav.soknad.arkivering.arkiveringsystemtests

import no.nav.soknad.arkivering.arkiveringsystemtests.environment.EmbeddedDockerImages
import no.nav.soknad.arkivering.avroschemas.EventTypes.*
import no.nav.soknad.arkivering.dto.InnsendtDokumentDto
import no.nav.soknad.arkivering.dto.InnsendtVariantDto
import no.nav.soknad.arkivering.dto.SoknadInnsendtDto
import no.nav.soknad.arkivering.innsending.*
import no.nav.soknad.arkivering.utils.createDto
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledIfSystemProperty
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.util.*

class EndToEndTests : SystemTestBase() {
	private val logger = LoggerFactory.getLogger(javaClass)

	private val embeddedDockerImages = EmbeddedDockerImages()

	@BeforeAll
	fun setup() {
		if (!isExternalEnvironment) {
			env.addEmbeddedDockerImages(embeddedDockerImages)
			embeddedDockerImages.startContainers()
		}

		setUp()
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
		val key = UUID.randomUUID().toString()
		val fileId = UUID.randomUUID().toString()
		val dto = createDto(fileId)
		setNormalArchiveBehaviour(key)

		sendFilesToFileStorage(fileId)
		sendDataToSoknadsmottaker(key, dto)

		assertThatArkivMock()
			.hasEntityInArchive(key)
			.hasCallCountInArchive(key, expectedCount = 1)
			.verify()
		pollAndVerifyDataInFileStorage(fileId, 0)
	}

	@Test
	fun `Poison pill followed by proper message - one file ends up in the archive`() {
		val key = UUID.randomUUID().toString()
		val fileId = UUID.randomUUID().toString()
		val dto = createDto(fileId)
		setNormalArchiveBehaviour(key)

		putPoisonPillOnKafkaTopic(UUID.randomUUID().toString())
		sendFilesToFileStorage(fileId)
		sendDataToSoknadsmottaker(key, dto)

		assertThatArkivMock()
			.hasEntityInArchive(key)
			.hasCallCountInArchive(key, expectedCount = 1)
			.verify()
		pollAndVerifyDataInFileStorage(fileId, 0)
	}

	@Test
	fun `Happy case - several files in file storage - one file ends up in the archive`() {
		val key = UUID.randomUUID().toString()
		val fileId0 = UUID.randomUUID().toString()
		val fileId1 = UUID.randomUUID().toString()
		val dto = SoknadInnsendtDto(UUID.randomUUID().toString(), false, "personId", "tema", LocalDateTime.now(),
			listOf(
				InnsendtDokumentDto("NAV 10-07.17", true, "Søknad om refusjon av reiseutgifter - bil",
					listOf(InnsendtVariantDto(fileId0, null, "filnavn", "1024", "variantformat", "PDFA"))),

				InnsendtDokumentDto("NAV 10-07.17", false, "Søknad om refusjon av reiseutgifter - bil",
					listOf(InnsendtVariantDto(fileId1, null, "filnavn", "1024", "variantformat", "PDFA")))
			))
		setNormalArchiveBehaviour(key)

		sendFilesToFileStorage(fileId0)
		sendFilesToFileStorage(fileId1)
		sendDataToSoknadsmottaker(key, dto)

		assertThatArkivMock()
			.hasEntityInArchive(key)
			.hasCallCountInArchive(key, expectedCount = 1)
			.verify()
		pollAndVerifyDataInFileStorage(fileId0, 0)
		pollAndVerifyDataInFileStorage(fileId1, 0)
	}

	@Test
	fun `No files in file storage - Nothing is sent to the archive`() {
		val key = UUID.randomUUID().toString()
		val fileId = UUID.randomUUID().toString()
		val dto = createDto(fileId)
		setNormalArchiveBehaviour(key)

		pollAndVerifyDataInFileStorage(fileId, 0)
		sendDataToSoknadsmottaker(key, dto)

		assertThatArkivMock()
			.hasNoEntityInArchive(key)
			.verify()
		pollAndVerifyDataInFileStorage(fileId, 0)
	}

	@Test
	fun `Several Hovedskjemas - Nothing is sent to the archive`() {
		val key = UUID.randomUUID().toString()
		val fileId0 = UUID.randomUUID().toString()
		val fileId1 = UUID.randomUUID().toString()
		val dto = SoknadInnsendtDto(UUID.randomUUID().toString(), false, "personId", "tema", LocalDateTime.now(),
			listOf(
				InnsendtDokumentDto("NAV 10-07.17", true, "Søknad om refusjon av reiseutgifter - bil",
					listOf(InnsendtVariantDto(fileId0, null, "filnavn", "1024", "variantformat", "PDFA"))),

				InnsendtDokumentDto("NAV 10-07.17", true, "Søknad om refusjon av reiseutgifter - bil",
					listOf(InnsendtVariantDto(fileId1, null, "filnavn", "1024", "variantformat", "PDFA")))
			))
		setNormalArchiveBehaviour(key)

		sendFilesToFileStorage(fileId0)
		sendFilesToFileStorage(fileId1)
		sendDataToSoknadsmottaker(key, dto)

		assertThatArkivMock()
			.hasNoEntityInArchive(key)
			.verify()
		pollAndVerifyDataInFileStorage(fileId0, 1)
		pollAndVerifyDataInFileStorage(fileId1, 1)
	}

	@Test
	fun `Archive responds 404 on first two attempts - Works on third attempt`() {
		val key = UUID.randomUUID().toString()
		val erroneousAttempts = 2
		val fileId = UUID.randomUUID().toString()
		val dto = createDto(fileId)

		sendFilesToFileStorage(fileId)
		mockArchiveRespondsWithCodeForXAttempts(key, 404, erroneousAttempts)
		sendDataToSoknadsmottaker(key, dto)

		assertThatArkivMock()
			.hasEntityInArchive(key)
			.hasCallCountInArchive(key, expectedCount = erroneousAttempts + 1)
			.verify()
		pollAndVerifyDataInFileStorage(fileId, 0)
	}

	@Test
	fun `Archive responds 500 on first attempt - Works on second attempt`() {
		val key = UUID.randomUUID().toString()
		val erroneousAttempts = 1
		val fileId = UUID.randomUUID().toString()
		val dto = createDto(fileId)

		sendFilesToFileStorage(fileId)
		mockArchiveRespondsWithCodeForXAttempts(key, 500, erroneousAttempts)
		sendDataToSoknadsmottaker(key, dto)

		assertThatArkivMock()
			.hasEntityInArchive(key)
			.hasCallCountInArchive(key, expectedCount = erroneousAttempts + 1)
			.verify()
		pollAndVerifyDataInFileStorage(fileId, 0)
	}

	@Test
	fun `Archive responds 200 but has wrong response body - Will retry`() {
		val key = UUID.randomUUID().toString()
		val erroneousAttempts = 3
		val fileId = UUID.randomUUID().toString()
		val dto = createDto(fileId)

		sendFilesToFileStorage(fileId)
		mockArchiveRespondsWithErroneousBodyForXAttempts(key, erroneousAttempts)
		sendDataToSoknadsmottaker(key, dto)

		assertThatArkivMock()
			.hasEntityInArchive(key)
			.hasCallCountInArchive(key, expectedCount = erroneousAttempts + 1)
			.verify()
		pollAndVerifyDataInFileStorage(fileId, 0)
	}

	@Test
	fun `Archive responds 200 but has wrong response body - Will retry until soknadsarkiverer gives up`() {
		val key = UUID.randomUUID().toString()
		val fileId = UUID.randomUUID().toString()
		val dto = createDto(fileId)
		val moreAttemptsThanSoknadsarkivererWillPerform = attemptsThanSoknadsarkivererWillPerform + 1

		resetArchiveDatabase()
		sendFilesToFileStorage(fileId)
		mockArchiveRespondsWithErroneousBodyForXAttempts(key, moreAttemptsThanSoknadsarkivererWillPerform)
		sendDataToSoknadsmottaker(key, dto)

		assertThatArkivMock()
			.hasEntityInArchive(key)
			.hasCallCountInArchive(key, expectedCount = attemptsThanSoknadsarkivererWillPerform)
			.verify()
		pollAndVerifyDataInFileStorage(fileId, 1)
	}

	@DisabledIfSystemProperty(named = "targetEnvironment", matches = externalEnvironments)
	@Test
	fun `Put input event on Kafka when Soknadsarkiverer is down - will start up and send to the archive`() {
		val key = UUID.randomUUID().toString()
		val fileId = UUID.randomUUID().toString()
		val innsendingsId = UUID.randomUUID().toString()
		setNormalArchiveBehaviour(key)

		sendFilesToFileStorage(fileId)

		shutDownSoknadsarkiverer()
		putInputEventOnKafkaTopic(key, innsendingsId, fileId)
		val verifier = assertThatArkivMock()
			.hasEntityInArchive(key)
			.hasCallCountInArchive(key, expectedCount = 1)
		startUpSoknadsarkiverer()

		verifier.verify()
		pollAndVerifyDataInFileStorage(fileId, 0)
	}

	@DisabledIfSystemProperty(named = "targetEnvironment", matches = externalEnvironments)
	@Test
	fun `Put input event and processing events on Kafka when Soknadsarkiverer is down - will start up and send to the archive`() {
		val key = UUID.randomUUID().toString()
		val fileId = UUID.randomUUID().toString()
		val innsendingsId = UUID.randomUUID().toString()
		setNormalArchiveBehaviour(key)

		sendFilesToFileStorage(fileId)

		shutDownSoknadsarkiverer()
		putInputEventOnKafkaTopic(key, innsendingsId, fileId)
		putProcessingEventOnKafkaTopic(key, RECEIVED, STARTED, STARTED)
		val verifier = assertThatArkivMock()
			.hasEntityInArchive(key)
			.hasCallCountInArchive(key, expectedCount = 1)
		startUpSoknadsarkiverer()

		verifier.verify()
		pollAndVerifyDataInFileStorage(fileId, 0)
	}

	@DisabledIfSystemProperty(named = "targetEnvironment", matches = externalEnvironments)
	@Test
	fun `Soknadsarkiverer restarts before finishing to put input event in the archive - will pick event up and send to the archive`() {
		val key = UUID.randomUUID().toString()
		val fileId = UUID.randomUUID().toString()
		val dto = createDto(fileId)

		sendFilesToFileStorage(fileId)
		mockArchiveRespondsWithCodeForXAttempts(key, 404, attemptsThanSoknadsarkivererWillPerform + 1)
		sendDataToSoknadsmottaker(key, dto)
		assertThatArkivMock()
			.hasCallCountInArchive(key, expectedCount = 1)
			.verify()

		shutDownSoknadsarkiverer()
		setNormalArchiveBehaviour(key)
		val verifier = assertThatArkivMock()
			.hasEntityInArchive(key)
			.hasCallCountInArchive(key, expectedCount = 2)
		startUpSoknadsarkiverer()

		verifier.verify()
		pollAndVerifyDataInFileStorage(fileId, 0)
	}

	@DisabledIfSystemProperty(named = "targetEnvironment", matches = externalEnvironments)
	@Test
	fun `Put finished input event on Kafka and send a new input event when Soknadsarkiverer is down - only the new input event ends up in the archive`() {
		val finishedKey = UUID.randomUUID().toString()
		val newKey = UUID.randomUUID().toString()
		val finishedFileId = UUID.randomUUID().toString()
		val newFileId = UUID.randomUUID().toString()
		val finishedInnsendingsId = UUID.randomUUID().toString()
		val newInnsendingsId = UUID.randomUUID().toString()

		val newDto = createDto(newFileId, newInnsendingsId)
		setNormalArchiveBehaviour(finishedKey)
		setNormalArchiveBehaviour(newKey)

		sendFilesToFileStorage(finishedFileId)
		sendFilesToFileStorage(newFileId)

		shutDownSoknadsarkiverer()
		putInputEventOnKafkaTopic(finishedKey, finishedInnsendingsId, finishedFileId)
		putProcessingEventOnKafkaTopic(finishedKey, RECEIVED, STARTED, ARCHIVED, FINISHED)
		sendDataToSoknadsmottaker(newKey, newDto)
		val verifier = assertThatArkivMock()
			.hasEntityInArchive(newKey)
			.hasCallCountInArchive(newKey, expectedCount = 1)
			.hasNoEntityInArchive(finishedInnsendingsId)
		startUpSoknadsarkiverer()

		verifier.verify()
		pollAndVerifyDataInFileStorage(finishedFileId, 1)
		pollAndVerifyDataInFileStorage(newFileId, 0)
	}


	private fun sendFilesToFileStorage(id: String) {
		sendFilesToFileStorage(id, config)
	}

	private fun pollAndVerifyDataInFileStorage(uuid: String, expectedNumberOfHits: Int) {
		pollAndVerifyDataInFileStorage(uuid, expectedNumberOfHits, config)
	}

	private fun sendDataToSoknadsmottaker(key: String, dto: SoknadInnsendtDto) {
		logger.debug("$key: Sending to Soknadsmottaker for test '${Thread.currentThread().stackTrace[2].methodName}'")
		sendDataToSoknadsmottaker(key, dto, false, config)
	}

	private fun shutDownSoknadsarkiverer() {
		embeddedDockerImages.shutDownSoknadsarkiverer()
	}

	private fun startUpSoknadsarkiverer() {
		embeddedDockerImages.startUpSoknadsarkiverer()
		val url = env.getUrlForSoknadsarkiverer() + "/internal/health"
		verifyComponentIsUp(url, "soknadsarkiverer")
	}


	private fun resetArchiveDatabase() {
		val url = env.getUrlForArkivMock() + "/rest/journalpostapi/v1/reset"
		performDeleteCall(url)
	}

	private fun setNormalArchiveBehaviour(uuid: String) {
		val url = env.getUrlForArkivMock() + "/arkiv-mock/response-behaviour/set-normal-behaviour/$uuid"
		performPutCall(url)
	}

	private fun mockArchiveRespondsWithCodeForXAttempts(uuid: String, status: Int, forAttempts: Int) {
		val url = env.getUrlForArkivMock() + "/arkiv-mock/response-behaviour/mock-response/$uuid/$status/$forAttempts"
		performPutCall(url)
	}

	private fun mockArchiveRespondsWithErroneousBodyForXAttempts(uuid: String, forAttempts: Int) {
		val url = env.getUrlForArkivMock() + "/arkiv-mock/response-behaviour/set-status-ok-with-erroneous-body/$uuid/$forAttempts"
		performPutCall(url)
	}
}
