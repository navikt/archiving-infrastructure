package no.nav.soknad.arkivering.arkivmock

import no.nav.soknad.arkivering.arkivmock.dto.ArkivData
import no.nav.soknad.arkivering.arkivmock.dto.Bruker
import no.nav.soknad.arkivering.arkivmock.repository.ArkivRepository
import no.nav.soknad.arkivering.arkivmock.rest.ArkivRestInterface
import no.nav.soknad.arkivering.arkivmock.rest.BehaviourMocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.*

@SpringBootTest
class IntegrationTest {
	private val tema = "Tema"
	private val title = "Title"

	@Autowired
	private lateinit var arkivRestInterface: ArkivRestInterface

	@Autowired
	private lateinit var arkivRepository: ArkivRepository

	@Autowired
	private lateinit var behaviourMocking: BehaviourMocking

	@Test
	fun `Will save to database when receiving message and can reset afterwards`() {
		val timeWhenStarting = LocalDateTime.now().toInstant(ZoneOffset.UTC).toEpochMilli()
		val id = UUID.randomUUID().toString()
		behaviourMocking.setNormalResponseBehaviour(id)

		arkivRestInterface.receiveMessage(createRequestData(id))

		val res = arkivRepository.findById(id)
		assertTrue(res.isPresent)
		val result = res.get()
		assertEquals(id, result.id)
		assertEquals(tema, result.tema)
		assertEquals(title, result.title)
		assertTrue(result.timesaved >= timeWhenStarting)
		assertTrue(result.timesaved <= LocalDateTime.now().toInstant(ZoneOffset.UTC).toEpochMilli())

		arkivRestInterface.reset()

		assertEquals(0, arkivRepository.count())
	}

	private fun createRequestData(personId: String) =
		ArkivData(
			Bruker(personId, "FNR"), LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME), emptyList(),
			personId, "INNGAAENDE", "NAV_NO", tema, title
		)
}
