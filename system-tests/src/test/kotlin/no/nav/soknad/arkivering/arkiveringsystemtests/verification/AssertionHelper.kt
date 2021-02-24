package no.nav.soknad.arkivering.arkiveringsystemtests.verification

import com.fasterxml.jackson.databind.ObjectMapper
import no.nav.soknad.arkivering.avroschemas.InnsendingMetrics
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.dto.ArkivDbData
import no.nav.soknad.arkivering.dto.SoknadInnsendtDto
import no.nav.soknad.arkivering.kafka.KafkaListener
import no.nav.soknad.arkivering.metrics.MetricsConsumer
import no.nav.soknad.arkivering.metrics.ProcessingEventConverter
import java.io.File

/**
 * This is a helper class for setting up asynchronous assertions of Kafka messages that will appear at some
 * point in the future. This class has various functions for adding a multitude of different checks to be performed.
 * However, it only perform the checks when the [verify] function (which is a blocking function) is called.
 *
 * Note that most if not all functions of the [AssertionHelper] has a side effect: the given [kafkaListener] will have
 * its consumers modified. By creating an [AssertionHelper], the consumers of the [kafkaListener] will be cleared.
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
	 * they can be consumed by the [MetricsConsumer].
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

	fun hasNumberOfFinishedEvents(countAndTimeout: Pair<Int, Long>): AssertionHelper {
		val countVerifier = createVerificationTaskForFinishedCount(countAndTimeout)
		verificationTaskManager.registerTasks(listOf(countVerifier))
		kafkaListener.addConsumerForMetrics(countVerifier)
		return this
	}

	private fun createVerificationTaskForFinishedCount(countAndTimeout: Pair<Int, Long>): VerificationTask<InnsendingMetrics> {
		val (expectedCount, timeout) = countAndTimeout

		val verificationFunction: (InnsendingMetrics) -> Int = {
			metricsConsumer.getMetrics()
				.flatMap { it.value }
				.filter { it.action == "FINISHED" }
				.count()
		}
		val verificationTask = VerificationTask.Builder<InnsendingMetrics>()
			.withManager(verificationTaskManager)
			.withTimeout(timeout)
			.verifyPresence()
			.verifyThat(expectedCount, verificationFunction, "Assert correct number of Finished Processing Events")
			.build()

		metricsConsumer.addExternalConsumer(verificationTask)
		return verificationTask
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

	private fun registerVerificationTasks(countVerifier: VerificationTask<Int>,
																				valueVerifier: VerificationTask<ArkivDbData>): AssertionHelper {

		verificationTaskManager.registerTasks(listOf(countVerifier, valueVerifier))
		kafkaListener.addConsumerForEntities(valueVerifier)
		kafkaListener.addConsumerForNumberOfCalls(countVerifier)
		return this
	}

	fun verify(saveMetrics: Boolean = true) {
		verificationTaskManager.assertAllTasksSucceeds()

		if (saveMetrics)
			saveMetrics()
	}

	private fun saveMetrics() {
		data class Metrics(val application: String, val action: String, val startTime: Long, val duration: Long)
		data class MetricsObject(val key: String, val datapoints: List<Metrics>)

		val objectMapper = ObjectMapper().also { it.findAndRegisterModules() }
		val metrics = metricsConsumer.getMetrics()
			.entries.map {
				MetricsObject(it.key, it.value
					.map { m -> Metrics(m.application, m.action, m.startTime, m.duration) }
				) }
		val json = objectMapper.writeValueAsString(metrics)

		val testName = Thread.currentThread().stackTrace[4].methodName
		val outputDir = "target/metrics"
		File(outputDir).mkdirs()
		val file = File("$outputDir/$testName").also { it.createNewFile() }
		file.writeText(json)
		println("Wrote metrics to '$outputDir/$testName'")
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
			.verifyThat(dto.innsendingsId, { entity -> entity.id }, "Assert correct entity id")
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

	private fun createVerificationTaskForAbsenceOfKey(key: String):
		Pair<VerificationTask<Int>, VerificationTask<ArkivDbData>> {

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
