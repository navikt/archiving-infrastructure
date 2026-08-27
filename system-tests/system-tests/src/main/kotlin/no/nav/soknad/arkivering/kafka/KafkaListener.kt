package no.nav.soknad.arkivering.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.readValue
import no.nav.soknad.arkivering.KafkaConfig
import no.nav.soknad.arkivering.dto.ArchiveEntity
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.common.config.SslConfigs
import org.apache.kafka.common.serialization.Serdes
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.errors.LogAndContinueExceptionHandler
import org.apache.kafka.streams.kstream.Consumed
import org.apache.kafka.streams.processor.api.*
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

class KafkaListener(private val kafkaConfig: KafkaConfig) {

	private val logger = LoggerFactory.getLogger(javaClass)
	private val verbose = true

	private val entityConsumers          = CopyOnWriteArrayList<KafkaEntityConsumer<ArchiveEntity>>()
	private val metricsConsumers         = CopyOnWriteArrayList<KafkaEntityConsumer<InnsendingMetricsJson>>()
	private val numberOfCallsConsumers   = CopyOnWriteArrayList<KafkaEntityConsumer<Int>>()
	private val processingEventConsumers = CopyOnWriteArrayList<KafkaEntityConsumer<ProcessingEventJson>>()
	private val arkiveringstilbakemeldingerConsumers = CopyOnWriteArrayList<KafkaEntityConsumer<String>>()

	private val kafkaStreams: KafkaStreams

	private val intSerde = Serdes.IntegerSerde()
	private val stringSerde = Serdes.StringSerde()
	private val mapper: ObjectMapper = ObjectMapper().also {
		it.enable(SerializationFeature.INDENT_OUTPUT)
		it.findAndRegisterModules()
	}

	init {
		logger.info("***Starting KafkaListener: kafkaBrokers=${kafkaConfig.brokers}***")
		val streamsBuilder = StreamsBuilder()
		kafkaStreams(streamsBuilder)
		val topology = streamsBuilder.build()

		val kafkaConfig = kafkaConfig()
		kafkaStreams = KafkaStreams(topology, kafkaConfig)
		kafkaStreams.start()
		Runtime.getRuntime().addShutdownHook(Thread(kafkaStreams::close))
	}


	private fun kafkaStreams(streamsBuilder: StreamsBuilder) {
		// Default system-test path reads the JSON v3 processing-event/metrics topics (issue #78):
		// plain JSON via Jackson, no Schema Registry. The legacy Avro v1/"v2" topics
		// (kafkaConfig.topics.processingTopic / metricsTopic) are retained for legacy replay but are
		// not consumed here.
		val metricsStream              = streamsBuilder.stream(kafkaConfig.topics.metricsTopicV3,    Consumed.with(stringSerde, stringSerde))
		val processingEventTopicStream = streamsBuilder.stream(kafkaConfig.topics.processingTopicV3,  Consumed.with(stringSerde, stringSerde))
		val arkiveringstilbakemeldingerStream = streamsBuilder.stream(kafkaConfig.topics.arkiveringstilbakemeldingerTopic,Consumed.with(stringSerde, stringSerde))
		val entitiesStream             = streamsBuilder.stream(kafkaConfig.topics.entitiesTopic,      Consumed.with(stringSerde, stringSerde))
		val numberOfCallsStream        = streamsBuilder.stream(kafkaConfig.topics.numberOfCallsTopic, Consumed.with(stringSerde, intSerde))

		entitiesStream
			.mapValues { json -> mapper.readValue<ArchiveEntity>(json) }
			.peek { key, entity -> log("$key: Archive Entities   - $entity") }
			.processValues({ TimestampExtractor() })
			.foreach { key, entity -> entityConsumers.forEach { it.consume(key, entity) } }

		metricsStream
			.mapValues { json -> mapper.readValue<InnsendingMetricsJson>(json) }
			.peek { key, entity -> log("$key: Metrics received   - $entity") }
			.processValues({ TimestampExtractor() })
			.foreach { key, entity -> metricsConsumers.forEach { it.consume(key, entity) } }

		numberOfCallsStream
			.peek { key, numberOfCalls -> log("$key: Number of Calls    - $numberOfCalls") }
			.processValues({ TimestampExtractor() })
			.foreach { key, numberOfCalls -> numberOfCallsConsumers.forEach { it.consume(key, numberOfCalls) } }

		processingEventTopicStream
			.mapValues { json -> mapper.readValue<ProcessingEventJson>(json) }
			.peek { key, entity -> log("$key: Processing Events  - $entity") }
			.processValues({ TimestampExtractor() })
			.foreach { key, entity -> processingEventConsumers.forEach { it.consume(key, entity) } }

		arkiveringstilbakemeldingerStream
			.peek { key, arkiveringstilbakemelding -> log("$key: Arkiveringstilbakemelding received  - $arkiveringstilbakemelding") }
			.processValues({ TimestampExtractor() })
			.foreach { key, entity -> arkiveringstilbakemeldingerConsumers.forEach { it.consume(key, entity)} }
	}

	private fun log(message: String) {
		if (verbose)
			logger.info(message)
	}

	private fun kafkaConfig() = Properties().also {
		it[StreamsConfig.APPLICATION_ID_CONFIG] = kafkaConfig.applicationId
		it[StreamsConfig.BOOTSTRAP_SERVERS_CONFIG] = kafkaConfig.brokers
		it[StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG] = Serdes.StringSerde::class.java
		it[StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG] = Serdes.StringSerde::class.java
		it[StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG] = LogAndContinueExceptionHandler::class.java
		it[StreamsConfig.COMMIT_INTERVAL_MS_CONFIG] = 1000

		if (kafkaConfig.security.enabled) {
			it[CommonClientConfigs.SECURITY_PROTOCOL_CONFIG] = "SSL"
			it[SslConfigs.SSL_KEYSTORE_TYPE_CONFIG] = "PKCS12"
			it[SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG] = kafkaConfig.security.trustStorePath
			it[SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG] = kafkaConfig.security.trustStorePassword
			it[SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG] = kafkaConfig.security.keyStorePath
			it[SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG] = kafkaConfig.security.keyStorePassword
			it[SslConfigs.SSL_KEY_PASSWORD_CONFIG] = kafkaConfig.security.keyStorePassword
		}
	}

	/**
	 * This class is just boilerplate for extracting a timestamp from a Kafka record.
	 */
	class TimestampExtractor<T> : FixedKeyProcessor<String, T, KafkaTimestampedEntity<T>> {
		private lateinit var context: FixedKeyProcessorContext<String, KafkaTimestampedEntity<T>>
		override fun init(context: FixedKeyProcessorContext<String, KafkaTimestampedEntity<T>>) {
			this.context = context
		}

		override fun process(record: FixedKeyRecord<String, T>) {
			context.forward(record.withValue(KafkaTimestampedEntity(record.value(), record.timestamp())))
		}

		override fun close() {
		}
	}


	fun close() {
		kafkaStreams.close()
		kafkaStreams.cleanUp()
	}


	/**
	 * A handle for a registered consumer, which allows the caller to remove that particular consumer -
	 * and only that consumer - once it is no longer needed. This keeps tests that run in parallel from
	 * removing each other's consumers.
	 */
	fun interface ConsumerRegistration {
		fun deregister()
	}

	private fun <T> register(
		consumers: MutableList<KafkaEntityConsumer<T>>,
		consumer: KafkaEntityConsumer<T>
	): ConsumerRegistration {
		consumers.add(consumer)
		return ConsumerRegistration { consumers.remove(consumer) }
	}

	@Suppress("unused")
	fun addConsumerForMetrics         (consumer: KafkaEntityConsumer<InnsendingMetricsJson>) = register(metricsConsumers, consumer)
	fun addConsumerForEntities        (consumer: KafkaEntityConsumer<ArchiveEntity>)         = register(entityConsumers, consumer)
	fun addConsumerForNumberOfCalls   (consumer: KafkaEntityConsumer<Int>)                   = register(numberOfCallsConsumers, consumer)
	fun addConsumerForProcessingEvents(consumer: KafkaEntityConsumer<ProcessingEventJson>)   = register(processingEventConsumers, consumer)
	@Suppress("unused")
	fun addConsumerForArkiveringstilbakemeldinger(consumer: KafkaEntityConsumer<String>)     = register(arkiveringstilbakemeldingerConsumers, consumer)
}
