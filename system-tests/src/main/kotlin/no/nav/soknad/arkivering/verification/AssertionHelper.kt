package no.nav.soknad.arkivering.verification

import no.nav.soknad.arkivering.avroschemas.InnsendingMetrics
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.dto.ArkivDbData
import no.nav.soknad.arkivering.kafka.KafkaListener
import no.nav.soknad.arkivering.metrics.MetricsConsumer
import no.nav.soknad.arkivering.metrics.ProcessingEventConverter

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
	 * The [VerificationTaskManager] is a manager for blocking and awaiting for all verifications to finish.
	 */
	private val verificationTaskManager = VerificationTaskManager()

	/**
	 * The [MetricsConsumer] will consume any and all [InnsendingMetrics] that appear on the appropriate Kafka topics
	 */
	private val metricsConsumer = MetricsConsumer()

	/**
	 * The [ProcessingEventConverter] is a helper class to convert [ProcessingEvent]'s to [InnsendingMetrics], so that
	 * they can be consumed by the [metricsConsumer].
	 */
	private val processingEventConverter = ProcessingEventConverter(metricsConsumer)

	init {
		kafkaListener.clearConsumers()
		addMetricsConsumers()
	}

	private fun addMetricsConsumers() {
		kafkaListener.addConsumerForMetrics(metricsConsumer)
		kafkaListener.addConsumerForProcessingEvents(processingEventConverter)
	}


	fun hasFinishedEvent(key: String, timeoutInMs: Long = verificationDefaultPresenceTimeout): AssertionHelper {

		val finishedEventIsPresent: (InnsendingMetrics) -> Boolean = {
			metricsConsumer.getMetrics()
				.filter { it.key == key }
				.flatMap { it.value }
				.any { it.action == "FINISHED" }
		}

		val verificationTask = VerificationTask.Builder<InnsendingMetrics>()
			.withManager(verificationTaskManager)
			.withTimeout(timeoutInMs)
			.verifyPresence()
			.verifyThat(finishedEventIsPresent) { "Expected '$key' to have a FINISHED Processing Event, but saw none" }
			.build()

		verificationTaskManager.registerTasks(listOf(verificationTask))
		metricsConsumer.addExternalConsumer(verificationTask)
		kafkaListener.addConsumerForMetrics(verificationTask)

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

		verificationTaskManager.registerTasks(listOf(verificationTask))
		kafkaListener.addConsumerForNumberOfCalls(verificationTask)

		return this
	}

	fun hasEntityInArchive(key: String): AssertionHelper {
		val verificationTask = VerificationTask.Builder<ArkivDbData>()
			.withManager(verificationTaskManager)
			.forKey(key)
			.verifyPresence()
			.build()

		verificationTaskManager.registerTasks(listOf(verificationTask))
		kafkaListener.addConsumerForEntities(verificationTask)

		return this
	}

	fun hasNoEntityInArchive(key: String): AssertionHelper {
		val verificationTask = VerificationTask.Builder<ArkivDbData>()
			.withManager(verificationTaskManager)
			.forKey(key)
			.verifyAbsence()
			.build()

		verificationTaskManager.registerTasks(listOf(verificationTask))
		kafkaListener.addConsumerForEntities(verificationTask)

		return this
	}


	fun verify() {
		verificationTaskManager.assertAllTasksSucceeds()
	}
}
