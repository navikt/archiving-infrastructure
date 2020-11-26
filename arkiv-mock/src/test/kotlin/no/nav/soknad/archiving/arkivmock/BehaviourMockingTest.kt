package no.nav.soknad.archiving.arkivmock

import no.nav.soknad.archiving.arkivmock.dto.Bruker
import no.nav.soknad.archiving.arkivmock.dto.ArkivData
import no.nav.soknad.archiving.arkivmock.exceptions.InternalServerErrorException
import no.nav.soknad.archiving.arkivmock.exceptions.NotFoundException
import no.nav.soknad.archiving.arkivmock.rest.BehaviourMocking
import no.nav.soknad.archiving.arkivmock.rest.ArkivRestInterface
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.*

@SpringBootTest
class BehaviourMockingTest {

	@Autowired
	private lateinit var arkivRestInterface: ArkivRestInterface

	@Autowired
	private lateinit var behaviourMocking: BehaviourMocking

	private val id = UUID.randomUUID().toString()

	@BeforeEach
	fun setup() {
		behaviourMocking.resetMockResponseBehaviour(id)
	}

	@Test
	fun `No specified mock behaviour - Will save to DB`() {
		val response = arkivRestInterface.receiveMessage(createRequestData(id))

		assertEquals(HttpStatus.OK, response.statusCode)
		assertTrue(response.body!!.contains("{\"dokumenter\":[],\"journalpostId\":\""))
		assertTrue(response.body!!.contains("\",\"journalpostferdigstilt\":true,\"journalstatus\":\"MIDLERTIDIG\",\"melding\":\"null\"}"))

		arkivRestInterface.lookup(id)

		assertEquals(1, behaviourMocking.getNumberOfCalls(id))
	}

	@Test
	fun `Mock response with Ok Status but wrong body - Will save to DB`() {
		behaviourMocking.mockOkResponseWithErroneousBody(id, 1)

		val response = arkivRestInterface.receiveMessage(createRequestData(id))

		assertEquals(HttpStatus.OK, response.statusCode)
		assertEquals("THIS_IS_A_MOCKED_INVALID_RESPONSE", response.body)

		arkivRestInterface.lookup(id)

		assertEquals(1, behaviourMocking.getNumberOfCalls(id))
	}

	@Test
	fun `Responds with status 404 two times, third time works - Will save to DB on third attempt`() {
		behaviourMocking.mockResponseBehaviour(id, 404, 2)

		assertThrows<NotFoundException> {
			arkivRestInterface.receiveMessage(createRequestData(id))
		}
		assertThrows<ResponseStatusException> {
			arkivRestInterface.lookup(id)
		}

		assertThrows<NotFoundException> {
			arkivRestInterface.receiveMessage(createRequestData(id))
		}
		assertThrows<ResponseStatusException> {
			arkivRestInterface.lookup(id)
		}

		val response = arkivRestInterface.receiveMessage(createRequestData(id))
		assertEquals(HttpStatus.OK, response.statusCode)
		arkivRestInterface.lookup(id)

		assertEquals(3, behaviourMocking.getNumberOfCalls(id))
	}

	@Test
	fun `Responds with status 500 three times, third time works - Will save to DB on third attempt`() {
		behaviourMocking.mockResponseBehaviour(id, 500, 3)

		assertThrows<InternalServerErrorException> {
			arkivRestInterface.receiveMessage(createRequestData(id))
		}
		assertThrows<ResponseStatusException> {
			arkivRestInterface.lookup(id)
		}

		assertThrows<InternalServerErrorException> {
			arkivRestInterface.receiveMessage(createRequestData(id))
		}
		assertThrows<ResponseStatusException> {
			arkivRestInterface.lookup(id)
		}

		assertThrows<InternalServerErrorException> {
			arkivRestInterface.receiveMessage(createRequestData(id))
		}
		assertThrows<ResponseStatusException> {
			arkivRestInterface.lookup(id)
		}

		val response = arkivRestInterface.receiveMessage(createRequestData(id))
		assertEquals(HttpStatus.OK, response.statusCode)
		arkivRestInterface.lookup(id)

		assertEquals(4, behaviourMocking.getNumberOfCalls(id))
	}

	private fun createRequestData(personId: String) =
		ArkivData(Bruker(personId, "FNR"), LocalDate.now().format(DateTimeFormatter.ISO_DATE), emptyList(),
			personId, "INNGAAENDE", "NAV_NO", "tema", "tittel")
}
