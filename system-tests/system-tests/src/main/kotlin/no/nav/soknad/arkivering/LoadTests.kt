package no.nav.soknad.arkivering

import kotlinx.coroutines.*
import no.nav.soknad.arkivering.innsending.InnsendingApi
import no.nav.soknad.arkivering.innsending.Vedlegg
import no.nav.soknad.arkivering.kafka.KafkaListener
import no.nav.soknad.arkivering.utils.retry
import no.nav.soknad.arkivering.utils.skjemaliste
import no.nav.soknad.arkivering.utils.vedleggsliste
import no.nav.soknad.arkivering.verification.AssertionHelper
import org.slf4j.LoggerFactory
import java.io.File
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
	private val innsendingApi = InnsendingApi(config, useOAuth)

	@Suppress("FunctionName")
	fun `TC01 - Innsending av 10 soknader, hver med to vedlegg pa 2MB`() = runCatching {
		val file = loadFile(fileOfSize2mb)
		val innsendingsIdListe: List<String> = opprettSoknaderAsync(10, 2, file)

		val verifier = setupVerificationThatFinishedEventsAreCreated(expectedKeys = innsendingsIdListe, 10)
		sendInnSoknader(innsendingsIdListe)

		verifier.verify()
	}

	@Suppress("FunctionName")
	fun `TC02 - Innsending av 100 soknader, hver med tre vedlegg pa 1MB`() = runCatching {
		val file = loadFile(fileOfSize1mb)
		val innsendingsIdListe: List<String> = opprettSoknaderAsync(100, 3, file)

		val verifier = setupVerificationThatFinishedEventsAreCreated(expectedKeys = innsendingsIdListe, 15)
		sendInnSoknader(innsendingsIdListe)

		verifier.verify()
	}

	@Suppress("FunctionName")
	fun `TC03 - Innsending av 1000 soknader, hver med to vedlegg pa 1MB`() = runCatching {
		val file = loadFile(fileOfSize1mb)
		val innsendingsIdListe: List<String> = opprettSoknaderAsync(1000, 2, file)

		val verifier = setupVerificationThatFinishedEventsAreCreated(expectedKeys = innsendingsIdListe, 30)
		sendInnSoknader(innsendingsIdListe)

		verifier.verify()
	}

	private suspend fun opprettEttersending(antallVedlegg: Int, file: File): String {
		return withContext(Dispatchers.IO) {
			val soknadDef = skjemaliste.random()
			val soknad = retry(3, logThrowable = logThrowableAsWarning("Feil ved opprettelse av søknad: $soknadDef")) {
				innsendingApi.opprettEttersending(
					skjemanr = soknadDef.skjemanr,
					tema = soknadDef.tema,
					tittel = soknadDef.tittel,
					vedleggListe = vedleggsliste
						.take(antallVedlegg)
						.map { Vedlegg(it.vedleggKode, it.vedleggTittel) }
				)
			}

			soknad.vedleggsliste()
				.verifyHasSize(antallVedlegg)
				.also { vedleggsliste ->
					(0 until antallVedlegg)
						.forEach {
							logger.debug("${soknad.innsendingsId}: Laster opp fil nr. ${it + 1} for søknad")
							val start = System.currentTimeMillis()
							retry(3, logThrowable = logThrowableAsWarning("${soknad.innsendingsId}: Feil ved opplastning av fil")) { vedleggsliste.lastOppFil(it, file) }
							logger.info("${soknad.innsendingsId}: Fullførte opplasting av fil nr. ${it + 1} for søknad på ${(System.currentTimeMillis() - start)/1000.0} sekunder")
						}
				}

			return@withContext soknad.innsendingsId
		}
	}

	private fun opprettSoknaderAsync(antallSoknader: Int, antallVedlegg: Int, file: File) = runBlocking {
		(0 until antallSoknader)
			.map { async { opprettEttersending(antallVedlegg, file)}  }
			.awaitAll()
	}

	private fun opprettSoknaderSync(antallSoknader: Int, antallVedlegg: Int, file: File): List<String> = runBlocking {
		(0 until antallSoknader)
			.map { opprettEttersending(antallVedlegg, file) }
	}

	private fun logThrowableAsWarning(message: String): (Throwable) -> Unit {
		return { t -> logger.warn("$message - ${t.message}", t) }
	}

	private suspend fun sendInnSoknad(innsendingsId: String) {
		return withContext(Dispatchers.IO) {
			retry(3, logThrowable = logThrowableAsWarning("$innsendingsId: Feil ved innsending")) { innsendingApi.sendInn(innsendingsId) }
		}
	}

	private fun sendInnSoknader(innsendingsIds: List<String>) = runBlocking {
		innsendingsIds.map { async { runCatching { sendInnSoknad(it) } }}.awaitAll()
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

	private fun setupVerificationThatFinishedEventsAreCreated(
		expectedKeys: List<String>,
		timeoutInMinutes: Int
	): AssertionHelper {

		val assertionHelper = AssertionHelper(kafkaListener)
		val timeoutInMs = timeoutInMinutes * 60 * 1000L

		expectedKeys.forEach { assertionHelper.hasFinishedEvent(it, timeoutInMs) }

		return assertionHelper
	}

}

private const val fileOfSize38mb = "/Midvinterblot_(Carl_Larsson)_-_Nationalmuseum_-_32534.png"
private const val fileOfSize2mb = "/Midvinterblot_(Carl_Larsson)_-_Nationalmuseum_-_32534_small.png"
private const val fileOfSize1mb = "/Midvinterblot_(Carl_Larsson)_-_Nationalmuseum_-_32534_small.jpg"
private const val fileOfSize1byte = "/1_byte_file"
