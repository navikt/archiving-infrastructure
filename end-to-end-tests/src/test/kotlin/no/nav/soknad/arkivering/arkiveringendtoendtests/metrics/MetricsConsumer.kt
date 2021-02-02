package no.nav.soknad.arkivering.arkiveringendtoendtests.metrics

import no.nav.soknad.arkivering.arkiveringendtoendtests.kafka.KafkaEntityConsumer
import no.nav.soknad.arkivering.arkiveringendtoendtests.kafka.KafkaTimestampedEntity
import no.nav.soknad.arkivering.arkiveringendtoendtests.verification.VerificationTask

/**
 * This class keeps track of all [Metrics] that it has consumed, for later retrieval.
 * One can optionally add an external [VerificationTask] that will be called every time
 * this class consumes an entity.
 */
class MetricsConsumer : KafkaEntityConsumer<Metrics> {
	private val metrics = hashMapOf<Key, MutableList<Metrics>>()
	private var verificationTask: VerificationTask<Metrics>? = null

	@Synchronized
	override fun consume(key: Key, timestampedEntity: KafkaTimestampedEntity<Metrics>) {
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
	fun addVerificationTask(verificationTask: VerificationTask<Metrics>) {
		this.verificationTask = verificationTask
	}
}

private typealias Key = String
