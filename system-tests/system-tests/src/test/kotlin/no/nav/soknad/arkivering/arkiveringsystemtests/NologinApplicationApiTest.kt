package no.nav.soknad.arkivering.arkiveringsystemtests

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import no.nav.soknad.arkivering.Config
import no.nav.soknad.arkivering.innsending.InnsendingApi
import no.nav.soknad.arkivering.innsending.model.AttachmentDto
import no.nav.soknad.arkivering.innsending.model.OpplastingsStatusDto
import no.nav.soknad.arkivering.utils.SubmitApplicationRequestBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetSocketAddress
import java.util.UUID

class NologinApplicationApiTest {
	@Test
	fun `upload submit and delete use the application nologin contract`() {
		val submissionId = UUID.randomUUID()
		val attachmentId = UUID.randomUUID().toString()
		val fileId = UUID.randomUUID()
		val requests = mutableListOf<Pair<String, String>>()
		val server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
		server.createContext("/") { exchange ->
			val body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
			requests += "${exchange.requestMethod} ${exchange.requestURI.path}" to body
			val response = when (exchange.requestMethod) {
				"DELETE" -> ""
				"POST" -> if (exchange.requestURI.path.endsWith("/$attachmentId")) {
					"""{"id":"$fileId","name":"One_MB.pdf"}"""
				} else {
					"""{"innsendingsId":"$submissionId","submittedAt":"2026-01-01T00:00:00Z"}"""
				}
				else -> error("Unexpected request: ${exchange.requestMethod}")
			}
			exchange.responseHeaders.set("Content-Type", "application/json")
			val status = when {
				exchange.requestMethod == "DELETE" -> 204
				exchange.requestURI.path.endsWith("/$attachmentId") -> 201
				else -> 200
			}
			exchange.sendResponseHeaders(status, if (response.isEmpty()) -1 else response.toByteArray().size.toLong())
			exchange.responseBody.use { it.write(response.toByteArray()) }
		}
		server.start()

		try {
			val api = InnsendingApi(Config(innsendingApiUrl = "http://localhost:${server.address.port}"))
			val file = File(requireNotNull(javaClass.getResource("/One_MB.pdf")).toURI())
			assertEquals(fileId, api.lastOppNoLoginFil(submissionId.toString(), attachmentId, file).getOrThrow().id)

			val application = SubmitApplicationRequestBuilder(brukerId = "19876898104")
				.medVedlegg(AttachmentDto("A1", "Attachment", OpplastingsStatusDto.lastetOpp, fileIds = listOf(fileId)))
				.build()
			assertEquals(submissionId, api.sendInNoLoginApplication(submissionId, application).getOrThrow().innsendingsId)
			api.slettNoLoginFil(submissionId.toString(), attachmentId, fileId.toString()).getOrThrow()

			val base = "/v1/application-nologin/$submissionId"
			assertEquals(listOf(
				"POST $base/attachments/$attachmentId",
				"POST $base",
				"DELETE $base/attachments/$attachmentId/$fileId",
			), requests.map { it.first })
			assertTrue(requests[0].second.contains("name=\"file\""))
			assertTrue(requests[0].second.contains("%PDF-"))
			val submitted = jacksonObjectMapper().readTree(requests[1].second)
			assertEquals(fileId.toString(), submitted["attachments"][0]["fileIds"][0].asText())
			assertEquals("19876898104", submitted["bruker"].asText())
			assertTrue(submitted.has("mainDocument"))
			assertTrue(submitted.has("mainDocumentAlt"))
		} finally {
			server.stop(0)
		}
	}
}
