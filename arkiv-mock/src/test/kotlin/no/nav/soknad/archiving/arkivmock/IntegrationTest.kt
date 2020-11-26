package no.nav.soknad.archiving.arkivmock

import no.nav.soknad.archiving.arkivmock.dto.Bruker
import no.nav.soknad.archiving.arkivmock.dto.ArkivData
import no.nav.soknad.archiving.arkivmock.rest.BehaviourMocking
import no.nav.soknad.archiving.arkivmock.rest.ArkivRestInterface
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.web.server.ResponseStatusException
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
	private lateinit var behaviourMocking: BehaviourMocking

	@Test
	fun `Will save to database when receiving message`() {
		val timeWhenStarting = LocalDateTime.now().minusSeconds(1)
		val id = UUID.randomUUID().toString()
		behaviourMocking.setNormalResponseBehaviour(id)

		arkivRestInterface.receiveMessage(createRequestData(id))

		val result = arkivRestInterface.lookup(id)
		assertEquals(id, result.id)
		assertEquals(tema, result.tema)
		assertEquals(title, result.title)
		assertTrue(result.timesaved.isBefore(LocalDateTime.now().plusSeconds(1)))
		assertTrue(result.timesaved.isAfter(timeWhenStarting))
	}

	@Test
	fun `Lookup throws exception if not found`() {
		assertThrows<ResponseStatusException> {
			arkivRestInterface.lookup("lookup key that is not in the database")
		}
	}

	private fun createRequestData(personId: String) =
		ArkivData(Bruker(personId, "FNR"), LocalDate.now().format(DateTimeFormatter.ISO_DATE), emptyList(),
			personId, "INNGAAENDE", "NAV_NO", tema, title)
}
