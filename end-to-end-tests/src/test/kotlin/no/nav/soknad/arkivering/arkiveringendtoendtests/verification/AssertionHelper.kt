package no.nav.soknad.arkivering.arkiveringendtoendtests.verification

import no.nav.soknad.arkivering.arkiveringendtoendtests.dto.ArkivDbData
import no.nav.soknad.arkivering.arkiveringendtoendtests.dto.SoknadInnsendtDto
import no.nav.soknad.arkivering.arkiveringendtoendtests.kafka.KafkaListener

class AssertionHelper(private val kafkaListener: KafkaListener) {

	private val verificationTaskManager = VerificationTaskManager()

	fun assertThatArkivMock(): AssertionHelper {
		kafkaListener.clearConsumers()
		return this
	}

	fun containsData(entityAndExpectedCount: Pair<SoknadInnsendtDto, Int>): AssertionHelper {
		val (entity, expectedCount) = entityAndExpectedCount
		val (countVerifier, valueVerifier) = createVerificationTasksForEntityAndCount(entity, expectedCount)
		return registerVerificationTasks(countVerifier, valueVerifier)
	}

	fun hasNumberOfEntities(countAndTimeout: Pair<Int, Long>): AssertionHelper {
		val countVerifier = createVerificationTaskForEntityCount(countAndTimeout)
		verificationTaskManager.registerTasks(listOf(countVerifier))
		kafkaListener.addConsumerForNumberOfEntities(countVerifier)
		return this
	}

	private fun createVerificationTaskForEntityCount(countAndTimeout: Pair<Int, Long>): VerificationTask<Int> {
		val (expectedCount, timeout) = countAndTimeout
		return VerificationTask.Builder<Int>()
			.withManager(verificationTaskManager)
			.withTimeout(timeout)
			.verifyPresence()
			.verifyThat(expectedCount, { count -> count }, "Assert correct number of entities in the Archive")
			.build()
	}

	fun hasBeenCalled(keyAndExpectedCount: Pair<String, Int>): AssertionHelper {
		val (key, expectedCount) = keyAndExpectedCount
		val countVerifier = createVerificationTaskForCount(key, expectedCount)
		verificationTaskManager.registerTasks(listOf(countVerifier))
		kafkaListener.addConsumerForNumberOfCalls(countVerifier)
		return this
	}

	fun doesNotContainKey(key: String): AssertionHelper {
		val (countVerifier, valueVerifier) = createVerificationTaskForAbsenceOfKey(key)
		return registerVerificationTasks(countVerifier, valueVerifier)
	}

	private fun registerVerificationTasks(countVerifier: VerificationTask<Int>, valueVerifier: VerificationTask<ArkivDbData>): AssertionHelper {

		verificationTaskManager.registerTasks(listOf(countVerifier, valueVerifier))
		kafkaListener.addConsumerForEntities(valueVerifier)
		kafkaListener.addConsumerForNumberOfCalls(countVerifier)
		return this
	}

	fun verify() {
		verificationTaskManager.assertAllTasksSucceeds()
	}


	private fun createVerificationTasksForEntityAndCount(
		dto: SoknadInnsendtDto,
		expectedCount: Int
	): Pair<VerificationTask<Int>, VerificationTask<ArkivDbData>> {

		val countVerifier = createVerificationTaskForCount(dto.innsendingsId, expectedCount)

		val valueVerifier = VerificationTask.Builder<ArkivDbData>()
			.withManager(verificationTaskManager)
			.forKey(dto.innsendingsId)
			.verifyPresence()
			.verifyThat(dto.innsendingsId, { entity -> entity.id }, "Assert correct number of entities in the Archive")
			.verifyThat(dto.tema, { entity -> entity.tema }, "Assert correct entity tema")
			.verifyThat(dto.innsendteDokumenter[0].tittel, { entity -> entity.title }, "Assert correct entity title")
			.build()

		return countVerifier to valueVerifier
	}

	private fun createVerificationTaskForCount(key: String, expectedCount: Int) =
		VerificationTask.Builder<Int>()
			.withManager(verificationTaskManager)
			.forKey(key)
			.verifyPresence()
			.verifyThat(expectedCount, { count -> count }, "Assert correct number of attempts to save to the Archive")
			.build()

	private fun createVerificationTaskForAbsenceOfKey(key: String): Pair<VerificationTask<Int>, VerificationTask<ArkivDbData>> {

		val countVerifier = VerificationTask.Builder<Int>()
			.withManager(verificationTaskManager)
			.forKey(key)
			.verifyAbsence()
			.build()

		val valueVerifier = VerificationTask.Builder<ArkivDbData>()
			.withManager(verificationTaskManager)
			.forKey(key)
			.verifyAbsence()
			.build()

		return countVerifier to valueVerifier
	}
}

/**
 * Syntactic sugar to make tests read nicer.
 */
infix fun SoknadInnsendtDto.andWasCalled(count: Int) = this to count

/**
 * Syntactic sugar to make tests read nicer.
 */
infix fun Int.timesForKey(key: String) = key to this

/**
 * Syntactic sugar to make tests read nicer.
 */
infix fun Int.inMinutes(minutes: Int) = this to (minutes * 60 * 1000).toLong()

/**
 * Syntactic sugar to make tests read nicer. The function just returns its parameter back.
 */
fun times(count: Int) = count
