package org.myorg.payments

import io.kopipes.core.JsonRaw
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue as StreamAV

/*
 * Semantic translation: core checking's ledger, in our words.
 *
 * | Ledger row (with our payment as REF_NO) | Event              |
 * |-----------------------------------------|--------------------|
 * | PMT  POSTED                             | FUNDS_WITHDRAWN    |
 * | PMT  REJECTED                           | WITHDRAWAL_FAILED  |
 * | RFND POSTED                             | FUNDS_REFUNDED     |
 * | RFND REJECTED                           | REFUND_FAILED      |
 * | no REF_NO, or not POSTED/REJECTED yet   | nothing            |
 */

/** A ledger row as change data capture delivers it: column → value. Every value is text in core checking. */
fun rowOf(image: Map<String, StreamAV?>?): Map<String, String> =
    image.orEmpty().mapNotNull { (column, value) -> (value?.s ?: value?.n)?.let { column to it } }.toMap()

/**
 * The events a ledger row means. The payment snapshot comes from the request this gateway remembered
 * ([PaymentLookup]); if it hasn't heard it yet, [PaymentNotKnownYet] makes the row wait.
 *
 * - **Event id:** `<ENTRY_ID>.<TYPE>`: the same ledger entry always yields the same event.
 * - **Timestamp:** when core checking posted it, not when we heard.
 * - **raw:** the row, whole, unknown columns included.
 */
suspend fun eventsOf(row: Map<String, String>, lookup: PaymentLookup): List<PaymentEvent> {
    if (row["REF_NO"].isNullOrBlank()) return emptyList() // not ours
    val entry = LedgerEntry.fromAttributes(row)
    if (entry.STAT != LedgerEntry.POSTED && entry.STAT != LedgerEntry.REJECTED) return emptyList()
    require(entry.TXN_TYP == LedgerEntry.PAYMENT || entry.TXN_TYP == LedgerEntry.REFUND) {
        "Ledger entry ${entry.ENTRY_ID} for payment ${entry.REF_NO} has an unknown TXN_TYP ${entry.TXN_TYP}"
    }

    val payment = lookup.payment(entry.REF_NO!!)
    val posted = entry.STAT == LedgerEntry.POSTED
    val reason = coreCheckingReason(entry.REJ_CD)
    val event: PaymentEvent = when (entry.TXN_TYP) {
        LedgerEntry.PAYMENT -> if (posted) FundsWithdrawn(payment) else WithdrawalFailed(payment, reason)
        else -> if (posted) FundsRefunded(payment) else RefundFailed(payment, reason)
    }
    return listOf(
        event.copyEvent(
            id = "${entry.ENTRY_ID}.${event.eventType()}",
            timestamp = entry.POST_TS.toLong(),
            partitionKey = payment.id,
            raw = JsonRaw(JsonObject(row.mapValues { JsonPrimitive(it.value) })),
        ) as PaymentEvent
    )
}
