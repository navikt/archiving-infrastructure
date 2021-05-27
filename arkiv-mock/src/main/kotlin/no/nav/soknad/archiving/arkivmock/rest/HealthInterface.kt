package no.nav.soknad.archiving.arkivmock.rest

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping
class HealthInterface {
	private val logger = LoggerFactory.getLogger(javaClass)

	@GetMapping(value = ["/isAlive"])
	fun isAlive(): ResponseEntity<String> {
		logger.info("/isAlive called")
		return ResponseEntity("Application is alive!", HttpStatus.OK)
	}
}
