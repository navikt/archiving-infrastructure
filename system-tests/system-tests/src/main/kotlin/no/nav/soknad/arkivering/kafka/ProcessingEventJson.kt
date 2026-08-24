package no.nav.soknad.arkivering.kafka

/**
 * Local JSON model for the `processingeventlog-v3` contract (see navikt/soknadsarkiverer#263 and
 * navikt/archiving-infrastructure#78). This is the plain-JSON replacement for the Avro-generated
 * `no.nav.soknad.arkivering.avroschemas.ProcessingEvent` used on the legacy `processingeventlog-v1`
 * (referred to as "v2" in the design spec) topic, and requires no Schema Registry.
 */
data class ProcessingEventJson(val type: ProcessingEventType)

/**
 * Event-type vocabulary for processing events. Must remain identical to the Avro `EventTypes`
 * symbols: RECEIVED, STARTED, ARCHIVED, FINISHED, FAILURE. The JSON migration does not redesign
 * processing-event semantics or event-type values.
 */
enum class ProcessingEventType {
	RECEIVED, STARTED, ARCHIVED, FINISHED, FAILURE
}
