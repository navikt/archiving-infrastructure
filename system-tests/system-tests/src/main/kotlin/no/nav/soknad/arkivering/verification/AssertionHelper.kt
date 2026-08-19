package no.nav.soknad.arkivering.verification

import no.nav.soknad.arkivering.dto.ArchiveEntity
import no.nav.soknad.arkivering.kafka.KafkaListener
import no.nav.soknad.arkivering.kafka.ProcessingEventJson
import no.nav.soknad.arkivering.kafka.ProcessingEventType

/**
 * This is a helper class for setting up asynchronous assertions of Kafka messages that will appear at some
 * point in the future. This class has various functions for adding a multitude of different checks to be performed.
 * However, it will only perform the checks when the [verify] function (which is a blocking function) is called.
 *
 * Note that most if not all functions of the [AssertionHelper] have a side effect: the given [kafkaListener] will have
 * its consumers modified. Each [AssertionHelper] registers its own consumers on the (potentially shared)
 * [kafkaListener], and [verify] deregisters exactly those consumers again when it is done. This makes it safe
 * for tests running in parallel to each use their own [AssertionHelper] on the same [KafkaListener].
 */
class AssertionHelper(private val kafkaListener: KafkaListener) {

	/**
	 * The [VerificationTaskManager] is a manager for blocking and waiting for all verifications to finish.
	 */
	private val verificationTaskManager = VerificationTaskManager()

	/**
	 * Registrations of all consumers this helper has added to the [kafkaListener]. They are
	 * deregistered when [verify] finishes so that no stale consumers linger on the shared listener.
	 */
	private val registrations = mutableListOf<KafkaListener.ConsumerRegistration>()

	fun hasFinishedEvent(key: String, timeoutInMs: Long = verificationDefaultPresenceTimeout): AssertionHelper =
		processingEventIsPresent(timeoutInMs, key, ProcessingEventType.FINISHED)

	fun hasFailureEvent(key: String, timeoutInMs: Long = verificationDefaultPresenceTimeout): AssertionHelper =
		processingEventIsPresent(timeoutInMs, key, ProcessingEventType.FAILURE)

	private fun processingEventIsPresent(
		timeoutInMs: Long,
		key: String,
		eventType: ProcessingEventType
	): AssertionHelper {
		val eventIsPresent: (ProcessingEventJson) -> Boolean = { it.type == eventType }
		val verificationTask = VerificationTask.Builder<ProcessingEventJson>()
			.withManager(verificationTaskManager)
			.withTimeout(timeoutInMs)
			.forKey(key)
			.verifyPresence()
			.verifyThat(eventIsPresent) { "Expected '$key' to have a $eventType Processing Event, but saw none" }
			.build()

		verificationTaskManager.registerTask(verificationTask)
		registrations.add(kafkaListener.addConsumerForProcessingEvents(verificationTask))

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
		registrations.add(kafkaListener.addConsumerForNumberOfCalls(verificationTask))

		return this
	}

	fun hasEntityInArchive(key: String): AssertionHelper {
		val verificationTask = VerificationTask.Builder<ArchiveEntity>()
			.withManager(verificationTaskManager)
			.forKey(key)
			.verifyPresence()
			.build()

		verificationTaskManager.registerTask(verificationTask)
		registrations.add(kafkaListener.addConsumerForEntities(verificationTask))

		return this
	}

	fun hasNoEntityInArchive(key: String): AssertionHelper {
		val verificationTask = VerificationTask.Builder<ArchiveEntity>()
			.withManager(verificationTaskManager)
			.forKey(key)
			.verifyAbsence()
			.build()

		verificationTaskManager.registerTask(verificationTask)
		registrations.add(kafkaListener.addConsumerForEntities(verificationTask))

		return this
	}


	fun verify() {
		try {
			verificationTaskManager.assertAllTasksSucceeds()
		} finally {
			registrations.forEach { it.deregister() }
		}
	}
}
