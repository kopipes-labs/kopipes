package org.myorg.payments

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import io.kopipes.aws.AwsEnvironmentConfig
import io.kopipes.aws.AwsKopipesContext
import io.kopipes.aws.testsupport.TestContext
import io.kopipes.core.JsonRaw
import io.kopipes.core.sinks.EventPublisherInMemory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

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

    private fun post(body: String, signature: String? = CardSignature.sign(secret, now / 1000, body)) = webhook.handle(
        APIGatewayProxyRequestEvent()
            .withHttpMethod("POST")
            .withPath("/webhooks/card-processor")
            .withHeaders(signature?.let { mapOf(CardSignature.HEADER to it) } ?: emptyMap())
            .withBody(body),
    )

    /** Invokes the function as Lambda does: JSON in, JSON out. */
    private fun invoke(input: String): JsonObject {
        val output = ByteArrayOutputStream()
        webhook.handleRequest(ByteArrayInputStream(input.encodeToByteArray()), output, TestContext())
        return Json.parseToJsonElement(output.toString()).jsonObject
    }

    /** What the resubmit tool sends for faults whose records are these bodies. */
    private fun resubmission(vararg bodies: String) =
        buildJsonObject { put("Records", JsonArray(bodies.map { buildJsonObject { put("value", it) } })) }.toString()

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
    fun `invoked by API Gateway - the request is read from its JSON, and the answer written as it expects`() {
        // Arrange
        val request = buildJsonObject {
            put("httpMethod", "POST")
            put("path", "/webhooks/card-processor")
            put("headers", buildJsonObject { put(CardSignature.HEADER, CardSignature.sign(secret, now / 1000, body())) })
            put("body", body())
            put("isBase64Encoded", false)
        }

        // Act
        val response = invoke(request.toString())

        // Assert
        response["statusCode"]?.jsonPrimitive?.int shouldBe 200
        bus.getUows().single().event!!.id shouldBe "evt_1.FUNDS_DEPOSITED"
    }

    @Test
    fun `resubmitted by the operator - the fault's body goes down the same pipeline, with no signature`() {
        // Act
        val response = invoke(resubmission(body()))

        // Assert
        response["statusCode"]?.jsonPrimitive?.int shouldBe 200
        val event = bus.getUows().single().event
        event.shouldBeInstanceOf<FundsDeposited>()
        event.id shouldBe "evt_1.FUNDS_DEPOSITED"
    }

    @Test
    fun `resubmitted but still unreadable - another fault, for the operator to look at again`() {
        // Act
        val response = invoke(resubmission("""{"not":"a webhook"}"""))

        // Assert
        response["statusCode"]?.jsonPrimitive?.int shouldBe 200
        bus.faults().size shouldBe 1
    }

    @Test
    fun `resubmitted before its deposit is known - the invocation fails, so the tool retries it`() {
        // Act & Assert
        shouldThrow<Exception> { invoke(resubmission(body(reference = "p-8"))) }.cause.shouldBeInstanceOf<PaymentNotKnownYet>()
        bus.getUows().shouldBeEmpty()
    }

    @Test
    fun `other webhook types mean nothing`(): Unit = runBlocking {
        eventsOf(body(type = "credit.pending"), known).shouldBeEmpty()
    }
}
