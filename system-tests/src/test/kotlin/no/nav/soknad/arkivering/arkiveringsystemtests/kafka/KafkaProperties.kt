package no.nav.soknad.arkivering.arkiveringsystemtests.kafka

class KafkaProperties {
	val inputTopic = "privat-soknadInnsendt-v1-default"
	val processingEventLogTopic = "privat-soknadInnsendt-processingEventLog-v1-default"
	val messageTopic = "privat-soknadInnsendt-messages-v1-default"
	val metricsTopic = "privat-soknadInnsendt-metrics-v1-default"

	val entitiesTopic = "privat-endToEndTests-entities"
	val numberOfCallsTopic = "privat-endToEndTests-numberOfCalls"
	val numberOfEntitiesTopic = "privat-endToEndTests-numberOfEntities"
}
