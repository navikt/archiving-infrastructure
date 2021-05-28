package no.nav.soknad.arkivering.arkivmock.rest

import no.nav.soknad.arkivering.arkivmock.service.BehaviourService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/arkiv-mock/response-behaviour")
class BehaviourMocking(val behaviourService: BehaviourService) {
	private val logger = LoggerFactory.getLogger(javaClass)

	@PutMapping("/mock-response/{uuid}/{statusCode}/{forAttempts}")
	fun mockResponseBehaviour(
		@PathVariable("uuid") uuid: String,
		@PathVariable("statusCode") statusCode: Int,
		@PathVariable("forAttempts") forAttempts: Int
	): ResponseEntity<String> {
		logger.info("For id=$uuid, http status code $statusCode will be returned $forAttempts times")

		behaviourService.mockException(uuid, statusCode, forAttempts)

		return ResponseEntity("", HttpStatus.OK)
	}

	@PutMapping("/set-status-ok-with-erroneous-body/{uuid}/{forAttempts}")
	fun mockOkResponseWithErroneousBody(
		@PathVariable("uuid") uuid: String,
		@PathVariable("forAttempts") forAttempts: Int
	): ResponseEntity<String> {
		logger.info("For id=$uuid, http status code ${HttpStatus.OK}, but with erroneous body will be returned $forAttempts times")

		behaviourService.mockResponseWithErroneousBody(uuid, forAttempts)

		return ResponseEntity("", HttpStatus.OK)
	}

	@PutMapping("/set-normal-behaviour/{uuid}")
	fun setNormalResponseBehaviour(@PathVariable("uuid") uuid: String): ResponseEntity<String> {
		logger.info("For id=$uuid, will accept all calls and return http status ${HttpStatus.OK}")

		behaviourService.setNormalBehaviour(uuid)

		return ResponseEntity("", HttpStatus.OK)
	}

	@PutMapping("/reset/{uuid}")
	fun resetMockResponseBehaviour(@PathVariable("uuid") uuid: String) = setNormalResponseBehaviour(uuid)

	@GetMapping("/number-of-calls/{uuid}")
	fun getNumberOfCalls(@PathVariable("uuid") uuid: String): Int {
		val numberOfCallsThatHaveBeenMade = behaviourService.getNumberOfCallsThatHaveBeenMade(uuid)

		logger.info("For id=$uuid, there have been made $numberOfCallsThatHaveBeenMade calls to save to the archive")

		return numberOfCallsThatHaveBeenMade
	}
}
