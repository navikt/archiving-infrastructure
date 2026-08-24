package no.nav.soknad.arkivering.kafka

import java.time.Instant

/**
 * Local JSON model for the `metrics-v3` contract. Field shape mirrors the shared JSON metrics
 * model published by `soknadsmottaker`'s OpenAPI specification (`InnsendingMetrics`, see
 * navikt/soknadsmottaker#205): application, action, an ISO-8601 [startTime], and a millisecond
 * [duration]. Encoded as plain JSON and requires no Schema Registry.
 */
data class InnsendingMetricsJson(
	val application: String,
	val action: String,
	val startTime: Instant,
	val duration: Long,
)
