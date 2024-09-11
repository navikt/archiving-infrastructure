package no.nav.soknad.arkivering

import kotlinx.coroutines.*
import no.nav.soknad.arkivering.dto.FileResponses
import no.nav.soknad.arkivering.innsending.*
import no.nav.soknad.arkivering.kafka.KafkaListener
import no.nav.soknad.arkivering.soknadsmottaker.model.Soknad
import no.nav.soknad.arkivering.utils.createSoknad
import no.nav.soknad.arkivering.utils.skjemaliste
import no.nav.soknad.arkivering.utils.vedleggsliste
import no.nav.soknad.arkivering.verification.AssertionHelper
import org.slf4j.LoggerFactory
import java.io.File
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempFile

class LoadTests(config: Config, private val kafkaListener: KafkaListener, val useOAuth: Boolean = true) {
	private val logger = LoggerFactory.getLogger(javaClass)
	/*
	Nils-Arne, 2020-12-11:
	Har sjekket på filstørrelser og antall på innsendte søknader siste 100 dager i arkivet.
	Det er kommet inn litt over 100400 søknader relevant for ny løsning, Viktigst å ta med seg av tallene:
	* Journalpost der summen av filene er størst  151 MB,  18 filer
	* av 100400 søknader er det under 400 hvor summen av størrelsen på filene er over 20MB
	* Gjennomsnitt sum på filstørrelse pr journalpost er 1,6MB
	* Gjennomsnitt antall filer pr journalpost 2,5
	* Gjennomsnitt filstørrelse 0,67MB
	*/

	private val soknadsmottakerApi = if (useOAuth) SoknadsmottakerApi(soknadApiWithOAuth2(config)) else SoknadsmottakerApi(soknadApiWithoutOAuth2(config))
	private val innsendingApi = InnsendingApi(config, useOAuth)
	private val arkivMockUrl = config.arkivMockUrl

	private val sykepenger: String = "NAV 08-07.04D"

	@Suppress("FunctionName")
	fun `100 simultaneous entities, 2 times 2 MB each`() {
		val testName = Thread.currentThread().stackTrace[1].methodName
		val numberOfEntities = 100
		val numberOfFilesPerEntity = 2
		val file = fileOfSize2mb

		performTest(testName, numberOfEntities, numberOfFilesPerEntity, file)
	}

	@Suppress("FunctionName")
	fun `100 simultaneous entities, 20 times 1 MB each`() {
		val testName = Thread.currentThread().stackTrace[1].methodName
		val numberOfEntities = 10
		val numberOfFilesPerEntity = 20
		val file = fileOfSize1mb

		performTest(testName, numberOfEntities, numberOfFilesPerEntity, file)
	}

	@Suppress("FunctionName")
	fun `2000 simultaneous entities, 1 times 1 byte each`() {
		val testName = Thread.currentThread().stackTrace[1].methodName
		val numberOfEntities = 2000
		val numberOfFilesPerEntity = 1
		val file = fileOfSize1byte

		performTest(testName, numberOfEntities, numberOfFilesPerEntity, file, 30)
	}

	@Suppress("FunctionName")
	fun `5 simultaneous entities, 4 times 38 MB each`() {
		val testName = Thread.currentThread().stackTrace[1].methodName
		val numberOfEntities = 2
		val numberOfFilesPerEntity = 4
		val file = fileOfSize38mb

		performTest(testName, numberOfEntities, numberOfFilesPerEntity, file, 30)
	}

	private fun opprettEttersending(antallVedlegg: Int, file: File): String {
		val soknadDef = skjemaliste.random()
		val soknad = innsendingApi.opprettEttersending(
			skjemanr = soknadDef.skjemanr,
			tema = soknadDef.tema,
			tittel = soknadDef.tittel,
			vedleggListe = vedleggsliste
				.take(antallVedlegg)
				.map { Vedlegg(it.vedleggKode, it.vedleggTittel) }
		)

		soknad.vedleggsliste()
			.verifyHasSize(antallVedlegg)
			.also { vedleggsliste ->
				(0 until antallVedlegg)
					.forEach { vedleggsliste.lastOppFil(it, file) }
			}

		return soknad.innsendingsId
	}

	private fun opprettSoknader(antallSoknader: Int, antallVedlegg: Int, file: File) = runBlocking {
		(0 until antallSoknader)
			.map { async { opprettEttersending(antallVedlegg, file)}  }
			.awaitAll()
	}

	private fun sendInnSoknader(innsendingsIds: List<String>) = runBlocking {
		innsendingsIds.map { async { innsendingApi.sendInn(it) }}.awaitAll()
	}

	@Suppress("FunctionName")
	fun `TC01 - Innsending av 10 soknader, hver med to vedlegg pa 2MB`() = runCatching {
		val testName = Thread.currentThread().stackTrace[1].methodName
		logger.info("Starting test: $testName")

		val file = loadFile(fileOfSize2mb)
		val innsendingsIdListe: List<String> = opprettSoknader(10, 2, file)

		val verifier = setupVerificationThatFinishedEventsAreCreated(expectedKeys = innsendingsIdListe, 30)
		sendInnSoknader(innsendingsIdListe)

		verifier.verify()
		logger.info("Finished test: $testName")
	}

	@Suppress("FunctionName")
	fun `TC02 - Innsending av 100 soknader, hver med fem vedlegg pa 1MB`() = runCatching {
		val testName = Thread.currentThread().stackTrace[1].methodName
		logger.info("Starting test: $testName")

		val file = loadFile(fileOfSize1mb)
		val innsendingsIdListe: List<String> = opprettSoknader(100, 5, file)

		val verifier = setupVerificationThatFinishedEventsAreCreated(expectedKeys = innsendingsIdListe, 30)
		sendInnSoknader(innsendingsIdListe)

		verifier.verify()
		logger.info("Finished test: $testName")
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

	private fun performTest(
		testName: String,
		numberOfEntities: Int,
		numberOfFilesPerEntity: Int,
		file: String,
		timeoutInMinutes: Int = 15
	) {
		logger.info("Starting test: $testName")

		val innsendingKeys = (0 until numberOfEntities).map { UUID.randomUUID().toString() }
		uploadData(innsendingKeys, numberOfEntities, numberOfFilesPerEntity, file, testName)
		warmupArchivingChain()
		val verifier = setupVerificationThatFinishedEventsAreCreated(expectedKeys = innsendingKeys, timeoutInMinutes)

		sendDataToSoknadsmottakerAsync(innsendingKeys, numberOfEntities, numberOfFilesPerEntity)

		verifier.verify()
		logger.info("Finished test: $testName")
	}


	@Suppress("FunctionName")
	fun `500 applications with 1 attachments each 1 MB`() {
		val testName = Thread.currentThread().stackTrace[1].methodName
		val numberOfApplications = 500
		val numberOfAttachmentsPerApplication = 1
		val fileBehaviour = FileResponses.One_MB.name

		performTest(testName, numberOfApplications, numberOfAttachmentsPerApplication, 30, fileBehaviour)
	}

	@Suppress("FunctionName")
	fun `100 applications with 2 attachments each 1 MB`() {
		val testName = Thread.currentThread().stackTrace[1].methodName
		val numberOfApplications = 100
		val numberOfAttachmentsPerApplication = 2
		val fileBehaviour = FileResponses.One_MB.name

		performTest(testName, numberOfApplications, numberOfAttachmentsPerApplication, 30, fileBehaviour)
	}


	@Suppress("FunctionName")
	fun `25 applications with 10 attachments each 10 MB`() {
		val testName = Thread.currentThread().stackTrace[1].methodName
		val numberOfApplications = 25
		val numberOfAttachmentsPerApplication = 10
		val fileBehaviour = FileResponses.Ten_MB.name

		performTest(testName, numberOfApplications, numberOfAttachmentsPerApplication, 30, fileBehaviour)
	}


	@Suppress("FunctionName")
	fun `5 applications with 3 attachments each 50 MB`() {
		val testName = Thread.currentThread().stackTrace[1].methodName
		val numberOfApplications = 5
		val numberOfAttachmentsPerApplication = 3
		val fileBehaviour = FileResponses.Fifty_50_MB.name

		performTest(testName, numberOfApplications, numberOfAttachmentsPerApplication, 30, fileBehaviour)
	}


	private fun performTest(
		testName: String,
		numberOfApplications: Int,
		numberOfAttachmentsPerApplication: Int,
		timeoutInMinutes: Int = 15,
		attachmentBeaviour: String
	) {
		logger.info("Starting test: $testName")

		val innsendingKeys = (0 until numberOfApplications).map { UUID.randomUUID().toString() }
		val applications = innsendingKeys.map{ createSoknad(it, sykepenger, "SYK", numberOfAttachmentsPerApplication ) }.toList()
		applications.forEach { prepareFiles(soknad = it, arkivMockUrl = arkivMockUrl, attachmentBeaviour = attachmentBeaviour) }

		val verifier = setupVerificationThatFinishedEventsAreCreated(expectedKeys = innsendingKeys, timeoutInMinutes)

		sendDataToSoknadsmottakerAsync(applications)

		verifier.verify()
		logger.info("Finished test: $testName")
	}

	private fun prepareFiles(soknad: Soknad, arkivMockUrl: String, attachmentBeaviour: String) {
		val dokumenter = soknad.dokumenter

		dokumenter.filter{it.erHovedskjema}.first().varianter.forEach {
			setFileFetchBehaviour(arkivMockUrl = arkivMockUrl, file_uuid = it.id, behaviour = FileResponses.OneHundred_KB.name )
		}
		dokumenter.filter{!it.erHovedskjema && it.skjemanummer.equals("L7", true)}.first().varianter.forEach {
			setFileFetchBehaviour(arkivMockUrl = arkivMockUrl, file_uuid = it.id, behaviour = FileResponses.OneHundred_KB.name )
		}
		dokumenter.filter{!it.erHovedskjema && !it.skjemanummer.equals("L7", true)}.first().varianter.forEach {
			setFileFetchBehaviour(arkivMockUrl = arkivMockUrl, file_uuid = it.id, behaviour = attachmentBeaviour)
		}

	}

	private fun sendDataToSoknadsmottakerAsync(
		soknader: List<Soknad>
	) {
		GlobalScope.launch {

			val startTimeSendingToSoknadsmottaker = System.currentTimeMillis()
			logger.info("About to send ${soknader.size} to Soknadsmottaker")

			soknader.forEach { sendDataToSoknadsmottaker(key = it.innsendingId, soknad = it, verbose = false) }

			val timeTaken = System.currentTimeMillis() - startTimeSendingToSoknadsmottaker
			logger.info("Sent ${soknader.size} to Soknadsmottaker in $timeTaken ms")
		}
	}

	private fun uploadData(
		innsendingKeys: List<String>,
		numberOfEntities: Int,
		numberOfFilesPerEntity: Int,
		filename: String,
		testName: String
	) {

		var keyIndex = 0
		var filesPerEntityCounter = 0
		val fileContent = LoadTests::class.java.getResource(filename)!!.readBytes()

		(0 until numberOfEntities * numberOfFilesPerEntity)
			.forEach { id ->

				sendFilesToFileStorage(innsendingKeys[keyIndex], id.toString(), fileContent,
					"${innsendingKeys[keyIndex]}: Uploading file with id $id for test '$testName'")

				filesPerEntityCounter += 1
				if (filesPerEntityCounter % numberOfFilesPerEntity == 0) {
					keyIndex += 1
					filesPerEntityCounter = 0
				}
			}
	}


	private fun warmupArchivingChain() {
		val startTime = System.currentTimeMillis()
		logger.debug("Warming up the archiving chain by sending a single message through the system")

		val fileId = "warmup_" + UUID.randomUUID().toString()
		val innsendingKey = UUID.randomUUID().toString()
		val soknad = createSoknad(innsendingKey, fileId)
		sendFilesToFileStorage(innsendingKey, fileId)
		sendDataToSoknadsmottaker(innsendingKey, soknad, verbose = true)

		setupVerificationThatFinishedEventsAreCreated(expectedKeys = listOf(innsendingKey), 1).verify()

		logger.debug("Archiving chain is warmed up in ${System.currentTimeMillis() - startTime} ms.")
	}


	/**
	 * This assumes that the file storage is already populated with files with ids ranging from 0 up to
	 * numberOfEntities * numberOfFilesPerEntity
	 */
	private fun sendDataToSoknadsmottakerAsync(
		innsendingKeys: List<String>,
		numberOfEntities: Int,
		numberOfFilesPerEntity: Int
	) {
		GlobalScope.launch {

			val startTimeSendingToSoknadsmottaker = System.currentTimeMillis()
			logger.info("About to send $numberOfEntities entities to Soknadsmottaker")
			val atomicInteger = AtomicInteger()

			(0 until numberOfEntities).map {
				val fileIds = (0 until numberOfFilesPerEntity).map { atomicInteger.getAndIncrement().toString() }
				sendDataToSoknadsmottakerAsync(innsendingKeys[it], fileIds)
			}

			val timeTaken = System.currentTimeMillis() - startTimeSendingToSoknadsmottaker
			logger.info("Sent $numberOfEntities entities to Soknadsmottaker in $timeTaken ms")
		}
	}

	private suspend fun sendDataToSoknadsmottakerAsync(innsendingKey: String, fileIds: List<String>): Soknad {
		return withContext(Dispatchers.Default) {

			val soknad = createSoknad(innsendingKey, fileIds)

			sendDataToSoknadsmottaker(innsendingKey, soknad, verbose = false)
			soknad
		}
	}

	private fun sendDataToSoknadsmottaker(key: String, soknad: Soknad, verbose: Boolean) {
		if (verbose)
			logger.debug("$key: Sending to Soknadsmottaker for test '${Thread.currentThread().stackTrace[2].methodName}'")
		soknadsmottakerApi.sendDataToSoknadsmottaker(soknad)
	}

	private fun setupVerificationThatFinishedEventsAreCreated(
		expectedKeys: List<String>,
		timeoutInMinutes: Int
	): AssertionHelper {

		val assertionHelper = AssertionHelper(kafkaListener)
		val timeoutInMs = timeoutInMinutes * 60 * 1000L

		expectedKeys.forEach { assertionHelper.hasFinishedEvent(it, timeoutInMs) }

		return assertionHelper
	}

	private fun sendFilesToFileStorage(innsendingId: String, fileId: String) {
		// TODO send til innsending-api
		// soknadsfillagerApi.sendFilesToFileStorage(innsendingId, fileId)
	}

	private fun sendFilesToFileStorage(innsendingId: String, fileId: String, payload: ByteArray, message: String) {
		// TODO send til innsending-api
		// soknadsfillagerApi.sendFilesToFileStorage(innsendingId, fileId, payload, message)
	}

	private fun setFileFetchBehaviour(arkivMockUrl: String, file_uuid: String, behaviour: String = FileResponses.NOT_FOUND.name , attempts: Int = -1) {
		val url = arkivMockUrl + "/arkiv-mock/mock-file-response/$file_uuid/$behaviour/$attempts"
		performPutCall(url)
	}

}

private const val fileOfSize38mb = "/Midvinterblot_(Carl_Larsson)_-_Nationalmuseum_-_32534.png"
private const val fileOfSize2mb = "/Midvinterblot_(Carl_Larsson)_-_Nationalmuseum_-_32534_small.png"
private const val fileOfSize1mb = "/Midvinterblot_(Carl_Larsson)_-_Nationalmuseum_-_32534_small.jpg"
private const val fileOfSize1byte = "/1_byte_file"
