package no.nav.soknad.arkivering

import no.nav.soknad.arkivering.kafka.KafkaListener
import no.nav.soknad.arkivering.utils.TestCase
import org.slf4j.LoggerFactory
import kotlin.reflect.KFunction1
import kotlin.system.exitProcess

private val logger = LoggerFactory.getLogger("no.nav.soknad.arkivering.Main")

val testCases: Map<TestCase, KFunction1<LoadTests, Result<Unit>>> = mapOf(
	TestCase.TC01 to LoadTests::`TC01 - Innsending av 10 soknader, hver med to vedlegg pa 38MB`,
	TestCase.TC02 to LoadTests::`TC02 - Innsending av 100 soknader, hver med tre vedlegg pa 2MB`,
	TestCase.TC03 to LoadTests::`TC03 - Innsending av 1000 soknader, hver med to vedlegg pa 1MB`,
)

fun main() {
	logger.info("Initializing Load Tests...")
	val loadTests = LoadTests(Config(), KafkaListener(KafkaConfig()))
	val metrics = Metrics(System.getenv("PUSH_GATEWAY_ADDRESS"))
	var exitStatus = 0

	val totalDurationTimer = metrics.totalDuration.startTimer()
	try {
		logger.info("Starting the Load Tests")
		testCases.entries.forEach { (testCase, testFunction) ->
			run {
				val testCaseId = testCase.name
				val timer = metrics.testCaseDuration.labels(testCaseId).startTimer()
				logger.info("Starting test: ${testFunction.name}")
				testFunction.invoke(loadTests)
					.onSuccess {
						val elapsed = timer.setDuration()
						logger.info("Test case $testCaseId completed successfully in $elapsed seconds")
					}
					.onFailure {
						logger.error("Test case $testCaseId failed", it)
						exitStatus = 1
					}
					.also {
						logger.info("Finished test: ${testFunction.name}")
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
