package no.nav.soknad.arkivering.arkivmock.service

import com.fasterxml.jackson.databind.ObjectMapper
import no.nav.soknad.arkivering.arkivmock.dto.OpprettJournalpostResponse
import no.nav.soknad.arkivering.arkivmock.exceptions.InternalServerErrorException
import no.nav.soknad.arkivering.arkivmock.exceptions.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class BehaviourService(val objectMapper: ObjectMapper) {
	private val logger = LoggerFactory.getLogger(javaClass)

	private val behaviours: MutableMap<String, BehaviourDto> = mutableMapOf()

	fun setNormalBehaviour(uuid: String) {
		val calls = behaviours[uuid]?.calls ?: 0
		behaviours[uuid] = BehaviourDto(BEHAVIOUR.NORMAL, null, -1, calls)
	}

	fun mockException(uuid: String, statusCode: Int, forAttempts: Int) {
		val calls = behaviours[uuid]?.calls ?: 0

		val mockedException = when (statusCode) {
			404 -> NotFoundException()
			500 -> InternalServerErrorException()
			else -> RuntimeException("Not implemented")
		}

		behaviours[uuid] = BehaviourDto(BEHAVIOUR.MOCK_EXCEPTION, mockedException, forAttempts, calls)
	}

	fun mockResponseWithErroneousBody(uuid: String, forAttempts: Int) {
		if (!behaviours.containsKey(uuid)) {
			setNormalBehaviour(uuid)
		}
		behaviours[uuid]!!.behaviour = BEHAVIOUR.RESPOND_WITH_ERRONEOUS_BODY
		behaviours[uuid]!!.forAttempts = forAttempts
	}

	fun reactToArchiveRequest(uuid: String) {
		if (!behaviours.containsKey(uuid)) {
			logger.warn("For id=$uuid, have not registered a behaviour. Will proceed as normal.")
			return
		}
		val behaviour = behaviours[uuid]!!
		behaviour.calls++
		if (behaviour.behaviour == BEHAVIOUR.MOCK_EXCEPTION && behaviour.calls <= behaviour.forAttempts && behaviour.mockedException != null) {
			logger.info("For id=$uuid, will return exception.")
			throw behaviour.mockedException as Exception
		}
	}

	fun alterResponse(uuid: String, response: OpprettJournalpostResponse): String? {
		val jsonResponse = objectMapper.writeValueAsString(response)
		if (!behaviours.containsKey(uuid)) {
			logger.warn("For id=$uuid, have not registered a behaviour. Will return normal response.")
			return jsonResponse
		}

		val behaviour = behaviours[uuid]!!
		return if (behaviour.behaviour == BEHAVIOUR.RESPOND_WITH_ERRONEOUS_BODY && behaviour.calls <= behaviour.forAttempts) {
			logger.info("For id=$uuid, will return erroneous response.")
			"THIS_IS_A_MOCKED_INVALID_RESPONSE"
		} else {
			logger.info("For id=$uuid, will return normal response")
			jsonResponse
		}
	}

	fun getNumberOfCallsThatHaveBeenMade(uuid: String) = behaviours[uuid]?.calls ?: -1
}

data class BehaviourDto(
	var behaviour: BEHAVIOUR = BEHAVIOUR.NORMAL,
	var mockedException: Exception? = null,
	var forAttempts: Int = -1,
	var calls: Int = 0
)

enum class BEHAVIOUR { NORMAL, MOCK_EXCEPTION, RESPOND_WITH_ERRONEOUS_BODY }
