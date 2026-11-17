package org.myorg.payments

import io.kopipes.core.JsonRaw
import kotlinx.serialization.json.Json

/*
 * Semantic translation: the card processor's webhooks, in our words.
 *
 * | Webhook (with our payment as metadata.reference) | Event                              |
 * |--------------------------------------------------|------------------------------------|
 * | credit.succeeded                                 | FUNDS_DEPOSITED                    |
 * | credit.failed                                    | DEPOSIT_FAILED, card-declined      |
 * | anything else                                    | nothing                            |
 */

/**
 * The events a webhook body means. Throws if the body can't be read. The payment snapshot comes from the
 * `DEPOSIT_REQUESTED` this gateway remembered; if it hasn't heard it yet, [PaymentNotKnownYet].
 *
 * - **Event id:** `<webhook event_id>.<TYPE>`: a redelivered webhook is the same event.
 * - **Reason:** every card failure is `card-declined`, whatever the processor's code (it stays in `raw`).
 * - **raw:** the webhook body, whole.
 */
suspend fun eventsOf(body: String, lookup: PaymentLookup): List<PaymentEvent> {
    val webhook = cardJson.decodeFromString(CardWebhook.serializer(), body)
    val event: PaymentEvent = when (webhook.type) {
        CardWebhook.SUCCEEDED -> FundsDeposited(lookup.payment(webhook.data.metadata.reference))
        CardWebhook.FAILED -> DepositFailed(lookup.payment(webhook.data.metadata.reference), Reasons.CARD_DECLINED)
        else -> return emptyList()
    }
    return listOf(
        event.copyEvent(
            id = "${webhook.eventId}.${event.eventType()}",
            timestamp = webhook.created * 1000,
            partitionKey = event.payment.id,
            raw = JsonRaw(Json.parseToJsonElement(body)),
        ) as PaymentEvent
    )
}
