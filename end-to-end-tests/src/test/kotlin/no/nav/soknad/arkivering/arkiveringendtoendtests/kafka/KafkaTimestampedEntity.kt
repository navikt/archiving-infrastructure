package no.nav.soknad.arkivering.arkiveringendtoendtests.kafka

data class KafkaTimestampedEntity<T>(val entity: T, val timestamp: Long)
