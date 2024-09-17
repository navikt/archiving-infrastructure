package no.nav.soknad.arkivering.utils

fun <R> retry(
	maxAttempts: Int,
	logThrowable: (t: Throwable) -> Unit = {},
	action: () -> R
): R {
	require(maxAttempts > 0) { "maxAttempts must be greater than 0" }
	return runCatching(action).getOrElse {
		logThrowable(it)
		val leftAttempts = maxAttempts.dec()
		if (leftAttempts == 0) throw it
		retry(leftAttempts, logThrowable, action)
	}
}
