package no.nav.soknad.arkivering.verification

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * This class is for asynchronously verifying [VerificationTask]s that complete at some point in the future.
 * The [VerificationTask]s will at some point in the future signal if they succeed or fail through the [channel].
 * This class needs to be told which [VerificationTask]s to consider through the [registerTask] function.
 * When calling [assertAllTasksSucceeds], the class will block and wait for all the registered tasks to complete.
 */
class VerificationTaskManager {

	private val logger = LoggerFactory.getLogger(javaClass)

	/**
	 * A [Channel] through which [VerificationTask]s will notify this class when the task is finished.
	 * The [Channel] transmits a pair with a message and a Boolean status. The status is true if the task finished
	 * successfully, and false if it was not successful.
	 */
	private val channel = Channel<Pair<String, Boolean>>()

	/**
	 * All the [VerificationTask]s that this class should keep track of.
	 */
	private val tasks = mutableListOf<VerificationTask<*>>()


	fun registerTask(task: VerificationTask<*>) {
		tasks.add(task)
	}

	fun assertAllTasksSucceeds() {
		runBlocking(Dispatchers.IO) {
			repeat(tasks.size) {
				val result = channel.receive()
				if (!result.second) {
					throw Exception("Task failed with message:\n${result.first}")
				}
			}
		}
	}

	fun getChannel() = channel
}
