package no.nav.soknad.arkivering.arkiveringsystemtests.kafka

data class KafkaTimestampedEntity<T>(val entity: T, val timestamp: Long)
