package no.nav.soknad.arkivering.kafka

interface KafkaEntityConsumer<T> {
	fun consume(key: String, timestampedEntity: KafkaTimestampedEntity<T>)
}
