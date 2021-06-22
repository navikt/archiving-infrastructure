package no.nav.soknad.arkivering.innsending

import com.fasterxml.jackson.core.type.TypeReference
import no.nav.soknad.arkivering.Configuration
import no.nav.soknad.arkivering.dto.FilElementDto
import no.nav.soknad.arkivering.utils.loopAndVerify
import java.time.LocalDateTime


fun sendFilesToFileStorage(uuid: String, appConfiguration: Configuration) {
	val message = "fileUuid is $uuid for test '${Thread.currentThread().stackTrace[2].methodName}'"
	sendFilesToFileStorage(uuid, "apabepa".toByteArray(), message, appConfiguration)
}

fun sendFilesToFileStorage(uuid: String, payload: ByteArray, message: String, appConfiguration: Configuration) {
	println(message)
	sendFilesToFileStorageAndVerify(uuid, payload, appConfiguration)
}

private fun sendFilesToFileStorageAndVerify(uuid: String, payload: ByteArray, appConfiguration: Configuration) {
	val files = listOf(FilElementDto(uuid, payload, LocalDateTime.now()))
	val url = appConfiguration.config.soknadsfillagerUrl + "/filer"

	val numberOfAttempts = 10
	val headers = appConfiguration.config.soknadsfillagerUsername to appConfiguration.config.soknadsfillagerPassword
	for (i in 0 .. numberOfAttempts) {
		performPostCall(files, url, headers, false)
		if (verifyFileExists(uuid, appConfiguration))
			return
		println("Failed to verify file '$uuid' - reattempting")
	}
	println("Failed to send file '$uuid' to Filestorage after $numberOfAttempts attempts!!")
}

fun pollAndVerifyDataInFileStorage(uuid: String, expectedNumberOfHits: Int, appConfiguration: Configuration) {
	val url = appConfiguration.config.soknadsfillagerUrl + "/filer?ids=$uuid"
	loopAndVerify(expectedNumberOfHits, { getNumberOfFiles(url, appConfiguration) },
		{ assert(expectedNumberOfHits == getNumberOfFiles(url, appConfiguration)) { "Expected $expectedNumberOfHits files in File Storage" } })
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
