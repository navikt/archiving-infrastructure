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

	fun push() = runCatching {
		val pushGateway = PushGateway(pushGatewayAddress)
		pushGateway.push(registry, "innsending-system-tests")
	}
}
