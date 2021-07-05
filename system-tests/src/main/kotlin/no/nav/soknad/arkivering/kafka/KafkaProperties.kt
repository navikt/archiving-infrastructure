package no.nav.soknad.arkivering.kafka

import no.nav.soknad.arkivering.defaultProperties

class KafkaProperties {
	val inputTopic = defaultProperties["KAFKA_INPUT_TOPIC"]!!
	val processingEventLogTopic = defaultProperties["KAFKA_PROCESSING_TOPIC"]!!
	val messageTopic = defaultProperties["KAFKA_MESSAGE_TOPIC"]!!
	val metricsTopic = defaultProperties["KAFKA_METRICS_TOPIC"]!!

	val entitiesTopic = "privat-soknadInnsendt-endToEndTests-entities"
	val numberOfCallsTopic = "privat-soknadInnsendt-endToEndTests-numberOfCalls"
	val numberOfEntitiesTopic = "privat-soknadInnsendt-endToEndTests-numberOfEntities"
}
