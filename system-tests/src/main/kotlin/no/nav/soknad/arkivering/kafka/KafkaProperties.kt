package no.nav.soknad.arkivering.kafka

class KafkaProperties {
	val inputTopic = "privat-soknadInnsendt-v1-default"
	val processingEventLogTopic = "privat-soknadInnsendt-processingEventLog-v1-default"
	val messageTopic = "privat-soknadInnsendt-messages-v1-default"
	val metricsTopic = "privat-soknadInnsendt-metrics-v1-default"

	val entitiesTopic = "privat-soknadInnsendt-endToEndTests-entities"
	val numberOfCallsTopic = "privat-soknadInnsendt-endToEndTests-numberOfCalls"
	val numberOfEntitiesTopic = "privat-soknadInnsendt-endToEndTests-numberOfEntities"
}
