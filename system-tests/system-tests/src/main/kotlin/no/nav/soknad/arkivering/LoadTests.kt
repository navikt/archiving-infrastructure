package no.nav.soknad.arkivering

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import no.nav.soknad.arkivering.innsending.SoknadsfillagerApi
import no.nav.soknad.arkivering.innsending.SoknadsmottakerApi
import no.nav.soknad.arkivering.kafka.KafkaListener
import no.nav.soknad.arkivering.soknadsmottaker.model.Soknad
import no.nav.soknad.arkivering.utils.createSoknad
import no.nav.soknad.arkivering.verification.AssertionHelper
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

class LoadTests(config: Config, kafkaConfig: KafkaConfig) {
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

	private val kafkaListener = KafkaListener(kafkaConfig)
	private val soknadsfillagerApi = SoknadsfillagerApi(config)
	private val soknadsmottakerApi = SoknadsmottakerApi(config)


	@Suppress("FunctionName")
	fun `5000 simultaneous entities, 1 times 1 MB each`() {
		val testName = Thread.currentThread().stackTrace[1].methodName
		val numberOfEntities = 5000
		val numberOfFilesPerEntity = 1
		val file = fileOfSize1mb

		performTest(testName, numberOfEntities, numberOfFilesPerEntity, file)
	}

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
		val numberOfEntities = 100
		val numberOfFilesPerEntity = 20
		val file = fileOfSize1mb

		performTest(testName, numberOfEntities, numberOfFilesPerEntity, file)
	}

	@Suppress("FunctionName")
	fun `10 000 simultaneous entities, 1 times 1 byte each`() {
		val testName = Thread.currentThread().stackTrace[1].methodName + "_7"
		val numberOfEntities = 2000
		val numberOfFilesPerEntity = 1
		val file = fileOfSize1byte

		performTest(testName, numberOfEntities, numberOfFilesPerEntity, file, 30)
	}

	@Suppress("FunctionName")
	fun `5 simultaneous entities, 8 times 38 MB each`() {
		val testName = Thread.currentThread().stackTrace[1].methodName
		val numberOfEntities = 5
		val numberOfFilesPerEntity = 8
		val file = fileOfSize38mb

		performTest(testName, numberOfEntities, numberOfFilesPerEntity, file, 30)
	}


	private fun performTest(
		testName: String,
		numberOfEntities: Int,
		numberOfFilesPerEntity: Int,
		file: String,
		timeoutInMinutes: Int = 10
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


	private fun uploadData(
		innsendingKeys: List<String>,
		numberOfEntities: Int,
		numberOfFilesPerEntity: Int,
		filename: String,
		testName: String
	) {

		val chunks = 2
		var keyIndex = 0
		var filesPerEntityCounter = 0
		val fileContent = LoadTests::class.java.getResource(filename)!!.readBytes()

		runBlocking(Dispatchers.IO) {
			(0 until numberOfEntities * numberOfFilesPerEntity)
				.chunked(chunks)
				.forEach { ids ->
					ids.forEach { id ->
						withContext(Dispatchers.Default) {

							sendFilesToFileStorage(innsendingKeys[keyIndex], id.toString(), fileContent,
								"${innsendingKeys[keyIndex]}: Uploading file with id $id for test '$testName'")

							filesPerEntityCounter += 1
							if (filesPerEntityCounter % numberOfFilesPerEntity == 0) {
								keyIndex += 1
								filesPerEntityCounter = 0
							}
						}
					}
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
	): List<Soknad> {

		val startTimeSendingToSoknadsmottaker = System.currentTimeMillis()
		logger.info("About to send $numberOfEntities entities to Soknadsmottaker")

		logger.info("Is blocking before sending to soknadmottaker")
		val soknader = runBlocking {
			val atomicInteger = AtomicInteger()
			(0 until numberOfEntities).map {
				val fileIds = (0 until numberOfFilesPerEntity).map { atomicInteger.getAndIncrement().toString() }
				sendDataToSoknadsmottakerAsync(innsendingKeys[it], fileIds)
			}
		}
    logger.info("Is unblocking after sending to soknadmottaker")
		val timeTaken = System.currentTimeMillis() - startTimeSendingToSoknadsmottaker
		logger.info("Sent $numberOfEntities entities to Soknadsmottaker in $timeTaken ms")
		return soknader
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
		soknadsfillagerApi.sendFilesToFileStorage(innsendingId, fileId)
	}

	private fun sendFilesToFileStorage(innsendingId: String, fileId: String, payload: ByteArray, message: String) {
		soknadsfillagerApi.sendFilesToFileStorage(innsendingId, fileId, payload, message)
	}
}

private const val fileOfSize38mb = "/Midvinterblot_(Carl_Larsson)_-_Nationalmuseum_-_32534.png"
private const val fileOfSize2mb = "/Midvinterblot_(Carl_Larsson)_-_Nationalmuseum_-_32534_small.png"
private const val fileOfSize1mb = "/Midvinterblot_(Carl_Larsson)_-_Nationalmuseum_-_32534_small.jpg"
private const val fileOfSize1byte = "/1_byte_file"
