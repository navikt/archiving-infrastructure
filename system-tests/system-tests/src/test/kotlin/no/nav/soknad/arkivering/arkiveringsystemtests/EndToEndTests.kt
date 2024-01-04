package no.nav.soknad.arkivering.arkiveringsystemtests

import no.nav.soknad.arkivering.arkiveringsystemtests.environment.EmbeddedDockerImages
import no.nav.soknad.arkivering.avroschemas.EventTypes.*
import no.nav.soknad.arkivering.avroschemas.Soknadstyper
import no.nav.soknad.arkivering.dto.FileResponses
import no.nav.soknad.arkivering.innsending.*
import no.nav.soknad.arkivering.soknadsmottaker.model.DocumentData
import no.nav.soknad.arkivering.soknadsmottaker.model.Soknad
import no.nav.soknad.arkivering.soknadsmottaker.model.Varianter
import no.nav.soknad.arkivering.utils.SoknadsBuilder
import no.nav.soknad.arkivering.utils.createSoknad
import no.nav.soknad.arkivering.utils.fnr
import no.nav.soknad.arkivering.utils.loopAndVerify
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.condition.DisabledIfSystemProperty
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.util.*

class EndToEndTests : SystemTestBase() {
	private val logger = LoggerFactory.getLogger(javaClass)

	private val embeddedDockerImages = EmbeddedDockerImages()
	private lateinit var soknadsfillagerApi: SoknadsfillagerApi
	private lateinit var soknadsmottakerApi: SoknadsmottakerApi

	private val kjoreliste: String = "NAV 11-12.10"
	private val sykepenger: String = "NAV 08-07.04D"
  private val titles: Map<String, String> = mapOf(kjoreliste to "Kjøreliste", sykepenger to "Søknad om sykepenger")
	@BeforeAll
	fun setup() {
		if (!isExternalEnvironment) {
			env.addEmbeddedDockerImages(embeddedDockerImages)
			embeddedDockerImages.startContainers()
		}

		setUp()
		soknadsfillagerApi = SoknadsfillagerApi(filesApiWithoutOAuth2(config))
		soknadsmottakerApi = SoknadsmottakerApi(soknadApiWithoutOAuth2(config))
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
		val skjemanummer = sykepenger
		val soknad = SoknadsBuilder()
			.withBehandlingsid(key)
			.withArkivtema("SYK")
			.withFodselsnummer(fnr)
			.withSoknadstype(Soknadstyper.SOKNAD)
			.withInnsendtDato(LocalDateTime.now())
			.withMottatteDokumenter(DocumentData(skjemanummer = skjemanummer, erHovedskjema = true, tittel = titles.get(sykepenger)?: "Dummy tittel",
				varianter = listOf(Varianter(id = fileId, mediaType = "application/pdf", filnavn = "$sykepenger.pdf", filtype = "PDFA"))))
			.build()

		setNormalArchiveBehaviour(key)

		if (skjemanummer == kjoreliste) sendFilesToFileStorage(key, fileId)
		sendDataToSoknadsmottaker(key, soknad)

		assertThatArkivMock()
			.hasEntityInArchive(key)
			.hasCallCountInArchive(key, expectedCount = 1)
			.verify()
	}


	@Test
	fun `Happy case - large attachment ends up in the archive`() {
		val key = UUID.randomUUID().toString()
		val fileId0 = UUID.randomUUID().toString()
		val fileId1 = UUID.randomUUID().toString()
		val fileId2 = UUID.randomUUID().toString()
		val skjemanummer = sykepenger
		val soknad = SoknadsBuilder()
			.withBehandlingsid(key)
			.withArkivtema("SYK")
			.withFodselsnummer(fnr)
			.withSoknadstype(Soknadstyper.SOKNAD)
			.withInnsendtDato(LocalDateTime.now())
			.withMottatteDokumenter(
				DocumentData(skjemanummer = skjemanummer, erHovedskjema = true, tittel = titles.get(sykepenger)?: "Dummy tittel",
				varianter = listOf(
					Varianter(id = fileId0, mediaType = "application/pdf", filnavn = "$sykepenger.pdf", filtype = "PDFA"))
				),
				DocumentData(skjemanummer = "L7", erHovedskjema = false, tittel = "Kvittering",
				varianter = listOf(
					Varianter(id = fileId1, mediaType = "application/pdf", filnavn = "kvittering.pdf", filtype = "PDFA"))
				),
				DocumentData(skjemanummer = "O2", erHovedskjema = false, tittel = "Vedlegg",
				varianter = listOf(
					Varianter(id = fileId2, mediaType = "application/pdf", filnavn = "vedlegg.pdf", filtype = "PDFA"))
			)
			)
			.build()

		setNormalArchiveBehaviour(key)

		setFileFetchBehaviour(fileId0, FileResponses.One_MB.name,-1)
		setFileFetchBehaviour(fileId1, FileResponses.OneHundred_KB.name,-1)
		setFileFetchBehaviour(fileId2, FileResponses.Fifty_50_MB.name,-1)

		sendDataToSoknadsmottaker(key, soknad)

		assertThatArkivMock()
			.hasEntityInArchive(key)
			.hasCallCountInArchive(key, expectedCount = 1)
			.verify()
	}


	@Test
	fun `File fetch error - third attempts succeeds application ends up in the archive`() {
		val key = UUID.randomUUID().toString()
		val fileId0 = UUID.randomUUID().toString()
		val fileId1 = UUID.randomUUID().toString()
		val fileId2 = UUID.randomUUID().toString()
		val skjemanummer = sykepenger
		val soknad = SoknadsBuilder()
			.withBehandlingsid(key)
			.withArkivtema("SYK")
			.withFodselsnummer(fnr)
			.withSoknadstype(Soknadstyper.SOKNAD)
			.withInnsendtDato(LocalDateTime.now())
			.withMottatteDokumenter(
				DocumentData(skjemanummer = skjemanummer, erHovedskjema = true, tittel = titles.get(sykepenger)?: "Dummy tittel",
					varianter = listOf(
						Varianter(id = fileId0, mediaType = "application/pdf", filnavn = "$sykepenger.pdf", filtype = "PDFA"))
				),
				DocumentData(skjemanummer = "L7", erHovedskjema = false, tittel = "Kvittering",
					varianter = listOf(
						Varianter(id = fileId1, mediaType = "application/pdf", filnavn = "kvittering.pdf", filtype = "PDFA"))
				),
				DocumentData(skjemanummer = "O2", erHovedskjema = false, tittel = "Vedlegg",
					varianter = listOf(
						Varianter(id = fileId2, mediaType = "application/pdf", filnavn = "vedlegg.pdf", filtype = "PDFA"))
				)
			)
			.build()

		setNormalArchiveBehaviour(key)

		setFileFetchBehaviour(fileId0, FileResponses.One_MB.name,-1)
		setFileFetchBehaviour(fileId1, FileResponses.OneHundred_KB.name,-1)
		setFileFetchBehaviour(fileId2, FileResponses.Fifty_50_MB.name,2)

		sendDataToSoknadsmottaker(key, soknad)

		assertThatArkivMock()
			.hasEntityInArchive(key)
			.hasCallCountInArchive(key, expectedCount = 1)
			.verify()
	}


	@Test
	@Disabled // Testen fungerer ikke helt, vil ta lang tid og bør bare unntaksvis kjøres
	fun `Archiving times out - third attempts succeeds and application ends up in the archive`() {
		val key = UUID.randomUUID().toString()
		val fileId0 = UUID.randomUUID().toString()
		val fileId1 = UUID.randomUUID().toString()
		val fileId2 = UUID.randomUUID().toString()
		val skjemanummer = sykepenger
		val soknad = SoknadsBuilder()
			.withBehandlingsid(key)
			.withArkivtema("SYK")
			.withFodselsnummer(fnr)
			.withSoknadstype(Soknadstyper.SOKNAD)
			.withInnsendtDato(LocalDateTime.now())
			.withMottatteDokumenter(
				DocumentData(skjemanummer = skjemanummer, erHovedskjema = true, tittel = titles.get(sykepenger)?: "Dummy tittel",
					varianter = listOf(
						Varianter(id = fileId0, mediaType = "application/pdf", filnavn = "$sykepenger.pdf", filtype = "PDFA"))
				),
				DocumentData(skjemanummer = "L7", erHovedskjema = false, tittel = "Kvittering",
					varianter = listOf(
						Varianter(id = fileId1, mediaType = "application/pdf", filnavn = "kvittering.pdf", filtype = "PDFA"))
				),
				DocumentData(skjemanummer = "O2", erHovedskjema = false, tittel = "Vedlegg",
					varianter = listOf(
						Varianter(id = fileId2, mediaType = "application/pdf", filnavn = "vedlegg.pdf", filtype = "PDFA"))
				)
			)
			.build()

		setDelayedNormalArchiveBehaviour(key)

		setFileFetchBehaviour(fileId0, FileResponses.One_MB.name,-1)
		setFileFetchBehaviour(fileId1, FileResponses.OneHundred_KB.name,-1)
		setFileFetchBehaviour(fileId2, FileResponses.Fifty_50_MB.name,-1)

		sendDataToSoknadsmottaker(key, soknad)

		assertThatArkivMock()
			.hasEntityInArchive(key)
			.hasCallCountInArchive(key, expectedCount = 3)
			.verify()
	}


	@Test
	fun `Archive responds 209 - application already archived`() {
		val key = UUID.randomUUID().toString()
		val fileId0 = UUID.randomUUID().toString()
		val fileId1 = UUID.randomUUID().toString()
		val fileId2 = UUID.randomUUID().toString()
		val skjemanummer = sykepenger
		val soknad = SoknadsBuilder()
			.withBehandlingsid(key)
			.withArkivtema("SYK")
			.withFodselsnummer(fnr)
			.withSoknadstype(Soknadstyper.SOKNAD)
			.withInnsendtDato(LocalDateTime.now())
			.withMottatteDokumenter(
				DocumentData(skjemanummer = skjemanummer, erHovedskjema = true, tittel = titles.get(sykepenger)?: "Dummy tittel",
					varianter = listOf(
						Varianter(id = fileId0, mediaType = "application/pdf", filnavn = "$sykepenger.pdf", filtype = "PDFA"))
				),
				DocumentData(skjemanummer = "L7", erHovedskjema = false, tittel = "Kvittering",
					varianter = listOf(
						Varianter(id = fileId1, mediaType = "application/pdf", filnavn = "kvittering.pdf", filtype = "PDFA"))
				),
				DocumentData(skjemanummer = "O2", erHovedskjema = false, tittel = "Vedlegg",
					varianter = listOf(
						Varianter(id = fileId2, mediaType = "application/pdf", filnavn = "vedlegg.pdf", filtype = "PDFA"))
				)
			)
			.build()

		mockArchiveRespondsWithCodeForXAttempts(key, 409, -1)

		setFileFetchBehaviour(fileId0, FileResponses.One_MB.name,-1)
		setFileFetchBehaviour(fileId1, FileResponses.OneHundred_KB.name,-1)
		setFileFetchBehaviour(fileId2, FileResponses.Fifty_50_MB.name,-1)

		sendDataToSoknadsmottaker(key, soknad)

		assertThatArkivMock()
			.hasEntityInArchive(key)
			.hasCallCountInArchive(key, expectedCount = 1)
			.verify()
	}

	@Test
	fun `Happy case - file in soknadsfillager ends up in the archive`() {
		val key = UUID.randomUUID().toString()
		val fileId = UUID.randomUUID().toString()
		val skjemanummer = kjoreliste
		val soknad = SoknadsBuilder()
			.withBehandlingsid(key)
			.withArkivtema("TSO")
			.withFodselsnummer(fnr)
			.withSoknadstype(Soknadstyper.SOKNAD)
			.withInnsendtDato(LocalDateTime.now())
			.withMottatteDokumenter(DocumentData(skjemanummer = skjemanummer, erHovedskjema = true, tittel = titles.get(sykepenger)?: "Dummy tittel",
				varianter = listOf(Varianter(id = fileId, mediaType = "application/pdf", filnavn = "$sykepenger.pdf", filtype = "PDFA"))))
			.build()

		setNormalArchiveBehaviour(key)

		if (skjemanummer == kjoreliste) {
			sendFilesToFileStorage(key, fileId)
			setFileFetchBehaviour(fileId, FileResponses.NOT_FOUND.name) // File in soknadsfillager not in innsending-api
		}
		sendDataToSoknadsmottaker(key, soknad)

		assertThatArkivMock()
			.hasEntityInArchive(key)
			.hasCallCountInArchive(key, expectedCount = 1)
			.verify()
	}

	@Test
	fun `Poison pill followed by proper message - one file ends up in the archive`() {
		val key = UUID.randomUUID().toString()
		val fileId = UUID.randomUUID().toString()
		val skjemanummer = sykepenger
		val soknad = SoknadsBuilder()
			.withBehandlingsid(key)
			.withArkivtema("SYK")
			.withFodselsnummer(fnr)
			.withSoknadstype(Soknadstyper.SOKNAD)
			.withInnsendtDato(LocalDateTime.now())
			.withMottatteDokumenter(DocumentData(skjemanummer = skjemanummer, erHovedskjema = true, tittel = titles.get(sykepenger)?: "Dummy tittel",
				varianter = listOf(Varianter(id = fileId, mediaType = "application/pdf", filnavn = "$sykepenger.pdf", filtype = "PDFA"))))
			.build()

		setNormalArchiveBehaviour(key)

		putPoisonPillOnKafkaTopic(UUID.randomUUID().toString())
		if (skjemanummer == kjoreliste) sendFilesToFileStorage(key, fileId)
		sendDataToSoknadsmottaker(key, soknad)

		assertThatArkivMock()
			.hasEntityInArchive(key)
			.hasCallCountInArchive(key, expectedCount = 1)
			.verify()
	}

	// If several variants with same soknadstype sent, only one is archived
	@Test
	fun `Happy case - several files in file storage - one file ends up in the archive`() {
		val key = UUID.randomUUID().toString()
		val skjemanummer = kjoreliste
		val fileId0 = UUID.randomUUID().toString()
		val fileId1 = UUID.randomUUID().toString()
		val soknad = Soknad(key, false, "personId", "tema",
			listOf(
				DocumentData(skjemanummer, true, "Kjøreliste for godkjent bruk av egen bil",
					listOf(Varianter(fileId0, "application/pdf", "filnavn", "PDFA"))),

				DocumentData(skjemanummer, false, "Kjøreliste for godkjent bruk av egen bil",
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
	}

	@Test
	fun `No files in file storage - Nothing is sent to the archive`() {
		val key = UUID.randomUUID().toString()
		val fileId = UUID.randomUUID().toString()
		val soknad = createSoknad(key, fileId)
		setNormalArchiveBehaviour(key)
		setFileFetchBehaviour(fileId, FileResponses.NOT_FOUND.name,-1)


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
				DocumentData("NAV 11-12.10", true, "Kjøreliste for godkjent bruk av egen bil",
					listOf(Varianter(fileId0, "application/pdf", "filnavn", "PDFA"))),

				DocumentData("NAV 11-12.10", true, "Kjøreliste for godkjent bruk av egen bil",
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

	private fun setDelayedNormalArchiveBehaviour(uuid: String) {
		val url = env.getUrlForArkivMock() + "/arkiv-mock/response-behaviour/set-delay-behaviour/$uuid"
		performPutCall(url)
	}

	private fun setFileFetchBehaviour(file_uuid: String, behaviour: String = FileResponses.NOT_FOUND.name , attempts: Int = -1) {
		val url = env.getUrlForArkivMock() + "/arkiv-mock/mock-file-response/$file_uuid/$behaviour/$attempts"
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
