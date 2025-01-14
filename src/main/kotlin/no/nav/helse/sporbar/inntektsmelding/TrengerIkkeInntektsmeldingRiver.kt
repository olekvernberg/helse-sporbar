package no.nav.helse.sporbar.inntektsmelding

import com.fasterxml.jackson.databind.JsonNode
import java.util.UUID
import net.logstash.logback.argument.StructuredArguments.keyValue
import no.nav.helse.rapids_rivers.JsonMessage
import no.nav.helse.rapids_rivers.MessageContext
import no.nav.helse.rapids_rivers.RapidsConnection
import no.nav.helse.rapids_rivers.River
import no.nav.helse.rapids_rivers.asLocalDate
import no.nav.helse.rapids_rivers.asLocalDateTime
import org.slf4j.Logger
import org.slf4j.LoggerFactory

internal class TrengerIkkeInntektsmeldingRiver(
    rapidsConnection: RapidsConnection,
    private val inntektsmeldingStatusMediator: InntektsmeldingStatusMediator
) : River.PacketListener {

    private val log: Logger = LoggerFactory.getLogger("inntektsmeldingstatus")

    init {
        River(rapidsConnection).apply {
            validate {
                it.requireValue("@event_name", "trenger_ikke_inntektsmelding")
                it.requireKey("organisasjonsnummer", "fødselsnummer", "aktørId", "vedtaksperiodeId")
                it.interestedIn("søknadIder")
                it.require("@id") { id -> UUID.fromString(id.asText()) }
                it.require("fom", JsonNode::asLocalDate)
                it.require("tom", JsonNode::asLocalDate)
                it.require("@opprettet", JsonNode::asLocalDateTime)
            }
        }.register(this)
    }

    override fun onPacket(packet: JsonMessage, context: MessageContext) {
        log.info("Trenger ikke inntektsmelding for vedtaksperiode {}", keyValue("vedtaksperiodeId", packet["vedtaksperiodeId"].asText()))
        inntektsmeldingStatusMediator.lagre(packet.somHarInntektsmelding())
    }

    internal companion object {
        internal fun JsonMessage.somHarInntektsmelding(): HarInntektsmelding {
            val id = UUID.randomUUID()
            val hendelseId = UUID.fromString(this["@id"].asText())
            val fødselsnummer = this["fødselsnummer"].asText()
            val aktørId = this["aktørId"].asText()
            val vedtaksperiodeId = UUID.fromString(this["vedtaksperiodeId"].asText())
            val organisasjonsnummer = this["organisasjonsnummer"].asText()
            val fom = this["fom"].asLocalDate()
            val tom = this["tom"].asLocalDate()
            val opprettet = this["@opprettet"].asLocalDateTime()

            return HarInntektsmelding(
                id = id,
                hendelseId = hendelseId,
                fødselsnummer = fødselsnummer,
                aktørId = aktørId,
                organisasjonsnummer = organisasjonsnummer,
                vedtaksperiodeId = vedtaksperiodeId,
                fom = fom,
                tom = tom,
                opprettet = opprettet,
                json = this
            )
        }
    }
}

