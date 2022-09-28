package no.nav.soknad.arkivering.tokensupport

import no.nav.security.token.support.client.core.ClientProperties
import no.nav.security.token.support.client.core.oauth2.OAuth2AccessTokenResponse
import no.nav.security.token.support.client.core.oauth2.OAuth2AccessTokenService

class TokenService(
	private val clientProperties: ClientProperties,
	private val oAuth2AccessTokenService: OAuth2AccessTokenService
) {
	fun getToken(): OAuth2AccessTokenResponse = oAuth2AccessTokenService.getAccessToken(clientProperties)
}
