package no.nav.soknad.archiving.arkivmock.rest

import no.nav.soknad.archiving.arkivmock.dto.ArkivData
import no.nav.soknad.archiving.arkivmock.dto.ArkivDbData
import no.nav.soknad.archiving.arkivmock.service.ArkivMockService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/rest/journalpostapi/v1")
class ArkivRestInterface(private val arkivMockService: ArkivMockService) {
	private val logger = LoggerFactory.getLogger(javaClass)

	@PostMapping(value = ["/journalpost"])
	fun receiveMessage(@RequestBody arkivData: ArkivData): ResponseEntity<String> {
		logger.info("Received message: '$arkivData'")

		val responseBody = arkivMockService.archive(arkivData)
		return ResponseEntity(responseBody, HttpStatus.OK)
	}

	@GetMapping("/lookup/{id}")
	fun lookup(@PathVariable("id") id: String): ArkivDbData {
		logger.info("Looking up '$id'")
		val response = arkivMockService.lookup(id)

		if (response.isPresent) {
			return response.get()
		} else {
			throw ResponseStatusException(HttpStatus.NOT_FOUND, "Failed to find $id")
		}
	}
}
