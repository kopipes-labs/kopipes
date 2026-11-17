package org.myorg.payments

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.myorg.payments.PaymentEvent.Companion.DEPOSIT_REQUESTED
import org.myorg.payments.PaymentEvent.Companion.PAYMENT_COMPLETED
import org.myorg.payments.PaymentEvent.Companion.PAYMENT_EXPIRED
import org.myorg.payments.PaymentEvent.Companion.PAYMENT_FAILED
import org.myorg.payments.PaymentEvent.Companion.REFUND_REQUESTED
import org.myorg.payments.PaymentEvent.Companion.WITHDRAWAL_REQUESTED

/**
 * The control service's decision table, one test per cell, run in process through its real pipelines: the listener
 * collects, the table's stream drives the trigger, the rules publish.
 */
class ControlServiceTest {

    private val submitted = PaymentSubmitted(P7).published("p-7.submitted", at = P7.submittedAt)
    private val withdrawn = FundsWithdrawn(P7).published("ledger-1.FUNDS_WITHDRAWN")
    private val withdrawalFailed = WithdrawalFailed(P7, Reasons.INSUFFICIENT_FUNDS).published("ledger-1.WITHDRAWAL_FAILED")
    private val deposited = FundsDeposited(P7).published("wh-1.FUNDS_DEPOSITED")
    private val depositFailed = DepositFailed(P7, Reasons.CARD_DECLINED).published("wh-1.DEPOSIT_FAILED")
    private val refunded = FundsRefunded(P7).published("ledger-2.FUNDS_REFUNDED")
    private val refundFailed = RefundFailed(P7, Reasons.ACCOUNT_CLOSED).published("ledger-2.REFUND_FAILED")

    @Test
    fun `start - a submitted payment requests the withdrawal`() {
        // Arrange
        val service = ControlService()

        // Act
        service.hear(submitted)

        // Assert
        val requested = service.published().single()
        requested.shouldBeInstanceOf<WithdrawalRequested>()
        requested.id shouldBe "p-7.start"
        requested.payment shouldBe P7
        requested.partitionKey shouldBe "p-7"
    }

    @Test
    fun `withdrawal exit only - next step requests the deposit`() {
        // Arrange
        val service = ControlService().hear(submitted).apply { clear() }

        // Act
        service.hear(withdrawn)

        // Assert
        service.publishedTypes() shouldContainExactly listOf(DEPOSIT_REQUESTED)
        service.published().single().id shouldBe "p-7.next-step"
    }

    @Test
    fun `withdrawal abort only - nothing to undo, the payment fails with the withdrawal's reason`() {
        // Arrange
        val service = ControlService().hear(submitted).apply { clear() }

        // Act
        service.hear(withdrawalFailed)

        // Assert
        val failed = service.published().single()
        failed.shouldBeInstanceOf<PaymentFailed>()
        failed.reason shouldBe Reasons.INSUFFICIENT_FUNDS
        failed.id shouldBe "p-7.nothing-to-undo"
    }

    @Test
    fun `deposit exit only - done, the payment completes`() {
        // Arrange
        val service = ControlService().hear(submitted, withdrawn).apply { clear() }

        // Act
        service.hear(deposited)

        // Assert
        service.publishedTypes() shouldContainExactly listOf(PAYMENT_COMPLETED)
    }

    @Test
    fun `deposit abort only - compensate requests the refund`() {
        // Arrange
        val service = ControlService().hear(submitted, withdrawn).apply { clear() }

        // Act
        service.hear(depositFailed)

        // Assert
        service.publishedTypes() shouldContainExactly listOf(REFUND_REQUESTED)
    }

    @Test
    fun `refund exit only - undone, the payment fails with the deposit's reason`() {
        // Arrange
        val service = ControlService().hear(submitted, withdrawn, depositFailed).apply { clear() }

        // Act
        service.hear(refunded)

        // Assert
        val failed = service.published().single()
        failed.shouldBeInstanceOf<PaymentFailed>()
        failed.reason shouldBe Reasons.CARD_DECLINED
        failed.id shouldBe "p-7.undone"
    }

    @Test
    fun `refund abort only - a fault for the operator, and no outcome yet`() {
        // Arrange
        val service = ControlService().hear(submitted, withdrawn, depositFailed).apply { clear() }

        // Act
        service.hear(refundFailed)

        // Assert
        service.published().shouldBeEmpty()
        service.faults().single().err?.message shouldContain "Refund failed for payment p-7"
    }

    @Test
    fun `refund abort then exit - the operator's fix landed, the payment fails`() {
        // Arrange
        val service = ControlService().hear(submitted, withdrawn, depositFailed, refundFailed).apply { clear() }

        // Act
        service.hear(refunded)

        // Assert
        service.publishedTypes() shouldContainExactly listOf(PAYMENT_FAILED)
        service.faults().shouldBeEmpty()
    }

    @Test
    fun `withdrawal exit and abort - a conflict, a fault and nothing more`() {
        // Arrange
        val service = ControlService().hear(submitted).apply { clear() }

        // Act
        service.hear(withdrawn, withdrawalFailed)

        // Assert
        service.published().shouldBeEmpty()
        service.faults().shouldNotBeEmpty()
        service.faults().first().err?.message shouldContain "Conflict on payment p-7"
    }

    @Test
    fun `deposit abort after the payment completed - a conflict, never a second outcome`() {
        // Arrange
        val service = ControlService().hear(submitted, withdrawn, deposited).apply { clear() }

        // Act
        service.hear(depositFailed)

        // Assert
        service.published().shouldBeEmpty()
        service.faults().shouldNotBeEmpty()
    }

    @Test
    fun `after a conflict no rule fires again`() {
        // Arrange
        val service = ControlService().hear(submitted, withdrawn, withdrawalFailed).apply { clear() }

        // Act
        service.hear(deposited)

        // Assert
        service.published().shouldBeEmpty()
    }

    @Test
    fun `each step's entry starts its deadline`() {
        // Arrange
        val service = ControlService()

        // Act
        service.hear(submitted)

        // Assert
        val deadline = service.table.items().single { it.suffix == "withdrawal-deadline" }
        deadline.pk shouldBe "p-7.withdrawal-deadline"
        deadline.expire shouldBe true
        deadline.ttl shouldBe P7.submittedAt / 1000 + 120
    }

    @Test
    fun `neither answer by the deadline - the payment expired on that step, dated at the deadline`() {
        // Arrange
        val service = ControlService().hear(submitted).apply { clear() }

        // Act
        service.deadlinePasses(Steps.WITHDRAWAL)

        // Assert
        val expired = service.published().single()
        expired.shouldBeInstanceOf<PaymentExpired>()
        expired.step shouldBe Steps.WITHDRAWAL
        expired.id shouldBe "p-7.expired-withdrawal"
        expired.timestamp shouldBe P7.submittedAt + 120_000
    }

    @Test
    fun `an answered step doesn't expire`() {
        // Arrange
        val service = ControlService().hear(submitted, withdrawn).apply { clear() }

        // Act
        service.deadlinePasses(Steps.WITHDRAWAL)

        // Assert
        service.publishedTypes().contains(PAYMENT_EXPIRED) shouldBe false
    }

    @Test
    fun `a deadline is not an outcome - a late answer still moves the payment on`() {
        // Arrange
        val service = ControlService().hear(submitted, withdrawn).deadlinePasses(Steps.DEPOSIT).apply { clear() }

        // Act
        service.hear(deposited)

        // Assert
        service.publishedTypes() shouldContainExactly listOf(PAYMENT_COMPLETED)
    }

    @Test
    fun `a duplicate event changes nothing`() {
        // Arrange
        val service = ControlService().hear(submitted).apply { clear() }

        // Act
        service.hear(submitted)

        // Assert
        service.published().shouldBeEmpty()
    }

    @Test
    fun `a rule that fires again emits the same event id`() {
        // Arrange
        val first = ControlService().hear(submitted).published().single()

        // Act
        val second = ControlService().hear(submitted).published().single()

        // Assert
        second.id shouldBe first.id
        second.eventType() shouldBe WITHDRAWAL_REQUESTED
    }
}
