package no.nav.soknad.arkivering.arkiveringsystemtests

import no.nav.soknad.arkivering.arkiveringsystemtests.environment.EmbeddedDockerImages
import no.nav.soknad.arkivering.arkiveringsystemtests.environment.setupMockedNetworkServices
import no.nav.soknad.arkivering.avroschemas.EventTypes.*
import no.nav.soknad.arkivering.innsending.*
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
		soknadsfillagerApi = SoknadsfillagerApi(filesApiWithoutOAuth2(config))
		soknadsmottakerApi = SoknadsmottakerApi(soknadApiWithoutOAuth2(config))
		//setupMockedNetworkServices(8093, "/graphql")
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
		verifyFileDeletedInFileStorage(key, fileId)
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
		verifyFileDeletedInFileStorage(key, fileId)
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
		verifyFileDeletedInFileStorage(key, fileId0)
		verifyFileDeletedInFileStorage(key, fileId1)
	}

	@Test
	fun `No files in file storage - Nothing is sent to the archive`() {
		val key = UUID.randomUUID().toString()
		val fileId = UUID.randomUUID().toString()
		val soknad = createSoknad(key, fileId)
		setNormalArchiveBehaviour(key)

		verifyFileNotFoundInFileStorage(key, fileId)
		sendDataToSoknadsmottaker(key, soknad)

		assertThatArkivMock()
			.hasNoEntityInArchive(key)
			.verify()
		verifyFileNotFoundInFileStorage(key, fileId)
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
		verifyFilePresentInFileStorage(key, fileId0)
		verifyFilePresentInFileStorage(key, fileId1)
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
		verifyFileDeletedInFileStorage(key, fileId)
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
		verifyFileDeletedInFileStorage(key, fileId)
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
		verifyFileDeletedInFileStorage(key, fileId)
	}

	@Test
	fun `Archive responds 200 but has wrong response body - Will retry until soknadsarkiverer gives up`() {
		val key = UUID.randomUUID().toString()
		val fileId = UUID.randomUUID().toString()
		val soknad = createSoknad(key, fileId)
		val moreAttemptsThanSoknadsarkivererWillPerform = attemptsThanSoknadsarkivererWillPerform + 1

		sendFilesToFileStorage(key, fileId)
		mockArchiveRespondsWithErroneousBodyForXAttempts(key, moreAttemptsThanSoknadsarkivererWillPerform)
		sendDataToSoknadsmottaker(key, soknad)

		assertThatArkivMock()
			.hasEntityInArchive(key)
			.hasCallCountInArchive(key, expectedCount = attemptsThanSoknadsarkivererWillPerform)
			.verify()
		verifyFilePresentInFileStorage(key, fileId)
	}

	@DisabledIfSystemProperty(named = "targetEnvironment", matches = externalEnvironments)
	@Test
	fun `Put main event on Kafka when Soknadsarkiverer is down - will start up and send to the archive`() {
		val key = UUID.randomUUID().toString()
		val fileId = UUID.randomUUID().toString()
		setNormalArchiveBehaviour(key)

		sendFilesToFileStorage(key, fileId)

		shutDownSoknadsarkiverer()
		putMainEventOnKafkaTopic(key, fileId)
		val verifier = assertThatArkivMock()
			.hasEntityInArchive(key)
			.hasCallCountInArchive(key, expectedCount = 1)
		startUpSoknadsarkiverer()

		verifier.verify()
		verifyFileDeletedInFileStorage(key, fileId)
	}

	@DisabledIfSystemProperty(named = "targetEnvironment", matches = externalEnvironments)
	@Test
	fun `Put main event and processing events on Kafka when Soknadsarkiverer is down - will start up and send to the archive`() {
		val key = UUID.randomUUID().toString()
		val fileId = UUID.randomUUID().toString()
		setNormalArchiveBehaviour(key)

		sendFilesToFileStorage(key, fileId)

		shutDownSoknadsarkiverer()
		putMainEventOnKafkaTopic(key, fileId)
		putProcessingEventOnKafkaTopic(key, RECEIVED, STARTED, STARTED)
		val verifier = assertThatArkivMock()
			.hasEntityInArchive(key)
			.hasCallCountInArchive(key, expectedCount = 1)
		startUpSoknadsarkiverer()

		verifier.verify()
		verifyFileDeletedInFileStorage(key, fileId)
	}

	@DisabledIfSystemProperty(named = "targetEnvironment", matches = externalEnvironments)
	@Test
	fun `Soknadsarkiverer restarts before finishing to put main event in the archive - will pick event up and send to the archive`() {
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
		verifyFileDeletedInFileStorage(key, fileId)
	}

	@DisabledIfSystemProperty(named = "targetEnvironment", matches = externalEnvironments)
	@Test
	fun `Put finished main event on Kafka and send a new main event when Soknadsarkiverer is down - only the new main event ends up in the archive`() {
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
		putMainEventOnKafkaTopic(finishedKey, finishedFileId)
		putProcessingEventOnKafkaTopic(finishedKey, RECEIVED, STARTED, ARCHIVED, FINISHED)
		sendDataToSoknadsmottaker(newKey, newSoknad)
		val verifier = assertThatArkivMock()
			.hasEntityInArchive(newKey)
			.hasCallCountInArchive(newKey, expectedCount = 1)
			.hasNoEntityInArchive(finishedKey)
		startUpSoknadsarkiverer()

		verifier.verify()
		verifyFilePresentInFileStorage(newKey, finishedFileId)
		verifyFileDeletedInFileStorage(newKey, newFileId)
	}


	private fun sendFilesToFileStorage(innsendingId: String, fileId: String) {
		soknadsfillagerApi.sendFilesToFileStorage(innsendingId, fileId)
	}

	private fun verifyFilePresentInFileStorage(innsendingId: String, fileId: String) {
		loopAndVerify(1, { getNumberOfFilesInFilestorage(innsendingId, fileId, "ok") })
	}

	private fun verifyFileNotFoundInFileStorage(innsendingId: String, fileId: String) {
		loopAndVerify(1, { getNumberOfFilesInFilestorage(innsendingId, fileId, "not-found") })
	}

	private fun verifyFileDeletedInFileStorage(innsendingId: String, fileId: String) {
		loopAndVerify(1, { getNumberOfFilesInFilestorage(innsendingId, fileId, "deleted") })
	}

	private fun getNumberOfFilesInFilestorage(innsendingId: String, fileId: String, expectedStatus: String): Int {
			val files = soknadsfillagerApi.getFiles(innsendingId, fileId, true)
			return files.filter { it.status == expectedStatus }.size
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
