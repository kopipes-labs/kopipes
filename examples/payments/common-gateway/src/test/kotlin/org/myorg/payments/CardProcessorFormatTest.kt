package org.myorg.payments

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CardProcessorFormatTest {

    private val p7 = Payment("p-7", "u-42", Party(Party.CHECKING, "C-1"), Party(Party.CARD, "K-2"), "100.00", "USD", 1L)
    private val body = """{"event_id":"evt_1"}"""

    @Test
    fun `a signature verifies with the secret, and not with another secret, another body or too late`() {
        // Arrange
        val header = CardSignature.sign("secret", timestampSeconds = 1000, body = body)

        // Act and assert
        CardSignature.verify("secret", header, body, nowSeconds = 1010) shouldBe true
        CardSignature.verify("other", header, body, nowSeconds = 1010) shouldBe false
        CardSignature.verify("secret", header, """{"event_id":"evt_2"}""", nowSeconds = 1010) shouldBe false
        CardSignature.verify("secret", header, body, nowSeconds = 2000) shouldBe false
        CardSignature.verify("secret", null, body, nowSeconds = 1010) shouldBe false
    }

    @Test
    fun `the simulated webhook is in the processor's format, and K-3 is declined`() {
        // Arrange and act
        val credited = Simulation.cardWebhook(p7, createdSeconds = 5)
        val declined = Simulation.cardWebhook(p7.copy(to = Party(Party.CARD, "K-3")), createdSeconds = 5)

        // Assert
        cardJson.encodeToString(CardWebhook.serializer(), credited) shouldBe
            """{"event_id":"evt_p-7","type":"credit.succeeded","created":5,"data":{"credit_id":"cr_p-7","amount_cents":10000,"currency":"usd","metadata":{"reference":"p-7"}}}"""
        declined.type shouldBe CardWebhook.FAILED
        declined.data.failureCode shouldBe "card_declined"
    }
}
