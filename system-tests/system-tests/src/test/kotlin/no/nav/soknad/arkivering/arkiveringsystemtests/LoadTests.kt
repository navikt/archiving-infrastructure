package no.nav.soknad.arkivering.arkiveringsystemtests

import no.nav.soknad.arkivering.LoadTests
import no.nav.soknad.arkivering.arkiveringsystemtests.environment.EmbeddedDockerImages
import no.nav.soknad.arkivering.innsending.SoknadsfillagerApi
import no.nav.soknad.arkivering.innsending.SoknadsmottakerApi
import no.nav.soknad.arkivering.innsending.filesApiWithoutOAuth2
import no.nav.soknad.arkivering.innsending.soknadApiWithoutOAuth2
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * Kjellman Load Tests
 *
 * The state of the art Load Test tool is Gatling, which is named after a machine gun, due to its rapid firing.
 * The Kjellman Load Tests are similarly named after The Kjellman Machine Gun from Sweden, being one of the first
 * fully automatic weapons ever conceived. Just as the Kjellman Machine Gun is a less sophisticated product than
 * the Gatling Machine Gun, the Kjellman Load Tests can be seen as a less sophisticated product than the Gatling
 * Load Tests.
 */
@DisplayName("Load-Tests")
@EnabledIfSystemProperty(named = "runLoadtests", matches = "true")
class KjellmanLoadTests : SystemTestBase() {

	private val embeddedDockerImages = EmbeddedDockerImages()
	private lateinit var loadTests: LoadTests

	@BeforeAll
	fun setup() {
		if (targetEnvironment == "embedded") {
			env.addEmbeddedDockerImages(embeddedDockerImages)
			embeddedDockerImages.startContainers()
		}

		setUp()
		loadTests = LoadTests(config, kafkaListener, false)
	}

	@AfterAll
	fun teardown() {
		tearDown()
		if (targetEnvironment == "embedded") {
			embeddedDockerImages.stopContainers()
		}
	}


	@Test
	fun `100 simultaneous entities, 2 times 2 MB each`() {
		loadTests.`100 simultaneous entities, 2 times 2 MB each`()
	}

	@Test
	fun `100 simultaneous entities, 20 times 1 MB each`() {
		loadTests.`100 simultaneous entities, 20 times 1 MB each`()
	}

	@Test
	fun `2000 simultaneous entities, 1 times 1 byte each`() {
		loadTests.`2000 simultaneous entities, 1 times 1 byte each`()
	}

	@Test
	fun `5 simultaneous entities, 4 times 38 MB each`() {
		loadTests.`5 simultaneous entities, 4 times 38 MB each`()
	}
}
