package no.nav.soknad.arkivering

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import no.nav.soknad.arkivering.dto.SoknadInnsendtDto
import no.nav.soknad.arkivering.innsending.performDeleteCall
import no.nav.soknad.arkivering.innsending.sendDataToSoknadsmottaker
import no.nav.soknad.arkivering.innsending.sendFilesToFileStorage
import no.nav.soknad.arkivering.kafka.KafkaListener
import no.nav.soknad.arkivering.utils.createDto
import no.nav.soknad.arkivering.verification.AssertionHelper
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

class LoadTests(private val config: Configuration) {
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

	private val kafkaListener = KafkaListener(config)


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
		val testName = Thread.currentThread().stackTrace[1].methodName
		val numberOfEntities = 10_000
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
		timeout: Int = 5
	) {
		logger.info("Starting test: $testName")

		val innsendingKeys = (0 until numberOfEntities).map { UUID.randomUUID().toString() }
		uploadData(numberOfEntities * numberOfFilesPerEntity, file, testName)
		warmupArchivingChain()
		val verifier = setupVerificationThatFinishedEventsAreCreated(expectedKeys = innsendingKeys, timeout)

		sendDataToSoknadsmottakerAsync(innsendingKeys, numberOfEntities, numberOfFilesPerEntity)

		verifier.verify()
		logger.info("Finished test: $testName")
	}


	private fun uploadData(numberOfFiles: Int, filename: String, testName: String) {
		val fileContent = LoadTests::class.java.getResource(filename)!!.readBytes()
		runBlocking {
			(0 until numberOfFiles)
				.chunked(2)
				.forEach { ids ->
					ids.map { id ->
						withContext(Dispatchers.Default) {
							sendFilesToFileStorage(id.toString(), fileContent,
								"Uploading file with fileUuid $id for test '$testName'", config)
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
		val dto = createDto(fileId)
		sendFilesToFileStorage(fileId, config)
		sendDataToSoknadsmottaker(innsendingKey, dto, async = false, verbose = true)

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
	): List<SoknadInnsendtDto> {

		val startTimeSendingToSoknadsmottaker = System.currentTimeMillis()
		logger.info("About to send $numberOfEntities entities to Soknadsmottaker")

		val atomicInteger = AtomicInteger()
		val dtos = runBlocking {
			(0 until numberOfEntities).map {
				val fileIds = (0 until numberOfFilesPerEntity).map { atomicInteger.getAndIncrement().toString() }
				sendDataToSoknadsmottakerAsync(innsendingKeys[it], fileIds)
			}
		}

		val timeTaken = System.currentTimeMillis() - startTimeSendingToSoknadsmottaker
		logger.info("Sent $numberOfEntities entities to Soknadsmottaker in $timeTaken ms")
		return dtos
	}

	private suspend fun sendDataToSoknadsmottakerAsync(innsendingKey: String, fileIds: List<String>): SoknadInnsendtDto {
		return withContext(Dispatchers.Default) {

			val dto = createDto(fileIds)

			sendDataToSoknadsmottaker(innsendingKey, dto, async = true, verbose = false)
			dto
		}
	}

	private fun sendDataToSoknadsmottaker(key: String, dto: SoknadInnsendtDto, async: Boolean, verbose: Boolean) {
		if (verbose)
			logger.debug("$key: Sending to Soknadsmottaker for test '${Thread.currentThread().stackTrace[2].methodName}'")
		sendDataToSoknadsmottaker(key, dto, async, config)
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

	fun resetArkivMockDatabase() {
		try {
			logger.info("Resetting arkiv-mock database")
			performDeleteCall(config.config.arkivMockUrl + "/rest/journalpostapi/v1/reset")
		} catch (e: Exception) {
			logger.error("Error when resetting arkiv-mock database", e)
		}
	}
}

private const val fileOfSize38mb = "/Midvinterblot_(Carl_Larsson)_-_Nationalmuseum_-_32534.png"
private const val fileOfSize2mb = "/Midvinterblot_(Carl_Larsson)_-_Nationalmuseum_-_32534_small.png"
private const val fileOfSize1mb = "/Midvinterblot_(Carl_Larsson)_-_Nationalmuseum_-_32534_small.jpg"
private const val fileOfSize1byte = "/1_byte_file"
