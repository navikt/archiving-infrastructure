package no.nav.soknad.arkivering.metrics

import no.nav.soknad.arkivering.avroschemas.InnsendingMetrics
import no.nav.soknad.arkivering.kafka.KafkaEntityConsumer
import no.nav.soknad.arkivering.kafka.KafkaTimestampedEntity

/**
 * This class keeps track of all [InnsendingMetrics] that it has consumed, for later retrieval.
 * One can optionally add an external [KafkaEntityConsumer] that will be called every time
 * this class consumes an entity.
 */
class MetricsConsumer : KafkaEntityConsumer<InnsendingMetrics> {
	private val metrics = hashMapOf<Key, MutableList<InnsendingMetrics>>()
	private var externalConsumer: KafkaEntityConsumer<InnsendingMetrics>? = null

	@Synchronized
	override fun consume(key: Key, timestampedEntity: KafkaTimestampedEntity<InnsendingMetrics>) {
		val value = timestampedEntity.entity

		if (metrics.containsKey(key))
			metrics[key]!!.add(value)
		else
			metrics[key] = mutableListOf(value)

		if (externalConsumer != null)
			externalConsumer!!.consume(key, timestampedEntity)
	}

	fun getMetrics() = metrics

	/**
	 * By adding an external [KafkaEntityConsumer], this class will send all entities that it consumes
	 * to it as well.
	 */
	fun addExternalConsumer(externalConsumer: KafkaEntityConsumer<InnsendingMetrics>) {
		this.externalConsumer = externalConsumer
	}
}

private typealias Key = String
