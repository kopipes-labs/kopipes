package org.myorg.payments

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The rules' conditions in isolation. */
class RulesTest {

    private fun heard(vararg events: PaymentEvent) = Heard(events.toList())

    @Test
    fun `a step's exit and abort together are a conflict, except for the refund`() {
        // Arrange
        val withdrawal = heard(FundsWithdrawn(P7), WithdrawalFailed(P7, Reasons.REJECTED))
        val deposit = heard(FundsDeposited(P7), DepositFailed(P7, Reasons.CARD_DECLINED))
        val refund = heard(FundsRefunded(P7), RefundFailed(P7, Reasons.ACCOUNT_CLOSED))
        val calm = heard(FundsWithdrawn(P7), DepositFailed(P7, Reasons.CARD_DECLINED))

        // Act and assert
        conflict(withdrawal) shouldBe true
        conflict(deposit) shouldBe true
        conflict(refund) shouldBe false
        conflict(calm) shouldBe false
    }

    @Test
    fun `a step is unanswered until it hears its exit or its abort`() {
        // Arrange
        val nothing = heard(PaymentSubmitted(P7))
        val exit = heard(FundsDeposited(P7))
        val abort = heard(DepositFailed(P7, Reasons.CARD_DECLINED))

        // Act and assert
        unanswered(Steps.DEPOSIT, nothing) shouldBe true
        unanswered(Steps.DEPOSIT, exit) shouldBe false
        unanswered(Steps.DEPOSIT, abort) shouldBe false
    }

    @Test
    fun `the refund is still owed after REFUND_FAILED until FUNDS_REFUNDED`() {
        // Arrange
        val failed = heard(RefundFailed(P7, Reasons.ACCOUNT_CLOSED))
        val fixed = heard(RefundFailed(P7, Reasons.ACCOUNT_CLOSED), FundsRefunded(P7))

        // Act and assert
        refundStillOwed(failed) shouldBe true
        refundStillOwed(fixed) shouldBe false
    }

    @Test
    fun `undone copies the deposit's reason, and can't decide without it`() {
        // Arrange
        val withCause = heard(DepositFailed(P7, Reasons.CARD_DECLINED), FundsRefunded(P7))
        val withoutCause = heard(FundsRefunded(P7))

        // Act
        val failed = undone(FundsRefunded(P7), withCause)

        // Assert
        failed.reason shouldBe Reasons.CARD_DECLINED
        shouldThrow<IllegalStateException> { undone(FundsRefunded(P7), withoutCause) }
    }
}
