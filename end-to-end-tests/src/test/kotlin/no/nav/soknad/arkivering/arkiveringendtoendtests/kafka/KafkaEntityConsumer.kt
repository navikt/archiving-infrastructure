package no.nav.soknad.arkivering.arkiveringendtoendtests.kafka

interface KafkaEntityConsumer<T> {
	fun consume(key: String, timestampedEntity: KafkaTimestampedEntity<T>)
}
