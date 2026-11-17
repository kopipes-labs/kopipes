package org.myorg.payments

import io.kopipes.core.JsonRaw
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.myorg.payments.LocalPayments.api
import org.myorg.payments.LocalPayments.awaitEvent
import org.myorg.payments.LocalPayments.cardSettled
import org.myorg.payments.LocalPayments.awaitFault
import org.myorg.payments.LocalPayments.awaitNotification
import org.myorg.payments.LocalPayments.notifications
import org.myorg.payments.LocalPayments.awaitStatus
import org.myorg.payments.LocalPayments.channel
import org.myorg.payments.LocalPayments.deadlinePasses
import org.myorg.payments.LocalPayments.ledgerRows
import org.myorg.payments.LocalPayments.paymentBody
import org.myorg.payments.LocalPayments.poll
import org.myorg.payments.LocalPayments.publish
import org.myorg.payments.LocalPayments.submit
import org.myorg.payments.LocalPayments.WEBHOOK_FUNCTION
import org.myorg.payments.LocalPayments.awaitStoredFaults
import org.myorg.payments.LocalPayments.resubmitFaults
import org.myorg.payments.LocalPayments.webhook
import org.myorg.payments.PaymentEvent.Companion.DEPOSIT_FAILED
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Payments through the deployed subsystem, end to end. The outside systems are simulated by their gateways, and the
 * account decides what they answer (PaymentsServiceSpecs.md › Simulation mode): `C-2`, `C-9` and `C-5` for core
 * checking, `K-3`, `K-4` and `K-7` for the card processor, anything else succeeds.
 */
class PaymentsITest {

    private val run = System.currentTimeMillis().toString(36)

    private fun paymentId(name: String) = "it-$run-$name"

    /** Submits through the BFF, from checking account [from] to card [to]. */
    private suspend fun submitted(name: String, from: String = "C-1", to: String = "K-2"): String {
        val id = paymentId(name)
        submit(id, body = paymentBody(from = from, to = to)).statusCode shouldBe 202
        return id
    }

    @Test
    fun `happy path - withdrawn, deposited, completed`(): Unit = runBlocking {
        // Arrange and act
        val id = submitted("happy")

        // Assert
        awaitEvent(id, PAYMENT_COMPLETED)
        awaitStatus(id, "COMPLETED")
        channel(id).map { it.eventType() } shouldContainExactly listOf(
            PAYMENT_SUBMITTED, WITHDRAWAL_REQUESTED, FUNDS_WITHDRAWN, DEPOSIT_REQUESTED, FUNDS_DEPOSITED, PAYMENT_COMPLETED,
        )
    }

    @Test
    fun `Notifications hears the outcome, with the public payment only, and nothing else`(): Unit = runBlocking {
        // Arrange and act
        val id = submitted("public")

        // Assert
        val completed = awaitNotification(id, "PAYMENTS.PAYMENT_COMPLETED")
        completed.getValue("id").jsonPrimitive.content shouldEndWith ".done.external"
        completed.getValue("tags").jsonObject.getValue("source").jsonPrimitive.content shouldBe "payments-egress-gateway"
        completed.getValue("payment").jsonObject.keys shouldBe setOf("id", "customerId", "amount", "currency", "submittedAt")
        completed.containsKey("raw") shouldBe false
        delay(10.seconds) // time for anything else to arrive, if it did
        notifications(id).map { it.getValue("detail-type").jsonPrimitive.content } shouldBe listOf("PAYMENTS.PAYMENT_COMPLETED")
    }

    @Test
    fun `Notifications hears why a payment failed, in public words`(): Unit = runBlocking {
        // Arrange and act
        val nsf = submitted("public-nsf", from = Simulation.INSUFFICIENT_FUNDS)
        val declined = submitted("public-declined", to = Simulation.DECLINED_CARD)

        // Assert
        awaitNotification(nsf, "PAYMENTS.PAYMENT_FAILED").getValue("reason").jsonPrimitive.content shouldBe "insufficient-funds"
        awaitNotification(declined, "PAYMENTS.PAYMENT_FAILED").getValue("reason").jsonPrimitive.content shouldBe "rejected"
    }

    @Test
    fun `the gateways' events - the outside system's answer, with the whole payment and the original as raw`(): Unit = runBlocking {
        // Arrange
        val id = submitted("translated")

        // Act
        val withdrawn = awaitEvent(id, FUNDS_WITHDRAWN)
        val deposited = awaitEvent(id, FUNDS_DEPOSITED)

        // Assert
        withdrawn.id shouldBe "${ledgerRows(id).single().getValue("ENTRY_ID")}.FUNDS_WITHDRAWN"
        withdrawn.payment.to shouldBe Party(Party.CARD, "K-2")
        withdrawn.tags!!["source"] shouldBe "payments-core-checking-ingress-gateway"
        (withdrawn.raw as JsonRaw).value.toString() shouldContain "\"TXN_TYP\":\"PMT\""

        deposited.id shouldBe "evt_$id.FUNDS_DEPOSITED"
        deposited.payment.from shouldBe Party(Party.CHECKING, "C-1")
        deposited.tags!!["source"] shouldBe "payments-card-processor-ingress-gateway"
        (deposited.raw as JsonRaw).value.toString() shouldContain "\"type\":\"credit.succeeded\""
    }

    @Test
    fun `insufficient funds - the withdrawal fails, and so does the payment`(): Unit = runBlocking {
        // Arrange and act
        val id = submitted("nsf", from = Simulation.INSUFFICIENT_FUNDS)

        // Assert
        awaitEvent(id, PAYMENT_FAILED).shouldBeInstanceOf<PaymentFailed>().reason shouldBe Reasons.INSUFFICIENT_FUNDS
        awaitStatus(id, "FAILED").reason shouldBe Reasons.INSUFFICIENT_FUNDS
    }

    @Test
    fun `someone else's account - account-not-found`(): Unit = runBlocking {
        // Arrange and act
        val id = submitted("not-owner", from = Simulation.NOT_OWNER)

        // Assert
        awaitEvent(id, WITHDRAWAL_FAILED).shouldBeInstanceOf<WithdrawalFailed>().reason shouldBe Reasons.ACCOUNT_NOT_FOUND
        awaitEvent(id, PAYMENT_FAILED)
    }

    @Test
    fun `card declined - core checking refunds, and the payment fails with the deposit's reason`(): Unit = runBlocking {
        // Arrange and act
        val id = submitted("declined", to = Simulation.DECLINED_CARD)

        // Assert
        awaitEvent(id, DEPOSIT_FAILED).tags!!["source"] shouldBe "payments-card-processor-ingress-gateway"
        awaitEvent(id, REFUND_REQUESTED)
        awaitEvent(id, FUNDS_REFUNDED)
        awaitEvent(id, PAYMENT_FAILED).shouldBeInstanceOf<PaymentFailed>().reason shouldBe Reasons.CARD_DECLINED
        awaitStatus(id, "FAILED")
        ledgerRows(id).map { it.getValue("TXN_TYP") }.sorted() shouldBe listOf("PMT", "RFND")
    }

    @Test
    fun `an invalid card - the processor refuses outright, the egress gateway says DEPOSIT_FAILED, and it's refunded`(): Unit = runBlocking {
        // Arrange and act
        val id = submitted("refused", to = Simulation.REFUSED_CARD)

        // Assert
        val failed = awaitEvent(id, DEPOSIT_FAILED)
        failed.id shouldBe "$id.refused.DEPOSIT_FAILED"
        failed.tags!!["source"] shouldBe "payments-card-processor-egress-gateway"
        failed.shouldBeInstanceOf<DepositFailed>().reason shouldBe Reasons.CARD_DECLINED
        awaitEvent(id, FUNDS_REFUNDED)
        awaitStatus(id, "FAILED")
    }

    @Test
    fun `the refund is rejected - a fault for the operator, and the customer sees DELAYED`(): Unit = runBlocking {
        // Arrange and act
        val id = submitted("refund-rejected", from = Simulation.REFUND_REJECTED, to = Simulation.DECLINED_CARD)

        // Assert
        awaitEvent(id, REFUND_FAILED).shouldBeInstanceOf<RefundFailed>().reason shouldBe Reasons.ACCOUNT_CLOSED
        awaitStatus(id, "DELAYED")
        awaitFault("Refund failed for payment $id")
        channel(id).none { it.eventType() == PAYMENT_FAILED } shouldBe true
    }

    @Test
    fun `a withdrawal both done and failed - a conflict, a fault, and no outcome`(): Unit = runBlocking {
        // Arrange: a late card, so nothing else happens to the payment for a while
        val id = submitted("conflict", to = Simulation.LATE_CARD)
        val payment = awaitEvent(id, DEPOSIT_REQUESTED).payment

        // Act
        publish(
            WithdrawalFailed(payment, Reasons.REJECTED, id = "$id.rejected", timestamp = System.currentTimeMillis()),
            "payments-core-checking-ingress-gateway",
        )

        // Assert
        awaitFault("Conflict on payment $id")
        channel(id).none { it.eventType() == PAYMENT_FAILED } shouldBe true
    }

    @Test
    fun `a card credited after its deadline - PAYMENT_EXPIRED, DELAYED, then a late PAYMENT_COMPLETED`(): Unit = runBlocking {
        // Arrange
        val id = submitted("late", to = Simulation.LATE_CARD)
        val payment = awaitEvent(id, DEPOSIT_REQUESTED).payment

        // Act: the deposit's two minutes pass before the processor's webhook comes. The test sends that webhook
        // itself rather than waiting for the simulator's, which comes long after (and is then a redelivery).
        deadlinePasses(id, Steps.DEPOSIT)
        awaitEvent(id, PAYMENT_EXPIRED).shouldBeInstanceOf<PaymentExpired>().step shouldBe Steps.DEPOSIT
        awaitStatus(id, "DELAYED")
        poll(1.minutes, "the webhook to be taken") { cardSettled(payment).takeIf { it.statusCode == 200 } } // 503 until the deposit is known

        // Assert
        awaitEvent(id, PAYMENT_COMPLETED)
        awaitStatus(id, "COMPLETED")
    }

    @Test
    fun `a redelivered event changes nothing, and core checking is called once`(): Unit = runBlocking {
        // Arrange
        val id = submitted("duplicate", to = Simulation.LATE_CARD)
        val requested = awaitEvent(id, WITHDRAWAL_REQUESTED)
        val withdrawn = awaitEvent(id, FUNDS_WITHDRAWN)

        // Act
        publish(requested, "payments-control-service")
        publish(withdrawn, "payments-core-checking-ingress-gateway")
        delay(20.seconds) // time for a second call or a second DEPOSIT_REQUESTED, if there were one

        // Assert
        ledgerRows(id).size shouldBe 1
        channel(id).count { it.eventType() == DEPOSIT_REQUESTED } shouldBe 1
    }

    @Test
    fun `the webhook - only the processor's signature, a 5xx until the deposit is known, a fault if unreadable`(): Unit = runBlocking {
        // Arrange
        val unknown = """{"event_id":"evt_x","type":"credit.succeeded","created":1,"data":{"credit_id":"cr_x","amount_cents":1,"currency":"usd","metadata":{"reference":"${paymentId("unknown")}"}}}"""
        val unreadable = """{"unreadable":"${paymentId("unreadable")}"}"""

        // Act
        val unsigned = webhook(unknown, signed = false)
        val tooEarly = webhook(unknown)
        val garbled = webhook(unreadable)

        // Assert
        unsigned.statusCode shouldBe 401
        tooEarly.statusCode shouldBe 503
        garbled.statusCode shouldBe 200
        awaitFault(paymentId("unreadable"))
    }

    @Test
    fun `a webhook fault, resubmitted by the operator - the tool finds its body and the webhook function takes it again`(): Unit = runBlocking {
        // Arrange: a signed webhook we can't read, so a fault with its body, delivered to the fault monitor's bucket
        val text = paymentId("resubmitted")
        webhook("""{"unreadable":"$text"}""").statusCode shouldBe 200

        // Act
        val counters = resubmitFaults(WEBHOOK_FUNCTION, text)

        // Assert: the function took the body, with no signature, and (still unreadable) faulted again
        counters.errors shouldBe 0
        counters.invoked!!.statuses shouldBe mapOf(200 to 1)
        awaitStoredFaults(WEBHOOK_FUNCTION, text, count = 2)
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
