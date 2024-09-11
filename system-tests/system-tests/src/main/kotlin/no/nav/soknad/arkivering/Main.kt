package no.nav.soknad.arkivering

import no.nav.soknad.arkivering.kafka.KafkaListener
import no.nav.soknad.arkivering.utils.TestCase
import org.slf4j.LoggerFactory
import kotlin.reflect.KFunction1
import kotlin.system.exitProcess

private val logger = LoggerFactory.getLogger("no.nav.soknad.arkivering.Main")

val testCases: Map<TestCase, KFunction1<LoadTests, Result<Unit>>> = mapOf(
	TestCase.TC01 to LoadTests::`TC01 - Innsending av 10 soknader, hver med to vedlegg pa 2MB`,
	TestCase.TC02 to LoadTests::`TC02 - Innsending av 100 soknader, hver med tre vedlegg pa 1MB`,
//	TestCase.TC03 to ("Innsending av 1000 søknader, hver med to vedlegg på 1MB"),
//	TestCase.TC04 to ("Innsending av 5 søknader, hver med fire vedlegg på 38MB"),
)

fun main() {
	logger.info("Initializing Load Tests...")
	val loadTests = LoadTests(Config(), KafkaListener(KafkaConfig()))
	val metrics = Metrics(System.getenv("PUSH_GATEWAY_ADDRESS"))
	var exitStatus = 0

//		loadTests.`100 simultaneous entities, 2 times 2 MB each`()
//		loadTests.`100 simultaneous entities, 20 times 1 MB each`()
//		loadTests.`2000 simultaneous entities, 1 times 1 byte each`()
//		loadTests.`5 simultaneous entities, 4 times 38 MB each`()

	val totalDurationTimer = metrics.totalDuration.startTimer()
	try {
		logger.info("Starting the Load Tests")
		testCases.entries.forEach { testCaseFunction ->
			run {
				val testCaseId = testCaseFunction.key.name
				val timer = metrics.testCaseDuration.labels(testCaseId).startTimer()
				testCaseFunction.value.invoke(loadTests)
					.onSuccess {
						val elapsed = timer.observeDuration()
						logger.info("Test case $testCaseId completed successfully in $elapsed seconds")
					}
					.onFailure {
						logger.error("Test case $testCaseId failed", it)
						exitStatus = 1
					}
			}
		}

		metrics.lastSuccess.setToCurrentTime()
		logger.info("Finished with the Load Tests")
	} catch (t: Throwable) {
		metrics.lastFailure.setToCurrentTime()
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
