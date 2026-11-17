package org.myorg.payments

import io.kopipes.aws.AwsKopipesContext
import io.kopipes.aws.flavors.CdcPipeline
import io.kopipes.aws.from.DynamodbAdapter
import io.kopipes.aws.from.RecordPair
import io.kopipes.core.Event
import io.kopipes.core.PipelineAssembler
import io.kopipes.core.UnitOfWork

/**
 * Accepted means published: `PAYMENT_SUBMITTED` comes from the views table's stream, never from the request itself.
 * There is no dual write, so a payment is never accepted without its event, nor published without being accepted.
 * It fires when the payment's item first gets its `requestHash`, which happens once per payment.
 */
fun toPaymentSubmitted(uow: UnitOfWork): Event? {
    val images = uow.event?.raw as? RecordPair ?: return null
    val new = images.new ?: return null
    if (new.getS("sk") != Items.PAYMENT) return null
    if (images.old?.getS(Items.REQUEST_HASH) != null) return null
    val payment = paymentRecordOf(new.keys, new)?.payment ?: return null
    return PaymentSubmitted(
        payment = payment,
        id = "${payment.id}.submitted", // one payment, one id, however often the request was retried
        timestamp = payment.submittedAt,
        partitionKey = payment.id,
    )
}

/** The trigger's wiring: the views table's stream → `PAYMENT_SUBMITTED` on the bus (change data capture). */
class TriggerContainer(val context: AwsKopipesContext) {
    companion object {
        const val SUBMITTED = "submitted"

        fun build() = TriggerContainer(AwsKopipesContext())
    }

    val cdcPipeline: CdcPipeline = CdcPipeline.builder()
        .id(SUBMITTED)
        .context(context)
        .eventPublisher(context.eventPublisher)
        .toEvent { uow -> toPaymentSubmitted(uow) }
        .build()

    val assembler: PipelineAssembler = PipelineAssembler.builder()
        .context(context)
        .addPipeline(cdcPipeline)
        .build()

    val dynamoDbAdapter = DynamodbAdapter(context)
}
