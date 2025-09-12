package no.nav.soknad.arkivering.arkiveringsystemtests

import no.nav.soknad.arkivering.LoadTests
import no.nav.soknad.arkivering.arkiveringsystemtests.environment.EmbeddedDockerImages
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.assertDoesNotThrow


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


	@Disabled("Dropper denne testen for naa siden den ikke gir nok verdi")
	@Test
	fun `TC01 - Innsending av 10 soknader, hver med to vedlegg pa 38MB`() {
		loadTests.`TC01 - Innsending av 10 soknader, hver med to vedlegg pa 38MB`()
	}

	@Test
	fun `TC02 - Innsending av 100 soknader, hver med tre vedlegg pa 2MB`() {
		loadTests.`TC02 - Innsending av 100 soknader, hver med tre vedlegg pa 2MB`()
	}

	@Disabled("Dropper denne testen da last testing blir gjort i test TC07")
	@Test
	fun `TC03 - Innsending av 1000 soknader, hver med to vedlegg pa 1MB`() {
		loadTests.`TC03 - Innsending av 1000 soknader, hver med to vedlegg pa 1MB`()
	}

	@Test
	fun `TC04 - Innsending av 10 soknader fra ikke innlogget bruker, hver med ett vedlegg pa 1MB`() {
		assertDoesNotThrow {
			loadTests.`TC04 - Innsending av 10 soknader fra ikke innlogget bruker, hver med ett vedlegg pa 1MB`()
		}
	}

	@Disabled("Dropper denne testen da det ikke er en ende-til-ende test")
	@Test
	fun `TC05 - Opplasting av en fil deretter sletter den`() {
		loadTests.`TC05 - Opplasting av en fil deretter sletter den`()
	}

	@Disabled("Dropper denne testen da last testing blir gjort i test TC07")
	@Test
	fun `TC06 - Innsending av 1000 soknader fra ikke innlogget bruker, hver med 2 vedlegg pa 1MB`() {
		loadTests.`TC06 - Innsending av 1000 soknader fra ikke innlogget bruker, hver med 2 vedlegg pa 1MB`()
	}

	@Test
	fun `TC07 - Innsending av 1000 soknader fra innlogget og ikke innlogget bruker`() {
		loadTests.`TC07 - Innsending av 1000 soknader fra innlogget og ikke innlogget bruker`()
	}
}
