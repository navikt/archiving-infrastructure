package no.nav.soknad.arkivering.innsending

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import no.nav.soknad.arkivering.Config
import no.nav.soknad.arkivering.OAuth2Config
import no.nav.soknad.arkivering.soknadsfillager.api.FilesApi
import no.nav.soknad.arkivering.soknadsfillager.infrastructure.ApiClient
import no.nav.soknad.arkivering.soknadsfillager.infrastructure.Serializer.jacksonObjectMapper
import no.nav.soknad.arkivering.soknadsfillager.model.FileData
import no.nav.soknad.arkivering.tokensupport.createOkHttpAuthorizationClient
import org.slf4j.LoggerFactory
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

		val scopesProvider = { oauth2Conf: OAuth2Config -> listOf(oauth2Conf.scopeSoknadsfillager) }

		filesApi = FilesApi(config.soknadsfillagerUrl, createOkHttpAuthorizationClient(scopesProvider))
	}


	fun getFiles(innsendingId: String, fileId: String, metadataOnly: Boolean?): List<FileData> {
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

		for (i in 1..maxTries) {
			val startTime = System.currentTimeMillis()
			try {
				filesApi.addFiles(files, innsendingId, "disabled")
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
