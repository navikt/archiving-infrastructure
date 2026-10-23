package no.nav.soknad.arkivering.tokensupport

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Duration
import java.time.Instant

/**
 * Henter ekte token fra mock-oauth2-server for systemtestene, med claims som tilsvarer produksjon.
 *
 * Claimene settes av `tokenCallbacks` i mock-oauth2-server (se [E2eTokenConfig.mockOAuth2ServerJsonConfig]),
 * valgt ut fra `client_id` i forespørselen. Uten [authServerUrl] sendes ingen token, slik som før.
 */
class E2eTokens(private val authServerUrl: String?) {

	private val httpClient = OkHttpClient()
	private val cache = mutableMapOf<E2eTokenConfig.Caller, CachedToken>()

	val enabled: Boolean
		get() = authServerUrl != null

	/** TokenX-token for innbyggeren testene oppretter søknader for */
	fun citizen(): String? = token(E2eTokenConfig.Caller.CITIZEN)

	/** Azure M2M-token for Fyllut sine kall uten innlogging, med rollen nologin-access */
	fun nologin(): String? = token(E2eTokenConfig.Caller.NOLOGIN)

	@Synchronized
	private fun token(caller: E2eTokenConfig.Caller): String? {
		val baseUrl = authServerUrl ?: return null
		val cached = cache[caller]
		if (cached != null && cached.expiresAt.isAfter(Instant.now().plus(REFRESH_MARGIN))) return cached.token

		val body = FormBody.Builder()
			.add("grant_type", "client_credentials")
			.add("client_id", caller.clientId)
			.add("client_secret", "e2e-secret")
			// Mappingen i mock-oauth2-server setter aud. Scope sendes for at forespørselen skal ligne produksjon
			.add("scope", caller.audience)
			.build()
		val request = Request.Builder()
			.url("$baseUrl/${caller.issuerId}/token")
			// Issueren i tokenet bygges fra Host. Innsending-api ser auth-serveren som authserver:6969 i Docker-nettverket
			.header("Host", E2eTokenConfig.AUTH_SERVER_HOST)
			.post(body)
			.build()

		val response = httpClient.newCall(request).execute().use { response ->
			check(response.isSuccessful) { "Kunne ikke hente token for $caller fra mock-oauth2-server: HTTP ${response.code}" }
			jacksonObjectMapper().readTree(response.body.string())
		}
		val token = response["access_token"].asText()
		val expiresIn = response["expires_in"]?.asLong() ?: 0L
		cache[caller] = CachedToken(token, Instant.now().plusSeconds(expiresIn))
		return token
	}

	/** Klient som legger ved tokenet fra [tokenSupplier], hvis det finnes */
	fun clientWithBearer(baseClient: OkHttpClient, tokenSupplier: () -> String?): OkHttpClient =
		baseClient.newBuilder()
			.addInterceptor { chain ->
				val token = tokenSupplier()
				val request = if (token == null) chain.request()
				else chain.request().newBuilder().header("Authorization", "Bearer $token").build()
				chain.proceed(request)
			}
			.build()

	private data class CachedToken(val token: String, val expiresAt: Instant)

	companion object {
		private val REFRESH_MARGIN: Duration = Duration.ofMinutes(5)

		val none = E2eTokens(null)
	}
}
