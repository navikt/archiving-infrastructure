package no.nav.soknad.arkivering.verification

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import no.nav.soknad.arkivering.verification.VerificationTask.Presence
import no.nav.soknad.arkivering.kafka.KafkaEntityConsumer
import no.nav.soknad.arkivering.kafka.KafkaTimestampedEntity
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.schedule

/**
 * This class consumes messages from a Kafka topic (since the class is a [KafkaEntityConsumer]), and on every
 * consumed message, it will verify the message through its [verifiers].
 * The [VerificationTask] is quite customisable, and the [verifiers] be used to verify for example:
 * - that a Kafka message contains certain values
 * - that no messages arrive before the given timeout
 * - that a certain number of messages with a given [key] has arrived before the given timeout
 *
 * Upon each consumed Kafka message, all [verifiers] will be checked. If every one succeeds, then the [VerificationTask]
 * will be satisfied. Also, if [presence] is set to [Presence.ABSENCE], then the [VerificationTask] will be satisfied
 * if no Kafka messages with the given [key] are observed before the given timeout.
 * Likewise, if the consumed Kafka messages don't satisfy all the [verifiers], or if [presence] is set to
 * [Presence.PRESENCE] and there were no Kafka messages consumed before the given timeout, then the [VerificationTask]
 * will be dissatisfied.
 *
 * When the [VerificationTask] is satisfied or dissatisfied, it will communicate that through the [channel], by sending
 * a Message and a Boolean status. The status will be true if the [VerificationTask] is satisfied, and false otherwise.
 * The Message will contain details about why the status is set the way it is.
 */
class VerificationTask<T> private constructor(
	private val channel: Channel<Pair<String, Boolean>>,
	private val key: String?,
	private val verifiers: MutableList<Assertion<*, T>>,
	private val presence: Presence,
	timeout: Long
): KafkaEntityConsumer<T> {

	private var hasSent = AtomicBoolean(false)
	private val observedValues = mutableListOf<T>()

	init {
		Timer("VerificationTaskTimer", false).schedule(timeout) {
			if (presence == Presence.ABSENCE) {
				// Verify absence - send satisfied signal after timeout.
				println("Verifying absence - sending Satisfied signal")
				sendSatisfiedSignal()
			} else {
				// Verify presence - send dissatisfied signal after timeout.
				if (hasSent.get().not()) {
					println("Verifying presence - sending Dissatisfied signal")
					sendDissatisfiedSignal()
				}
			}
		}
	}

	override fun consume(key: String, timestampedEntity: KafkaTimestampedEntity<T>) {
		verifyEntity(key, timestampedEntity.entity)
	}

	private fun verifyEntity(key: String, value: T) {
		observedValues.add(value)
		if (presence == Presence.ABSENCE && this.key == key) {
			sendDissatisfiedSignal()
			return
		}

		if (this.key == null || this.key == key) {
			if (verifiers.all { it.expected == it.actualFunction.invoke(value) }) {
				sendSatisfiedSignal()
			}
		}
	}

	private fun sendSatisfiedSignal() = sendSignal("ok", true)

	private fun sendDissatisfiedSignal() {
		val text = when {
			presence == Presence.ABSENCE -> "Verification failed - Expected not to see value"
			observedValues.isEmpty() -> "Verification failed - Expected to see value but saw none"
			else -> try {
				val lastSeenValue = observedValues.last()
				val firstFailingVerification = verifiers.first { it.expected != it.actualFunction.invoke(lastSeenValue) }
				"Verification failed for assertion '${firstFailingVerification.message}'!\n" +
					"Expected: '${firstFailingVerification.expected}'\n" +
					"But found '${firstFailingVerification.actualFunction.invoke(lastSeenValue)}'"
			} catch (e: Exception) {
				"Verification failed with error '$e'!"
			}
		}

		val keyText = if (key != null) "For key $key: " else ""
		sendSignal(keyText + text, false)
	}

	@Synchronized
	private fun sendSignal(text: String, value: Boolean) {
		if (hasSent.get().not()) {
			hasSent.set(true)
			runBlocking {
				channel.send(text to value)
			}
		}
	}


	class Builder<T>(private var timeout: Long = 10_000) {

		private var channel: Channel<Pair<String, Boolean>> = Channel(0)
		private var verifier: MutableList<Assertion<*, T>> = mutableListOf()
		private var presence: Presence = Presence.PRESENCE
		private var key: String? = null

		fun withTimeout(timeout: Long) = apply { this.timeout = timeout }
		fun withManager(manager: VerificationTaskManager) = apply { this.channel = manager.getChannel() }
		fun forKey(key: String) = apply { this.key = key }
		fun verifyPresence(): VerificationSteps { this.presence = Presence.PRESENCE; return VerificationSteps(this) }
		fun verifyAbsence() = apply { this.presence = Presence.ABSENCE }

		fun build() = VerificationTask(channel, key, verifier, presence, timeout)

		inner class VerificationSteps(private val builder: Builder<T>) {
			fun <R> verifyThat(expected: R, function: (T) -> R, message: String) =
				apply { verifier.add(Assertion(expected, function, message)) }
			fun build() = builder.build()
		}
	}

	private data class Assertion<V, T>(val expected: V, val actualFunction: (T) -> V, val message: String)
	private enum class Presence { PRESENCE, ABSENCE }
}
