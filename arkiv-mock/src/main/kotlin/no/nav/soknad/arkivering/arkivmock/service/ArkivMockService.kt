package no.nav.soknad.arkivering.arkivmock.service

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import no.nav.soknad.arkivering.arkivmock.dto.ArkivData
import no.nav.soknad.arkivering.arkivmock.dto.ArkivDbData
import no.nav.soknad.arkivering.arkivmock.dto.Dokumenter
import no.nav.soknad.arkivering.arkivmock.dto.OpprettJournalpostResponse
import no.nav.soknad.arkivering.arkivmock.repository.ArkivRepository
import no.nav.soknad.arkivering.arkivmock.service.kafka.KafkaPublisher
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.*

@Service
class ArkivMockService(
	private val arkivRepository: ArkivRepository,
	private val behaviourService: BehaviourService,
	private val kafkaPublisher: KafkaPublisher
) {

	fun reset() {
		arkivRepository.deleteAll()
	}

	fun archive(arkivData: ArkivData): String? {
		reactToArchiveRequest(arkivData)

		val data = createArkivDbData(arkivData)
		saveToDatabaseAndAlertOnKafka(data)

		val response = createResponse(arkivData, data)
		return behaviourService.alterResponse(arkivData.eksternReferanseId, response)
	}

	private fun reactToArchiveRequest(arkivData: ArkivData) {
		val id = arkivData.eksternReferanseId

		try {
			behaviourService.reactToArchiveRequest(id)
		} finally {
			val numberOfCalls = behaviourService.getNumberOfCallsThatHaveBeenMade(id)
			GlobalScope.launch { kafkaPublisher.putNumberOfCallsOnTopic(id, if (numberOfCalls > 0) numberOfCalls else 1) }
		}
	}

	private fun createResponse(arkivData: ArkivData, data: ArkivDbData): OpprettJournalpostResponse {
		val dokumenter = arkivData.dokumenter.map { Dokumenter(it.brevkode, UUID.randomUUID().toString(), it.tittel) }
		return OpprettJournalpostResponse(dokumenter, data.id, true, "MIDLERTIDIG", "null")
	}

	private fun createArkivDbData(arkivData: ArkivData): ArkivDbData {
		return ArkivDbData(
			arkivData.eksternReferanseId,
			arkivData.tittel,
			arkivData.tema,
			LocalDateTime.now().toInstant(ZoneOffset.UTC).toEpochMilli()
		)
	}

	private fun saveToDatabaseAndAlertOnKafka(data: ArkivDbData) {
		val dbEntity = arkivRepository.save(data)
		GlobalScope.launch {
			kafkaPublisher.putDataOnTopic(data.id, dbEntity)
			kafkaPublisher.putNumberOfEntitiesOnTopic(data.id, arkivRepository.count().toInt())
		}
	}
}
