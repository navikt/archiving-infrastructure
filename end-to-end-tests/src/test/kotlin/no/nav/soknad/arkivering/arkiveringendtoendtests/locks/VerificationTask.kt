package no.nav.soknad.arkivering.arkiveringendtoendtests.locks

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.schedule

class VerificationTask<T> private constructor(
	private val channel: Channel<Pair<String, Boolean>>,
	private val key: String?,
	private val verifier: MutableList<Assertion<*, T>>,
	private val presence: Presence,
	timeout: Long
) {

	private var hasSent = AtomicBoolean(false)
	private val observedValues = mutableListOf<T>()

	init {
		Timer("VerificationTaskTimer", false).schedule(timeout) {
			if (presence == Presence.ABSENCE) {
				// Verify absence - send satisfied signal after timeout.
				sendSatisfiedSignal()
			} else {
				// Verify presence - send dissatisfied signal after timeout.
				if (hasSent.get().not()) {
					sendDissatisfiedSignal()
				}
			}
		}
	}

	fun verify(key: String, value: T) {
		observedValues.add(value)
		if (presence == Presence.ABSENCE && this.key == key) {
			sendDissatisfiedSignal()
			return
		}

		if (this.key == null || this.key == key) {
			if (verifier.all { it.expected == it.actualFunction.invoke(value) }) {
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
				val firstFailingVerification = verifier.first { it.expected != it.actualFunction.invoke(lastSeenValue) }
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
			fun <R> verifyThat(expected: R, function: (T) -> R, message: String) = apply { verifier.add(Assertion(expected, function, message)) }
			fun build() = builder.build()
		}
	}

	private data class Assertion<V, T>(val expected: V, val actualFunction: (T) -> V, val message: String)
	private enum class Presence { PRESENCE, ABSENCE }
}
