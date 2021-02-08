package no.nav.soknad.arkivering.arkiveringsystemtests.verification

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail

class VerificationTaskManager {

	private val channel = Channel<Pair<String, Boolean>>()
	private val tasks = mutableListOf<VerificationTask<*>>()

	fun registerTasks(task: List<VerificationTask<*>>) {
		tasks.addAll(task)
	}

	fun assertAllTasksSucceeds() {
		runBlocking {
			repeat(tasks.size) {
				val result = channel.receive()
				if (!result.second) {
					fail("Task failed with message:\n${result.first}")
				}
			}
		}
	}

	fun getChannel() = channel
}
