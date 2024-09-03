package no.nav.soknad.arkivering.arkiveringsystemtests

import no.nav.soknad.arkivering.arkiveringsystemtests.environment.EmbeddedDockerImages
import no.nav.soknad.arkivering.dto.SafResponses
import no.nav.soknad.arkivering.innsending.*
import no.nav.soknad.arkivering.innsending.model.ArkiveringsStatusDto
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.*

class EndToEndTests : SystemTestBase() {
	private val embeddedDockerImages = EmbeddedDockerImages()
	private lateinit var soknadsmottakerApi: SoknadsmottakerApi
	private lateinit var innsendingApi: InnsendingApi

	@BeforeAll
	fun setup() {
		if (!isExternalEnvironment) {
			env.addEmbeddedDockerImages(embeddedDockerImages)
			embeddedDockerImages.startContainers()
		}

		setUp()
		soknadsmottakerApi = SoknadsmottakerApi(soknadApiWithoutOAuth2(config))
		innsendingApi = InnsendingApi(config)
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
		val soknadTestdata = innsendingApi.opprettEttersending()
		val innsendingsId = soknadTestdata.innsendingsId

		soknadTestdata.vedleggsliste()
			.verifyHasSize(1)
			.lastOppFil(0, "OneHundred_KB.pdf")

		innsendingApi.sendInn(soknadTestdata)

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

		innsendingApi.sendInn(soknadTestdata)

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
		innsendingApi.sendInn(soknadTestdata)

		assertThatArkivMock()
			.hasFailureEvent(innsendingsId)
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
		innsendingApi.sendInn(soknadTestdata)

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
		innsendingApi.sendInn(soknadTestdata)

		assertThatArkivMock()
			.hasFinishedEvent(innsendingsId)
			.verify()

		assertThatSoknad(innsendingsId)
			.hasStatus(ArkiveringsStatusDto.ikkeSatt)
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
		innsendingApi.sendInn(soknadTestdata)

		assertThatArkivMock()
			.hasCallCountInArchive(innsendingsId, expectedCount = 1)
			.verify()

		assertThatSoknad(innsendingsId)
			.hasStatus(ArkiveringsStatusDto.ikkeSatt)
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

		putPoisonPillOnKafkaTopic(UUID.randomUUID().toString())
		innsendingApi.sendInn(soknadTestdata)
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
		innsendingApi.sendInn(soknadTestdata)

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
		innsendingApi.sendInn(soknadTestdata)

		assertThatArkivMock()
			.hasEntityInArchive(innsendingsId)
			.hasCallCountInArchive(innsendingsId, expectedCount = erroneousAttempts + 1)
			.verify()

		assertThatSoknad(innsendingsId)
			.hasStatus(ArkiveringsStatusDto.arkivert)
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
