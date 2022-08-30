package no.nav.soknad.arkivering.tokensupport

import no.nav.security.token.support.client.core.ClientProperties
import no.nav.security.token.support.client.core.oauth2.OAuth2AccessTokenResponse
import no.nav.security.token.support.client.core.oauth2.OAuth2AccessTokenService
import org.slf4j.LoggerFactory


class TokenService(private val clientProperties: ClientProperties, private val oAuth2AccessTokenService: OAuth2AccessTokenService) {
	private val logger = LoggerFactory.getLogger(javaClass)
	fun getToken() : String {

			val response: OAuth2AccessTokenResponse = oAuth2AccessTokenService.getAccessToken(clientProperties)
		logger.info("Acces token is retrieved " + response.accessToken)
		return response.accessToken
		}
}
