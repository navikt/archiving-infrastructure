package no.nav.soknad.arkivering

import kotlin.system.exitProcess

fun main() {
	val config = Configuration()
	val loadTests = LoadTests(config)
	var exitStatus = 0

	try {
		println("Starting the Load Tests")

		loadTests.`100 simultaneous entities, 2 times 2 MB each`()
		loadTests.`100 simultaneous entities, 20 times 1 MB each`()
		loadTests.`10 000 simultaneous entities, 1 times 1 byte each`()
		loadTests.`5 simultaneous entities, 8 times 38 MB each`()

		println("Finished with the Load Tests")
	} catch (t: Throwable) {
		println("Load tests were erroneous: $t")
		exitStatus = 1
	} finally {
		exitProcess(exitStatus)
	}
}
