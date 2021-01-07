package no.nav.soknad.archiving.arkivmock.service

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import no.nav.soknad.archiving.arkivmock.dto.ArkivData
import no.nav.soknad.archiving.arkivmock.dto.ArkivDbData
import no.nav.soknad.archiving.arkivmock.dto.Dokumenter
import no.nav.soknad.archiving.arkivmock.dto.OpprettJournalpostResponse
import no.nav.soknad.archiving.arkivmock.repository.ArkivRepository
import no.nav.soknad.archiving.arkivmock.service.kafka.KafkaPublisher
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME
import java.time.temporal.ChronoUnit
import java.util.*

@Service
class ArkivMockService(private val arkivRepository: ArkivRepository,
											 private val behaviourService: BehaviourService,
											 private val kafkaPublisher: KafkaPublisher) {

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
		val timesaved = LocalDateTime.now()
		val origtime = LocalDateTime.parse(arkivData.datoMottatt, ISO_LOCAL_DATE_TIME)
		return ArkivDbData(
			arkivData.eksternReferanseId, arkivData.tittel, arkivData.tema, timesaved,
			origtime, origtime.until(timesaved, ChronoUnit.MILLIS))
	}

	private fun saveToDatabaseAndAlertOnKafka(data: ArkivDbData) {
		val dbEntity = arkivRepository.save(data)
		GlobalScope.launch {
			kafkaPublisher.putDataOnTopic(data.id, dbEntity)
			kafkaPublisher.putNumberOfEntitiesOnTopic(data.id, arkivRepository.count().toInt())
		}
	}
}
