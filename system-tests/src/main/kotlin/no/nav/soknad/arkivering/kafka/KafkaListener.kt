package no.nav.soknad.arkivering.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.readValue
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde
import no.nav.soknad.arkivering.Configuration
import no.nav.soknad.arkivering.avroschemas.InnsendingMetrics
import no.nav.soknad.arkivering.avroschemas.ProcessingEvent
import no.nav.soknad.arkivering.dto.ArkivDbData
import org.apache.avro.specific.SpecificRecord
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.common.config.SaslConfigs
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.KeyValue
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.errors.LogAndContinueExceptionHandler
import org.apache.kafka.streams.kstream.Consumed
import org.apache.kafka.streams.kstream.Transformer
import org.apache.kafka.streams.processor.ProcessorContext
import org.slf4j.LoggerFactory
import java.util.*

class KafkaListener(private val appConfiguration: Configuration) {

	private val logger = LoggerFactory.getLogger(javaClass)
	private val verbose = true

	private val entityConsumers           = mutableListOf<KafkaEntityConsumer<ArkivDbData>>()
	private val metricsConsumers          = mutableListOf<KafkaEntityConsumer<InnsendingMetrics>>()
	private val numberOfCallsConsumers    = mutableListOf<KafkaEntityConsumer<Int>>()
	private val numberOfEntitiesConsumers = mutableListOf<KafkaEntityConsumer<Int>>()
	private val processingEventConsumers  = mutableListOf<KafkaEntityConsumer<ProcessingEvent>>()

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

		val kafkaConfig = kafkaConfig()
		kafkaStreams = KafkaStreams(topology, kafkaConfig)
		kafkaStreams.start()
		Runtime.getRuntime().addShutdownHook(Thread(kafkaStreams::close))
	}


	private fun kafkaStreams(streamsBuilder: StreamsBuilder) {
		val metricsStream              = streamsBuilder.stream(appConfiguration.kafkaConfig.metricsTopic,    Consumed.with(stringSerde, createInnsendingMetricsSerde()))
		val processingEventTopicStream = streamsBuilder.stream(appConfiguration.kafkaConfig.processingTopic, Consumed.with(stringSerde, createProcessingEventSerde()))
		val entitiesStream             = streamsBuilder.stream(kafkaProperties.entitiesTopic,                Consumed.with(stringSerde, stringSerde))
		val numberOfCallsStream        = streamsBuilder.stream(kafkaProperties.numberOfCallsTopic,           Consumed.with(stringSerde, intSerde))
		val numberOfEntitiesStream     = streamsBuilder.stream(kafkaProperties.numberOfEntitiesTopic,        Consumed.with(stringSerde, intSerde))

		entitiesStream
			.mapValues { json -> mapper.readValue<ArkivDbData>(json) }
			.peek { key, entity -> log("Joark Entities    : $key - $entity") }
			.transform({ TimestampExtractor() })
			.foreach { key, entity -> entityConsumers.forEach { it.consume(key, entity) } }

		metricsStream
			.peek { key, entity -> log("Metrics received  : $key - $entity") }
			.transform({ TimestampExtractor() })
			.foreach { key, entity -> metricsConsumers.forEach { it.consume(key, entity) } }

		numberOfCallsStream
			.peek { key, numberOfCalls -> log("Number of Calls   : $key - $numberOfCalls") }
			.transform({ TimestampExtractor() })
			.foreach { key, numberOfCalls -> numberOfCallsConsumers.forEach { it.consume(key, numberOfCalls) } }

		numberOfEntitiesStream
			.peek { key, numberOfEntities -> log("Number of Entities: $key - $numberOfEntities") }
			.transform({ TimestampExtractor() })
			.foreach { key, numberOfEntities -> numberOfEntitiesConsumers.forEach { it.consume(key, numberOfEntities) } }

		processingEventTopicStream
			.peek { key, entity -> log("Processing Events : $key - $entity") }
			.transform({ TimestampExtractor() })
			.foreach { key, entity -> processingEventConsumers.forEach { it.consume(key, entity) } }
	}

	private fun log(message: String) {
		if (verbose)
			logger.info(message)
	}

	private fun kafkaConfig() = Properties().also {
		it[AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG] = appConfiguration.kafkaConfig.schemaRegistryUrl
		it[StreamsConfig.APPLICATION_ID_CONFIG] = "innsending-system-tests"
		it[StreamsConfig.BOOTSTRAP_SERVERS_CONFIG] = appConfiguration.kafkaConfig.servers
		it[StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG] = Serdes.StringSerde::class.java
		it[StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG] = SpecificAvroSerde::class.java
		it[StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG] = LogAndContinueExceptionHandler::class.java
		it[StreamsConfig.COMMIT_INTERVAL_MS_CONFIG] = 1000

		if (appConfiguration.kafkaConfig.secure == "TRUE") {
			it[CommonClientConfigs.SECURITY_PROTOCOL_CONFIG] = appConfiguration.kafkaConfig.protocol
			it[SaslConfigs.SASL_JAAS_CONFIG] = appConfiguration.kafkaConfig.saslJaasConfig
			it[SaslConfigs.SASL_MECHANISM] = appConfiguration.kafkaConfig.salsmec
		}
	}

	private fun createProcessingEventSerde(): SpecificAvroSerde<ProcessingEvent> = createAvroSerde()
	private fun createInnsendingMetricsSerde(): SpecificAvroSerde<InnsendingMetrics> = createAvroSerde()

	private fun <T : SpecificRecord> createAvroSerde(): SpecificAvroSerde<T> {
		val serdeConfig = hashMapOf(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG to appConfiguration.kafkaConfig.schemaRegistryUrl)
		return SpecificAvroSerde<T>().also { it.configure(serdeConfig, false) }
	}

	/**
	 * This class is just boilerplate for extracting a timestamp from a Kafka record.
	 */
	class TimestampExtractor<T> : Transformer<String, T, KeyValue<String, KafkaTimestampedEntity<T>>> {
		private lateinit var context: ProcessorContext
		override fun init(context: ProcessorContext) {
			this.context = context
		}

		override fun transform(key: String, value: T): KeyValue<String, KafkaTimestampedEntity<T>> {
			return KeyValue(key, KafkaTimestampedEntity(value, context.timestamp()))
		}

		override fun close() {
		}
	}


	fun close() {
		kafkaStreams.close()
		kafkaStreams.cleanUp()
	}


	fun clearConsumers() {
		entityConsumers.clear()
		metricsConsumers.clear()
		numberOfCallsConsumers.clear()
		numberOfEntitiesConsumers.clear()
		processingEventConsumers.clear()
	}

	fun addConsumerForEntities        (consumer: KafkaEntityConsumer<ArkivDbData>)       = entityConsumers          .add(consumer)
	fun addConsumerForMetrics         (consumer: KafkaEntityConsumer<InnsendingMetrics>) = metricsConsumers         .add(consumer)
	fun addConsumerForNumberOfCalls   (consumer: KafkaEntityConsumer<Int>)               = numberOfCallsConsumers   .add(consumer)
	fun addConsumerForNumberOfEntities(consumer: KafkaEntityConsumer<Int>)               = numberOfEntitiesConsumers.add(consumer)
	fun addConsumerForProcessingEvents(consumer: KafkaEntityConsumer<ProcessingEvent>)   = processingEventConsumers .add(consumer)
}
