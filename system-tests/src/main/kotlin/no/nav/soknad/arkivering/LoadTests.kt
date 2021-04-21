package no.nav.soknad.arkivering

import kotlinx.coroutines.*
import no.nav.soknad.arkivering.dto.SoknadInnsendtDto
import no.nav.soknad.arkivering.innsending.sendDataToMottaker
import no.nav.soknad.arkivering.innsending.sendFilesToFileStorage
import no.nav.soknad.arkivering.kafka.KafkaListener
import no.nav.soknad.arkivering.utils.createDto
import no.nav.soknad.arkivering.verification.AssertionHelper
import no.nav.soknad.arkivering.verification.inMinutes
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

class LoadTests(private val config: Configuration) {
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


	fun `10 000 simultaneous entities, 1 times 1 byte each`() {
		val numberOfEntities = 10_000
		val numberOfFilesPerEntity = 1
		uploadData(numberOfEntities, "0".toByteArray(), "10 000 simultaneous entities, 1 times 1 byte each")
		warmupArchivingChain()

		sendDataToMottakerAsync(numberOfEntities, numberOfFilesPerEntity)

		assertThatFinishedEventsAreCreated(numberOfEntities inMinutes 30)
	}

	fun `10 simultaneous entities, 8 times 38 MB each`() {
		val numberOfEntities = 2
		val numberOfFilesPerEntity = 8
		val file = fileOfSize38mb
		uploadImages(numberOfEntities * numberOfFilesPerEntity, file)
		warmupArchivingChain()

		sendDataToMottakerAsync(numberOfEntities, numberOfFilesPerEntity)

		assertThatFinishedEventsAreCreated(numberOfEntities inMinutes 10)
	}

	fun `100 simultaneous entities, 2 times 2 MB each`() {
		val numberOfEntities = 100
		val numberOfFilesPerEntity = 2
		val file = fileOfSize2mb
		uploadImages(numberOfEntities * numberOfFilesPerEntity, file)
		warmupArchivingChain()

		sendDataToMottakerAsync(numberOfEntities, numberOfFilesPerEntity)

		assertThatFinishedEventsAreCreated(numberOfEntities inMinutes 3)
	}

	fun `100 simultaneous entities, 20 times 1 MB each`() {
		val numberOfEntities = 100
		val numberOfFilesPerEntity = 20
		val file = fileOfSize1mb
		uploadImages(numberOfEntities * numberOfFilesPerEntity, file)
		warmupArchivingChain()

		sendDataToMottakerAsync(numberOfEntities, numberOfFilesPerEntity)

		assertThatFinishedEventsAreCreated(numberOfEntities inMinutes 3)
	}


	private fun uploadImages(numberOfImages: Int, filename: String) {
		val fileContent = LoadTests::class.java.getResource(filename).readBytes()
		uploadData(numberOfImages, fileContent, Thread.currentThread().stackTrace[2].methodName)
	}

	private fun uploadData(numberOfImages: Int, fileContent: ByteArray, testName: String) {
		(0 until numberOfImages)
			.chunked(2)
			.forEach { ids ->
				val deferredUploads = ids.map { id -> GlobalScope.async {
					sendFilesToFileStorage(id.toString(), fileContent, "fileUuid is $id for test '$testName'", config)
				} }
				runBlocking { deferredUploads.awaitAll() }
			}
	}


	private fun warmupArchivingChain() {
		val startTime = System.currentTimeMillis()
		println("Warming up the archiving chain by sending a single message through the system")

		val fileId = UUID.randomUUID().toString()
		val dto = createDto(fileId)
		sendFilesToFileStorage(fileId, config)
		sendDataToMottaker(dto, async = false, verbose = true)

		assertThatFinishedEventsAreCreated(1 inMinutes 10)

		println("Archiving chain is warmed up in ${System.currentTimeMillis() - startTime} ms.")
	}


	/**
	 * This assumes that the file storage is already populated with files with ids ranging from 0 up to numberOfEntities * numberOfFilesPerEntity
	 */
	private fun sendDataToMottakerAsync(numberOfEntities: Int, numberOfFilesPerEntity: Int): List<SoknadInnsendtDto> {
		val startTimeSendingToMottaker = System.currentTimeMillis()
		println("About to send $numberOfEntities entities to Mottaker")

		val atomicInteger = AtomicInteger()
		val deferredDtos = (0 until (numberOfEntities)).map {
			val fileIds = (0 until numberOfFilesPerEntity).map { atomicInteger.getAndIncrement().toString() }
			sendDataToMottakerAsync(fileIds)
		}
		val dtos = runBlocking { deferredDtos.awaitAll() }

		val finishTimeSendingToMottaker = System.currentTimeMillis()
		println("Sent $numberOfEntities entities to Mottaker in ${finishTimeSendingToMottaker - startTimeSendingToMottaker} ms")
		return dtos
	}

	private fun sendDataToMottakerAsync(fileIds: List<String>): Deferred<SoknadInnsendtDto> {
		return GlobalScope.async {

			val dto = createDto(fileIds)

			sendDataToMottaker(dto, async = true, verbose = false)
			dto
		}
	}

	private fun sendDataToMottaker(dto: SoknadInnsendtDto, async: Boolean, verbose: Boolean) {
		if (verbose)
			println("innsendingsId is ${dto.innsendingsId} for test '${Thread.currentThread().stackTrace[2].methodName}'")
		sendDataToMottaker(dto, async, config)
	}

	private fun assertThatFinishedEventsAreCreated(countAndTimeout: Pair<Int, Long>) {
		AssertionHelper(kafkaListener)
			.hasNumberOfFinishedEvents(countAndTimeout)
			.verify()
	}
}

private const val fileOfSize38mb = "/Midvinterblot_(Carl_Larsson)_-_Nationalmuseum_-_32534.png"
private const val fileOfSize2mb = "/Midvinterblot_(Carl_Larsson)_-_Nationalmuseum_-_32534_small.png"
private const val fileOfSize1mb = "/Midvinterblot_(Carl_Larsson)_-_Nationalmuseum_-_32534_small.jpg"
