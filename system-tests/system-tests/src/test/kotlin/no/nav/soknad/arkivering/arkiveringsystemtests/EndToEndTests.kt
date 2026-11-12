package no.nav.soknad.arkivering.arkiveringsystemtests

import no.nav.soknad.arkivering.Config
import no.nav.soknad.arkivering.arkiveringsystemtests.environment.EmbeddedDockerImages
import no.nav.soknad.arkivering.dto.ArchiveDocument
import no.nav.soknad.arkivering.dto.SafResponses
import no.nav.soknad.arkivering.innsending.*
import no.nav.soknad.arkivering.innsending.model.ArkiveringsStatusDto
import no.nav.soknad.arkivering.innsending.model.AttachmentDto
import no.nav.soknad.arkivering.innsending.model.Mimetype
import no.nav.soknad.arkivering.innsending.model.OpplastingsStatusDto
import no.nav.soknad.arkivering.innsending.model.SkjemaDokumentDto
import no.nav.soknad.arkivering.innsending.model.SkjemaDto
import no.nav.soknad.arkivering.innsending.model.SoknadsStatusDto
import no.nav.soknad.arkivering.innsending.model.SubmitApplicationRequest
import no.nav.soknad.arkivering.tokensupport.E2eTokenConfig
import no.nav.soknad.arkivering.utils.SubmitApplicationRequestBuilder
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.*
import javax.imageio.ImageIO

@Execution(ExecutionMode.CONCURRENT)
class EndToEndTests : SystemTestBase() {
	private val embeddedDockerImages = EmbeddedDockerImages()
	private lateinit var innsendingApi: InnsendingApi

	val testpersonid = E2eTokenConfig.TEST_PERSON_ID

	@BeforeAll
	fun setup() {
		if (!isExternalEnvironment) {
			env.addEmbeddedDockerImages(embeddedDockerImages)
			embeddedDockerImages.startContainers()
		}

		setUp()
		innsendingApi = InnsendingApi(config, tokens)
	}

	@AfterAll
	fun teardown() {
		tearDown()

		if (!isExternalEnvironment) {
			embeddedDockerImages.stopContainers()
		}
	}

	// Uten -De2eTokenValidation=true ignorerer innsending-api tokenene, så resten av testene ville passert med feil token.
	// Denne testen sikrer at tokenene har issueren og claimene innsending-api krever når valideringen er slått på.
	@Test
	fun `tokens sent to innsending-api have the issuer and claims used in production`() {
		assumeTrue(tokens.enabled, "Ingen auth-server i dette miljøet")

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
	// Hoppes over uten -De2eTokenValidation=true. Da validerer ikke innsending-api token, og testene ville feilet uten grunn.
	// Med flagget og containere startet av testene kjører de alltid, slik at feil oppsett (for eksempel 503 fordi Texas mangler)
	// gir rød test og ikke en stille overhopping.
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
		assumeTrue(E2eTokenConfig.tokenValidationEnabled, "Tokenvalidering i innsending-api er ikke slått på (-De2eTokenValidation=true)")
	}

	private fun assertRejected(endpoint: Endpoint, token: String?, description: String) {
		val status = statusFor(endpoint, token)
		assertTrue(status in rejectedStatuses, "${endpoint.method} ${endpoint.path} med $description: forventet avvisning, fikk $status")
	}

	private fun assertAllowed(endpoint: Endpoint, token: String?, description: String) {
		val status = statusFor(endpoint, token)
		assertTrue(
			status !in rejectedStatuses && status != tokenValidationUnavailableStatus,
			"${endpoint.method} ${endpoint.path} med $description: forventet tilgang, fikk $status",
		)
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

	// Innsending-api avviser med 401 eller 403 etter migreringen til Spring Security og Texas (innsending-api#518)
	private val rejectedStatuses = setOf(401, 403)

	// Innsending-api svarer 503 når Texas ikke kan validere tokenet. Det er verken avvisning eller tilgang
	private val tokenValidationUnavailableStatus = 503

	@Test
	fun `Happy case - one file ends up in the archive`() {
		val soknadTestdata = innsendingApi.opprettEttersending()
		val innsendingsId = soknadTestdata.innsendingsId

		soknadTestdata.vedleggsliste()
			.verifyHasSize(1)
			.lastOppFil(0, "OneHundred_KB.pdf")

		assertThatArkivMock()
			.hasFinishedEvent(innsendingsId)
			.hasEntityInArchive(innsendingsId)
			.hasCallCountInArchive(innsendingsId, expectedCount = 1)
			.verify { innsendingApi.sendInn(soknadTestdata).getOrThrow() }

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

		assertThatArkivMock()
			.hasFinishedEvent(innsendingsId)
			.hasEntityInArchive(innsendingsId)
			.hasCallCountInArchive(innsendingsId, expectedCount = 1)
			.verify { innsendingApi.sendInn(soknadTestdata).getOrThrow() }

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

		assertThatArkivMock()
			.hasFailureEvent(innsendingsId)
			.hasNoEntityInArchive(innsendingsId, timeoutInMs = 5_000L)
			.hasCallCountInArchive(innsendingsId, expectedCount = attemptsThanSoknadsarkivererWillPerform)
			.verify { innsendingApi.sendInn(soknadTestdata).getOrThrow() }

		assertThatSoknad(innsendingsId)
			.hasStatus(ArkiveringsStatusDto.arkiveringFeilet)
	}

	@Test
	fun `Archive responds 409 - application already archived`() {
		val soknadTestdata = innsendingApi.opprettEttersending(
			vedleggListe = listOf(Vedlegg("N6", "Bekreftelse på skoleplass"))
		)
		val innsendingsId = soknadTestdata.innsendingsId

		soknadTestdata.vedleggsliste()
			.verifyHasSize(1)
			.lastOppFil(0, "OneHundred_KB.pdf")

		mockArchiveRespondsWithCodeForXAttempts(innsendingsId, 409, 1)

		assertThatArkivMock()
			.hasFinishedEvent(innsendingsId)
			.hasNoEntityInArchive(innsendingsId)
			.hasCallCountInArchive(innsendingsId, expectedCount = 1)
			.verify { innsendingApi.sendInn(soknadTestdata).getOrThrow() }

		assertThatSoknad(innsendingsId)
			.hasStatus(ArkiveringsStatusDto.arkivert)
	}


	@Test
	fun `SAF respond with journalpost - application already archived`() {
		val soknadTestdata = innsendingApi.opprettEttersending(
			vedleggListe = listOf(Vedlegg("N6", "Bekreftelse på skoleplass"))
		)
		val innsendingsId = soknadTestdata.innsendingsId

		soknadTestdata.vedleggsliste()
			.verifyHasSize(1)
			.lastOppFil(0, "OneHundred_KB.pdf")

		setSafFetchBehaviour(innsendingsId, SafResponses.OK.name, -1)

		assertThatArkivMock()
			.hasFinishedEvent(innsendingsId)
			.hasNoEntityInArchive(innsendingsId)
			.hasNoCallInArchive(innsendingsId)
			.verify { innsendingApi.sendInn(soknadTestdata).getOrThrow() }

		assertThatSoknad(innsendingsId)
			.hasStatus(ArkiveringsStatusDto.arkivert)
	}

	@Test
	fun `Request responds with 408 - second attempt already archived from SAF`() {
		val soknadTestdata = innsendingApi.opprettEttersending(
			vedleggListe = listOf(Vedlegg("N6", "Bekreftelse på skoleplass"))
		)
		val innsendingsId = soknadTestdata.innsendingsId

		soknadTestdata.vedleggsliste()
			.verifyHasSize(1)
			.lastOppFil(0, "OneHundred_KB.pdf")

		setSafFetchBehaviour(innsendingsId, SafResponses.NOT_FOUND.name, 1)
		mockArchiveRespondsWithCodeForXAttempts(innsendingsId, 408, 1)

		assertThatArkivMock()
			.hasCallCountInArchive(innsendingsId, expectedCount = 1)
			.verify { innsendingApi.sendInn(soknadTestdata).getOrThrow() }

		assertThatSoknad(innsendingsId)
			.hasStatus(ArkiveringsStatusDto.arkivert)
	}

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

		assertThatArkivMock()
			.hasFinishedEvent(innsendingsId)
			.hasEntityInArchive(innsendingsId)
			.hasCallCountInArchive(innsendingsId, expectedCount = 1)
			.verify {
				// Same key means both records use the same partition. The producer waits for the poison
				// pill acknowledgement, so Kafka must process it before the valid submission record.
				putPoisonPillOnKafkaTopic(innsendingsId)
				innsendingApi.sendInn(soknadTestdata).getOrThrow()
			}

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

		assertThatArkivMock()
			.hasEntityInArchive(innsendingsId)
			.hasCallCountInArchive(innsendingsId, expectedCount = erroneousAttempts + 1)
			.verify { innsendingApi.sendInn(soknadTestdata).getOrThrow() }

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

		assertThatArkivMock()
			.hasEntityInArchive(innsendingsId)
			.hasCallCountInArchive(innsendingsId, expectedCount = erroneousAttempts + 1)
			.verify { innsendingApi.sendInn(soknadTestdata).getOrThrow() }

		assertThatSoknad(innsendingsId)
			.hasStatus(ArkiveringsStatusDto.arkivert)
	}

	private val fileOfSize1mb = "/One_MB.pdf"

	@Test
	fun `Happy case - one submission from not logged in user ends up in the archive`() {

		val innsendingsUUID = UUID.randomUUID()
		val nologinSoknad = prepareNoLoginApplication(innsendingsUUID, mapOf(UUID.randomUUID().toString() to listOf(loadFile(fileOfSize1mb))))

		val innsendingsId = innsendingsUUID.toString()

		assertThatArkivMock()
			.hasFinishedEvent(innsendingsId)
			.hasEntityInArchive(innsendingsId)
			.hasCallCountInArchive(innsendingsId, expectedCount = 1)
			.hasDocumentsInArchive(
				innsendingsId,
				listOf(
					ArchiveDocument("Søknad", "NAV 08-07.04D", listOf("ARKIV", "ORIGINAL")),
					ArchiveDocument("Vedlegg", "T1", listOf("ARKIV")),
				)
			)
			.hasEntityInArchiveWithOverstyrInnsynsregler(innsendingsId, null)
			.verify { innsendingApi.sendInNoLoginApplication(innsendingsUUID, nologinSoknad).getOrThrow() }

		assertThatSoknad(innsendingsId)
			.hasStatus(ArkiveringsStatusDto.arkivert)
	}

	@Test
	fun `Happy case - two PDF files for one attachment are archived as one two-page document`() {
		val innsendingsUUID = UUID.randomUUID()
		val attachmentId = UUID.randomUUID().toString()
		val pdf = loadFile("/Synthetic_one_page.pdf")
		val nologinSoknad = prepareNoLoginApplication(innsendingsUUID, mapOf(attachmentId to listOf(pdf, pdf)))
		val attachment = checkNotNull(nologinSoknad.attachments).single()
		val fileIds = checkNotNull(attachment.fileIds)
		assertEquals(2, fileIds.size)
		assertEquals(2, fileIds.toSet().size)

		val innsendingsId = innsendingsUUID.toString()
		assertThatArkivMock()
			.hasFinishedEvent(innsendingsId)
			.hasEntityInArchive(innsendingsId)
			.hasCallCountInArchive(innsendingsId, expectedCount = 1)
			.hasDocumentsInArchive(
				innsendingsId,
				listOf(
					ArchiveDocument("Søknad", "NAV 08-07.04D", listOf("ARKIV", "ORIGINAL")),
					ArchiveDocument("Vedlegg", "T1", listOf("ARKIV"), antallSider = 2),
				)
			)
			.verify { innsendingApi.sendInNoLoginApplication(innsendingsUUID, nologinSoknad).getOrThrow() }

		assertThatSoknad(innsendingsId)
			.hasStatus(ArkiveringsStatusDto.arkivert)
	}

	@Test
	fun `Happy case - uploaded PNG is converted and archived as one-page PDF`() {
		val innsendingsUUID = UUID.randomUUID()
		val imageFile = Files.createTempFile("synthetic-attachment-", ".png").toFile()
		try {
			val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB)
			image.createGraphics().apply {
				color = Color(40, 100, 180)
				fillRect(0, 0, image.width, image.height)
				dispose()
			}
			check(ImageIO.write(image, "png", imageFile)) { "PNG writer is unavailable" }

			val nologinSoknad = prepareNoLoginApplication(
				innsendingsUUID,
				mapOf(UUID.randomUUID().toString() to listOf(imageFile)),
			)
			val innsendingsId = innsendingsUUID.toString()
			assertThatArkivMock()
				.hasFinishedEvent(innsendingsId)
				.hasEntityInArchive(innsendingsId)
				.hasCallCountInArchive(innsendingsId, expectedCount = 1)
				.hasDocumentsInArchive(
					innsendingsId,
					listOf(
						ArchiveDocument("Søknad", "NAV 08-07.04D", listOf("ARKIV", "ORIGINAL")),
						ArchiveDocument("Vedlegg", "T1", listOf("ARKIV"), antallSider = 1),
					)
				)
				.verify { innsendingApi.sendInNoLoginApplication(innsendingsUUID, nologinSoknad).getOrThrow() }

			assertThatSoknad(innsendingsId)
				.hasStatus(ArkiveringsStatusDto.arkivert)
		} finally {
			Files.deleteIfExists(imageFile.toPath())
		}
	}

	@Test
	fun `Happy case - logged in first submission archives PDF JSON variant and attachment`() {
		val formNumber = "NAV 08-07.04D"
		val mainDocumentTitle = "Søknad"
		val attachmentTitle = "Vedlegg"
		val attachmentId = UUID.randomUUID().toString()
		val mainDocument = loadFile("/mellomstor.pdf")
		val requestBuilder = SubmitApplicationRequestBuilder(
			brukerId = testpersonid,
			skjemanr = formNumber,
			tittel = mainDocumentTitle,
			tema = "SYK",
			status = SoknadsStatusDto.utfylt,
			hoveddokument = mainDocument.readBytes(),
		)
		val jsonVariant = requestBuilder.hoveddokumentVariant
		val applicationMetadata = SkjemaDto(
			skjemanr = formNumber,
			tittel = mainDocumentTitle,
			tema = "SYK",
			spraak = "nb_NO",
			hoveddokument = SkjemaDokumentDto(
				vedleggsnr = formNumber,
				tittel = mainDocumentTitle,
				label = mainDocumentTitle,
				pakrevd = true,
				mimetype = Mimetype.applicationSlashPdf,
			),
			hoveddokumentVariant = SkjemaDokumentDto(
				vedleggsnr = formNumber,
				tittel = mainDocumentTitle,
				label = mainDocumentTitle,
				pakrevd = false,
				mimetype = Mimetype.applicationSlashJson,
				document = jsonVariant,
			),
			status = SoknadsStatusDto.opprettet,
			vedleggsListe = listOf(
				SkjemaDokumentDto(
					vedleggsnr = "T1",
					tittel = attachmentTitle,
					label = attachmentTitle,
					pakrevd = false,
					mimetype = Mimetype.applicationSlashPdf,
					formioId = attachmentId,
				)
			),
		)
		val innsendingsUUID = UUID.fromString(innsendingApi.createDigitalApplication(applicationMetadata).getOrThrow())
		val innsendingsId = innsendingsUUID.toString()
		val uploadedAttachment = innsendingApi
			.uploadDigitalAttachmentFile(innsendingsUUID, attachmentId, loadFile(fileOfSize1mb))
			.getOrThrow()
		val attachment = AttachmentDto(
			attachmentCode = "T1",
			label = attachmentTitle,
			uploadStatus = OpplastingsStatusDto.lastetOpp,
			title = attachmentTitle,
			fileIds = listOf(uploadedAttachment.id),
		)
		val request = requestBuilder
			.medVedlegg(attachment)
			.build()

		assertThatArkivMock()
			.hasFinishedEvent(innsendingsId)
			.hasEntityInArchive(innsendingsId)
			.hasCallCountInArchive(innsendingsId, expectedCount = 1)
			.hasDocumentsInArchive(
				innsendingsId,
				listOf(
					ArchiveDocument(mainDocumentTitle, formNumber, listOf("ARKIV", "ORIGINAL")),
					ArchiveDocument(attachmentTitle, "T1", listOf("ARKIV")),
				)
			)
			.verify { innsendingApi.submitDigitalApplication(innsendingsUUID, request).getOrThrow() }

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

		assertThatArkivMock()
			.hasFinishedEvent(innsendingsUUID.toString())
			.hasEntityInArchiveWithOverstyrInnsynsregler(innsendingsUUID.toString(), "VISES_MASKINELT_GODKJENT")
			.verify { innsendingApi.sendInNoLoginApplication(innsendingsUUID, application).getOrThrow() }
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
				attachmentCode = "T1",
				label = "Vedlegg",
				uploadStatus = OpplastingsStatusDto.lastetOpp,
				title = "Vedlegg",
				description = "Dette er opplysninger som er nødvendig for beregning av utbetaling av foreldrepenger eller svangerskapspenger.",
				fileIds = fileIds,
			)
		}
		return SubmitApplicationRequestBuilder(
			brukerId = testpersonid,
			skjemanr = "NAV 08-07.04D",
			tittel = "Søknad",
			tema = "SYK",
			hoveddokument = loadFile("/mellomstor.pdf").readBytes(),
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
