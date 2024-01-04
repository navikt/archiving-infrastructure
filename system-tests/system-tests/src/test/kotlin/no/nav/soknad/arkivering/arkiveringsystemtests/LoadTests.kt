package no.nav.soknad.arkivering.arkiveringsystemtests

import no.nav.soknad.arkivering.LoadTests
import no.nav.soknad.arkivering.arkiveringsystemtests.environment.EmbeddedDockerImages
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
		Thread.sleep(10_000) // Vent litt slik at infrastrukturen er oppe og går før testene kjører
		loadTests = LoadTests(config, kafkaListener, targetEnvironment != "embedded")
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

	@Test
	fun `500 applications with 1 attachments each 1 MB`() {
		loadTests.`500 applications with 1 attachments each 1 MB`()
	}

	@Test
	fun `100 applications with 2 attachments each 1 MB`() {
		loadTests.`100 applications with 2 attachments each 1 MB`()
	}

	@Test
	fun `25 applications with 10 attachments each 10 MB`() {
		loadTests.`25 applications with 10 attachments each 10 MB`()
	}

	@Test
	fun `5 applications with 3 attachments each 50 MB`() {
		loadTests.`5 applications with 3 attachments each 50 MB`()
	}
}
