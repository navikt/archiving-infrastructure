package no.nav.soknad.arkivering.tokensupport

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * Tokenene systemtestene bruker, med claims hentet fra produksjonskonsumentene av innsending-api.
 *
 * Audience må stemme med klient-ID-ene innsending-api får i e2e (TOKEN_X_CLIENT_ID og AZURE_APP_CLIENT_ID).
 * Innsending-api validerer ikke token i e2e ennå, så disse verdiene tar først effekt når valideringen slås på.
 */
object E2eTokenConfig {

	/** Host og port for mock-oauth2-server i Docker-nettverket. Issuer blir http://authserver:6969/<issuerId> */
	const val AUTH_SERVER_HOST = "authserver:6969"

	const val TOKEN_X_AUDIENCE = "dev-gcp:team-soknad:innsending-api"
	const val AZURE_AUDIENCE = "aud-localhost"

	/** Personen testene oppretter søknader for. PdlAPITest i innsending-api gir samme ident */
	const val TEST_PERSON_ID = "19876898104"

	private const val NOLOGIN_OBJECT_ID = "6f1c2b8e-3d4a-4f5b-9c7d-1e2f3a4b5c6d"

	enum class Caller(val issuerId: String, val clientId: String, val audience: String, val claims: Map<String, Any>) {
		// Som send-inn-frontend og fyllut-ettersending: TokenX utvekslet fra ID-porten med høyt sikkerhetsnivå
		CITIZEN(
			issuerId = "tokenx",
			clientId = "e2e-send-inn-frontend",
			audience = TOKEN_X_AUDIENCE,
			claims = mapOf(
				"sub" to TEST_PERSON_ID,
				"pid" to TEST_PERSON_ID,
				"acr" to "idporten-loa-high",
				"idp" to "https://test.idporten.no",
				"client_id" to "dev-gcp:team-soknad:send-inn-frontend",
			),
		),

		// Som Fyllut (skjemautfylling) uten innlogging: Azure client credentials med app-rollen nologin-access
		NOLOGIN(
			issuerId = "azuread",
			clientId = "e2e-skjemautfylling",
			audience = AZURE_AUDIENCE,
			claims = mapOf(
				"sub" to NOLOGIN_OBJECT_ID,
				"oid" to NOLOGIN_OBJECT_ID,
				"azp" to "e2e-skjemautfylling",
				"azp_name" to "dev-gcp:skjemadigitalisering:skjemautfylling",
				"idtyp" to "app",
				"roles" to listOf("access_as_application", "nologin-access"),
			),
		),
	}

	/**
	 * JSON_CONFIG for mock-oauth2-server. Hver kaller får claimene sine ut fra client_id.
	 *
	 * Når en issuer har tokenCallbacks, gir mock-serveren forespørsler uten treff verken sub eller aud.
	 * Den siste mappingen gjenskaper derfor standardoppførselen for andre klienter, for eksempel soknadsarkiverer:
	 * sub er klient-ID-en og aud er scope.
	 */
	fun mockOAuth2ServerJsonConfig(): String {
		val tokenCallbacks = Caller.entries.groupBy { it.issuerId }.map { (issuerId, callers) ->
			val callerMappings = callers.map { caller ->
				mapOf(
					"requestParam" to "client_id",
					"match" to caller.clientId,
					"claims" to caller.claims + ("aud" to listOf(caller.audience)) + ("tid" to issuerId),
				)
			}
			val defaultMapping = mapOf(
				"requestParam" to "client_id",
				"match" to "*",
				"claims" to mapOf("sub" to "\${clientId}", "aud" to "\${scope}", "tid" to issuerId),
			)
			mapOf(
				"issuerId" to issuerId,
				"tokenExpiry" to 3600,
				"requestMappings" to callerMappings + defaultMapping,
			)
		}
		return jacksonObjectMapper().writeValueAsString(
			mapOf(
				"interactiveLogin" to true,
				"httpServer" to "NettyWrapper",
				"tokenCallbacks" to tokenCallbacks,
			)
		)
	}
}
