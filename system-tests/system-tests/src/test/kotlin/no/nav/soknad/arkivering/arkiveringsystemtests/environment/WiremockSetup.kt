package no.nav.soknad.arkivering.arkiveringsystemtests.environment

import com.expediagroup.graphql.client.types.GraphQLClientError
import com.expediagroup.graphql.client.types.GraphQLClientResponse
import com.expediagroup.graphql.client.types.GraphQLClientSourceLocation
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.http.RequestMethod
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder
import com.github.tomakehurst.wiremock.stubbing.Scenario
import com.github.tomakehurst.wiremock.verification.LoggedRequest
import no.nav.soknad.arkivering.saf.generated.HentJournalpostGittEksternReferanseId
import no.nav.soknad.arkivering.saf.generated.enums.*
import no.nav.soknad.arkivering.saf.generated.hentjournalpostgitteksternreferanseid.AvsenderMottaker
import no.nav.soknad.arkivering.saf.generated.hentjournalpostgitteksternreferanseid.Bruker
import no.nav.soknad.arkivering.saf.generated.hentjournalpostgitteksternreferanseid.Journalpost
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.*


private lateinit var wiremockServer: WireMockServer
private lateinit var safUrl: String

fun setupMockedNetworkServices(port: Int, urlSaf: String) {
    safUrl = urlSaf

    wiremockServer = WireMockServer(port)
    wiremockServer.start()
}

fun stopMockedNetworkServices() {
    wiremockServer.stop()
}

fun verifyMockedGetRequests(expectedCount: Int, url: String) = verifyMockedRequests(expectedCount, url, RequestMethod.GET)
fun verifyMockedPostRequests(expectedCount: Int, url: String) = verifyMockedRequests(expectedCount, url, RequestMethod.POST)
fun verifyMockedDeleteRequests(expectedCount: Int, url: String) = verifyMockedRequests(expectedCount, url, RequestMethod.DELETE)

fun countRequests(url: String, requestMethod: RequestMethod): Int {
    val requestPattern = RequestPatternBuilder.newRequestPattern(requestMethod, urlMatching(url)).build()
    return wiremockServer.countRequestsMatching(requestPattern).count
}

private fun verifyMockedRequests(expectedCount: Int, url: String, requestMethod: RequestMethod) {

    val requestPattern = RequestPatternBuilder.newRequestPattern(requestMethod, urlMatching(url)).build()
    val getCount = { wiremockServer.countRequestsMatching(requestPattern).count }

    loopAndVerify(expectedCount, getCount)
}

fun verifyPostRequest(url: String): List<LoggedRequest> = wiremockServer.findAll(postRequestedFor(urlMatching(url)))



fun mockSafRequest_notFound(url: String? = safUrl, innsendingsId: String) {
	wiremockServer.stubFor(
		post(urlMatching(url))
			.willReturn(
				aResponse()
					.withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
					.withBody(createSafResponse_withoutJournalpost(innsendingsId = innsendingsId))
					.withStatus(HttpStatus.OK.value())
			)
	)
}

fun mockSafRequest_found(url: String? = safUrl, innsendingsId: String) {
	wiremockServer.stubFor(
		post(urlMatching(url))
			.willReturn(
				aResponse()
					.withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
					.withBody(createSafResponse_withJournalpost(innsendingsId = innsendingsId ))
					.withStatus(HttpStatus.OK.value())
			)
	)
}

fun mockSafRequest_error(url: String? = safUrl, innsendingsId: String) {
	wiremockServer.stubFor(
		post(urlMatching(url))
			.willReturn(
				aResponse()
					.withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
					.withBody(createSafResponse_withError(innsendingsId = innsendingsId ))
					.withStatus(HttpStatus.OK.value())
			)
	)
}

fun mockSafRequest_foundAfterAttempt(url: String? = safUrl, innsendingsId: String, attempts: Int) {
	val stateNames = listOf(Scenario.STARTED).plus((0 until attempts).map { "iteration_$it" })
	for (attempt in (0 until stateNames.size - 1)) {
		wiremockServer.stubFor(
			post(urlMatching(url))
				.inScenario("integrationTest").whenScenarioStateIs(stateNames[attempt])
				.willReturn(
					aResponse()
						.withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
						.withBody(createSafResponse_withoutJournalpost(innsendingsId = innsendingsId))
						.withStatus(HttpStatus.OK.value())
				)
		)
	}
	wiremockServer.stubFor(
		post(urlMatching(url))
			.inScenario("integrationTest").whenScenarioStateIs(stateNames.last())
			.willReturn(
				aResponse()
					.withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
					.withBody(createSafResponse_withJournalpost(innsendingsId = innsendingsId ))
					.withStatus(HttpStatus.OK.value())
			)
	)
}

private fun mockSAF(statusCode: Int, responseBody: String, delay: Int) {
    wiremockServer.stubFor(
        post(urlEqualTo(safUrl))
            .willReturn(aResponse()
                .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .withBody(responseBody)
                .withStatus(statusCode)
                .withFixedDelay(delay)))
}


private fun createSafResponse_withJournalpost(innsendingsId: String): String {
	val objectMapper = ObjectMapper()
	return objectMapper.writeValueAsString(
		graphQlResponse(data = createSafJournalpostResponse(innsendingsId), errors = null, extensions = null)
	)
}

private fun createSafResponse_withoutJournalpost(innsendingsId: String): String
{
	val objectMapper = ObjectMapper()
	return objectMapper.writeValueAsString(
		graphQlResponse(data = null, errors = null, extensions = null))
}

private fun createSafResponse_withError(innsendingsId: String): String =  ObjectMapper().writeValueAsString(
	graphQlResponse(data = null, errors = createSafErrorResponse(innsendingsId), extensions = null)
)

private fun createSafJournalpostResponse(innsendingsId: String) =
	HentJournalpostGittEksternReferanseId.Result(
		Journalpost(
			journalpostId = "12345", tittel = "Test søknad",
			journalposttype = Journalposttype.I, journalstatus = Journalstatus.MOTTATT,
			tema = Tema.BID, bruker = Bruker("12345678901", BrukerIdType.FNR),
			avsenderMottaker = AvsenderMottaker("12345678901", type = AvsenderMottakerIdType.FNR),
			datoOpprettet = LocalDateTime.now().minusMinutes(2).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
			eksternReferanseId = innsendingsId
		)
	)

private fun createSafErrorResponse(innsendingsId: String) =
	listOf(GraphQLErrorResponse(message = "401 Unauthorized. Exception when looking for $innsendingsId"))

data class graphQlResponse<Journalpost> (
	override val data: Journalpost? = null,
	override val errors: List<GraphQLClientError>? = null,
	override val extensions: Map<String, Any?>? = null
): GraphQLClientResponse<Journalpost>

data class GraphQLErrorResponse (
	override val extensions: Map<String, Any?>? = null,
	override val locations: List<GraphQLClientSourceLocation>? = null,
	override val message: String,
	override val path: List<Any>? = null
): GraphQLClientError

