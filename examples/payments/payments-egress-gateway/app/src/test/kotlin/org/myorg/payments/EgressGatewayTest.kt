package org.myorg.payments

import com.amazonaws.services.lambda.runtime.events.KinesisEvent
import io.kopipes.aws.AwsKopipesContext
import io.kopipes.aws.testsupport.TestContext
import io.kopipes.core.JsonRaw
import io.kopipes.core.sinks.EventPublisherInMemory
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.ByteBuffer

class EgressGatewayTest {

    private val p7 = Payment("p-7", "u-42", Party(Party.CHECKING, "C-1"), Party(Party.CARD, "K-2"), "100.00", "USD", 1_730_900_520_000L)

    @Test
    fun `only insufficient-funds is told apart, and any other reason, even a new one, is rejected`() {
        publicReason(Reasons.INSUFFICIENT_FUNDS) shouldBe "insufficient-funds"
        listOf(Reasons.ACCOUNT_CLOSED, Reasons.ACCOUNT_NOT_FOUND, Reasons.CARD_DECLINED, Reasons.REJECTED, "fraud-suspected")
            .map(::publicReason).distinct() shouldBe listOf("rejected")
    }

    @Test
    fun `an outcome crosses with the public payment only, and an id derived from the internal one`() {
        // Arrange
        val failed = PaymentFailed(p7, Reasons.CARD_DECLINED, id = "p-7.undone", timestamp = 9L, raw = JsonRaw(JsonObject(emptyMap())))

        // Act
        val external = externalEventOf(failed)

        // Assert
        external.shouldBeInstanceOf<ExternalPaymentFailed>()
        external.id shouldBe "p-7.undone.external"
        external.timestamp shouldBe 9L
        external.partitionKey shouldBe "p-7"
        external.reason shouldBe "rejected"
        external.payment shouldBe PublicPayment("p-7", "u-42", "100.00", "USD", 1_730_900_520_000L)
        external.raw.shouldBeNull()
    }

    @Test
    fun `step events and expiries stay inside`() {
        externalEventOf(FundsWithdrawn(p7, id = "e")).shouldBeNull()
        externalEventOf(PaymentExpired(p7, Steps.DEPOSIT, id = "e")).shouldBeNull()
    }

    @Test
    fun `what is published has the fields of the published JSON Schema, and nothing else`() {
        // Arrange
        val schema = Json.parseToJsonElement(File("../contract/payments-external-events.schema.json").readText()).jsonObject
        val defs = schema.getValue("\$defs").jsonObject
        fun props(def: String) = defs.getValue(def).jsonObject.getValue("properties").jsonObject.keys
        val allowedEnvelope = props("envelope") + "reason"
        val required = defs.getValue("payment").jsonObject.getValue("required").jsonArray.map { it.jsonPrimitive.content }

        // Act
        val published = Json.parseToJsonElement(
            externalEventOf(PaymentFailed(p7, Reasons.INSUFFICIENT_FUNDS, id = "p-7.nothing-to-undo", timestamp = 9L)
            )!!.copyEvent(tags = mapOf("source" to "payments-egress-gateway")).toString()
        ).jsonObject

        // Assert
        allowedEnvelope shouldContainAll published.keys
        published.getValue("payment").jsonObject.keys shouldBe props("payment")
        published.getValue("payment").jsonObject.keys shouldContainAll required
        published.getValue("type").jsonPrimitive.content shouldBe "PAYMENTS.PAYMENT_FAILED"
    }

    @Test
    fun `the listener publishes the external events of the outcomes it hears`() {
        // Arrange
        val bus = EventPublisherInMemory()
        val listener = Listener { EgressContainer(AwsKopipesContext(eventPublisher = bus)) }
        val events = listOf(PaymentCompleted(p7, id = "p-7.done", timestamp = 9L), DepositRequested(p7, id = "p-7.next-step"))
        val input = KinesisEvent().apply {
            records = events.mapIndexed { i, event ->
                KinesisEvent.KinesisEventRecord().apply {
                    eventID = "shard:$i"
                    kinesis = KinesisEvent.Record().apply {
                        data = ByteBuffer.wrap(event.toString().toByteArray())
                        partitionKey = "p-7"
                        sequenceNumber = "$i"
                    }
                }
            }
        }

        // Act
        listener.handleRequest(input, TestContext())

        // Assert
        val published = bus.getUows().single().event
        published.shouldBeInstanceOf<ExternalPaymentCompleted>()
        published.eventType() shouldBe "PAYMENTS.PAYMENT_COMPLETED"
        published.id shouldBe "p-7.done.external"
    }
}
