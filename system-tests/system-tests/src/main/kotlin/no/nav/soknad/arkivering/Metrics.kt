package no.nav.soknad.arkivering

import io.prometheus.client.CollectorRegistry
import io.prometheus.client.Gauge
import io.prometheus.client.Histogram
import io.prometheus.client.exporter.PushGateway


class Metrics(
	private val pushGatewayAddress: String
) {
	private val registry: CollectorRegistry = CollectorRegistry()

	val totalDuration: Gauge = Gauge.build()
		.name("innsendingloadtests_duration_seconds")
		.help("Duration of loadtests run in seconds.")
		.register(registry)

	var lastSuccess: Gauge = Gauge.build()
		.name("innsendingloadtests_last_success")
		.help("Last time loadtests succeeded, in unixtime.")
		.register(registry)

	var lastFailure: Gauge = Gauge.build()
		.name("innsendingloadtests_last_failure")
		.help("Last time loadtests failed, in unixtime.")
		.register(registry)

	var testCaseDuration: Histogram = Histogram.build()
		.name("innsendingloadtests_xtestcase_duration_seconds") // TODO remove x prefix, used during initial testing of metrics
		.help("Duration of test case in seconds.")
		.exponentialBuckets(10.0, 2.0, 10)
		.labelNames("testcase_id", "status")
		.register(registry)

	fun push() = runCatching {
		val pushGateway = PushGateway(pushGatewayAddress)
		pushGateway.push(registry, "innsending-system-tests")
	}
}
