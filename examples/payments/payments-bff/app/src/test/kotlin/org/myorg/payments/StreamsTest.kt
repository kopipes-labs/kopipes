package org.myorg.payments

import io.kopipes.aws.from.RecordImage
import io.kopipes.aws.from.RecordPair
import io.kopipes.aws.from.DynamoDbChangeEvent
import io.kopipes.core.UnitOfWork
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue as EventAV

/** The BFF's two stream functions: what it records from the channel, and what it publishes from its table. */
class StreamsTest {

    private val p7 = Payment("p-7", "u-42", Party(Party.CHECKING, "C-1"), Party(Party.CARD, "K-2"), "100.00", "USD", 1000L)

    private fun s(v: String) = EventAV().withS(v)

    private val accepted = RecordImage(
        mapOf(
            "pk" to s("p-7"), "sk" to s("PAYMENT"), "customerId" to s("u-42"),
            "fromKind" to s("checking"), "fromAccount" to s("C-1"), "toKind" to s("card"), "toAccount" to s("K-2"),
            "amount" to s("100.00"), "currency" to s("USD"), "submittedAt" to EventAV().withN("1000"), "requestHash" to s("h"),
        )
    )

    private fun change(new: RecordImage?, old: RecordImage?) = UnitOfWork(event = DynamoDbChangeEvent(raw = RecordPair(new, old)))

    @Test
    fun `the first time a payment is accepted, PAYMENT_SUBMITTED is published with one id per payment`() {
        // Arrange and act
        val event = toPaymentSubmitted(change(new = accepted, old = null))

        // Assert
        event.shouldBeInstanceOf<PaymentSubmitted>()
        event.payment shouldBe p7
        event.id shouldBe "p-7.submitted"
        event.timestamp shouldBe 1000L
    }

    @Test
    fun `later changes of the item, and items not yet accepted, publish nothing`() {
        // Arrange
        val heardOnly = RecordImage(mapOf("pk" to s("p-7"), "sk" to s("PAYMENT"), "at_FUNDS_WITHDRAWN" to EventAV().withN("5")))

        // Act and assert
        toPaymentSubmitted(change(new = accepted, old = accepted)).shouldBeNull()
        toPaymentSubmitted(change(new = heardOnly, old = null)).shouldBeNull()
    }

    @Test
    fun `heard events are recorded once, failures with their reason, expiries by step`() {
        // Arrange
        val failed = DepositFailed(p7, Reasons.CARD_DECLINED, timestamp = 7L)
        val expired = PaymentExpired(p7, Steps.DEPOSIT, timestamp = 8L)

        // Act
        val failure = heardUpdate("views", UnitOfWork(event = failed))!!
        val expiry = heardUpdate("views", UnitOfWork(event = expired))!!

        // Assert
        failure.updateExpression shouldBe "SET #at = if_not_exists(#at, :at), #reason = if_not_exists(#reason, :reason)"
        failure.expressionAttributeNames shouldBe mapOf("#at" to "at_DEPOSIT_FAILED", "#reason" to "reason_DEPOSIT_FAILED")
        expiry.expressionAttributeNames shouldBe mapOf("#at" to "at_PAYMENT_EXPIRED_deposit")
    }

    @Test
    fun `the BFF ignores events that don't move the view`() {
        heardUpdate("views", UnitOfWork(event = WithdrawalRequested(p7, timestamp = 1L))).shouldBeNull()
    }
}
