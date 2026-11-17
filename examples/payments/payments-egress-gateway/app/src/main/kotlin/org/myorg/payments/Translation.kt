package org.myorg.payments

import io.kopipes.core.Event

/*
 * What payments tells the rest of the bank, and what it hides.
 *
 * | Internal           | External                     |
 * |--------------------|------------------------------|
 * | PAYMENT_COMPLETED  | PAYMENTS.PAYMENT_COMPLETED   |
 * | PAYMENT_FAILED     | PAYMENTS.PAYMENT_FAILED      |
 * | anything else      | nothing: it stays inside     |
 *
 * Kept: the payment id, customerId, amount, currency and submittedAt. Dropped: `from` and `to` (account and card
 * numbers), `raw`, every step event.
 */

/**
 * The public reason. Only `insufficient-funds` is told apart; everything else, including any reason added later, is
 * `rejected`, so account numbers can't be probed.
 */
fun publicReason(internal: String): String =
    if (internal == Reasons.INSUFFICIENT_FUNDS) Reasons.INSUFFICIENT_FUNDS else Reasons.REJECTED

fun publicPaymentOf(payment: Payment) = PublicPayment(
    id = payment.id,
    customerId = payment.customerId,
    amount = payment.amount,
    currency = payment.currency,
    submittedAt = payment.submittedAt,
)

/**
 * The external event for an internal one, or `null` if it stays inside. Its id is derived from the internal event's,
 * so a redelivered internal event gives the same external event; its timestamp is when the outcome happened.
 */
fun externalEventOf(event: Event?): PaymentsExternalEvent? {
    val external = when (event) {
        is PaymentCompleted -> ExternalPaymentCompleted(publicPaymentOf(event.payment))
        is PaymentFailed -> ExternalPaymentFailed(publicPaymentOf(event.payment), reason = publicReason(event.reason))
        else -> return null
    }
    return external.copyEvent(
        id = "${event.id}.external",
        timestamp = event.timestamp,
        partitionKey = event.payment.id,
    ) as PaymentsExternalEvent
}
