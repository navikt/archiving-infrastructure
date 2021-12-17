package no.nav.soknad.arkivering.innsending

import com.fasterxml.jackson.core.type.TypeReference
import no.nav.soknad.arkivering.Configuration
import no.nav.soknad.arkivering.dto.FilElementDto
import no.nav.soknad.arkivering.utils.loopAndVerify
import org.slf4j.LoggerFactory
import java.time.LocalDateTime

private val logger = LoggerFactory.getLogger("no.nav.soknad.arkivering.innsending.Soknadsfillager")

fun sendFilesToFileStorage(uuid: String, appConfiguration: Configuration) {
	val message = "Uploading file with fileUuid $uuid for test '${Thread.currentThread().stackTrace[2].methodName}'"
	sendFilesToFileStorage(uuid, "apabepa".toByteArray(), message, appConfiguration)
}

fun sendFilesToFileStorage(uuid: String, payload: ByteArray, message: String, appConfiguration: Configuration) {
	logger.debug(message)
	sendFilesToFileStorageAndVerify(uuid, payload, appConfiguration)
}

private fun sendFilesToFileStorageAndVerify(uuid: String, payload: ByteArray, appConfiguration: Configuration) {
	val files = listOf(FilElementDto(uuid, payload, LocalDateTime.now()))
	val url = appConfiguration.config.soknadsfillagerUrl + "/filer"

	val numberOfAttempts = 10
	val headers = appConfiguration.config.soknadsfillagerUsername to appConfiguration.config.soknadsfillagerPassword
	for (i in 0..numberOfAttempts) {
		performPostCall(files, url, headers, false)
		if (verifyFileExists(uuid, appConfiguration))
			return
		logger.warn("Failed to verify file '$uuid' - reattempting")
	}
	logger.error("Failed to send file '$uuid' to Filestorage after $numberOfAttempts attempts!!")
}

fun pollAndVerifyDataInFileStorage(uuid: String, expectedNumberOfHits: Int, appConfiguration: Configuration) {
	val url = appConfiguration.config.soknadsfillagerUrl + "/filer?ids=$uuid"

	val errorMessage = "Expected $expectedNumberOfHits files in File Storage"
	val finalCheck = { assert(expectedNumberOfHits == getNumberOfFiles(url, appConfiguration)) { errorMessage } }

	loopAndVerify(expectedNumberOfHits, { getNumberOfFiles(url, appConfiguration) }, finalCheck)
}

private fun verifyFileExists(uuid: String, appConfiguration: Configuration): Boolean {
	val url = appConfiguration.config.soknadsfillagerUrl + "/filer?ids=$uuid"
	return getNumberOfFiles(url, appConfiguration) == 1
}


private fun getNumberOfFiles(url: String, appConfiguration: Configuration): Int {
	val headers = appConfiguration.config.soknadsfillagerUsername to appConfiguration.config.soknadsfillagerPassword
	val bytes = performGetCall(url, headers)

	val listOfFiles = try {
		objectMapper.readValue(bytes, object : TypeReference<List<FilElementDto>>() {})
	} catch (e: Exception) {
		emptyList()
	}

	return listOfFiles.filter { file -> file.fil != null }.size
}
