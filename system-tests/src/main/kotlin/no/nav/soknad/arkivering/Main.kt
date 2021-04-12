package no.nav.soknad.arkivering

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.web.reactive.function.client.WebClient

@SpringBootApplication
class KjellmanLoadTests(@Qualifier("archiveWebClient") private val webClient: WebClient) : CommandLineRunner {

	override fun run(vararg args: String?) {
		val config = Configuration()
		val loadTests = LoadTests(config, webClient)
		println("Starting the Load Tests")

		loadTests.`10 simultaneous entities, 8 times 38 MB each`()
		loadTests.`100 simultaneous entities, 2 times 2 MB each`()
		loadTests.`100 simultaneous entities, 20 times 1 MB each`()
		loadTests.`10 000 simultaneous entities, 1 times 1 byte each`()

		println("Finished with the Load Tests")
	}

	companion object {
		@JvmStatic
		fun main(args: Array<String>) {
			runApplication<KjellmanLoadTests>(*args)
		}
	}
}
