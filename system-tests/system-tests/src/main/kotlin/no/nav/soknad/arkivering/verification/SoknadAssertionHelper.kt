package no.nav.soknad.arkivering.verification

import no.nav.soknad.arkivering.innsending.InnsendingApi
import no.nav.soknad.arkivering.innsending.model.ArkiveringsStatusDto
import org.slf4j.LoggerFactory

class SoknadAssertionHelper(
	private val innsendingApi: InnsendingApi,
	private val innsendingsId: String
) {
	private val logger = LoggerFactory.getLogger(javaClass)

	fun hasStatus(expectedStatus: ArkiveringsStatusDto): SoknadAssertionHelper {
		val maxAttempts = 30
		val attemptDelayMs = 2000L
		var attemptsLeft = maxAttempts
		var currentStatus: ArkiveringsStatusDto?
		do {
			currentStatus = innsendingApi.getArkiveringsstatus(innsendingsId)
			attemptsLeft--
			if (currentStatus === expectedStatus) {
				logger.info("Forventet arkiveringsstatus funnet - $currentStatus (forsøk nr ${maxAttempts - attemptsLeft})")
				break
			}
			logger.info("Arkiveringsstatus=$currentStatus (forsøk igjen $attemptsLeft)")
			Thread.sleep(attemptDelayMs)
		} while (attemptsLeft > 0)

		if (currentStatus != expectedStatus) {
			throw Exception("Forventet at søknad skulle få status '$expectedStatus' i databasen hos innsending-api, men status er '$currentStatus'")
		}
		return this
	}

}
