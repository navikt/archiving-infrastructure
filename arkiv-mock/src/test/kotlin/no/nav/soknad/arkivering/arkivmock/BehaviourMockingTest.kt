package no.nav.soknad.arkivering.arkivmock

import com.nhaarman.mockitokotlin2.*
import no.nav.soknad.arkivering.arkivmock.dto.ArkivData
import no.nav.soknad.arkivering.arkivmock.dto.Bruker
import no.nav.soknad.arkivering.arkivmock.exceptions.InternalServerErrorException
import no.nav.soknad.arkivering.arkivmock.exceptions.NotFoundException
import no.nav.soknad.arkivering.arkivmock.repository.ArkivRepository
import no.nav.soknad.arkivering.arkivmock.rest.ArkivRestInterface
import no.nav.soknad.arkivering.arkivmock.rest.BehaviourMocking
import no.nav.soknad.arkivering.arkivmock.service.kafka.KafkaPublisher
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.HttpStatus
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.TimeUnit

@SpringBootTest
class BehaviourMockingTest {

	@Autowired
	private lateinit var arkivRestInterface: ArkivRestInterface

	@Autowired
	private lateinit var behaviourMocking: BehaviourMocking

	@Autowired
	private lateinit var arkivRepository: ArkivRepository

	@MockBean
	private lateinit var kafkaPublisher: KafkaPublisher

	private val id = UUID.randomUUID().toString()

	@BeforeEach
	fun setup() {
		behaviourMocking.resetMockResponseBehaviour(id)
	}

	@AfterEach
	fun teardown() {
		reset(kafkaPublisher)
		clearInvocations(kafkaPublisher)
	}

	@Test
	fun `No specified mock behaviour - Will save to DB`() {
		val dbCountBefore = arkivRepository.count().toInt()
		val response = arkivRestInterface.receiveMessage(createRequestData(id))

		assertEquals(HttpStatus.OK, response.statusCode)
		assertTrue(response.body!!.contains("{\"dokumenter\":[],\"journalpostId\":\""))
		assertTrue(response.body!!.contains("\",\"journalpostferdigstilt\":true,\"journalstatus\":\"MIDLERTIDIG\",\"melding\":\"null\"}"))
		assertEquals(1, behaviourMocking.getNumberOfCalls(id))
		TimeUnit.SECONDS.sleep(1)
		verify(kafkaPublisher, times(1)).putNumberOfCallsOnTopic(eq(id), eq(1), any())
		verify(kafkaPublisher, times(1)).putDataOnTopic(eq(id), any(), any())
		verify(kafkaPublisher, times(1)).putNumberOfEntitiesOnTopic(eq(id), eq(dbCountBefore + 1), any())
	}

	@Test
	fun `Mock response with Ok Status but wrong body - Will save to DB`() {
		val dbCountBefore = arkivRepository.count().toInt()
		behaviourMocking.mockOkResponseWithErroneousBody(id, 1)

		val response = arkivRestInterface.receiveMessage(createRequestData(id))

		assertEquals(HttpStatus.OK, response.statusCode)
		assertEquals("THIS_IS_A_MOCKED_INVALID_RESPONSE", response.body)
		assertEquals(1, behaviourMocking.getNumberOfCalls(id))
		TimeUnit.SECONDS.sleep(1)
		verify(kafkaPublisher, times(1)).putNumberOfCallsOnTopic(eq(id), eq(1), any())
		verify(kafkaPublisher, times(1)).putDataOnTopic(eq(id), any(), any())
		verify(kafkaPublisher, times(1)).putNumberOfEntitiesOnTopic(eq(id), eq(dbCountBefore + 1), any())
	}

	@Test
	fun `Responds with status 404 two times, third time works - Will save to DB on third attempt`() {
		val dbCountBefore = arkivRepository.count().toInt()
		behaviourMocking.mockResponseBehaviour(id, 404, 2)

		assertThrows<NotFoundException> {
			arkivRestInterface.receiveMessage(createRequestData(id))
		}

		assertThrows<NotFoundException> {
			arkivRestInterface.receiveMessage(createRequestData(id))
		}

		val response = arkivRestInterface.receiveMessage(createRequestData(id))

		assertEquals(HttpStatus.OK, response.statusCode)
		assertEquals(3, behaviourMocking.getNumberOfCalls(id))
		TimeUnit.SECONDS.sleep(1)
		verify(kafkaPublisher, times(1)).putNumberOfCallsOnTopic(eq(id), eq(1), any())
		verify(kafkaPublisher, times(1)).putDataOnTopic(eq(id), any(), any())
		verify(kafkaPublisher, times(1)).putNumberOfEntitiesOnTopic(eq(id), eq(dbCountBefore + 1), any())
	}

	@Test
	fun `Responds with status 500 three times, third time works - Will save to DB on third attempt`() {
		val dbCountBefore = arkivRepository.count().toInt()
		behaviourMocking.mockResponseBehaviour(id, 500, 3)

		assertThrows<InternalServerErrorException> {
			arkivRestInterface.receiveMessage(createRequestData(id))
		}

		assertThrows<InternalServerErrorException> {
			arkivRestInterface.receiveMessage(createRequestData(id))
		}

		assertThrows<InternalServerErrorException> {
			arkivRestInterface.receiveMessage(createRequestData(id))
		}

		val response = arkivRestInterface.receiveMessage(createRequestData(id))
		assertEquals(HttpStatus.OK, response.statusCode)
		assertEquals(4, behaviourMocking.getNumberOfCalls(id))
		TimeUnit.SECONDS.sleep(1)
		verify(kafkaPublisher, times(1)).putNumberOfCallsOnTopic(eq(id), eq(1), any())
		verify(kafkaPublisher, times(1)).putDataOnTopic(eq(id), any(), any())
		verify(kafkaPublisher, times(1)).putNumberOfEntitiesOnTopic(eq(id), eq(dbCountBefore + 1), any())
	}

	private fun createRequestData(personId: String) =
		ArkivData(
			Bruker(personId, "FNR"), LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME), emptyList(),
			personId, "INNGAAENDE", "NAV_NO", "tema", "tittel"
		)
}
