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
	fun `TC01 - Innsending av 10 soknader, hver med to vedlegg pa 38MB`() {
		loadTests.`TC01 - Innsending av 10 soknader, hver med to vedlegg pa 38MB`()
	}

	@Test
	fun `TC02 - Innsending av 100 soknader, hver med tre vedlegg pa 2MB`() {
		loadTests.`TC02 - Innsending av 100 soknader, hver med tre vedlegg pa 2MB`()
	}

	@Test
	fun `TC03 - Innsending av 1000 soknader, hver med to vedlegg pa 1MB`() {
		loadTests.`TC03 - Innsending av 1000 soknader, hver med to vedlegg pa 1MB`()
	}

}
