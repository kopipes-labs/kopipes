package org.myorg.payments

import io.kopipes.aws.AwsKopipesContext
import io.kopipes.aws.from.KinesisAdapter
import io.kopipes.aws.sinks.DynamoDbEventsMicrostore
import io.kopipes.core.PipelineAssembler
import io.kopipes.core.filters.EventFilters
import io.kopipes.core.flavors.CollectPipeline
import io.kopipes.core.sinks.EventsMicrostore

/**
 * The listener's wiring: the default channel → collect → the events table.
 *
 * Writing first and acting later, from the table's stream (see [TriggerContainer]), means the service never acts on
 * a fact it hasn't recorded. A redelivered event writes the same item again, which changes nothing.
 */
class ListenerContainer(
    val context: AwsKopipesContext,
    val eventsMicrostore: EventsMicrostore = DynamoDbEventsMicrostore(context),
) {
    companion object {
        const val COLLECT = "collect"

        /** The events the payment process reacts to. Its own events (the entries and outcomes) aren't among them. */
        val CONSUMED = setOf(
            PaymentEvent.PAYMENT_SUBMITTED,
            PaymentEvent.FUNDS_WITHDRAWN, PaymentEvent.WITHDRAWAL_FAILED,
            PaymentEvent.FUNDS_DEPOSITED, PaymentEvent.DEPOSIT_FAILED,
            PaymentEvent.FUNDS_REFUNDED, PaymentEvent.REFUND_FAILED,
        )

        /** The production wiring. */
        fun build() = ListenerContainer(AwsKopipesContext(envConfig = ControlServiceConfig()))
    }

    /** Saves each consumed event as `pk = <event id>, sk = EVENT`, with the payment id as its correlation key. */
    val collectPipeline: CollectPipeline = CollectPipeline.builder()
        .id(COLLECT)
        .eventFilter(EventFilters.onContent { it.eventType() in CONSUMED })
        .correlationKey { uow -> (uow.event as PaymentEvent).payment.id }
        .eventsMicrostore(eventsMicrostore)
        .build()

    val assembler: PipelineAssembler = PipelineAssembler.builder()
        .context(context)
        .addPipeline(collectPipeline)
        .build()

    /** An event it can't decode becomes a fault, and the stream moves on. */
    val kinesisAdapter = KinesisAdapter(context, PaymentsEventCodec)
}
