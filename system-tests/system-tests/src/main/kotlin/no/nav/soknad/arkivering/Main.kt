package no.nav.soknad.arkivering

import no.nav.soknad.arkivering.kafka.KafkaListener
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

private val logger = LoggerFactory.getLogger("no.nav.soknad.arkivering.Main")

fun main() {
	logger.info("Initializing Load Tests...")
	val loadTests = LoadTests(Config(), KafkaListener(KafkaConfig()))
	val metrics = Metrics(System.getenv("PUSH_GATEWAY_ADDRESS"))
	var exitStatus = 0

	val totalDurationTimer = metrics.totalDuration.startTimer()
	try {
		logger.info("Starting the Load Tests")

//		loadTests.`100 simultaneous entities, 2 times 2 MB each`()
//		loadTests.`100 simultaneous entities, 20 times 1 MB each`()
//		loadTests.`2000 simultaneous entities, 1 times 1 byte each`()
//		loadTests.`5 simultaneous entities, 4 times 38 MB each`()
		loadTests.`Innsending av 100 soknader, hver med to vedlegg`()

		metrics.lastSuccess.setToCurrentTime()
		logger.info("Finished with the Load Tests")
	} catch (t: Throwable) {
		logger.error("Load tests were erroneous", t)
		exitStatus = 1
	} finally {
		totalDurationTimer.setDuration()
		metrics.push()
			.onSuccess { logger.info("Metrics pushed ok") }
			.onFailure { logger.error("Failed to push metrics", it) }
		exitProcess(exitStatus)
	}
}
