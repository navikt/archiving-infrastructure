package no.nav.soknad.arkivering.metrics

import no.nav.soknad.arkivering.avroschemas.InnsendingMetrics
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.kafka.KafkaEntityConsumer
import no.nav.soknad.arkivering.kafka.KafkaTimestampedEntity

/**
 * This class converts a [ProcessingEvent] to a [InnsendingMetrics] object, and feeds it to the [MetricsConsumer].
 * In other words, we treat a [ProcessingEvent] as a [InnsendingMetrics] object with a duration of -1.
 */
class ProcessingEventConverter(private val metricsConsumer: MetricsConsumer) : KafkaEntityConsumer<ProcessingEvent> {

	/**
	 * Consume a [ProcessingEvent], turn it into a [InnsendingMetrics] and pass it to the [metricsConsumer] so that it
	 * can consume it as well.
	 */
	override fun consume(key: String, timestampedEntity: KafkaTimestampedEntity<ProcessingEvent>) {

		val metrics = InnsendingMetrics(
			"soknadsarkiverer",
			timestampedEntity.entity.type.name,
			timestampedEntity.timestamp,
			-1)

		val value = KafkaTimestampedEntity(metrics, timestampedEntity.timestamp)

		metricsConsumer.consume(key, value)
	}
}
