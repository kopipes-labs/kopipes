package org.myorg.payments

import io.kopipes.core.Event
import io.kopipes.core.EventCodec
import io.kopipes.core.serialization.KotlinxEventCodec
import io.kopipes.core.serialization.KotlinxSerializationStrategy
import kotlinx.serialization.json.Json

/** Liberal in what it accepts: unknown fields are ignored, so a producer can add one without breaking anybody. */
val paymentsJson: Json = KotlinxSerializationStrategy.defaultJson()

/** Codec for every internal event of the payments subsystem: `type` picks the class. */
object PaymentsEventCodec : EventCodec by KotlinxEventCodec(PaymentEvent.serializer(), paymentsJson)

/**
 * The DynamoDB events microstore hands back correlated events as generic JSON events. This decodes them into
 * [PaymentEvent]s, so the rules can use their types and fields.
 */
fun List<Event>?.asPaymentEvents(): List<PaymentEvent> =
    orEmpty().map { it as? PaymentEvent ?: PaymentsEventCodec.decode(it.toString()) as PaymentEvent }
