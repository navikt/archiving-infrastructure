package no.nav.soknad.arkivering.arkiveringendtoendtests

import kotlinx.coroutines.*
import no.nav.soknad.arkivering.arkiveringendtoendtests.dto.SoknadInnsendtDto
import no.nav.soknad.arkivering.arkiveringendtoendtests.verification.andWasCalled
import no.nav.soknad.arkivering.arkiveringendtoendtests.verification.inMinutes
import no.nav.soknad.arkivering.arkiveringendtoendtests.verification.times
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis

/**
 * Kjellman Load Tests
 *
 * The state of the art Load Test tool is Gatling, which is named after a machine gun, due to its rapid firing.
 * The Kjellman Load Tests are similarly named after The Kjellman Machine Gun from Sweden, being one of the first
 * fully automatic weapons ever conceived. Just as the Kjellman Machine Gun is a less sophisticated product than
 * the Gatling Machine Gun, the Kjellman Load Tests can be seen as a less sophisticated product than the Gatling
 * Load Tests.
 */
@DisplayName("Kjellman Load Tests")
class LoadTests : BaseTests() {
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

	@Test
	fun `10 000 simultaneous entities, 1 times 1 byte each`() {
		val numberOfEntities = 10_000
		val numberOfFilesPerEntity = 1
		preloadDatabase(numberOfEntities)
		warmupArchivingChain()

		sendDataToMottakerAsync2(numberOfEntities, numberOfFilesPerEntity)

		assertThatArkivMock()
			.hasNumberOfEntities(numberOfEntities inMinutes 30)
			.verify()
	}

	@Disabled
	@Test
	fun `10 simultaneous entities, 7 times 46 MB each`() {
		val numberOfEntities = 1
		val numberOfFilesPerEntity = 7
		val file = fileOfSize46mb
		uploadImages(numberOfEntities * numberOfFilesPerEntity, file)
		warmupArchivingChain()

		sendDataToMottakerAsync2(numberOfEntities, numberOfFilesPerEntity)

		assertThatArkivMock()
			.hasNumberOfEntities(numberOfEntities inMinutes 3)
			.verify()
	}

	@Test
	fun `100 simultaneous entities, 50 MB each`() {

	}

	@Test
	fun `100 simultaneous entities, 2 times 2 MB each`() {
		val numberOfEntities = 100
		val numberOfFilesPerEntity = 2
		val file = fileOfSize2mb
		uploadImages(numberOfEntities * numberOfFilesPerEntity, file)
		warmupArchivingChain()

		sendDataToMottakerAsync2(numberOfEntities, numberOfFilesPerEntity)

		assertThatArkivMock()
			.hasNumberOfEntities(numberOfEntities inMinutes 3)
			.verify()
	}

	@Test
	fun `100 simultaneous entities, 20 times 1 MB each`() {
		val numberOfEntities = 100
		val numberOfFilesPerEntity = 20
		val file = fileOfSize1mb
		uploadImages(numberOfEntities * numberOfFilesPerEntity, file)
		warmupArchivingChain()

		sendDataToMottakerAsync2(numberOfEntities, numberOfFilesPerEntity)

		assertThatArkivMock()
			.hasNumberOfEntities(numberOfEntities inMinutes 3)
			.verify()
	}


	private fun uploadImages(numberOfImages: Int, filename: String) {
		val fileContent = LoadTests::class.java.getResource(filename).readBytes()
		uploadData(numberOfImages, fileContent)
	}

	private fun uploadData(numberOfImages: Int, fileContent: ByteArray) {
		(0..numberOfImages).forEach { sendFilesToFileStorage(it.toString(), fileContent) }
	}


	private fun preloadDatabase(numberOfEntities: Int) {
		println("Preloading database...")
		val timeTaken = measureTimeMillis {

			if (useTestcontainers) {
				/*
				val baseId = UUID.randomUUID().toString()
				sendFilesToFileStorage(baseId, "0".toByteArray())

				val result = executeQueryInPostgres("DROP TABLE IF EXISTS documents;" +
					"CREATE TABLE documents \n" +
					"(\n" +
					"    id        VARCHAR(255) NOT NULL,\n" +
					"    document  BYTEA,\n" +
					"    created   TIMESTAMP WITH TIME ZONE NOT NULL default (now() at time zone 'UTC'),\n" +
					"    PRIMARY KEY (id)\n" +
					");")
				if (result.exitCode != 0)
					println("Error when preloading database!\n${result.exitCode}\n${result.stdout}\n${result.stderr}")
				 */

				val batchSize = 1000
				val numberOfBatches = numberOfEntities / batchSize
				// This will insert numberOfBatches * batchSize documents into the database.
				for (i in 0 until numberOfBatches) {
					val res = executeQueryInPostgres("INSERT INTO documents(id, document) VALUES " +
						((i * batchSize) until ((i + 1) * batchSize)).joinToString(",") { "('$it','0')" } + ";")

					if (res.exitCode != 0)
						println("Error when preloading database!\n${res.exitCode}\n${res.stdout}\n${res.stderr}")
					else if (i % numberOfBatches == 0)
						println("$i of $numberOfBatches (${100 * i / numberOfBatches}%)")
				}
			} else {
				uploadData(numberOfEntities, "0".toByteArray())
			}
		}
		println("Preloading database took $timeTaken ms")
	}

	private fun warmupArchivingChain() {
		val startTime = System.currentTimeMillis()
		println("Warming up the archiving chain by sending a single message through the system")

		val fileId = UUID.randomUUID().toString()
		val dto = createDto(fileId)
		setNormalArchiveBehaviour(dto.innsendingsId)
		sendFilesToFileStorage(fileId)
		sendDataToMottaker(dto)

		assertThatArkivMock()
			.containsData(dto andWasCalled times(1))
			.verify()

		resetArchiveDatabase()
		println("Archiving chain is warmed up in ${System.currentTimeMillis() - startTime} ms.")
	}

	private fun sendDataToMottakerAsync(numberOfEntities: Int): List<SoknadInnsendtDto> {
		val startTimeSendingToMottaker = System.currentTimeMillis()
		println("About to send $numberOfEntities entities to Mottaker")

		val deferredDtos = (0 until (numberOfEntities)).map { sendDataToMottakerAsync2(listOf(it.toString())) }
		val dtos = runBlocking { deferredDtos.awaitAll() }

		val finishTimeSendingToMottaker = System.currentTimeMillis()
		println("Sent $numberOfEntities entities to Mottaker in ${finishTimeSendingToMottaker - startTimeSendingToMottaker} ms")
		return dtos
	}

	private fun sendDataToMottakerAsync(atomicInteger: AtomicInteger): Deferred<SoknadInnsendtDto> {
		return GlobalScope.async {

			val dto = createDto(atomicInteger.getAndIncrement().toString())

			sendDataToMottaker(dto, async = true, verbose = false)
			dto
		}
	}

	/**
	 * This assumes that the file storage is already populated with files with ids ranging from 0 up to numberOfEntities * numberOfFilesPerEntity
	 */
	private fun sendDataToMottakerAsync2(numberOfEntities: Int, numberOfFilesPerEntity: Int): List<SoknadInnsendtDto> { // TODO: Name of function
		val startTimeSendingToMottaker = System.currentTimeMillis()
		println("About to send $numberOfEntities entities to Mottaker")

		val atomicInteger = AtomicInteger()
		val deferredDtos = (0 until (numberOfEntities)).map {
			val fileIds = (0 until numberOfFilesPerEntity).map { atomicInteger.getAndIncrement().toString() }
			sendDataToMottakerAsync2(fileIds)
		}
		val dtos = runBlocking { deferredDtos.awaitAll() }

		val finishTimeSendingToMottaker = System.currentTimeMillis()
		println("Sent $numberOfEntities entities to Mottaker in ${finishTimeSendingToMottaker - startTimeSendingToMottaker} ms")
		return dtos
	}

	private fun sendDataToMottakerAsync2(fileIds: List<String>): Deferred<SoknadInnsendtDto> { // TODO: Name of function
		return GlobalScope.async {

			val dto = createDto(fileIds)

			sendDataToMottaker(dto, async = true, verbose = false)
			dto
		}
	}
}

private const val fileOfSize81mb = "/Midvinterblot_(Carl_Larsson)_-_Nationalmuseum_-_32534.png"
private const val fileOfSize46mb = "/Midvinterblot_(Carl_Larsson)_-_Nationalmuseum_-_32534_2.png"
private const val fileOfSize2mb = "/Midvinterblot_(Carl_Larsson)_-_Nationalmuseum_-_32534_small.png"
private const val fileOfSize1mb = "/Midvinterblot_(Carl_Larsson)_-_Nationalmuseum_-_32534_small.jpg"
