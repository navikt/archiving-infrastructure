package no.nav.soknad.arkivering.kafka

data class KafkaTimestampedEntity<T>(val entity: T, val timestamp: Long)
