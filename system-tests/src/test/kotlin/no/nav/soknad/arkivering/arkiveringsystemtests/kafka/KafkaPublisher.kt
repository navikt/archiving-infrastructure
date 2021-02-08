package no.nav.soknad.arkivering.arkiveringsystemtests.kafka

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerializer
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.avroschemas.Soknadarkivschema
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.header.Headers
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.serialization.StringSerializer
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.collections.HashMap

class KafkaPublisher(private val kafkaBootstrapServersUrl: String, private val schemaRegistryUrl: String) {

	private val kafkaProperties = KafkaProperties()
	private val kafkaInputProducer = KafkaProducer<String, Soknadarkivschema>(kafkaConfigMap())
	private val kafkaProcessingEventProducer = KafkaProducer<String, ProcessingEvent>(kafkaConfigMap())
	private val kafkaStringProducer = KafkaProducer<String, String>(kafkaConfigMap().also { it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java })

	fun putDataOnTopic(key: String, value: Soknadarkivschema, headers: Headers = RecordHeaders()) {
		val topic = kafkaProperties.inputTopic
		val kafkaProducer = kafkaInputProducer
		putDataOnTopic(key, value, headers, topic, kafkaProducer)
	}

	fun putDataOnTopic(key: String, value: ProcessingEvent, headers: Headers = RecordHeaders()) {
		val topic = kafkaProperties.processingEventLogTopic
		val kafkaProducer = kafkaProcessingEventProducer
		putDataOnTopic(key, value, headers, topic, kafkaProducer)
	}

	fun putDataOnTopic(key: String, value: String, headers: Headers = RecordHeaders()) {
		val topic = kafkaProperties.inputTopic
		val kafkaProducer = kafkaStringProducer
		putDataOnTopic(key, value, headers, topic, kafkaProducer)
	}

	private fun <T> putDataOnTopic(key: String?, value: T, headers: Headers, topic: String,
																 kafkaProducer: KafkaProducer<String, T>): RecordMetadata {

		val producerRecord = ProducerRecord(topic, key, value)
		headers.add("MESSAGE_ID", UUID.randomUUID().toString().toByteArray())
		headers.forEach { h -> producerRecord.headers().add(h) }

		return kafkaProducer
			.send(producerRecord)
			.get(1000, TimeUnit.MILLISECONDS) // Blocking call
	}

	private fun kafkaConfigMap() = HashMap<String, Any>().also {
		it[AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG] = schemaRegistryUrl
		it[ProducerConfig.BOOTSTRAP_SERVERS_CONFIG] = kafkaBootstrapServersUrl
		it[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
		it[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = SpecificAvroSerializer::class.java
	}
}
