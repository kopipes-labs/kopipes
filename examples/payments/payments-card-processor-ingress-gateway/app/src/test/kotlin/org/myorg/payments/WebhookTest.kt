package org.myorg.payments

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import io.kopipes.aws.AwsEnvironmentConfig
import io.kopipes.aws.AwsKopipesContext
import io.kopipes.aws.testsupport.TestContext
import io.kopipes.core.JsonRaw
import io.kopipes.core.sinks.EventPublisherInMemory
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class WebhookTest {

    private val p7 = Payment("p-7", "u-42", Party(Party.CHECKING, "C-1"), Party(Party.CARD, "K-2"), "100.00", "USD", 1L)
    private val known = PaymentLookup { reference -> if (reference == "p-7") p7 else throw PaymentNotKnownYet(reference) }
    private val now = 1_730_900_600_000L
    private val secret = "test-secret"

    private fun body(type: String = CardWebhook.SUCCEEDED, reference: String = "p-7") = cardJson.encodeToString(
        CardWebhook.serializer(),
        CardWebhook("evt_1", type, created = 1_730_900_590, data = CardCredit("cr_1", 10000, "usd", CardMetadata(reference), failureCode = if (type == CardWebhook.FAILED) "do_not_honor" else null)),
    )

    private class Config : AwsEnvironmentConfig() {
        override fun env(name: String): String? = if (name == "STREAM_RETRY_ENABLED") "true" else null
    }

    private val bus = EventPublisherInMemory()
    private val webhook = Webhook {
        val context = AwsKopipesContext(envConfig = Config(), eventPublisher = bus, isRetryable = { it is PaymentNotKnownYet })
        WebhookContainer(context, secret, known, clock = { now })
    }

    private fun post(body: String, signature: String? = CardSignature.sign(secret, now / 1000, body)) = webhook.handleRequest(
        APIGatewayProxyRequestEvent()
            .withHttpMethod("POST")
            .withPath("/webhooks/card-processor")
            .withHeaders(signature?.let { mapOf(CardSignature.HEADER to it) } ?: emptyMap())
            .withBody(body),
        TestContext(),
    )

    @Test
    fun `a credit that succeeded is FUNDS_DEPOSITED, answered once published`() {
        // Act
        val response = post(body())

        // Assert
        response.statusCode shouldBe 200
        val event = bus.getUows().single().event
        event.shouldBeInstanceOf<FundsDeposited>()
        event.id shouldBe "evt_1.FUNDS_DEPOSITED"
        event.payment shouldBe p7
        event.timestamp shouldBe 1_730_900_590_000L
        (event.raw as JsonRaw).value.toString() shouldBe body()
    }

    @Test
    fun `a failed credit is DEPOSIT_FAILED, card-declined whatever the processor's code`() {
        // Act
        post(body(type = CardWebhook.FAILED))

        // Assert
        bus.getUows().single().event.shouldBeInstanceOf<DepositFailed>().reason shouldBe Reasons.CARD_DECLINED
    }

    @Test
    fun `the same webhook again is the same event`() {
        // Act
        post(body())
        post(body())

        // Assert
        bus.getUows().map { it.event!!.id }.distinct() shouldBe listOf("evt_1.FUNDS_DEPOSITED")
    }

    @Test
    fun `a bad or missing signature is a 401, and nothing is published`() {
        // Act
        val forged = post(body(), signature = CardSignature.sign("other-secret", now / 1000, body()))
        val unsigned = post(body(), signature = null)

        // Assert
        forged.statusCode shouldBe 401
        unsigned.statusCode shouldBe 401
        bus.getUows().shouldBeEmpty()
    }

    @Test
    fun `a webhook for a deposit not heard yet is a 503, so the processor sends it again`() {
        // Act
        val response = post(body(reference = "p-8"))

        // Assert
        response.statusCode shouldBe 503
        bus.getUows().shouldBeEmpty()
    }

    @Test
    fun `signed but unreadable - a fault for the operator, and 200`() {
        // Act
        val response = post("""{"not":"a webhook"}""")

        // Assert
        response.statusCode shouldBe 200
        bus.faults().size shouldBe 1
    }

    @Test
    fun `other webhook types mean nothing`(): Unit = runBlocking {
        eventsOf(body(type = "credit.pending"), known).shouldBeEmpty()
    }
}
