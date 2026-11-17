package org.myorg.payments

import aws.sdk.kotlin.services.sqs.SqsClient
import aws.sdk.kotlin.services.sqs.model.SendMessageRequest

/**
 * Simulation mode (PaymentsServiceSpecs.md › Simulation mode): off by default, never on in production. With it on,
 * an egress gateway doesn't contact the outside system. After a delay it produces the answer that system would have
 * sent, in that system's own format, and delivers it to its ingress partner's real input. The ingress gateways don't
 * change and can't tell the difference. The answer depends only on the account.
 */
object Simulation {
    /** Withdrawal rejected: insufficient funds. */
    const val INSUFFICIENT_FUNDS = "C-2"

    /** Withdrawal rejected: someone else's account. */
    const val NOT_OWNER = "C-9"

    /** Withdrawal succeeds, but the refund is rejected (account closed). Pair it with a declined card. */
    const val REFUND_REJECTED = "C-5"

    /** Card credit declined: the webhook says it failed. Starts the refund. */
    const val DECLINED_CARD = "K-3"

    /** Card request refused outright (`4xx`, an invalid card): the egress gateway emits DEPOSIT_FAILED itself. */
    const val REFUSED_CARD = "K-4"

    /** Card credited, but after the deposit deadline: PAYMENT_EXPIRED, then a late PAYMENT_COMPLETED. */
    const val LATE_CARD = "K-7"

    /**
     * The webhook the processor would send once it settled this payment's credit. The processor deduplicates on our
     * idempotency key (the payment id), so a repeated request is the same credit, with the same ids.
     */
    fun cardWebhook(payment: Payment, createdSeconds: Long): CardWebhook {
        val declined = payment.to.account == DECLINED_CARD
        return CardWebhook(
            eventId = "evt_${payment.id}",
            type = if (declined) CardWebhook.FAILED else CardWebhook.SUCCEEDED,
            created = createdSeconds,
            data = CardCredit(
                creditId = "cr_${payment.id}",
                amountCents = amountInCents(payment.amount),
                currency = payment.currency.lowercase(),
                metadata = CardMetadata(reference = payment.id),
                failureCode = if (declined) "card_declined" else null,
            ),
        )
    }

    /** What core checking would write for a debit (`PMT`) or a refund credit (`RFND`) of this payment. */
    fun ledgerEntry(payment: Payment, txnType: String, postedAt: Long, entryId: String): LedgerEntry {
        val rejectCode = when {
            txnType == LedgerEntry.PAYMENT && payment.from.account == INSUFFICIENT_FUNDS -> LedgerEntry.NSF
            txnType == LedgerEntry.PAYMENT && payment.from.account == NOT_OWNER -> LedgerEntry.NOT_OWNER
            txnType == LedgerEntry.REFUND && payment.from.account == REFUND_REJECTED -> LedgerEntry.ACCOUNT_CLOSED
            else -> null
        }
        return LedgerEntry(
            ENTRY_ID = entryId,
            ACCT_NO = payment.from.account,
            AMT = if (txnType == LedgerEntry.PAYMENT) "-${payment.amount}" else payment.amount,
            TXN_TYP = txnType,
            REF_NO = payment.id,
            STAT = if (rejectCode == null) LedgerEntry.POSTED else LedgerEntry.REJECTED,
            REJ_CD = rejectCode,
            POST_TS = postedAt.toString(),
        )
    }
}

/** Delivers a simulated answer later. */
fun interface DelayedDelivery {
    suspend fun deliverLater(body: String, delaySeconds: Int)
}

/** An SQS message with a delay (up to 15 minutes); a simulator function picks it up and plays the outside system. */
class SqsDelayedDelivery(private val client: SqsClient, private val queueUrl: String) : DelayedDelivery {
    override suspend fun deliverLater(body: String, delaySeconds: Int) {
        client.sendMessage(SendMessageRequest {
            queueUrl = this@SqsDelayedDelivery.queueUrl
            messageBody = body
            this.delaySeconds = delaySeconds.coerceIn(0, 900)
        })
    }
}
