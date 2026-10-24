package no.nav.soknad.arkivering.verification

import no.nav.soknad.arkivering.kafka.KafkaTimestampedEntity
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class VerificationTaskTest {
	@Test
	fun `absence observes an entity after its old window but before presence`() {
		val manager = VerificationTaskManager()
		val presence = VerificationTask.Builder<String>()
			.withManager(manager)
			.withTimeout(5_000)
			.forKey("key")
			.verifyPresence()
			.verifyThat({ it == "FINISHED" }) { "Expected FINISHED" }
			.build()
		val absence = VerificationTask.Builder<String>()
			.withManager(manager)
			.withTimeout(25)
			.forKey("key")
			.verifyAbsence()
			.build()
		manager.registerTask(presence)
		manager.registerTask(absence)
		val executor = Executors.newSingleThreadExecutor()
		try {
			val result = executor.submit { manager.assertAllTasksSucceeds() }
			assertFalse(CountDownLatch(1).await(100, TimeUnit.MILLISECONDS))

			absence.consume("key", KafkaTimestampedEntity("entity", 1))

			assertThrows(ExecutionException::class.java) { result.get(1, TimeUnit.SECONDS) }
			assertTrue(presence.isFinished, "manager failure must cancel the pending presence timer")
		} finally {
			manager.cancelTasks()
			executor.shutdownNow()
		}
	}

	@Test
	fun `absence timeout starts only after presence succeeds`() {
		val manager = VerificationTaskManager()
		val presence = VerificationTask.Builder<String>()
			.withManager(manager)
			.withTimeout(2_000)
			.forKey("key")
			.verifyPresence()
			.build()
		val absence = VerificationTask.Builder<String>()
			.withManager(manager)
			.withTimeout(50)
			.forKey("key")
			.verifyAbsence()
			.build()
		manager.registerTask(presence)
		manager.registerTask(absence)
		val executor = Executors.newSingleThreadExecutor()
		try {
			val result = executor.submit { manager.assertAllTasksSucceeds() }
			assertFalse(CountDownLatch(1).await(100, TimeUnit.MILLISECONDS))
			assertFalse(result.isDone, "absence must not finish before the presence task")

			presence.consume("key", KafkaTimestampedEntity("FINISHED", 2))

			result.get(1, TimeUnit.SECONDS)
			assertTrue(absence.isFinished)
		} finally {
			manager.cancelTasks()
			executor.shutdownNow()
		}
	}

	@Test
	fun `task signal does not block when manager is not receiving`() {
		val manager = VerificationTaskManager()
		val absence = VerificationTask.Builder<String>()
			.withManager(manager)
			.withTimeout(30_000)
			.forKey("key")
			.verifyAbsence()
			.build()
		manager.registerTask(absence)
		val executor = Executors.newSingleThreadExecutor()
		try {
			val consumed = executor.submit {
				absence.consume("key", KafkaTimestampedEntity("entity", 3))
			}
			consumed.get(1, TimeUnit.SECONDS)
			assertTrue(absence.isFinished)
		} finally {
			manager.cancelTasks()
			executor.shutdownNow()
		}
	}
}
