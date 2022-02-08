package no.nav.soknad.arkivering.arkiveringsystemtests

import no.nav.soknad.arkivering.arkiveringsystemtests.environment.EmbeddedDockerImages
import no.nav.soknad.arkivering.avroschemas.EventTypes.*
import no.nav.soknad.arkivering.innsending.SoknadsfillagerApi
import no.nav.soknad.arkivering.innsending.SoknadsmottakerApi
import no.nav.soknad.arkivering.innsending.performDeleteCall
import no.nav.soknad.arkivering.innsending.performPutCall
import no.nav.soknad.arkivering.soknadsfillager.infrastructure.ClientException
import no.nav.soknad.arkivering.soknadsmottaker.model.DocumentData
import no.nav.soknad.arkivering.soknadsmottaker.model.Soknad
import no.nav.soknad.arkivering.soknadsmottaker.model.Varianter
import no.nav.soknad.arkivering.utils.createSoknad
import no.nav.soknad.arkivering.utils.loopAndVerify
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledIfSystemProperty
import org.slf4j.LoggerFactory
import java.util.*

class EndToEndTests : SystemTestBase() {
	private val logger = LoggerFactory.getLogger(javaClass)

	private val embeddedDockerImages = EmbeddedDockerImages()
	private lateinit var soknadsfillagerApi: SoknadsfillagerApi
	private lateinit var soknadsmottakerApi: SoknadsmottakerApi

	@BeforeAll
	fun setup() {
		if (!isExternalEnvironment) {
			env.addEmbeddedDockerImages(embeddedDockerImages)
			embeddedDockerImages.startContainers()
		}

		setUp()
		soknadsfillagerApi = SoknadsfillagerApi(config)
		soknadsmottakerApi = SoknadsmottakerApi(config)
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
		val soknad = createSoknad(key, fileId)
		setNormalArchiveBehaviour(key)

		sendFilesToFileStorage(key, fileId)
		sendDataToSoknadsmottaker(key, soknad)

		assertThatArkivMock()
			.hasEntityInArchive(key)
			.hasCallCountInArchive(key, expectedCount = 1)
			.verify()
		verifyAbsenceInFileStorage(key, fileId)
	}

	@Test
	fun `Poison pill followed by proper message - one file ends up in the archive`() {
		val key = UUID.randomUUID().toString()
		val fileId = UUID.randomUUID().toString()
		val soknad = createSoknad(key, fileId)
		setNormalArchiveBehaviour(key)

		putPoisonPillOnKafkaTopic(UUID.randomUUID().toString())
		sendFilesToFileStorage(key, fileId)
		sendDataToSoknadsmottaker(key, soknad)

		assertThatArkivMock()
			.hasEntityInArchive(key)
			.hasCallCountInArchive(key, expectedCount = 1)
			.verify()
		verifyAbsenceInFileStorage(key, fileId)
	}

	@Test
	fun `Happy case - several files in file storage - one file ends up in the archive`() {
		val key = UUID.randomUUID().toString()
		val fileId0 = UUID.randomUUID().toString()
		val fileId1 = UUID.randomUUID().toString()
		val soknad = Soknad(key, false, "personId", "tema",
			listOf(
				DocumentData("NAV 10-07.17", true, "Søknad om refusjon av reiseutgifter - bil",
					listOf(Varianter(fileId0, "application/pdf", "filnavn", "PDFA"))),

				DocumentData("NAV 10-07.17", false, "Søknad om refusjon av reiseutgifter - bil",
					listOf(Varianter(fileId1, "application/pdf", "filnavn", "PDFA")))
			))
		setNormalArchiveBehaviour(key)

		sendFilesToFileStorage(key, fileId0)
		sendFilesToFileStorage(key, fileId1)
		sendDataToSoknadsmottaker(key, soknad)

		assertThatArkivMock()
			.hasEntityInArchive(key)
			.hasCallCountInArchive(key, expectedCount = 1)
			.verify()
		verifyAbsenceInFileStorage(key, fileId0)
		verifyAbsenceInFileStorage(key, fileId1)
	}

	@Test
	fun `No files in file storage - Nothing is sent to the archive`() {
		val key = UUID.randomUUID().toString()
		val fileId = UUID.randomUUID().toString()
		val soknad = createSoknad(key, fileId)
		setNormalArchiveBehaviour(key)

		verifyAbsenceInFileStorage(key, fileId, 404)
		sendDataToSoknadsmottaker(key, soknad)

		assertThatArkivMock()
			.hasNoEntityInArchive(key)
			.verify()
		verifyAbsenceInFileStorage(key, fileId, 404)
	}

	@Test
	fun `Several Hovedskjemas - Nothing is sent to the archive`() {
		val key = UUID.randomUUID().toString()
		val fileId0 = UUID.randomUUID().toString()
		val fileId1 = UUID.randomUUID().toString()
		val soknad = Soknad(key, false, "personId", "tema",
			listOf(
				DocumentData("NAV 10-07.17", true, "Søknad om refusjon av reiseutgifter - bil",
					listOf(Varianter(fileId0, "application/pdf", "filnavn", "PDFA"))),

				DocumentData("NAV 10-07.17", true, "Søknad om refusjon av reiseutgifter - bil",
					listOf(Varianter(fileId1, "application/pdf", "filnavn", "PDFA")))
			))
		setNormalArchiveBehaviour(key)

		sendFilesToFileStorage(key, fileId0)
		sendFilesToFileStorage(key, fileId1)
		sendDataToSoknadsmottaker(key, soknad)

		assertThatArkivMock()
			.hasNoEntityInArchive(key)
			.verify()
		verifyPresenceInFileStorage(key, fileId0)
		verifyPresenceInFileStorage(key, fileId1)
	}

	@Test
	fun `Archive responds 404 on first two attempts - Works on third attempt`() {
		val key = UUID.randomUUID().toString()
		val erroneousAttempts = 2
		val fileId = UUID.randomUUID().toString()
		val soknad = createSoknad(key, fileId)

		sendFilesToFileStorage(key, fileId)
		mockArchiveRespondsWithCodeForXAttempts(key, 404, erroneousAttempts)
		sendDataToSoknadsmottaker(key, soknad)

		assertThatArkivMock()
			.hasEntityInArchive(key)
			.hasCallCountInArchive(key, expectedCount = erroneousAttempts + 1)
			.verify()
		verifyAbsenceInFileStorage(key, fileId)
	}

	@Test
	fun `Archive responds 500 on first attempt - Works on second attempt`() {
		val key = UUID.randomUUID().toString()
		val erroneousAttempts = 1
		val fileId = UUID.randomUUID().toString()
		val soknad = createSoknad(key, fileId)

		sendFilesToFileStorage(key, fileId)
		mockArchiveRespondsWithCodeForXAttempts(key, 500, erroneousAttempts)
		sendDataToSoknadsmottaker(key, soknad)

		assertThatArkivMock()
			.hasEntityInArchive(key)
			.hasCallCountInArchive(key, expectedCount = erroneousAttempts + 1)
			.verify()
		verifyAbsenceInFileStorage(key, fileId)
	}

	@Test
	fun `Archive responds 200 but has wrong response body - Will retry`() {
		val key = UUID.randomUUID().toString()
		val erroneousAttempts = 3
		val fileId = UUID.randomUUID().toString()
		val soknad = createSoknad(key, fileId)

		sendFilesToFileStorage(key, fileId)
		mockArchiveRespondsWithErroneousBodyForXAttempts(key, erroneousAttempts)
		sendDataToSoknadsmottaker(key, soknad)

		assertThatArkivMock()
			.hasEntityInArchive(key)
			.hasCallCountInArchive(key, expectedCount = erroneousAttempts + 1)
			.verify()
		verifyAbsenceInFileStorage(key, fileId)
	}

	@Test
	fun `Archive responds 200 but has wrong response body - Will retry until soknadsarkiverer gives up`() {
		val key = UUID.randomUUID().toString()
		val fileId = UUID.randomUUID().toString()
		val soknad = createSoknad(key, fileId)
		val moreAttemptsThanSoknadsarkivererWillPerform = attemptsThanSoknadsarkivererWillPerform + 1

		resetArchiveDatabase()
		sendFilesToFileStorage(key, fileId)
		mockArchiveRespondsWithErroneousBodyForXAttempts(key, moreAttemptsThanSoknadsarkivererWillPerform)
		sendDataToSoknadsmottaker(key, soknad)

		assertThatArkivMock()
			.hasEntityInArchive(key)
			.hasCallCountInArchive(key, expectedCount = attemptsThanSoknadsarkivererWillPerform)
			.verify()
		verifyPresenceInFileStorage(key, fileId)
	}

	@DisabledIfSystemProperty(named = "targetEnvironment", matches = externalEnvironments)
	@Test
	fun `Put input event on Kafka when Soknadsarkiverer is down - will start up and send to the archive`() {
		val key = UUID.randomUUID().toString()
		val fileId = UUID.randomUUID().toString()
		setNormalArchiveBehaviour(key)

		sendFilesToFileStorage(key, fileId)

		shutDownSoknadsarkiverer()
		putInputEventOnKafkaTopic(key, fileId)
		val verifier = assertThatArkivMock()
			.hasEntityInArchive(key)
			.hasCallCountInArchive(key, expectedCount = 1)
		startUpSoknadsarkiverer()

		verifier.verify()
		verifyAbsenceInFileStorage(key, fileId)
	}

	@DisabledIfSystemProperty(named = "targetEnvironment", matches = externalEnvironments)
	@Test
	fun `Put input event and processing events on Kafka when Soknadsarkiverer is down - will start up and send to the archive`() {
		val key = UUID.randomUUID().toString()
		val fileId = UUID.randomUUID().toString()
		setNormalArchiveBehaviour(key)

		sendFilesToFileStorage(key, fileId)

		shutDownSoknadsarkiverer()
		putInputEventOnKafkaTopic(key, fileId)
		putProcessingEventOnKafkaTopic(key, RECEIVED, STARTED, STARTED)
		val verifier = assertThatArkivMock()
			.hasEntityInArchive(key)
			.hasCallCountInArchive(key, expectedCount = 1)
		startUpSoknadsarkiverer()

		verifier.verify()
		verifyAbsenceInFileStorage(key, fileId)
	}

	@DisabledIfSystemProperty(named = "targetEnvironment", matches = externalEnvironments)
	@Test
	fun `Soknadsarkiverer restarts before finishing to put input event in the archive - will pick event up and send to the archive`() {
		val key = UUID.randomUUID().toString()
		val fileId = UUID.randomUUID().toString()
		val soknad = createSoknad(key, fileId)

		sendFilesToFileStorage(key, fileId)
		mockArchiveRespondsWithCodeForXAttempts(key, 404, attemptsThanSoknadsarkivererWillPerform + 1)
		sendDataToSoknadsmottaker(key, soknad)
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
		verifyAbsenceInFileStorage(key, fileId)
	}

	@DisabledIfSystemProperty(named = "targetEnvironment", matches = externalEnvironments)
	@Test
	fun `Put finished input event on Kafka and send a new input event when Soknadsarkiverer is down - only the new input event ends up in the archive`() {
		val finishedKey = UUID.randomUUID().toString()
		val newKey = UUID.randomUUID().toString()
		val finishedFileId = UUID.randomUUID().toString()
		val newFileId = UUID.randomUUID().toString()

		val newSoknad = createSoknad(newKey, newFileId)
		setNormalArchiveBehaviour(finishedKey)
		setNormalArchiveBehaviour(newKey)

		sendFilesToFileStorage(newKey, finishedFileId)
		sendFilesToFileStorage(newKey, newFileId)

		shutDownSoknadsarkiverer()
		putInputEventOnKafkaTopic(finishedKey, finishedFileId)
		putProcessingEventOnKafkaTopic(finishedKey, RECEIVED, STARTED, ARCHIVED, FINISHED)
		sendDataToSoknadsmottaker(newKey, newSoknad)
		val verifier = assertThatArkivMock()
			.hasEntityInArchive(newKey)
			.hasCallCountInArchive(newKey, expectedCount = 1)
			.hasNoEntityInArchive(finishedKey)
		startUpSoknadsarkiverer()

		verifier.verify()
		verifyPresenceInFileStorage(newKey, finishedFileId)
		verifyAbsenceInFileStorage(newKey, newFileId)
	}


	private fun sendFilesToFileStorage(innsendingId: String, fileId: String) {
		soknadsfillagerApi.sendFilesToFileStorage(innsendingId, fileId)
	}

	private fun verifyPresenceInFileStorage(innsendingId: String, fileId: String) {
		soknadsfillagerApi.checkFilesInFileStorage(innsendingId, fileId)
	}

	private fun verifyAbsenceInFileStorage(innsendingId: String, fileId: String, expectedStatusCode: Int = 410) {
		loopAndVerify(0, { getNumberOfFilesInFilestorage(innsendingId, fileId, expectedStatusCode) })
	}

	private fun getNumberOfFilesInFilestorage(innsendingId: String, fileId: String, expectedStatusCode: Int): Int {
		return try {
			verifyPresenceInFileStorage(innsendingId, fileId)
			1 // Files are present
		} catch (e: ClientException) {
			if (e.statusCode == expectedStatusCode)
				0 // No files are present
			else {
				logger.error("Unexpected status code: ${e.statusCode}", e)
				-1 // Return -1 as an error code
			}
		} catch (e: Exception) {
			logger.error("Unexpected exception", e)
			-1 // Return -1 as an error code
		}
	}

	private fun sendDataToSoknadsmottaker(key: String, soknad: Soknad) {
		logger.debug("$key: Sending to Soknadsmottaker for test '${Thread.currentThread().stackTrace[2].methodName}'")
		soknadsmottakerApi.sendDataToSoknadsmottaker(soknad)
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
