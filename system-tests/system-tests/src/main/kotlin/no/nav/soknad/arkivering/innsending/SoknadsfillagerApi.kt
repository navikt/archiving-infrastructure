package no.nav.soknad.arkivering.innsending

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import no.nav.soknad.arkivering.Config
import no.nav.soknad.arkivering.soknadsfillager.api.FilesApi
import no.nav.soknad.arkivering.soknadsfillager.infrastructure.ApiClient
import no.nav.soknad.arkivering.soknadsfillager.infrastructure.Serializer.jacksonObjectMapper
import no.nav.soknad.arkivering.soknadsfillager.model.FileData
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.ZoneOffset

class SoknadsfillagerApi(config: Config) {
	private val logger = LoggerFactory.getLogger(javaClass)

	private val filesApi: FilesApi

	init {
		jacksonObjectMapper.registerModule(JavaTimeModule())
		ApiClient.username = config.soknadsfillagerUsername
		ApiClient.password = config.soknadsfillagerPassword
		filesApi = FilesApi(config.soknadsfillagerUrl)
	}

	fun checkFilesInFileStorage(innsendingId: String, fileId: String) {
		filesApi.checkFilesByIds(listOf(fileId), innsendingId)
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
		val files = listOf(FileData(fileId, payload, OffsetDateTime.now(ZoneOffset.UTC)))
		filesApi.addFiles(files, innsendingId)
	}
}
