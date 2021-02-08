package no.nav.soknad.arkivering.arkiveringsystemtests.kafka

interface KafkaEntityConsumer<T> {
	fun consume(key: String, timestampedEntity: KafkaTimestampedEntity<T>)
}
