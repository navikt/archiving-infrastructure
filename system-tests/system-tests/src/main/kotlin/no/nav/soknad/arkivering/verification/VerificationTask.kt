package no.nav.soknad.arkivering.verification

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import no.nav.soknad.arkivering.kafka.KafkaEntityConsumer
import no.nav.soknad.arkivering.kafka.KafkaTimestampedEntity
import no.nav.soknad.arkivering.verification.VerificationTask.Presence
import org.slf4j.LoggerFactory
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
	private val verifiers: MutableList<Assertion<T>>,
	private val presence: Presence,
	timeoutInMs: Long
) : KafkaEntityConsumer<T> {

	private val logger = LoggerFactory.getLogger(javaClass)

	private var hasSentSignalToChannel = AtomicBoolean(false)
	private var lastObservedValueForKey: T? = null

	init {
		Timer("VerificationTaskTimer", false).schedule(timeoutInMs) {
			if (presence == Presence.ABSENCE) {
				// Verify absence - send satisfied signal after timeout.
				logger.info("$key: Verifying absence  - sending Satisfied signal")
				sendSatisfiedSignal()
			} else {
				// Verify presence - send dissatisfied signal after timeout.
				if (hasSentSignalToChannel.get().not()) {
					logger.warn("$key: Verifying presence - sending Dissatisfied signal")
					sendDissatisfiedSignal()
				}
			}
		}
	}

	override fun consume(key: String, timestampedEntity: KafkaTimestampedEntity<T>) {
		verifyEntity(key, timestampedEntity.entity)
	}

	private fun verifyEntity(key: String, value: T) {
		if (presence == Presence.ABSENCE && this.key == key) {
			sendDissatisfiedSignal()
			return
		}

		if (this.key == null || this.key == key) {
			lastObservedValueForKey = value
			if (verifiers.all { it.isSatisfied(value) }) {
				sendSatisfiedSignal()
			}
		}
	}

	private fun sendSatisfiedSignal() = sendSignal("ok", true)

	private fun sendDissatisfiedSignal() {
		val lastObservedValue = lastObservedValueForKey
		val text = when {
			presence == Presence.ABSENCE -> "Verification failed - Expected not to see value"
			lastObservedValue == null -> "Verification failed - Expected to see value but saw none"

			else -> try {
				val errorMessages = verifiers
					.filter { !it.isSatisfied(lastObservedValue) }
					.joinToString("\n") { it.errorMessage(lastObservedValue) }
				"Verification failed!\n$errorMessages"
			} catch (e: Exception) {
				"Verification failed with error '$e'!"
			}
		}

		val keyText = if (key != null) "For key $key: " else ""
		sendSignal(keyText + text, false)
	}

	private fun sendSignal(text: String, value: Boolean) {
		val hasSentAlready = hasSentSignalToChannel.compareAndSet(false, true)
		if (hasSentAlready) {
			runBlocking(Dispatchers.IO) {
				channel.send(text to value)
			}
		}
	}


	class Builder<T>(private var timeoutInMs: Long = -1) {

		private var channel: Channel<Pair<String, Boolean>> = Channel(0)
		private var verifiers: MutableList<Assertion<T>> = mutableListOf()
		private var presence: Presence = Presence.PRESENCE
		private var key: String? = null

		fun withTimeout(timeoutInMs: Long) = apply { this.timeoutInMs = timeoutInMs }
		fun withManager(manager: VerificationTaskManager) = apply { this.channel = manager.getChannel() }
		fun forKey(key: String) = apply { this.key = key }
		fun verifyPresence(): VerificationSteps { this.presence = Presence.PRESENCE; return VerificationSteps(this) }
		fun verifyAbsence() = apply { this.presence = Presence.ABSENCE }

		fun build() = VerificationTask(channel, key, verifiers, presence, getTimeout())


		inner class VerificationSteps(private val builder: Builder<T>) {

			fun verifyThat(function: (T) -> Boolean, message: (T) -> String) =
				apply { verifiers.add(Assertion(function, message)) }

			fun build() = builder.build()
		}

		private fun getTimeout(): Long {
			return when {
				timeoutInMs >= 0 -> timeoutInMs
				presence == Presence.PRESENCE -> verificationDefaultPresenceTimeout
				else -> verificationDefaultAbsenceTimeout
			}
		}
	}

	private data class Assertion<T>(val assertion: (T) -> Boolean, val errorMessage: (T) -> String) {
		fun isSatisfied(value: T) = assertion.invoke(value)
		fun errorMessage(value: T) = errorMessage.invoke(value)
	}

	private enum class Presence { PRESENCE, ABSENCE }
}

private const val verificationDefaultAbsenceTimeout = 30_000L
const val verificationDefaultPresenceTimeout = 100_000L
