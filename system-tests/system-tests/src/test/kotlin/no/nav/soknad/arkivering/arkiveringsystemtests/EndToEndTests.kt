package no.nav.soknad.arkivering.arkiveringsystemtests

import no.nav.soknad.arkivering.Config
import no.nav.soknad.arkivering.arkiveringsystemtests.environment.EmbeddedDockerImages
import no.nav.soknad.arkivering.dto.SafResponses
import no.nav.soknad.arkivering.innsending.*
import no.nav.soknad.arkivering.innsending.model.ArkiveringsStatusDto
import no.nav.soknad.arkivering.innsending.model.AttachmentDto
import no.nav.soknad.arkivering.innsending.model.OpplastingsStatusDto
import no.nav.soknad.arkivering.innsending.model.SoknadsStatusDto
import no.nav.soknad.arkivering.innsending.model.SubmitApplicationRequest
import no.nav.soknad.arkivering.tokensupport.E2eTokenConfig
import no.nav.soknad.arkivering.utils.Skjema.generateVedleggsnr
import no.nav.soknad.arkivering.utils.SubmitApplicationRequestBuilder
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceAccessMode
import org.junit.jupiter.api.parallel.ResourceLock
import java.io.File
import java.util.*

@Execution(ExecutionMode.CONCURRENT)
class EndToEndTests : SystemTestBase() {
	private val embeddedDockerImages = EmbeddedDockerImages()
	private lateinit var soknadsmottakerApi: SoknadsmottakerApi
	private lateinit var innsendingApi: InnsendingApi

	val testpersonid = E2eTokenConfig.TEST_PERSON_ID

	@BeforeAll
	fun setup() {
		if (!isExternalEnvironment) {
			env.addEmbeddedDockerImages(embeddedDockerImages)
			embeddedDockerImages.startContainers()
		}

		setUp()
		soknadsmottakerApi = SoknadsmottakerApi(soknadApiWithoutOAuth2(config))
		innsendingApi = InnsendingApi(config, tokens)
	}

	@AfterAll
	fun teardown() {
		tearDown()

		if (!isExternalEnvironment) {
			embeddedDockerImages.stopContainers()
		}
	}
	@Test
	fun `jackson test`() {
		val jsonResponse = "{\"status\":\"UP\", \"ping\":{\"status\":\"UP\"}, \"ssl\":{\"status\":\"UP\",\"details\":{\"validChains\":[]}}}"

		val health = objectMapper.readValue(jsonResponse, Health::class.java)
		assertTrue(health.status == "UP")

	}

	// Innsending-api validerer ikke token i e2e ennå, så resten av testene ville passert med feil token.
	// Denne testen sikrer at tokenene har issueren og claimene innsending-api vil kreve når valideringen slås på.
	@Test
	fun `tokens sent to innsending-api have the issuer and claims used in production`() {
		if (!tokens.enabled) return

		val citizen = jwtPayload(tokens.citizen()!!)
		assertEquals("http://${E2eTokenConfig.AUTH_SERVER_HOST}/tokenx", citizen["iss"].asText())
		assertEquals(listOf(E2eTokenConfig.TOKEN_X_AUDIENCE), audience(citizen))
		assertEquals(testpersonid, citizen["pid"].asText())
		assertEquals("idporten-loa-high", citizen["acr"].asText())
		assertTrue(citizen.has("exp") && citizen.has("iat") && citizen.has("sub"))

		val nologin = jwtPayload(tokens.nologin()!!)
		assertEquals("http://${E2eTokenConfig.AUTH_SERVER_HOST}/azuread", nologin["iss"].asText())
		assertEquals(listOf(E2eTokenConfig.AZURE_AUDIENCE), audience(nologin))
		assertEquals("app", nologin["idtyp"].asText())
		assertTrue(nologin["roles"].map { it.asText() }.contains("nologin-access"))
		assertTrue(!nologin.has("pid"), "Azure-token skal ikke ha personident")
	}

	private fun jwtPayload(token: String) =
		objectMapper.readTree(Base64.getUrlDecoder().decode(token.split(".")[1]))

	private fun audience(payload: com.fasterxml.jackson.databind.JsonNode): List<String> =
		payload["aud"].let { aud -> if (aud.isArray) aud.map { it.asText() } else listOf(aud.asText()) }

	// Tilgangskontrakt for innsending-api med ekte token (#461 i innsending-api).
	// Hoppes over så lenge innsending-api ikke validerer token i e2e. Da svarer det på alt, og testene ville feilet uten grunn.
	// Testene sjekker bare om kallet slipper forbi tilgangskontrollen, ikke resultatet. Id-ene finnes ikke, så ingen data endres.

	@Test
	fun `innsending-api rejects citizen calls without a valid citizen token`() {
		assumeInnsendingApiValidatesTokens()

		assertRejected(citizenEndpoint(), token = null, "uten token")
		assertRejected(citizenEndpoint(), tokens.forCaller(E2eTokenConfig.Caller.CITIZEN_LOW_ACR), "TokenX med acr=idporten-loa-substantial")
		assertRejected(citizenEndpoint(), tokens.forCaller(E2eTokenConfig.Caller.CITIZEN_WRONG_AUDIENCE), "TokenX til annen audience")
		assertRejected(citizenEndpoint(), tokens.nologin(), "Azure-token mot innbyggerendepunkt")

		assertAllowed(citizenEndpoint(), tokens.citizen(), "TokenX med acr=idporten-loa-high")
	}

	@Test
	fun `innsending-api rejects nologin calls without the nologin-access role`() {
		assumeInnsendingApiValidatesTokens()

		assertRejected(nologinEndpoint(), token = null, "uten token")
		assertRejected(nologinEndpoint(), tokens.forCaller(E2eTokenConfig.Caller.NOLOGIN_WITHOUT_ROLE), "Azure-token uten nologin-access")
		assertRejected(nologinEndpoint(), tokens.citizen(), "TokenX-token mot nologin-endepunkt")

		assertAllowed(nologinEndpoint(), tokens.nologin(), "Azure-token med nologin-access")
	}

	private data class Endpoint(val method: String, val path: String)

	private fun citizenEndpoint() = Endpoint("GET", "/endtoend/v1/soknad/${UUID.randomUUID()}/arkiveringsstatus")

	private fun nologinEndpoint() =
		Endpoint("DELETE", "/v1/application-nologin/${UUID.randomUUID()}/attachments/${UUID.randomUUID()}/${UUID.randomUUID()}")

	private fun assumeInnsendingApiValidatesTokens() {
		assumeTrue(tokens.enabled, "Ingen auth-server i dette miljøet")
		val status = statusFor(citizenEndpoint(), token = null)
		assumeTrue(status in rejectedStatuses, "innsending-api validerer ikke token i e2e (svarte $status uten token)")
	}

	private fun assertRejected(endpoint: Endpoint, token: String?, description: String) {
		val status = statusFor(endpoint, token)
		assertTrue(status in rejectedStatuses, "${endpoint.method} ${endpoint.path} med $description: forventet avvisning, fikk $status")
	}

	private fun assertAllowed(endpoint: Endpoint, token: String?, description: String) {
		val status = statusFor(endpoint, token)
		assertTrue(status !in rejectedStatuses, "${endpoint.method} ${endpoint.path} med $description: forventet tilgang, fikk $status")
	}

	private fun statusFor(endpoint: Endpoint, token: String?): Int {
		val request = Request.Builder()
			.url(config.innsendingApiUrl + endpoint.path)
			.method(endpoint.method, null)
			.apply { token?.let { header("Authorization", "Bearer $it") } }
			.build()
		return httpClient.newCall(request).execute().use { it.code }
	}

	private val httpClient = OkHttpClient()

	// 403 er dagens svar fra token-support. 401 tas med fordi teamet kan velge det i Spring Security-migreringen
	private val rejectedStatuses = setOf(401, 403)

	@Test
	fun `Happy case - one file ends up in the archive`() {
		val soknadTestdata = innsendingApi.opprettEttersending()
		val innsendingsId = soknadTestdata.innsendingsId

		soknadTestdata.vedleggsliste()
			.verifyHasSize(1)
			.lastOppFil(0, "OneHundred_KB.pdf")

		try {
			innsendingApi.sendInn(soknadTestdata)
		} catch (e: Exception) {
			throw e
		}

		assertThatArkivMock()
			.hasFinishedEvent(innsendingsId)
			.hasEntityInArchive(innsendingsId)
			.hasCallCountInArchive(innsendingsId, expectedCount = 1)
			.verify()

		assertThatSoknad(innsendingsId)
			.hasStatus(ArkiveringsStatusDto.arkivert)
	}

	@Test
	fun `Happy case - large attachment ends up in the archive`() {
		val soknadTestdata = innsendingApi.opprettEttersending()
		val innsendingsId = soknadTestdata.innsendingsId

		soknadTestdata.vedleggsliste()
			.verifyHasSize(1)
			.lastOppFil(0, "Thirty_MB.pdf")

		try {
			innsendingApi.sendInn(soknadTestdata)
		} catch (e: Exception) {
			throw e
		}

		assertThatArkivMock()
			.hasFinishedEvent(innsendingsId)
			.hasEntityInArchive(innsendingsId)
			.hasCallCountInArchive(innsendingsId, expectedCount = 1)
			.verify()

		assertThatSoknad(innsendingsId)
			.hasStatus(ArkiveringsStatusDto.arkivert)
	}

	@Test
	fun `Arkivering av ettersending feiler mot arkivet`() {
		val soknadTestdata = innsendingApi.opprettEttersending()
		val innsendingsId = soknadTestdata.innsendingsId

		soknadTestdata.vedleggsliste()
			.verifyHasSize(1)
			.lastOppFil(0, "OneHundred_KB.pdf")

		mockArchiveRespondsWithCodeForXAttempts(innsendingsId, 500, attemptsThanSoknadsarkivererWillPerform + 1)
		try {
			innsendingApi.sendInn(soknadTestdata)
		} catch (e: Exception) {
			throw e
		}

		assertThatArkivMock()
			.hasFailureEvent(innsendingsId, 350_000L)
			.hasNoEntityInArchive(innsendingsId)
			.verify()

		assertThatSoknad(innsendingsId)
			.hasStatus(ArkiveringsStatusDto.arkiveringFeilet)
	}

	@Test
	fun `Archive responds 409 - application already archived`() {
		val soknadTestdata = innsendingApi.opprettEttersending(
			vedleggListe = listOf(
				Vedlegg("N6", "Bekreftelse på skoleplass"),
				Vedlegg("A5", "Vitnemål grunnskole"),
				Vedlegg("T1", "Bekreftelse fra fastlege"),
			)
		)
		val innsendingsId = soknadTestdata.innsendingsId

		soknadTestdata.vedleggsliste()
			.verifyHasSize(3)
			.lastOppFil(0, "OneHundred_KB.pdf")
			.lastOppFil(1, "Ten_MB.pdf")
			.lastOppFil(2, "OneHundred_KB.pdf")

		mockArchiveRespondsWithCodeForXAttempts(innsendingsId, 409, -1)
		try {
			innsendingApi.sendInn(soknadTestdata)
		} catch (e: Exception) {
			throw e
		}

		assertThatArkivMock()
			.hasFinishedEvent(innsendingsId)
			.hasEntityInArchive(innsendingsId)
			.hasCallCountInArchive(innsendingsId, expectedCount = 1)
			.verify()

		assertThatSoknad(innsendingsId)
			.hasStatus(ArkiveringsStatusDto.arkivert)
	}


	@Test
	fun `SAF respond with journalpost - application already archived`() {
		val soknadTestdata = innsendingApi.opprettEttersending(
			vedleggListe = listOf(
				Vedlegg("N6", "Bekreftelse på skoleplass"),
				Vedlegg("A5", "Vitnemål grunnskole"),
				Vedlegg("T1", "Bekreftelse fra fastlege"),
			)
		)
		val innsendingsId = soknadTestdata.innsendingsId

		soknadTestdata.vedleggsliste()
			.verifyHasSize(3)
			.lastOppFil(0, "OneHundred_KB.pdf")
			.lastOppFil(1, "Ten_MB.pdf")
			.lastOppFil(2, "OneHundred_KB.pdf")

		setSafFetchBehaviour(innsendingsId, SafResponses.OK.name, -1)
		try {
			innsendingApi.sendInn(soknadTestdata)
		} catch (e: Exception) {
			throw e
		}

		assertThatArkivMock()
			.hasFinishedEvent(innsendingsId)
			.verify()

		assertThatSoknad(innsendingsId)
			.hasStatus(ArkiveringsStatusDto.arkivert)
	}

	@Test
	fun `Request responds with 408 - second attempt already archived from SAF`() {
		val soknadTestdata = innsendingApi.opprettEttersending(
			vedleggListe = listOf(
				Vedlegg("N6", "Bekreftelse på skoleplass"),
				Vedlegg("A5", "Vitnemål grunnskole"),
				Vedlegg("T1", "Bekreftelse fra fastlege"),
			)
		)
		val innsendingsId = soknadTestdata.innsendingsId

		soknadTestdata.vedleggsliste()
			.verifyHasSize(3)
			.lastOppFil(0, "OneHundred_KB.pdf")
			.lastOppFil(1, "Ten_MB.pdf")
			.lastOppFil(2, "OneHundred_KB.pdf")

		setSafFetchBehaviour(innsendingsId, SafResponses.NOT_FOUND.name, 1)
		mockArchiveRespondsWithCodeForXAttempts(innsendingsId, 408, 1)
		try {
			innsendingApi.sendInn(soknadTestdata)
		} catch (e: Exception) {
			throw e
		}

		assertThatArkivMock()
			.hasCallCountInArchive(innsendingsId, expectedCount = 1)
			.verify()

		assertThatSoknad(innsendingsId)
			.hasStatus(ArkiveringsStatusDto.arkivert)
	}

	// The poison pill tests global deserialization robustness and is not tied to a single innsendingsId,
	// so it is kept apart from the other resource heavy tests.
	@ResourceLock(value = heavyTestsResource, mode = ResourceAccessMode.READ_WRITE)
	@Test
	fun `Poison pill followed by proper message - one file ends up in the archive`() {
		val soknadTestdata = innsendingApi.opprettEttersending(
			vedleggListe = listOf(
				Vedlegg("T1", "Bekreftelse fra fastlege"),
			)
		)
		val innsendingsId = soknadTestdata.innsendingsId

		soknadTestdata.vedleggsliste()
			.verifyHasSize(1)
			.lastOppFil(0, "OneHundred_KB.pdf")

		putPoisonPillOnKafkaTopic(UUID.randomUUID().toString())
		try {
			innsendingApi.sendInn(soknadTestdata)
		} catch (e: Exception) {
			throw e
		}
		assertThatArkivMock()
			.hasEntityInArchive(innsendingsId)
			.hasCallCountInArchive(innsendingsId, expectedCount = 1)
			.verify()

		assertThatSoknad(innsendingsId)
			.hasStatus(ArkiveringsStatusDto.arkivert)
	}

	@Test
	fun `Archive responds 404 on first two attempts - Works on third attempt`() {
		val erroneousAttempts = 2

		val soknadTestdata = innsendingApi.opprettEttersending(
			vedleggListe = listOf(
				Vedlegg("T1", "Bekreftelse fra fastlege"),
			)
		)
		val innsendingsId = soknadTestdata.innsendingsId

		soknadTestdata.vedleggsliste()
			.verifyHasSize(1)
			.lastOppFil(0, "OneHundred_KB.pdf")

		mockArchiveRespondsWithCodeForXAttempts(innsendingsId, 404, erroneousAttempts)
		try {
			innsendingApi.sendInn(soknadTestdata)
		} catch (e: Exception) {
			throw e
		}

		assertThatArkivMock()
			.hasEntityInArchive(innsendingsId)
			.hasCallCountInArchive(innsendingsId, expectedCount = erroneousAttempts + 1)
			.verify()

		assertThatSoknad(innsendingsId)
			.hasStatus(ArkiveringsStatusDto.arkivert)
	}

	@Test
	fun `Archive responds 200 but has wrong response body - Will retry`() {
		val erroneousAttempts = 3

		val soknadTestdata = innsendingApi.opprettEttersending(
			vedleggListe = listOf(
				Vedlegg("T1", "Bekreftelse fra fastlege"),
			)
		)
		val innsendingsId = soknadTestdata.innsendingsId

		soknadTestdata.vedleggsliste()
			.verifyHasSize(1)
			.lastOppFil(0, "OneHundred_KB.pdf")

		mockArchiveRespondsWithErroneousBodyForXAttempts(innsendingsId, erroneousAttempts)
		try {
			innsendingApi.sendInn(soknadTestdata)
		} catch (e: Exception) {
			throw e
		}

		assertThatArkivMock()
			.hasEntityInArchive(innsendingsId)
			.hasCallCountInArchive(innsendingsId, expectedCount = erroneousAttempts + 1)
			.verify()

		assertThatSoknad(innsendingsId)
			.hasStatus(ArkiveringsStatusDto.arkivert)
	}

	private val fileOfSize1mb = "/One_MB.pdf"

	@Test
	fun `Happy case - one submission from not logged in user ends up in the archive`() {

		val innsendingsUUID = UUID.randomUUID()
		val nologinSoknad = prepareNoLoginApplication(innsendingsUUID, mapOf(UUID.randomUUID().toString() to listOf(loadFile(fileOfSize1mb))))

		val soknadTestResponse = try {
			innsendingApi.sendInNoLoginApplication(innsendingsUUID, nologinSoknad)
		} catch (e: Exception) {
			throw e
		}

		assertTrue(soknadTestResponse.isSuccess)

		val innsendingsId = innsendingsUUID.toString()

		assertThatArkivMock()
			.hasFinishedEvent(innsendingsId)
			.hasEntityInArchive(innsendingsId)
			.hasCallCountInArchive(innsendingsId, expectedCount = 1)
			.verify()

		assertThatSoknad(innsendingsId)
			.hasStatus(ArkiveringsStatusDto.arkivert)
	}

	@Test
	fun `Granting user digital access sets the Joark access override`() {
		val innsendingsUUID = UUID.randomUUID()
		val application = prepareNoLoginApplication(
			innsendingsUUID,
			mapOf(UUID.randomUUID().toString() to listOf(loadFile(fileOfSize1mb))),
			grantUserDigitalAccess = true
		)

		assertTrue(innsendingApi.sendInNoLoginApplication(innsendingsUUID, application).isSuccess)

		assertThatArkivMock()
			.hasFinishedEvent(innsendingsUUID.toString())
			.hasEntityInArchiveWithOverstyrInnsynsregler(innsendingsUUID.toString(), "VISES_MASKINELT_GODKJENT")
			.verify()
	}

	@Test
	fun `Absent user digital access leaves the Joark access override absent`() {
		val innsendingsUUID = UUID.randomUUID()
		val application = prepareNoLoginApplication(
			innsendingsUUID,
			mapOf(UUID.randomUUID().toString() to listOf(loadFile(fileOfSize1mb)))
		)

		assertTrue(innsendingApi.sendInNoLoginApplication(innsendingsUUID, application).isSuccess)

		assertThatArkivMock()
			.hasFinishedEvent(innsendingsUUID.toString())
			.hasEntityInArchiveWithOverstyrInnsynsregler(innsendingsUUID.toString(), null)
			.verify()
	}

	// Ten submissions in a loop put a lot of load on the shared containers, so this test is kept apart
	// from the other resource heavy tests.
	@ResourceLock(value = heavyTestsResource, mode = ResourceAccessMode.READ_WRITE)
	@Test
	fun `Happy case - ten submission from not logged in user ends up in the archive`() {
		repeat(10) {
			`Happy case - one submission from not logged in user ends up in the archive`()
		}
	}

	@Test
	fun `Happy case - upload one file and then deletes it`() {

		val innsendingsId = UUID.randomUUID().toString()
		val vedleggsId = UUID.randomUUID().toString()

		val uploadResponse = innsendingApi.lastOppNoLoginFil(innsendingsId, vedleggsId, loadFile(fileOfSize1mb))
		assertTrue(uploadResponse.isSuccess)

		val fileId = uploadResponse.getOrThrow().id
		assertTrue { fileId.toString().isNotEmpty() }

		val deleteResponse = innsendingApi.slettNoLoginFil(innsendingsId, vedleggsId, fileId.toString())
		assertTrue(deleteResponse.isSuccess)
	}

	private fun prepareNoLoginApplication(
		innsendingsId: UUID,
		vedleggMap: Map<String, List<File>>,
		grantUserDigitalAccess: Boolean? = null
	): SubmitApplicationRequest {
		val attachments = vedleggMap.map { (attachmentId, files) ->
			val fileIds = files.map { file ->
				innsendingApi.lastOppNoLoginFil(innsendingsId.toString(), attachmentId, file).getOrThrow().id
			}
			AttachmentDto(
				attachmentCode = generateVedleggsnr(),
				label = "Inntektsopplysninger for selvstendig næringsdrivende og frilansere som skal ha foreldrepenger eller svangerskapspenger.",
				uploadStatus = OpplastingsStatusDto.lastetOpp,
				title = "Vedleggseksempel",
				description = "Dette er opplysninger som er nødvendig for beregning av utbetaling av foreldrepenger eller svangerskapspenger.",
				fileIds = fileIds,
			)
		}
		return SubmitApplicationRequestBuilder(
			brukerId = testpersonid,
			status = SoknadsStatusDto.utfylt,
			grantUserDigitalAccess = grantUserDigitalAccess
		)
			.medVedlegg(attachments)
			.build()
	}

	private fun loadFile(fileName: String): File {
		val resource = Config::class.java.getResource(fileName) ?: throw Exception("$fileName not found")
		return File(resource.toURI())
	}


	private fun setSafFetchBehaviour(uuid: String, behaviour: String = SafResponses.NOT_FOUND.name, attempts: Int = -1) {
		val url = env.getUrlForArkivMock() + "/arkiv-mock/mock-saf-response/$uuid/$behaviour/$attempts"
		performPutCall(url)
	}

	private fun mockArchiveRespondsWithCodeForXAttempts(uuid: String, status: Int, forAttempts: Int) {
		val url = env.getUrlForArkivMock() + "/arkiv-mock/response-behaviour/mock-response/$uuid/$status/$forAttempts"
		performPutCall(url)
	}

	private fun mockArchiveRespondsWithErroneousBodyForXAttempts(uuid: String, forAttempts: Int) {
		val url =
			env.getUrlForArkivMock() + "/arkiv-mock/response-behaviour/set-status-ok-with-erroneous-body/$uuid/$forAttempts"
		performPutCall(url)
	}
}

/**
 * Resource lock shared by the tests that should not run at the same time as each other. Note that
 * [org.junit.jupiter.api.parallel.Resources.GLOBAL] (and thereby `@Isolated`) must not be used on a test method here:
 * a method level global lock makes JUnit run the whole test run in a single thread.
 */
private const val heavyTestsResource = "end-to-end-heavy-tests"
