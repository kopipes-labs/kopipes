package org.myorg.payments

import io.kopipes.core.JsonRaw
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
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
import org.myorg.payments.LocalPayments.ledgerRows
import org.myorg.payments.LocalPayments.paymentBody
import org.myorg.payments.LocalPayments.publish
import org.myorg.payments.LocalPayments.submit
import org.myorg.payments.PaymentEvent.Companion.DEPOSIT_REQUESTED
import org.myorg.payments.PaymentEvent.Companion.FUNDS_DEPOSITED
import org.myorg.payments.PaymentEvent.Companion.FUNDS_REFUNDED
import org.myorg.payments.PaymentEvent.Companion.FUNDS_WITHDRAWN
import org.myorg.payments.PaymentEvent.Companion.PAYMENT_COMPLETED
import org.myorg.payments.PaymentEvent.Companion.PAYMENT_EXPIRED
import org.myorg.payments.PaymentEvent.Companion.PAYMENT_FAILED
import org.myorg.payments.PaymentEvent.Companion.PAYMENT_SUBMITTED
import org.myorg.payments.PaymentEvent.Companion.REFUND_FAILED
import org.myorg.payments.PaymentEvent.Companion.REFUND_REQUESTED
import org.myorg.payments.PaymentEvent.Companion.WITHDRAWAL_FAILED
import org.myorg.payments.PaymentEvent.Companion.WITHDRAWAL_REQUESTED
import kotlin.time.Duration.Companion.seconds

/**
 * A payment through the deployed services, with core checking simulated by its gateways (accounts `C-2`, `C-9`, `C-5`
 * answer differently; see PaymentsServiceSpecs.md › Simulation mode). The card processor's gateways aren't built yet,
 * so the tests play them: they publish the deposit's answer to the bus.
 */
class PaymentsITest {

    private val run = System.currentTimeMillis().toString(36)

    private fun paymentId(name: String) = "it-$run-$name"

    private val cardProcessor = "payments-card-processor-ingress-gateway"

    /** Submits through the BFF and waits for core checking's answer to the withdrawal. */
    private suspend fun submitted(name: String, from: String = "C-1"): String {
        val id = paymentId(name)
        submit(id, body = paymentBody(from = from)).statusCode shouldBe 202
        return id
    }

    /** ... and for the control service to ask for the deposit. */
    private suspend fun sentToCard(name: String, from: String = "C-1"): Payment {
        val id = submitted(name, from)
        return awaitEvent(id, DEPOSIT_REQUESTED).payment
    }

    private suspend fun cardAnswers(event: PaymentEvent) = publish(event, cardProcessor)

    @Test
    fun `happy path - withdrawn by core checking, deposited, completed`(): Unit = runBlocking {
        // Arrange
        val payment = sentToCard("happy")
        awaitStatus(payment.id, "SENT_TO_CARD")

        // Act
        cardAnswers(FundsDeposited(payment, id = "${payment.id}.deposited", timestamp = System.currentTimeMillis()))

        // Assert
        awaitEvent(payment.id, PAYMENT_COMPLETED)
        awaitStatus(payment.id, "COMPLETED")
        channel(payment.id).map { it.eventType() } shouldContainExactly listOf(
            PAYMENT_SUBMITTED, WITHDRAWAL_REQUESTED, FUNDS_WITHDRAWN, DEPOSIT_REQUESTED, FUNDS_DEPOSITED, PAYMENT_COMPLETED,
        )
    }

    @Test
    fun `core checking's answer - the ledger row as our event, with the whole payment and the row as raw`(): Unit = runBlocking {
        // Arrange
        val id = submitted("ledger")

        // Act
        val withdrawn = awaitEvent(id, FUNDS_WITHDRAWN)

        // Assert
        val row = ledgerRows(id).single()
        withdrawn.id shouldBe "${row.getValue("ENTRY_ID")}.FUNDS_WITHDRAWN"
        withdrawn.payment.to shouldBe Party(Party.CARD, "K-2")
        withdrawn.tags!!["source"] shouldBe "payments-core-checking-ingress-gateway"
        (withdrawn.raw as JsonRaw).value.toString() shouldContain "\"TXN_TYP\":\"PMT\""
    }

    @Test
    fun `insufficient funds - the withdrawal fails, and so does the payment`(): Unit = runBlocking {
        // Arrange
        val id = submitted("nsf", from = Simulation.INSUFFICIENT_FUNDS)

        // Act
        val failed = awaitEvent(id, PAYMENT_FAILED)

        // Assert
        failed.shouldBeInstanceOf<PaymentFailed>().reason shouldBe Reasons.INSUFFICIENT_FUNDS
        awaitStatus(id, "FAILED").reason shouldBe Reasons.INSUFFICIENT_FUNDS
    }

    @Test
    fun `someone else's account - account-not-found`(): Unit = runBlocking {
        // Arrange
        val id = submitted("not-owner", from = Simulation.NOT_OWNER)

        // Act
        val failed = awaitEvent(id, WITHDRAWAL_FAILED)

        // Assert
        failed.shouldBeInstanceOf<WithdrawalFailed>().reason shouldBe Reasons.ACCOUNT_NOT_FOUND
        awaitEvent(id, PAYMENT_FAILED)
    }

    @Test
    fun `card declined - core checking refunds, and the payment fails with the deposit's reason`(): Unit = runBlocking {
        // Arrange
        val payment = sentToCard("declined")

        // Act
        cardAnswers(DepositFailed(payment, Reasons.CARD_DECLINED, id = "${payment.id}.declined", timestamp = System.currentTimeMillis()))

        // Assert
        awaitEvent(payment.id, REFUND_REQUESTED)
        awaitEvent(payment.id, FUNDS_REFUNDED)
        awaitEvent(payment.id, PAYMENT_FAILED).shouldBeInstanceOf<PaymentFailed>().reason shouldBe Reasons.CARD_DECLINED
        awaitStatus(payment.id, "FAILED")
        ledgerRows(payment.id).map { it.getValue("TXN_TYP") }.sorted() shouldBe listOf("PMT", "RFND")
    }

    @Test
    fun `the refund is rejected - a fault for the operator, and the customer sees DELAYED`(): Unit = runBlocking {
        // Arrange
        val payment = sentToCard("refund-rejected", from = Simulation.REFUND_REJECTED)

        // Act
        cardAnswers(DepositFailed(payment, Reasons.CARD_DECLINED, id = "${payment.id}.declined", timestamp = System.currentTimeMillis()))

        // Assert
        awaitEvent(payment.id, REFUND_FAILED).shouldBeInstanceOf<RefundFailed>().reason shouldBe Reasons.ACCOUNT_CLOSED
        awaitStatus(payment.id, "DELAYED")
        awaitFault("Refund failed for payment ${payment.id}")
        channel(payment.id).none { it.eventType() == PAYMENT_FAILED } shouldBe true
    }

    @Test
    fun `a withdrawal both done and failed - a conflict, a fault, and no outcome`(): Unit = runBlocking {
        // Arrange
        val payment = sentToCard("conflict")

        // Act
        publish(
            WithdrawalFailed(payment, Reasons.REJECTED, id = "${payment.id}.rejected", timestamp = System.currentTimeMillis()),
            "payments-core-checking-ingress-gateway",
        )

        // Assert
        awaitFault("Conflict on payment ${payment.id}")
        channel(payment.id).none { it.eventType() == PAYMENT_FAILED } shouldBe true
    }

    @Test
    fun `the card is past its deadline - PAYMENT_EXPIRED, DELAYED, and a late answer still completes it`(): Unit = runBlocking {
        // Arrange
        val payment = sentToCard("late")

        // Act
        deadlinePasses(payment.id, Steps.DEPOSIT)

        // Assert
        awaitEvent(payment.id, PAYMENT_EXPIRED).shouldBeInstanceOf<PaymentExpired>().step shouldBe Steps.DEPOSIT
        awaitStatus(payment.id, "DELAYED")

        cardAnswers(FundsDeposited(payment, id = "${payment.id}.deposited", timestamp = System.currentTimeMillis()))
        awaitEvent(payment.id, PAYMENT_COMPLETED)
        awaitStatus(payment.id, "COMPLETED")
    }

    @Test
    fun `a redelivered event changes nothing, and core checking is called once`(): Unit = runBlocking {
        // Arrange
        val payment = sentToCard("duplicate")
        val requested = awaitEvent(payment.id, WITHDRAWAL_REQUESTED)
        val withdrawn = awaitEvent(payment.id, FUNDS_WITHDRAWN)

        // Act
        publish(requested, "payments-control-service")
        publish(withdrawn, "payments-core-checking-ingress-gateway")
        delay(20.seconds) // time for a second call or a second DEPOSIT_REQUESTED, if there were one

        // Assert
        ledgerRows(payment.id).size shouldBe 1
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
        awaitEvent(id, WITHDRAWAL_REQUESTED).id!! shouldEndWith ".start"
        channel(id).count { it.eventType() == PAYMENT_SUBMITTED } shouldBe 1
        api("GET", "/payments").body!! shouldContain "\"id\":\"$id\""
    }
}
