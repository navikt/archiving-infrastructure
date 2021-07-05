package no.nav.soknad.arkivering.arkivmock

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class ArkivMockApplication

fun main(args: Array<String>) {
	runApplication<ArkivMockApplication>(*args)
}
