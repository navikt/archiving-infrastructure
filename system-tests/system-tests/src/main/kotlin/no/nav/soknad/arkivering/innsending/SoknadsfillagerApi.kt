package no.nav.soknad.arkivering.innsending

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.nimbusds.oauth2.sdk.auth.ClientAuthenticationMethod
import no.nav.security.token.support.client.core.ClientAuthenticationProperties
import no.nav.security.token.support.client.core.ClientProperties
import no.nav.security.token.support.client.core.OAuth2GrantType
import no.nav.security.token.support.client.core.oauth2.ClientCredentialsTokenClient
import no.nav.security.token.support.client.core.oauth2.OAuth2AccessTokenService
import no.nav.soknad.arkivering.Config
import no.nav.soknad.arkivering.Oauth2Config
import no.nav.soknad.arkivering.soknadsfillager.api.FilesApi
import no.nav.soknad.arkivering.soknadsfillager.infrastructure.ApiClient
import no.nav.soknad.arkivering.soknadsfillager.infrastructure.Serializer.jacksonObjectMapper
import no.nav.soknad.arkivering.soknadsfillager.model.FileData
import no.nav.soknad.arkivering.tokensupport.DefaultOAuth2HttpClient
import no.nav.soknad.arkivering.tokensupport.TokenService
import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory
import java.net.URI
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

class SoknadsfillagerApi(config: Config) {
	private val logger = LoggerFactory.getLogger(javaClass)

	private val filesApi: FilesApi

	init {
		jacksonObjectMapper.registerModule(JavaTimeModule())
		ApiClient.username = config.soknadsfillagerUsername
		ApiClient.password = config.soknadsfillagerPassword
		val auth2Conf = Oauth2Config()
		val okHttpClientOauth2  = OkHttpClient()
		val OAuth2AccessTokenService = OAuth2AccessTokenService(null,null, ClientCredentialsTokenClient(
			DefaultOAuth2HttpClient(okHttpClientOauth2)
		),null)
		val clientProperties = ClientProperties(
			URI.create(auth2Conf.tokenEndpointUrl),
			null, OAuth2GrantType(auth2Conf.grantType) ,
			listOf(auth2Conf.scopeSoknadsfillager),
			ClientAuthenticationProperties(auth2Conf.clientId,
				ClientAuthenticationMethod(auth2Conf.clientAuthMethod),auth2Conf.clientSecret,null),null,null)
		val tokenService = TokenService(clientProperties,OAuth2AccessTokenService)

		val okHttpClientTokenService  = OkHttpClient().newBuilder().addInterceptor {
			val token =	tokenService.getToken()
			logger.info("Adding header to request with token " + token)
			val bearerRequest = it.request().newBuilder().headers(it.request().headers).header("Authorization", "Bearer$token"
			).build()

			it.proceed(bearerRequest)
		}.build()

		filesApi = FilesApi(config.soknadsfillagerUrl,okHttpClientTokenService)
	}


	fun getFiles(innsendingId: String, fileId: String, metadataOnly: Boolean?) : List<FileData> {
		return filesApi.findFilesByIds(ids = listOf(fileId), xInnsendingId = innsendingId, metadataOnly = metadataOnly)
	}

	fun sendFilesToFileStorage(innsendingId: String, fileId: String) {
		val message = "$innsendingId: Uploading file with id $fileId for test '${Thread.currentThread().stackTrace[2].methodName}'"
		sendFilesToFileStorage(innsendingId, fileId, "apabepa".toByteArray(), message)
	}

	fun sendFilesToFileStorage(innsendingId: String, fileId: String, payload: ByteArray, message: String) {
		logger.debug(message)
		sendFilesToFileStorage(innsendingId, fileId, payload)
	}

	private fun sendFilesToFileStorage(innsendingId: String, fileId: String, payload: ByteArray) {
		val maxTries = 3
		val files = listOf(FileData(fileId, payload, OffsetDateTime.now(ZoneOffset.UTC)))

		for (i in 1 .. maxTries) {
			val startTime = System.currentTimeMillis()
			try {
				filesApi.addFiles(files, innsendingId)
				break

			} catch (e: Exception) {
				val timeElapsed = System.currentTimeMillis() - startTime
				logger.error("$innsendingId: $i / $maxTries: Failed to send file $fileId to Filestorage in ${timeElapsed}ms", e)

				if (i == maxTries) {
					logger.error("Too many failed attempts; giving up")
					throw e
				}
				TimeUnit.SECONDS.sleep(i * 5L)
			}
		}
	}
}
