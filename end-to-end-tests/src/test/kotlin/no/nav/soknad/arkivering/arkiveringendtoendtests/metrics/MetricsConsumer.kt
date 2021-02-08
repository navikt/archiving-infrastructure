package no.nav.soknad.arkivering.arkiveringendtoendtests.metrics

import no.nav.soknad.arkivering.arkiveringendtoendtests.kafka.KafkaEntityConsumer
import no.nav.soknad.arkivering.arkiveringendtoendtests.kafka.KafkaTimestampedEntity
import no.nav.soknad.arkivering.arkiveringendtoendtests.verification.VerificationTask
import no.nav.soknad.arkivering.avroschemas.InnsendingMetrics

/**
 * This class keeps track of all [InnsendingMetrics] that it has consumed, for later retrieval.
 * One can optionally add an external [VerificationTask] that will be called every time
 * this class consumes an entity.
 */
class MetricsConsumer : KafkaEntityConsumer<InnsendingMetrics> {
	private val metrics = hashMapOf<Key, MutableList<InnsendingMetrics>>()
	private var verificationTask: VerificationTask<InnsendingMetrics>? = null

	@Synchronized
	override fun consume(key: Key, timestampedEntity: KafkaTimestampedEntity<InnsendingMetrics>) {
		val value = timestampedEntity.entity

		if (metrics.containsKey(key))
			metrics[key]!!.add(value)
		else
			metrics[key] = mutableListOf(value)

		if (verificationTask != null)
			verificationTask!!.consume(key, timestampedEntity)
	}

	fun getMetrics() = metrics

	/**
	 * By adding a [VerificationTask], this class will send all entities that it consumes
	 * to the [VerificationTask] as well.
	 */
	fun addVerificationTask(verificationTask: VerificationTask<InnsendingMetrics>) {
		this.verificationTask = verificationTask
	}
}

private typealias Key = String
