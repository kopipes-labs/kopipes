package org.myorg.payments

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.myorg.payments.PaymentEvent.Companion.DEPOSIT_FAILED
import org.myorg.payments.PaymentEvent.Companion.DEPOSIT_REQUESTED
import org.myorg.payments.PaymentEvent.Companion.FUNDS_DEPOSITED
import org.myorg.payments.PaymentEvent.Companion.FUNDS_REFUNDED
import org.myorg.payments.PaymentEvent.Companion.FUNDS_WITHDRAWN
import org.myorg.payments.PaymentEvent.Companion.PAYMENT_COMPLETED
import org.myorg.payments.PaymentEvent.Companion.PAYMENT_FAILED
import org.myorg.payments.PaymentEvent.Companion.REFUND_FAILED
import org.myorg.payments.PaymentEvent.Companion.REFUND_REQUESTED
import org.myorg.payments.PaymentEvent.Companion.WITHDRAWAL_FAILED

/** The spec's status table, one test per row, and the steps list. */
class PaymentViewTest {

    private val p7 = Payment("p-7", "u-42", Party(Party.CHECKING, "C-1"), Party(Party.CARD, "K-2"), "100.00", "USD", 1000L)

    private fun record(vararg heard: String, delayed: Set<String> = emptySet(), reasons: Map<String, String> = emptyMap()) =
        PaymentRecord(p7, "hash", heard = heard.associateWith { 2000L }, reasons = reasons, delayed = delayed)

    @Test
    fun `1 - PAYMENT_FAILED is FAILED, with its reason, whatever else was heard`() {
        // Arrange
        val failed = record(DEPOSIT_FAILED, REFUND_FAILED, FUNDS_REFUNDED, PAYMENT_FAILED, reasons = mapOf(PAYMENT_FAILED to "card-declined"))

        // Act
        val view = viewOf(failed)

        // Assert
        view.status shouldBe Status.FAILED
        view.reason shouldBe "card-declined"
    }

    @Test
    fun `2 - PAYMENT_COMPLETED is COMPLETED`() {
        statusOf(record(FUNDS_WITHDRAWN, DEPOSIT_REQUESTED, FUNDS_DEPOSITED, PAYMENT_COMPLETED)) shouldBe Status.COMPLETED
    }

    @Test
    fun `3 - a failed refund not yet fixed is DELAYED, above REFUNDING`() {
        statusOf(record(DEPOSIT_FAILED, REFUND_REQUESTED, REFUND_FAILED)) shouldBe Status.DELAYED
    }

    @Test
    fun `3 - an expired step still unanswered is DELAYED, and an answered one isn't`() {
        // Arrange
        val late = record(FUNDS_WITHDRAWN, DEPOSIT_REQUESTED, delayed = setOf(Steps.DEPOSIT))
        val answered = record(FUNDS_WITHDRAWN, DEPOSIT_REQUESTED, FUNDS_DEPOSITED, delayed = setOf(Steps.DEPOSIT))

        // Act and assert
        statusOf(late) shouldBe Status.DELAYED
        statusOf(answered) shouldBe Status.SENT_TO_CARD
    }

    @Test
    fun `4 - a failed deposit or a refund is REFUNDING`() {
        statusOf(record(FUNDS_WITHDRAWN, DEPOSIT_FAILED)) shouldBe Status.REFUNDING
        statusOf(record(REFUND_REQUESTED)) shouldBe Status.REFUNDING
        statusOf(record(FUNDS_REFUNDED)) shouldBe Status.REFUNDING
    }

    @Test
    fun `5 6 7 - the payment moves on as it is heard`() {
        statusOf(record(FUNDS_WITHDRAWN, DEPOSIT_REQUESTED)) shouldBe Status.SENT_TO_CARD
        statusOf(record(FUNDS_WITHDRAWN)) shouldBe Status.WITHDRAWN
        statusOf(record()) shouldBe Status.SUBMITTED
    }

    @Test
    fun `a WITHDRAWAL_FAILED shows on its step, the status waits for PAYMENT_FAILED`() {
        // Arrange
        val failed = record(WITHDRAWAL_FAILED, reasons = mapOf(WITHDRAWAL_FAILED to "insufficient-funds"))

        // Act
        val view = viewOf(failed)

        // Assert
        view.status shouldBe Status.SUBMITTED
        view.steps[1] shouldBe Step("withdrawn", Step.FAILED, at = 2000L, reason = "insufficient-funds")
    }

    @Test
    fun `the steps show what each step's events said, dated when they happened`() {
        // Arrange
        val sent = record(FUNDS_WITHDRAWN, DEPOSIT_REQUESTED)

        // Act
        val view = viewOf(sent)

        // Assert
        view.steps shouldBe listOf(
            Step("submitted", Step.DONE, at = 1000L),
            Step("withdrawn", Step.DONE, at = 2000L),
            Step("deposited", Step.PENDING),
        )
    }

    @Test
    fun `a late step shows as delayed`() {
        // Arrange
        val late = record(FUNDS_WITHDRAWN, DEPOSIT_REQUESTED, delayed = setOf(Steps.DEPOSIT))

        // Act
        val view = viewOf(late)

        // Assert
        view.steps.last() shouldBe Step("deposited", Step.DELAYED)
    }
}
