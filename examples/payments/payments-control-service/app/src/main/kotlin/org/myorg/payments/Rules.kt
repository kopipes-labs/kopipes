package org.myorg.payments

import org.myorg.payments.PaymentEvent.Companion.DEPOSIT_FAILED
import org.myorg.payments.PaymentEvent.Companion.FUNDS_DEPOSITED
import org.myorg.payments.PaymentEvent.Companion.FUNDS_REFUNDED
import org.myorg.payments.PaymentEvent.Companion.FUNDS_WITHDRAWN
import org.myorg.payments.PaymentEvent.Companion.REFUND_FAILED
import org.myorg.payments.PaymentEvent.Companion.WITHDRAWAL_FAILED

/*
 * The payment process, as plain functions of what has been heard for a payment. No framework here: the pipelines in
 * TriggerContainer decide *when* a rule runs, these decide *what* it emits. Each rule depends only on which events
 * have been heard, never on their order, so a late or repeated event gives the same result.
 *
 * | Rule            | When it has heard…                                   | It emits                          |
 * |-----------------|------------------------------------------------------|-----------------------------------|
 * | start           | PAYMENT_SUBMITTED                                    | WITHDRAWAL_REQUESTED              |
 * | next-step       | FUNDS_WITHDRAWN                                      | DEPOSIT_REQUESTED                 |
 * | nothing-to-undo | WITHDRAWAL_FAILED                                    | PAYMENT_FAILED (its reason)       |
 * | done            | FUNDS_DEPOSITED                                      | PAYMENT_COMPLETED                 |
 * | compensate      | DEPOSIT_FAILED                                       | REFUND_REQUESTED                  |
 * | undone          | FUNDS_REFUNDED                                       | PAYMENT_FAILED (deposit's reason) |
 * | refund-failed   | REFUND_FAILED, and not FUNDS_REFUNDED                | a fault; no outcome yet           |
 * | conflict        | a step's exit and its abort                          | a fault; nothing more, ever       |
 * | expired-<step>  | the step's entry, and by its deadline no answer      | PAYMENT_EXPIRED                   |
 *
 * Conflict is checked first: once it holds, no other rule fires for the payment.
 */

/** Everything heard for one payment: the events correlated under its id. */
class Heard(val events: List<PaymentEvent>) {
    val types: Set<String> = events.map { it.eventType() }.toSet()

    operator fun contains(type: String): Boolean = type in types

    inline fun <reified T : PaymentEvent> first(): T? = events.filterIsInstance<T>().firstOrNull()
}

/** A step's exit and abort, the two answers that settle it. */
data class StepAnswers(val step: String, val exit: String, val abort: String)

val STEP_ANSWERS = listOf(
    StepAnswers(Steps.WITHDRAWAL, exit = FUNDS_WITHDRAWN, abort = WITHDRAWAL_FAILED),
    StepAnswers(Steps.DEPOSIT, exit = FUNDS_DEPOSITED, abort = DEPOSIT_FAILED),
    StepAnswers(Steps.REFUND, exit = FUNDS_REFUNDED, abort = REFUND_FAILED),
)

fun answersOf(step: String): StepAnswers = STEP_ANSWERS.first { it.step == step }

/**
 * Conflict: the withdrawal or the deposit was both done and failed. Only an operator can sort that out. A refund
 * that failed and then posted is not a conflict: it's the operator's fix landing.
 */
fun conflict(heard: Heard): Boolean =
    STEP_ANSWERS.filter { it.step != Steps.REFUND }.any { it.exit in heard && it.abort in heard }

/** Expired: the step's deadline passed and the step has neither answer. */
fun unanswered(step: String, heard: Heard): Boolean {
    val answers = answersOf(step)
    return answers.exit !in heard && answers.abort !in heard
}

/** Refund failed: the money is still owed, until the operator's fix in core checking arrives as FUNDS_REFUNDED. */
fun refundStillOwed(heard: Heard): Boolean = REFUND_FAILED in heard && FUNDS_REFUNDED !in heard

// What each rule emits. The trigger is the event the rule fired on; it carries the payment.

fun start(trigger: PaymentEvent) = WithdrawalRequested(trigger.payment)

fun nextStep(trigger: PaymentEvent) = DepositRequested(trigger.payment)

fun nothingToUndo(trigger: WithdrawalFailed) = PaymentFailed(trigger.payment, reason = trigger.reason)

fun done(trigger: PaymentEvent) = PaymentCompleted(trigger.payment)

fun compensate(trigger: PaymentEvent) = RefundRequested(trigger.payment)

/**
 * Undone: the refund posted, so the payment failed for the reason the deposit failed. A refund is only ever requested
 * after a DEPOSIT_FAILED, so it's there; if it isn't, the rule can't decide, and the payment becomes a fault.
 */
fun undone(trigger: PaymentEvent, heard: Heard): PaymentFailed {
    val depositFailed = heard.first<DepositFailed>()
        ?: throw IllegalStateException("FUNDS_REFUNDED for ${trigger.payment.id} without a DEPOSIT_FAILED")
    return PaymentFailed(trigger.payment, reason = depositFailed.reason)
}

fun expired(trigger: PaymentEvent, step: String) = PaymentExpired(trigger.payment, step = step)

/** Thrown to turn the unit of work, with the payment's events, into a fault for the operator. */
class PaymentNeedsOperator(message: String) : RuntimeException(message)

fun conflictFault(payment: Payment, heard: Heard): Nothing =
    throw PaymentNeedsOperator("Conflict on payment ${payment.id}: heard ${heard.types.sorted()}. No rule fires for it again.")

fun refundFailedFault(trigger: RefundFailed): Nothing =
    throw PaymentNeedsOperator(
        "Refund failed for payment ${trigger.payment.id} (${trigger.reason}): post the refund in core checking " +
            "with reference ${trigger.payment.id}."
    )
