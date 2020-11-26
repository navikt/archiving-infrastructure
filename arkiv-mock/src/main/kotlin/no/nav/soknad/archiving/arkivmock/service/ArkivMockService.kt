package no.nav.soknad.archiving.arkivmock.service

import no.nav.soknad.archiving.arkivmock.dto.Dokumenter
import no.nav.soknad.archiving.arkivmock.dto.ArkivData
import no.nav.soknad.archiving.arkivmock.dto.OpprettJournalpostResponse
import no.nav.soknad.archiving.arkivmock.dto.ArkivDbData
import no.nav.soknad.archiving.arkivmock.repository.ArkivRepository
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.*

@Service
class ArkivMockService(private val arkivRepository: ArkivRepository, private val behaviourService: BehaviourService) {

	fun archive(arkivData: ArkivData): String? {
		behaviourService.reactToArchiveRequest(arkivData.eksternReferanseId)

		val data = createArkivDbData(arkivData)
		arkivRepository.save(data)

		val response = createResponse(arkivData, data)
		return behaviourService.alterResponse(arkivData.eksternReferanseId, response)
	}

	private fun createResponse(arkivData: ArkivData, data: ArkivDbData): OpprettJournalpostResponse {
		val dokumenter = arkivData.dokumenter.map { Dokumenter(it.brevkode, UUID.randomUUID().toString(), it.tittel) }
		return OpprettJournalpostResponse(dokumenter, data.id, true, "MIDLERTIDIG", "null")
	}

	fun lookup(id: String): Optional<ArkivDbData> {
		return arkivRepository.findById(id)
	}

	private fun createArkivDbData(arkivData: ArkivData) =
		ArkivDbData(arkivData.eksternReferanseId, arkivData.tittel, arkivData.tema, LocalDateTime.now())
}
