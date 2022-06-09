package no.nav.soknad.arkivering

import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

private val logger = LoggerFactory.getLogger("no.nav.soknad.arkivering.Main")

fun main() {
	val config = Configuration()
	val loadTests = LoadTests(config)
	var exitStatus = 0

	try {
		logger.info("Starting the Load Tests")

		loadTests.`100 simultaneous entities, 2 times 2 MB each`()
		loadTests.`100 simultaneous entities, 20 times 1 MB each`()
//		loadTests.`10 000 simultaneous entities, 1 times 1 byte each`() // TODO: Make these work reliably
		loadTests.`5 simultaneous entities, 8 times 38 MB each`()

		logger.info("Finished with the Load Tests")
	} catch (t: Throwable) {
		logger.error("Load tests were <TODO REMOVE> erroneous", t) // TODO: Remove "<TODO REMOVE>"
		exitStatus = 1
	} finally {
		exitProcess(exitStatus)
	}
}
