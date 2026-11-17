package org.myorg.payments

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.myorg.payments.LocalPayments.api
import org.myorg.payments.LocalPayments.awaitEvent
import org.myorg.payments.LocalPayments.awaitFault
import org.myorg.payments.LocalPayments.awaitStatus
import org.myorg.payments.LocalPayments.channel
import org.myorg.payments.LocalPayments.deadlinePasses
import org.myorg.payments.LocalPayments.paymentBody
import org.myorg.payments.LocalPayments.publish
import org.myorg.payments.LocalPayments.submit
import org.myorg.payments.PaymentEvent.Companion.DEPOSIT_REQUESTED
import org.myorg.payments.PaymentEvent.Companion.PAYMENT_COMPLETED
import org.myorg.payments.PaymentEvent.Companion.PAYMENT_EXPIRED
import org.myorg.payments.PaymentEvent.Companion.PAYMENT_FAILED
import org.myorg.payments.PaymentEvent.Companion.PAYMENT_SUBMITTED
import org.myorg.payments.PaymentEvent.Companion.REFUND_REQUESTED
import org.myorg.payments.PaymentEvent.Companion.WITHDRAWAL_REQUESTED
import kotlin.time.Duration.Companion.seconds

/**
 * A payment through the deployed BFF and control service. The gateways aren't built yet, so each test plays them:
 * it publishes their exits and aborts to the bus, as they will.
 */
class PaymentsITest {

    private val run = System.currentTimeMillis().toString(36)

    private fun paymentId(name: String) = "it-$run-$name"

    private val coreChecking = "payments-core-checking-ingress-gateway"
    private val cardProcessor = "payments-card-processor-ingress-gateway"

    /** Submits through the BFF and waits for the control service to ask for the withdrawal. */
    private suspend fun started(name: String): Payment {
        val id = paymentId(name)
        submit(id).statusCode shouldBe 202
        return awaitEvent(id, WITHDRAWAL_REQUESTED).payment
    }

    @Test
    fun `happy path - withdrawn, deposited, completed`(): Unit = runBlocking {
        // Arrange
        val payment = started("happy")

        // Act
        publish(FundsWithdrawn(payment, id = "${payment.id}.withdrawn", timestamp = System.currentTimeMillis()), coreChecking)
        awaitEvent(payment.id, DEPOSIT_REQUESTED)
        awaitStatus(payment.id, "SENT_TO_CARD")
        publish(FundsDeposited(payment, id = "${payment.id}.deposited", timestamp = System.currentTimeMillis()), cardProcessor)

        // Assert
        awaitEvent(payment.id, PAYMENT_COMPLETED)
        awaitStatus(payment.id, "COMPLETED")
        channel(payment.id).map { it.eventType() } shouldContainExactly listOf(
            PAYMENT_SUBMITTED, WITHDRAWAL_REQUESTED, PaymentEvent.FUNDS_WITHDRAWN, DEPOSIT_REQUESTED,
            PaymentEvent.FUNDS_DEPOSITED, PAYMENT_COMPLETED,
        )
    }

    @Test
    fun `withdrawal fails - the payment fails with its reason`(): Unit = runBlocking {
        // Arrange
        val payment = started("nsf")

        // Act
        publish(WithdrawalFailed(payment, Reasons.INSUFFICIENT_FUNDS, id = "${payment.id}.nsf", timestamp = System.currentTimeMillis()), coreChecking)

        // Assert
        val failed = awaitEvent(payment.id, PAYMENT_FAILED)
        failed.shouldBeInstanceOf<PaymentFailed>()
        failed.reason shouldBe Reasons.INSUFFICIENT_FUNDS
        awaitStatus(payment.id, "FAILED").reason shouldBe Reasons.INSUFFICIENT_FUNDS
    }

    @Test
    fun `deposit fails - refunded, and the payment fails with the deposit's reason`(): Unit = runBlocking {
        // Arrange
        val payment = started("declined")
        publish(FundsWithdrawn(payment, id = "${payment.id}.withdrawn", timestamp = System.currentTimeMillis()), coreChecking)
        awaitEvent(payment.id, DEPOSIT_REQUESTED)

        // Act
        publish(DepositFailed(payment, Reasons.CARD_DECLINED, id = "${payment.id}.declined", timestamp = System.currentTimeMillis()), cardProcessor)
        awaitEvent(payment.id, REFUND_REQUESTED)
        awaitStatus(payment.id, "REFUNDING")
        publish(FundsRefunded(payment, id = "${payment.id}.refunded", timestamp = System.currentTimeMillis()), coreChecking)

        // Assert
        val failed = awaitEvent(payment.id, PAYMENT_FAILED)
        failed.shouldBeInstanceOf<PaymentFailed>()
        failed.reason shouldBe Reasons.CARD_DECLINED
        awaitStatus(payment.id, "FAILED")
    }

    @Test
    fun `refund fails - a fault for the operator, and the customer sees DELAYED`(): Unit = runBlocking {
        // Arrange
        val payment = started("refund-failed")
        publish(FundsWithdrawn(payment, id = "${payment.id}.withdrawn", timestamp = System.currentTimeMillis()), coreChecking)
        awaitEvent(payment.id, DEPOSIT_REQUESTED)
        publish(DepositFailed(payment, Reasons.CARD_DECLINED, id = "${payment.id}.declined", timestamp = System.currentTimeMillis()), cardProcessor)
        awaitEvent(payment.id, REFUND_REQUESTED)

        // Act
        publish(RefundFailed(payment, Reasons.ACCOUNT_CLOSED, id = "${payment.id}.refund-failed", timestamp = System.currentTimeMillis()), coreChecking)

        // Assert
        awaitStatus(payment.id, "DELAYED")
        awaitFault("Refund failed for payment ${payment.id}")
        channel(payment.id).none { it.eventType() == PAYMENT_FAILED } shouldBe true
    }

    @Test
    fun `a withdrawal both done and failed - a conflict, a fault, and no outcome`(): Unit = runBlocking {
        // Arrange
        val payment = started("conflict")
        publish(FundsWithdrawn(payment, id = "${payment.id}.withdrawn", timestamp = System.currentTimeMillis()), coreChecking)
        awaitEvent(payment.id, DEPOSIT_REQUESTED)

        // Act
        publish(WithdrawalFailed(payment, Reasons.REJECTED, id = "${payment.id}.rejected", timestamp = System.currentTimeMillis()), coreChecking)

        // Assert
        awaitFault("Conflict on payment ${payment.id}")
        channel(payment.id).none { it.eventType() == PAYMENT_FAILED } shouldBe true
    }

    @Test
    fun `a step past its deadline - PAYMENT_EXPIRED, DELAYED, and a late answer still moves it on`(): Unit = runBlocking {
        // Arrange
        val payment = started("late")

        // Act
        deadlinePasses(payment.id, Steps.WITHDRAWAL)

        // Assert
        val expired = awaitEvent(payment.id, PAYMENT_EXPIRED)
        expired.shouldBeInstanceOf<PaymentExpired>()
        expired.step shouldBe Steps.WITHDRAWAL
        awaitStatus(payment.id, "DELAYED")

        publish(FundsWithdrawn(payment, id = "${payment.id}.withdrawn", timestamp = System.currentTimeMillis()), coreChecking)
        awaitEvent(payment.id, DEPOSIT_REQUESTED)
        awaitStatus(payment.id, "SENT_TO_CARD")
    }

    @Test
    fun `a duplicate event changes nothing`(): Unit = runBlocking {
        // Arrange
        val payment = started("duplicate")
        val withdrawn = FundsWithdrawn(payment, id = "${payment.id}.withdrawn", timestamp = System.currentTimeMillis())

        // Act
        publish(withdrawn, coreChecking)
        publish(withdrawn, coreChecking)
        awaitEvent(payment.id, DEPOSIT_REQUESTED)
        delay(20.seconds) // time for a second DEPOSIT_REQUESTED, if there were one

        // Assert
        channel(payment.id).count { it.eventType() == DEPOSIT_REQUESTED } shouldBe 1
    }

    @Test
    fun `the BFF - a retried PUT is one payment, another body a conflict, another customer's payment not found`(): Unit = runBlocking {
        // Arrange
        val id = paymentId("api")

        // Act
        val first = submit(id)
        val retried = submit(id)
        val conflicting = submit(id, body = paymentBody(amount = "5.00"))
        val invalid = submit(paymentId("invalid"), body = paymentBody(amount = "0"))
        val theirs = api("GET", "/payments/$id", customerId = "u-99")
        val anonymous = api("GET", "/payments/$id", customerId = "")

        // Assert
        first.statusCode shouldBe 202
        retried.statusCode shouldBe 202
        conflicting.statusCode shouldBe 409
        invalid.statusCode shouldBe 400
        invalid.body!! shouldContain "\"field\":\"amount\""
        theirs.statusCode shouldBe 404
        anonymous.statusCode shouldBe 401
        awaitEvent(id, WITHDRAWAL_REQUESTED)
        channel(id).count { it.eventType() == PAYMENT_SUBMITTED } shouldBe 1
        api("GET", "/payments").body!! shouldContain "\"id\":\"$id\""
    }
}
