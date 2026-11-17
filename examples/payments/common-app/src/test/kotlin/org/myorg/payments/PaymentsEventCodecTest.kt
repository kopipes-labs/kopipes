package org.myorg.payments

import io.kopipes.core.JsonEvent
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class PaymentsEventCodecTest {

    private val p7 = Payment(
        id = "p-7",
        customerId = "u-42",
        from = Party(Party.CHECKING, "C-1"),
        to = Party(Party.CARD, "K-2"),
        amount = "100.00",
        currency = "USD",
        submittedAt = 1_730_900_520_000L,
    )

    @Test
    fun `every event survives a round trip with its type`() {
        // Arrange
        val events = listOf(
            PaymentSubmitted(p7, id = "p-7.submitted", timestamp = 1L, partitionKey = "p-7"),
            WithdrawalRequested(p7),
            FundsWithdrawn(p7),
            WithdrawalFailed(p7, Reasons.INSUFFICIENT_FUNDS),
            DepositRequested(p7),
            FundsDeposited(p7),
            DepositFailed(p7, Reasons.CARD_DECLINED),
            RefundRequested(p7),
            FundsRefunded(p7),
            RefundFailed(p7, Reasons.ACCOUNT_CLOSED),
            PaymentCompleted(p7),
            PaymentFailed(p7, Reasons.CARD_DECLINED),
            PaymentExpired(p7, Steps.DEPOSIT),
        )

        // Act
        val decoded = events.map { PaymentsEventCodec.decode(PaymentsEventCodec.encode(it)) }

        // Assert
        decoded shouldBe events
        decoded.map { it.eventType() } shouldBe events.map { it.eventType() }
    }

    @Test
    fun `the type is in the json, and unknown fields are ignored`() {
        // Arrange
        val json = """
            {"type":"FUNDS_WITHDRAWN","id":"e-1","newField":42,
             "payment":{"id":"p-7","customerId":"u-42","from":{"kind":"checking","account":"C-1"},
                        "to":{"kind":"card","account":"K-2"},"amount":"100.00","currency":"USD","submittedAt":1}}
        """.trimIndent()

        // Act
        val event = PaymentsEventCodec.decode(json)

        // Assert
        event.shouldBeInstanceOf<FundsWithdrawn>()
        event.eventType() shouldBe PaymentEvent.FUNDS_WITHDRAWN
        PaymentsEventCodec.encode(event).contains("\"type\": \"FUNDS_WITHDRAWN\"") shouldBe true
    }

    @Test
    fun `asPaymentEvents decodes the microstore's generic json events`() {
        // Arrange
        val generic = listOf(JsonEvent(DepositFailed(p7, Reasons.CARD_DECLINED, id = "e-2").toString()))

        // Act
        val events = generic.asPaymentEvents()

        // Assert
        events.single() shouldBe DepositFailed(p7, Reasons.CARD_DECLINED, id = "e-2")
    }
}
