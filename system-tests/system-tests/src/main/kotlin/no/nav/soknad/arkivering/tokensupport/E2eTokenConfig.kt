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

	/**
	 * Slår på tokenvalidering i innsending-api og token fra soknadsarkiverer: `-De2eTokenValidation=true`.
	 * Krever innsending-api med profilen endtoend-auth og soknadsarkiverer med innsendingapi.send-token.
	 */
	val tokenValidationEnabled: Boolean
		get() = System.getProperty("e2eTokenValidation").toBoolean()

	/** Scopet soknadsarkiverer ber om for innsending-api, som i dev */
	const val SOKNADSARKIVERER_INNSENDING_API_SCOPE = "api://dev-gcp.team-soknad.innsending-api/.default"

	private const val SOKNADSARKIVERER_OBJECT_ID = "0b8f5a1e-7c2d-4e3f-8a9b-5c6d7e8f9a0b"

	const val TOKEN_X_AUDIENCE = "dev-gcp:team-soknad:innsending-api"
	const val AZURE_AUDIENCE = "aud-localhost"

	/** Personen testene oppretter søknader for. PdlAPITest i innsending-api gir samme ident */
	const val TEST_PERSON_ID = "19876898104"

	private const val NOLOGIN_OBJECT_ID = "6f1c2b8e-3d4a-4f5b-9c7d-1e2f3a4b5c6d"

	private val citizenClaims: Map<String, Any> = mapOf(
		"sub" to TEST_PERSON_ID,
		"pid" to TEST_PERSON_ID,
		"acr" to "idporten-loa-high",
		"idp" to "https://test.idporten.no",
		"client_id" to "dev-gcp:team-soknad:send-inn-frontend",
	)

	private val nologinClaims: Map<String, Any> = mapOf(
		"sub" to NOLOGIN_OBJECT_ID,
		"oid" to NOLOGIN_OBJECT_ID,
		"azp" to "e2e-skjemautfylling",
		"azp_name" to "dev-gcp:skjemadigitalisering:skjemautfylling",
		"idtyp" to "app",
		"roles" to listOf("access_as_application", "nologin-access"),
	)

	enum class Caller(val issuerId: String, val clientId: String, val audience: String, val claims: Map<String, Any>) {
		// Som send-inn-frontend og fyllut-ettersending: TokenX utvekslet fra ID-porten med høyt sikkerhetsnivå
		CITIZEN("tokenx", "e2e-send-inn-frontend", TOKEN_X_AUDIENCE, citizenClaims),

		// Som Fyllut (skjemautfylling) uten innlogging: Azure client credentials med app-rollen nologin-access
		NOLOGIN("azuread", "e2e-skjemautfylling", AZURE_AUDIENCE, nologinClaims),

		// Token innsending-api skal avvise. Hvert avviker fra et gyldig token på ett punkt
		CITIZEN_LOW_ACR(
			"tokenx", "e2e-citizen-low-acr", TOKEN_X_AUDIENCE,
			citizenClaims + ("acr" to "idporten-loa-substantial"),
		),
		CITIZEN_WRONG_AUDIENCE("tokenx", "e2e-citizen-wrong-audience", "dev-gcp:team-soknad:annen-app", citizenClaims),
		NOLOGIN_WITHOUT_ROLE(
			"azuread", "e2e-azure-without-nologin-role", AZURE_AUDIENCE,
			nologinClaims + ("roles" to listOf("access_as_application")),
		),
	}

	/**
	 * JSON_CONFIG for mock-oauth2-server. Hver kaller får claimene sine ut fra client_id.
	 *
	 * Når en issuer har tokenCallbacks, gir mock-serveren forespørsler uten treff verken sub eller aud.
	 * Den siste mappingen gjenskaper derfor standardoppførselen for andre klienter: sub er klient-ID-en og aud er scope.
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
				"requestMappings" to callerMappings + scopeMappings(issuerId) + defaultMapping,
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

	// soknadsarkiverer henter M2M-token selv. Den kjennes igjen på scopet og får claims som en Azure-app i dev
	private fun scopeMappings(issuerId: String): List<Map<String, Any>> =
		if (issuerId != "azuread") emptyList()
		else listOf(
			mapOf(
				"requestParam" to "scope",
				"match" to SOKNADSARKIVERER_INNSENDING_API_SCOPE,
				"claims" to mapOf(
					"sub" to SOKNADSARKIVERER_OBJECT_ID,
					"oid" to SOKNADSARKIVERER_OBJECT_ID,
					"azp" to "\${clientId}",
					"azp_name" to "dev-gcp:team-soknad:soknadsarkiverer",
					"idtyp" to "app",
					"roles" to listOf("access_as_application"),
					"aud" to listOf(AZURE_AUDIENCE),
					"tid" to issuerId,
				),
			)
		)
}
