package no.nav.soknad.arkivering.arkivmock.rest

import no.nav.soknad.arkivering.arkivmock.dto.ArkivData
import no.nav.soknad.arkivering.arkivmock.service.ArkivMockService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/rest/journalpostapi/v1")
class ArkivRestInterface(private val arkivMockService: ArkivMockService) {
	private val logger = LoggerFactory.getLogger(javaClass)

	@PostMapping(value = ["/journalpost"])
	fun receiveMessage(@RequestBody arkivData: ArkivData): ResponseEntity<String> {
		logger.info("Received message with id '${arkivData.eksternReferanseId}'")

		val responseBody = arkivMockService.archive(arkivData)
		return ResponseEntity(responseBody, HttpStatus.OK)
	}

	@DeleteMapping(value = ["/reset"])
	fun reset() {
		logger.info("Will reset local database")

		arkivMockService.reset()
	}
}
