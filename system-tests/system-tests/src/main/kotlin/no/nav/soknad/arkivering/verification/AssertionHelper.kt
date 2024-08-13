package no.nav.soknad.arkivering.verification

import no.nav.soknad.arkivering.avroschemas.EventTypes
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.dto.ArchiveEntity
import no.nav.soknad.arkivering.kafka.KafkaListener

/**
 * This is a helper class for setting up asynchronous assertions of Kafka messages that will appear at some
 * point in the future. This class has various functions for adding a multitude of different checks to be performed.
 * However, it will only perform the checks when the [verify] function (which is a blocking function) is called.
 *
 * Note that most if not all functions of the [AssertionHelper] have a side effect: the given [kafkaListener] will have
 * its consumers modified. Also, by creating an [AssertionHelper], the consumers of the provided [kafkaListener] will
 * be cleared.
 */
class AssertionHelper(private val kafkaListener: KafkaListener) {

	/**
	 * The [VerificationTaskManager] is a manager for blocking and waiting for all verifications to finish.
	 */
	private val verificationTaskManager = VerificationTaskManager()

	init {
		kafkaListener.clearConsumers()
	}

	fun hasFinishedEvent(key: String, timeoutInMs: Long = verificationDefaultPresenceTimeout): AssertionHelper =
		processingEventIsPresent(timeoutInMs, key, EventTypes.FINISHED)

	fun hasFailureEvent(key: String, timeoutInMs: Long = verificationDefaultPresenceTimeout): AssertionHelper =
		processingEventIsPresent(timeoutInMs, key, EventTypes.FAILURE)

	private fun processingEventIsPresent(
		timeoutInMs: Long,
		key: String,
		eventType: EventTypes
	): AssertionHelper {
		val eventIsPresent: (ProcessingEvent) -> Boolean = { it.type == eventType }
		val verificationTask = VerificationTask.Builder<ProcessingEvent>()
			.withManager(verificationTaskManager)
			.withTimeout(timeoutInMs)
			.forKey(key)
			.verifyPresence()
			.verifyThat(eventIsPresent) { "Expected '$key' to have a $eventType Processing Event, but saw none" }
			.build()

		verificationTaskManager.registerTask(verificationTask)
		kafkaListener.addConsumerForProcessingEvents(verificationTask)

		return this
	}

	fun hasCallCountInArchive(key: String, expectedCount: Int): AssertionHelper {
		val callCountIsCorrect: (Int) -> Boolean = { count -> count == expectedCount }

		val verificationTask = VerificationTask.Builder<Int>()
			.withManager(verificationTaskManager)
			.forKey(key)
			.verifyPresence()
			.verifyThat(callCountIsCorrect) { count ->
				"For key $key: Expected $expectedCount attempts to save to the Archive, but found $count"
			}
			.build()

		verificationTaskManager.registerTask(verificationTask)
		kafkaListener.addConsumerForNumberOfCalls(verificationTask)

		return this
	}

	fun hasEntityInArchive(key: String): AssertionHelper {
		val verificationTask = VerificationTask.Builder<ArchiveEntity>()
			.withManager(verificationTaskManager)
			.forKey(key)
			.verifyPresence()
			.build()

		verificationTaskManager.registerTask(verificationTask)
		kafkaListener.addConsumerForEntities(verificationTask)

		return this
	}

	fun hasNoEntityInArchive(key: String): AssertionHelper {
		val verificationTask = VerificationTask.Builder<ArchiveEntity>()
			.withManager(verificationTaskManager)
			.forKey(key)
			.verifyAbsence()
			.build()

		verificationTaskManager.registerTask(verificationTask)
		kafkaListener.addConsumerForEntities(verificationTask)

		return this
	}


	fun verify() {
		verificationTaskManager.assertAllTasksSucceeds()
	}
}
