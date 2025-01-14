package no.nav.helse.sporbar

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.Properties
import no.nav.helse.rapids_rivers.RapidApplication
import no.nav.helse.rapids_rivers.RapidsConnection
import no.nav.helse.sporbar.inntektsmelding.InntektsmeldingStatusMediator
import no.nav.helse.sporbar.inntektsmelding.InntektsmeldingStatusPubliserer
import no.nav.helse.sporbar.inntektsmelding.InntektsmeldingStatusVedtaksperiodeEndretRiver
import no.nav.helse.sporbar.inntektsmelding.InntektsmeldingStatusVedtaksperiodeForkastetRiver
import no.nav.helse.sporbar.inntektsmelding.Kafka
import no.nav.helse.sporbar.inntektsmelding.PostgresInntektsmeldingStatusDao
import no.nav.helse.sporbar.inntektsmelding.TrengerIkkeInntektsmeldingRiver
import no.nav.helse.sporbar.inntektsmelding.TrengerInntektsmeldingRiver
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.config.SslConfigs
import org.apache.kafka.common.security.auth.SecurityProtocol
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory

val objectMapper: ObjectMapper = jacksonObjectMapper()
    .registerModule(JavaTimeModule())
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

fun main() {
    val log = LoggerFactory.getLogger("sporbar")
    val env = Environment(System.getenv())
    try {
        launchApplication(env)
    } catch (e: Exception) {
        log.error("Feil under kjøring", e)
        throw e
    }
}

fun launchApplication(env: Environment) {
    val dataSourceBuilder = DataSourceBuilder(env.db)

    RapidApplication.create(env.raw).apply {
        register(object : RapidsConnection.StatusListener {
            override fun onStartup(rapidsConnection: RapidsConnection) {
                dataSourceBuilder.migrate()
            }
        })

        val dataSource by lazy { dataSourceBuilder.getDataSource() }
        val dokumentDao = DokumentDao { dataSource }
        val aivenProducer = createAivenProducer(env.raw)

        val inntektsmeldingStatusDao = PostgresInntektsmeldingStatusDao { dataSource }
        val inntektsmeldingStatusMediator = InntektsmeldingStatusMediator(
            inntektsmeldingStatusDao = inntektsmeldingStatusDao,
            producer = Kafka(aivenProducer)
        )
        val vedtakFattetMediator = VedtakFattetMediator(
            dokumentDao = dokumentDao,
            producer = aivenProducer
        )
        val utbetalingMediator = UtbetalingMediator(
            producer = aivenProducer
        )

        NyttDokumentRiver(this, dokumentDao)
        VedtakFattetRiver(this, vedtakFattetMediator)
        UtbetalingUtbetaltRiver(this, utbetalingMediator)
        UtbetalingUtenUtbetalingRiver(this, utbetalingMediator)
        AnnulleringRiver(this, aivenProducer)
        TrengerInntektsmeldingRiver(this, inntektsmeldingStatusMediator)
        TrengerIkkeInntektsmeldingRiver(this, inntektsmeldingStatusMediator)
        InntektsmeldingStatusVedtaksperiodeForkastetRiver(this, inntektsmeldingStatusMediator)
        InntektsmeldingStatusVedtaksperiodeEndretRiver(this, inntektsmeldingStatusMediator)
        InntektsmeldingStatusPubliserer(this, inntektsmeldingStatusMediator)
    }.start()
}

private fun createAivenProducer(env: Map<String, String>): KafkaProducer<String, JsonNode> {
    val properties = Properties().apply {
        put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, env.getValue("KAFKA_BROKERS"))
        put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, SecurityProtocol.SSL.name)
        put(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG, "")
        put(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG, "jks")
        put(SslConfigs.SSL_KEYSTORE_TYPE_CONFIG, "PKCS12")
        put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, env.getValue("KAFKA_TRUSTSTORE_PATH"))
        put(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, env.getValue("KAFKA_CREDSTORE_PASSWORD"))
        put(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, env.getValue("KAFKA_KEYSTORE_PATH"))
        put(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG, env.getValue("KAFKA_CREDSTORE_PASSWORD"))

        put(ProducerConfig.ACKS_CONFIG, "1")
        put(ProducerConfig.LINGER_MS_CONFIG, "0")
        put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "1")
    }
    return KafkaProducer(properties, StringSerializer(), JsonNodeSerializer())
}
