package no.nav.soknad.arkivering.arkiveringendtoendtests.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.readValue
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde
import no.nav.soknad.arkivering.arkiveringendtoendtests.dto.ArkivDbData
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.kstream.Consumed
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.*

class KafkaListener(private val kafkaPort: Int,
										private val schemaRegistryPort: Int) {

	private val logger = LoggerFactory.getLogger(javaClass)

	private val entityConsumers = mutableListOf<KafkaEntityConsumer<ArkivDbData>>()
	private val numberOfCallsConsumers = mutableListOf<KafkaEntityConsumer<Int>>()
	private val numberOfEntitiesConsumers = mutableListOf<KafkaEntityConsumer<Int>>()

	private val kafkaStreams: KafkaStreams
	private val kafkaProperties = KafkaProperties()

	private val intSerde = Serdes.IntegerSerde()
	private val stringSerde = Serdes.StringSerde()
	private val mapper: ObjectMapper = ObjectMapper().also {
		it.enable(SerializationFeature.INDENT_OUTPUT)
		it.findAndRegisterModules()
	}

	init {
		val streamsBuilder = StreamsBuilder()
		kafkaStreams(streamsBuilder)
		val topology = streamsBuilder.build()

		kafkaStreams = KafkaStreams(topology, kafkaConfig())
		kafkaStreams.start()
		Runtime.getRuntime().addShutdownHook(Thread(kafkaStreams::close))
	}


	private fun kafkaStreams(streamsBuilder: StreamsBuilder) {

		val numberOfCallsStream = streamsBuilder.stream(kafkaProperties.numberOfCallsTopic, Consumed.with(stringSerde, intSerde))
		val numberOfEntitiesStream = streamsBuilder.stream(kafkaProperties.numberOfEntitiesTopic, Consumed.with(stringSerde, intSerde))
		val entitiesStream = streamsBuilder.stream(kafkaProperties.entitiesTopic, Consumed.with(stringSerde, stringSerde))

		entitiesStream
			.mapValues { json -> mapper.readValue<ArkivDbData>(json) }
			.peek { key, entity -> logger.info("Entities: $key  -  $entity") }
			.foreach { key, entity -> entityConsumers.forEach { it.consume(key, entity) } }

		numberOfCallsStream
			.peek { key, numberOfCalls -> logger.info("Number of Calls: $key  -  $numberOfCalls") }
			.foreach { key, numberOfCalls -> numberOfCallsConsumers.forEach { it.consume(key, numberOfCalls) } }

		numberOfEntitiesStream
			.peek { key, numberOfEntities -> logger.info("Number of Entities: $key  -  $numberOfEntities") }
			.foreach { key, numberOfEntities -> numberOfEntitiesConsumers.forEach { it.consume(key, numberOfEntities) } }
	}

	private fun kafkaConfig() = Properties().also {
		it[AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG] = "http://localhost:$schemaRegistryPort"
		it[StreamsConfig.BOOTSTRAP_SERVERS_CONFIG] = "localhost:$kafkaPort"
		it[StreamsConfig.APPLICATION_ID_CONFIG] = "end-to-end-tests"
		it[StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG] = Serdes.StringSerde::class.java
		it[StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG] = SpecificAvroSerde::class.java
		it[StreamsConfig.COMMIT_INTERVAL_MS_CONFIG] = 1000
	}

	fun close() {
		kafkaStreams.close(Duration.ofSeconds(10))
		kafkaStreams.cleanUp()
	}


	fun clearConsumers() {
		entityConsumers.clear()
		numberOfCallsConsumers.clear()
		numberOfEntitiesConsumers.clear()
	}

	fun addConsumerForEntities(consumer: KafkaEntityConsumer<ArkivDbData>) = entityConsumers.add(consumer)
	fun addConsumerForNumberOfCalls(consumer: KafkaEntityConsumer<Int>) = numberOfCallsConsumers.add(consumer)
	fun addConsumerForNumberOfEntities(consumer: KafkaEntityConsumer<Int>) = numberOfEntitiesConsumers.add(consumer)
}
