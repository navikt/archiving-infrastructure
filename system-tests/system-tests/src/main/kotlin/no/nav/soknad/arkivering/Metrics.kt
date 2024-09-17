package no.nav.soknad.arkivering

import io.prometheus.client.CollectorRegistry
import io.prometheus.client.Gauge
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

	val testCaseDuration: Gauge = Gauge.build()
		.name("innsendingloadtests_ytestcase_duration_seconds") // TODO remove y prefix, used during initial testing of metrics
		.help("Duration of test case in seconds.")
		.labelNames("testcase_id")
		.register(registry)

	fun push() = runCatching {
		val pushGateway = PushGateway(pushGatewayAddress)
		pushGateway.push(registry, "innsending-system-tests")
	}
}
