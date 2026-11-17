package org.myorg.payments

import io.kopipes.aws.AwsKopipesContext
import io.kopipes.aws.flavors.CdcPipeline
import io.kopipes.aws.from.KinesisAdapter
import io.kopipes.core.PipelineAssembler
import io.kopipes.core.filters.EventFilters

/**
 * The listener's wiring: the default channel → translate outcomes → our own hub, as external events. The function's
 * bus source is `external`, so the default channel doesn't carry them back in; the hub's egress rule forwards
 * `PAYMENTS.*` to each downstream subsystem. This gateway's job ends once the external event is on our hub.
 */
class EgressContainer(val context: AwsKopipesContext) {
    companion object {
        fun build() = EgressContainer(AwsKopipesContext())
    }

    val translatePipeline: CdcPipeline = CdcPipeline.builder()
        .id("external")
        .eventFilter(EventFilters.anyOf(EventFilters.name(PaymentEvent.PAYMENT_COMPLETED), EventFilters.name(PaymentEvent.PAYMENT_FAILED)))
        .toEvent { uow -> externalEventOf(uow.event) }
        .eventPublisher(context.eventPublisher)
        .build()

    val assembler: PipelineAssembler = PipelineAssembler.builder()
        .context(context)
        .addPipeline(translatePipeline)
        .build()

    /** An event it can't read becomes a fault, and no external event. */
    val kinesisAdapter = KinesisAdapter(context, PaymentsEventCodec)
}
