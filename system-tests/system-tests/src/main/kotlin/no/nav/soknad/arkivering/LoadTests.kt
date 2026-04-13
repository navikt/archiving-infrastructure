package no.nav.soknad.arkivering

import kotlinx.coroutines.*
import no.nav.soknad.arkivering.innsending.InnsendingApi
import no.nav.soknad.arkivering.innsending.Vedlegg
import no.nav.soknad.arkivering.innsending.model.Mimetype
import no.nav.soknad.arkivering.innsending.model.SkjemaDokumentDtoV2
import no.nav.soknad.arkivering.innsending.model.SkjemaDtoV2
import no.nav.soknad.arkivering.innsending.model.SoknadsStatusDto
import no.nav.soknad.arkivering.innsending.model.VisningsType
import no.nav.soknad.arkivering.kafka.KafkaListener
import no.nav.soknad.arkivering.utils.SkjemaDokumentDtoV2TestBuilder
import no.nav.soknad.arkivering.utils.retry
import no.nav.soknad.arkivering.utils.skjemaliste
import no.nav.soknad.arkivering.utils.vedleggsliste
import no.nav.soknad.arkivering.verification.AssertionHelper
import no.nav.soknad.innsending.utils.builders.SkjemaDtoV2TestBuilder
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID
import kotlin.io.path.createTempFile

class LoadTests(config: Config, private val kafkaListener: KafkaListener, val useOAuth: Boolean = true) {
	private val logger = LoggerFactory.getLogger(javaClass)

	private val testpersonid = "19876898104"
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
	fun `TC01 - Innsending av 10 soknader, hver med to vedlegg pa 38MB`() = runCatching {
		val file = loadFile(fileOfSize38mb)
		val innsendingsIdListe: List<String> = opprettSoknaderAsync(10, 2, file)

		val verifier = setupVerificationThatFinishedEventsAreCreated(expectedKeys = innsendingsIdListe, 10)
		sendInnSoknader(innsendingsIdListe)

		verifier.verify()
	}

	@Suppress("FunctionName")
	fun `TC02 - Innsending av 100 soknader, hver med tre vedlegg pa 2MB`() = runCatching {
		val file = loadFile(fileOfSize2mb)
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


	@Suppress("FunctionName")
	fun `TC04 - Innsending av 10 soknader fra ikke innlogget bruker, hver med ett vedlegg pa 1MB`() = runCatching {

		val soknadListe = mutableListOf<SkjemaDtoV2>()
		try {
			repeat(10) {
				soknadListe.add(prepareNoLoginSoknad(mapOf(UUID.randomUUID().toString() to listOf(loadFile(pdffile)))))
			}
		} catch (e: Exception) {
			logger.error("Failed to create soknadListe", e)
		}
		if (soknadListe.isEmpty()) {
			throw Exception("Failed to prepare soknadListe")
		}
		val verifier = setupVerificationThatFinishedEventsAreCreated(expectedKeys = soknadListe.map{it.innsendingsId!!}, 30)

		sendinnsoknaderNologin(soknadListe)

		verifier.verify()
	}


	@Suppress("FunctionName")
	fun `TC05 - Opplasting av en fil deretter sletter den`() = runCatching {

		val innsendingsId = UUID.randomUUID().toString()
		val vedleggsRef = UUID.randomUUID().toString()

		val response = lastOppEnFil(innsendingsId, vedleggsRef, loadFile(pdffile))
		//assertTrue(response.isSuccess)

		val filId = response.getOrThrow().filId
		val deleteResponse = slettEnFil(innsendingsId, filId.toString())
	}


	@Suppress("FunctionName")
	fun `TC06 - Innsending av 1000 soknader fra ikke innlogget bruker, hver med 2 vedlegg pa 1MB`() = runCatching {

		val soknadListe = mutableListOf<SkjemaDtoV2>()
		repeat(1000) {
			soknadListe.add(prepareNoLoginSoknad(
				mapOf(UUID.randomUUID().toString() to listOf(loadFile(pdffile)),
				UUID.randomUUID().toString() to listOf(loadFile(pdffile)))))
		}

		val verifier = setupVerificationThatFinishedEventsAreCreated(expectedKeys = soknadListe.map{it.innsendingsId!!}, 30)

		sendinnsoknaderNologin(soknadListe)

		verifier.verify()
	}


	@Suppress("FunctionName")
	fun `TC07 - Innsending av 1000 soknader fra innlogget og ikke innlogget bruker`() = runCatching {

		val antallSoknader = 500
		val file = loadFile(fileOfSize1mb)
		val innsendingsIdListe: List<String> = opprettSoknaderAsync(antallSoknader, 2, file)

		val soknadListe = mutableListOf<SkjemaDtoV2>()
		repeat(antallSoknader) {
			soknadListe.add(prepareNoLoginSoknad(
				mapOf(UUID.randomUUID().toString() to listOf(loadFile(pdffile)),
					UUID.randomUUID().toString() to listOf(loadFile(pdffile)))))
		}

		val verifier = setupVerificationThatFinishedEventsAreCreated(expectedKeys = soknadListe.map{it.innsendingsId!! + innsendingsIdListe}, 30)

		var index = 0
		repeat(antallSoknader) {
			sendInnSoknader(listOf(innsendingsIdListe.get(index)))
			sendinnsoknaderNologin(listOf(soknadListe.get(index)))
			index++
		}

		verifier.verify()
	}
	// Lagster opp filer på vedlegg til søknad, og returnerer SkjemaDtoV2 klar for innsending
	private fun prepareNoLoginSoknad(vedleggMap: Map<String, List<File>>): SkjemaDtoV2 {
		val brukerId = testpersonid
		val innsendingsId = UUID.randomUUID().toString()

		val vedleggsListe: List<SkjemaDokumentDtoV2> = lastOppFilerTilSoknad(innsendingsId, vedleggMap) // returnerer map med fyllutVedleggIds til liste med lagringsId for opplastede filer til vedlegg

		val skjemDtoV2 = SkjemaDtoV2TestBuilder(
			brukerId = brukerId,
			innsendingsId = innsendingsId,
			status = SoknadsStatusDto.utfylt,
			visningsType = VisningsType.nologin,
		)
			.medVedlegg(vedleggsListe)
			.build()

		return skjemDtoV2
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

	private suspend fun sendInnSoknad(nologinSoknad: SkjemaDtoV2) {
		return withContext(Dispatchers.IO) {
			retry(3, logThrowable = logThrowableAsWarning("${nologinSoknad.innsendingsId}: Feil ved innsending")) { innsendingApi.lagreOgSendInnNoLoginSoknad(nologinSoknad) }}
		}


	private fun lastOppEnFil(innsendingsId: String, vedleggRef: String, file: File) =
		innsendingApi.lastOppNoLoginFil(innsendingsId, vedleggRef, file)
			.onSuccess { logger.info("Lastet opp filId=${it.filId} til vedleggRef=$vedleggRef for innsendingsId=$innsendingsId") }
			.onFailure { throw it }

	private fun slettEnFil(innsendingsId: String, filId: String) =
		innsendingApi.slettNoLoginFil(innsendingsId, filId)
			.onSuccess { logger.info("slettet filId=${filId} til innsendingsId=$innsendingsId") }
			.onFailure { throw it }

	private fun sendInnSoknader(innsendingsIds: List<String>) = runBlocking {
		logger.info("Load test: Sender inn innsendingsIds=${innsendingsIds.joinToString { it }}")
		innsendingsIds.map { async { runCatching { sendInnSoknad(it) } }}.awaitAll()
	}

	private fun sendinnsoknaderNologin(nologinSoknader: List<SkjemaDtoV2>) = runBlocking {
		logger.info("Load test: Sender inn innsendingsIds=${nologinSoknader.filter {it.innsendingsId != null}.map{it.innsendingsId}.toList().joinToString { it ?: "" }}")
		nologinSoknader.map{ async { runCatching { sendInnSoknad(it) } }}.awaitAll()
	}

	private fun lastOppFilerTilVedlegg(innsendingsId: String, vedleggRef: String, files: List<File>) = runBlocking {
		files
			.map { file ->
				async {
					lastOppEnFil(
						innsendingsId = innsendingsId,
						vedleggRef = vedleggRef,
						file = file
					).getOrThrow() // unwrap Result or throw
				}
			}
			.awaitAll()
	}

	private fun lastOppFilerTilSoknad(innsendingsId: String, vedleggMap: Map<String, List<File>>) = runBlocking {
		val vedleggListe = vedleggMap
			.mapValues { (vedleggRef, files) ->
			// Upload all files for this vedleggRef
			lastOppFilerTilVedlegg(innsendingsId, vedleggRef, files)
				.map { it.filId.toString() } // extract just the fileId
		}

		vedleggListe
			.mapKeys {
			SkjemaDokumentDtoV2TestBuilder(
				tittel = "Vedleggseksempel", mimetype = Mimetype.applicationSlashPdf, formioId = it.key
			)
			.withFilIdListe(it.value)
			.build()
			}.keys
	}.toList()


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
private const val jsonfile = "/innsending.json"
private const val pdffile = "/mellomstor.pdf"
