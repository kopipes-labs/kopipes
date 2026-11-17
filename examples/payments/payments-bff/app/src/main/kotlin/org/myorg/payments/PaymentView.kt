package org.myorg.payments

import kotlinx.serialization.Serializable
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

/**
 * What the BFF knows about a payment: the payment as accepted, and which events it has heard.
 *
 * `heard` maps an event type to the event's own timestamp (when it happened, not when the BFF heard it);
 * `reasons` maps a failure type to its reason; `delayed` holds the steps `PAYMENT_EXPIRED` named. They only grow,
 * so a late, repeated or out-of-order event never makes the view go backwards.
 */
data class PaymentRecord(
    val payment: Payment,
    val requestHash: String,
    val heard: Map<String, Long> = emptyMap(),
    val reasons: Map<String, String> = emptyMap(),
    val delayed: Set<String> = emptySet(),
)

enum class Status { SUBMITTED, WITHDRAWN, SENT_TO_CARD, REFUNDING, DELAYED, COMPLETED, FAILED }

@Serializable
data class Step(val step: String, val state: String, val at: Long? = null, val reason: String? = null) {
    companion object {
        const val DONE = "done"
        const val PENDING = "pending"
        const val FAILED = "failed"
        const val DELAYED = "delayed"
    }
}

/** The payment as the customer sees it. */
@Serializable
data class PaymentView(
    val id: String,
    val status: Status,
    val amount: String,
    val currency: String,
    val to: Party,
    val steps: List<Step>,
    val reason: String? = null,
)

/** Each step and the events that answer it, as the BFF reads them. */
private val ANSWERS = mapOf(
    Steps.WITHDRAWAL to (FUNDS_WITHDRAWN to WITHDRAWAL_FAILED),
    Steps.DEPOSIT to (FUNDS_DEPOSITED to DEPOSIT_FAILED),
    Steps.REFUND to (FUNDS_REFUNDED to REFUND_FAILED),
)

/** A step `PAYMENT_EXPIRED` named, and still without its exit or abort. */
private fun stillLate(step: String, record: PaymentRecord): Boolean {
    val (exit, abort) = ANSWERS.getValue(step)
    return step in record.delayed && exit !in record.heard && abort !in record.heard
}

/**
 * `status` depends only on which events have been heard, never on their order. The first row that matches wins.
 * Only the control service decides the outcome: `COMPLETED` comes only from `PAYMENT_COMPLETED`, `FAILED` only from
 * `PAYMENT_FAILED`.
 */
fun statusOf(record: PaymentRecord): Status {
    val heard = record.heard.keys
    return when {
        PAYMENT_FAILED in heard -> Status.FAILED
        PAYMENT_COMPLETED in heard -> Status.COMPLETED
        (REFUND_FAILED in heard && FUNDS_REFUNDED !in heard) || Steps.ALL.any { stillLate(it, record) } -> Status.DELAYED
        DEPOSIT_FAILED in heard || REFUND_REQUESTED in heard || FUNDS_REFUNDED in heard -> Status.REFUNDING
        DEPOSIT_REQUESTED in heard -> Status.SENT_TO_CARD
        FUNDS_WITHDRAWN in heard -> Status.WITHDRAWN
        else -> Status.SUBMITTED
    }
}

/** A step's state: done on its exit, failed on its abort, delayed past its deadline, otherwise pending. */
private fun step(name: String, step: String, record: PaymentRecord): Step {
    val (exit, abort) = ANSWERS.getValue(step)
    return when {
        exit in record.heard -> Step(name, Step.DONE, at = record.heard[exit])
        abort in record.heard -> Step(name, Step.FAILED, at = record.heard[abort], reason = record.reasons[abort])
        stillLate(step, record) -> Step(name, Step.DELAYED)
        else -> Step(name, Step.PENDING)
    }
}

fun viewOf(record: PaymentRecord): PaymentView {
    val heard = record.heard.keys
    val steps = buildList {
        add(Step("submitted", Step.DONE, at = record.payment.submittedAt))
        add(step("withdrawn", Steps.WITHDRAWAL, record))
        if (setOf(DEPOSIT_REQUESTED, FUNDS_DEPOSITED, DEPOSIT_FAILED).any { it in heard } || Steps.DEPOSIT in record.delayed) {
            add(step("deposited", Steps.DEPOSIT, record))
        }
        if (setOf(REFUND_REQUESTED, FUNDS_REFUNDED, REFUND_FAILED).any { it in heard } || Steps.REFUND in record.delayed) {
            add(step("refunded", Steps.REFUND, record))
        }
    }
    val status = statusOf(record)
    return PaymentView(
        id = record.payment.id,
        status = status,
        amount = record.payment.amount,
        currency = record.payment.currency,
        to = record.payment.to,
        steps = steps,
        reason = if (status == Status.FAILED) record.reasons[PAYMENT_FAILED] else null,
    )
}
