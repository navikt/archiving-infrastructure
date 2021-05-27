package no.nav.soknad.archiving.arkivmock.service.kafka

import no.nav.soknad.archiving.arkivmock.dto.ArkivDbData
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.header.Headers
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.serialization.IntegerSerializer
import org.apache.kafka.common.serialization.Serializer
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.*
import java.util.concurrent.TimeUnit

@Service
class KafkaPublisher {
	private val logger = LoggerFactory.getLogger(javaClass)

	private val kafkaBootstrapServers = getEnvironmentVariable("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")

	private val kafkaProperties = KafkaProperties()
	private val kafkaStringProducer = KafkaProducer<String, String>(kafkaConfigMap(StringSerializer()))
	private val kafkaIntProducer = KafkaProducer<String, Int>(kafkaConfigMap(IntegerSerializer()))


	fun putDataOnTopic(key: String, value: ArkivDbData, headers: Headers = RecordHeaders()) {
		val topic = kafkaProperties.entitiesTopic
		val kafkaProducer = kafkaStringProducer
		putDataOnTopic(key, value.toString(), headers, topic, kafkaProducer)
	}

	fun putNumberOfCallsOnTopic(key: String, value: Int, headers: Headers = RecordHeaders()) {
		val topic = kafkaProperties.numberOfCallsTopic
		val kafkaProducer = kafkaIntProducer
		putDataOnTopic(key, value, headers, topic, kafkaProducer)
	}

	fun putNumberOfEntitiesOnTopic(key: String, value: Int, headers: Headers = RecordHeaders()) {
		val topic = kafkaProperties.numberOfEntitiesTopic
		val kafkaProducer = kafkaIntProducer
		putDataOnTopic(key, value, headers, topic, kafkaProducer)
	}

	private fun <T> putDataOnTopic(
		key: String?, value: T, headers: Headers, topic: String,
		kafkaProducer: KafkaProducer<String, T>
	): RecordMetadata {

		val producerRecord = ProducerRecord(topic, key, value)
		headers.add("MESSAGE_ID", UUID.randomUUID().toString().toByteArray())
		headers.forEach { h -> producerRecord.headers().add(h) }

		logger.info("Publishing to topic '$topic' with key $key: '$value'")

		return kafkaProducer
			.send(producerRecord)
			.get(1000, TimeUnit.MILLISECONDS) // Blocking call
	}

	private fun <T> kafkaConfigMap(valueSerializer: Serializer<T>) = HashMap<String, Any>().also {
		it[ProducerConfig.BOOTSTRAP_SERVERS_CONFIG] = kafkaBootstrapServers
		it[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
		it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = valueSerializer::class.java
	}


	private fun getEnvironmentVariable(name: String, default: String): String {
		return try {
			System.getenv(name)
		} catch (e: Exception) {
			default
		}
	}
}
