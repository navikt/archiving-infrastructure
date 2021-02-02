package no.nav.soknad.arkivering.arkiveringendtoendtests.metrics

data class Metrics(val application: String, val action: String, val startTime: Long, val duration: Long = -1)
