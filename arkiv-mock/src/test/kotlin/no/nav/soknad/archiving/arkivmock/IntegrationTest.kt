package no.nav.soknad.archiving.arkivmock

import no.nav.soknad.archiving.arkivmock.dto.ArkivData
import no.nav.soknad.archiving.arkivmock.dto.Bruker
import no.nav.soknad.archiving.arkivmock.repository.ArkivRepository
import no.nav.soknad.archiving.arkivmock.rest.ArkivRestInterface
import no.nav.soknad.archiving.arkivmock.rest.BehaviourMocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.LocalDate
import java.time.LocalDateTime
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
	fun `Will save to database when receiving message`() {
		val timeWhenStarting = LocalDateTime.now().minusSeconds(1)
		val id = UUID.randomUUID().toString()
		behaviourMocking.setNormalResponseBehaviour(id)

		arkivRestInterface.receiveMessage(createRequestData(id))

		val res = arkivRepository.findById(id)
		assertTrue(res.isPresent)
		val result = res.get()
		assertEquals(id, result.id)
		assertEquals(tema, result.tema)
		assertEquals(title, result.title)
		assertTrue(result.timesaved.isBefore(LocalDateTime.now().plusSeconds(1)))
		assertTrue(result.timesaved.isAfter(timeWhenStarting))
	}

	private fun createRequestData(personId: String) =
		ArkivData(
			Bruker(personId, "FNR"), LocalDate.now().format(DateTimeFormatter.ISO_DATE), emptyList(),
			personId, "INNGAAENDE", "NAV_NO", tema, title
		)
}
