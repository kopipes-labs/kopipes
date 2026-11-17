package org.myorg.payments

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.UpdateItemRequest
import io.kopipes.aws.AwsKopipesContext
import io.kopipes.aws.flavors.MaterializePipeline
import io.kopipes.aws.from.KinesisAdapter
import io.kopipes.core.PipelineAssembler
import io.kopipes.core.UnitOfWork
import io.kopipes.core.filters.EventFilters

/** The events that move the customer's view. */
val VIEW_EVENTS = setOf(
    PaymentEvent.FUNDS_WITHDRAWN, PaymentEvent.WITHDRAWAL_FAILED,
    PaymentEvent.DEPOSIT_REQUESTED, PaymentEvent.FUNDS_DEPOSITED, PaymentEvent.DEPOSIT_FAILED,
    PaymentEvent.REFUND_REQUESTED, PaymentEvent.FUNDS_REFUNDED, PaymentEvent.REFUND_FAILED,
    PaymentEvent.PAYMENT_EXPIRED,
    PaymentEvent.PAYMENT_COMPLETED, PaymentEvent.PAYMENT_FAILED,
)

/**
 * Records that the payment heard [UnitOfWork.event]: `at_<TYPE>` is the event's timestamp, and a failure's reason goes
 * in `reason_<TYPE>`. `if_not_exists` keeps the first one, so a repeat changes nothing and the view never goes back.
 */
fun heardUpdate(table: String, uow: UnitOfWork): UpdateItemRequest? {
    val event = uow.event as? PaymentEvent ?: return null
    val type = event.eventType()
    if (type !in VIEW_EVENTS) return null
    val timestamp = event.timestamp ?: error("$type ${event.id} has no timestamp")

    val heardAs = if (event is PaymentExpired) Items.EXPIRED_PREFIX + event.step else Items.HEARD_PREFIX + type
    val names = mutableMapOf("#at" to heardAs)
    val values = mutableMapOf<String, AttributeValue>(":at" to AttributeValue.N(timestamp.toString()))
    val sets = mutableListOf("#at = if_not_exists(#at, :at)")
    if (event is Failure) {
        names["#reason"] = Items.REASON_PREFIX + type
        values[":reason"] = AttributeValue.S(event.reason)
        sets += "#reason = if_not_exists(#reason, :reason)"
    }
    return UpdateItemRequest {
        tableName = table
        key = Items.key(event.payment.id)
        updateExpression = "SET " + sets.joinToString(", ")
        expressionAttributeNames = names
        expressionAttributeValues = values
    }
}

/** The listener's wiring: the default channel → materialize what was heard into the views table. */
class ListenerContainer(val context: AwsKopipesContext, val tableName: String) {
    companion object {
        const val VIEW = "view"

        fun build(): ListenerContainer {
            val context = AwsKopipesContext()
            return ListenerContainer(context, context.envConfig.entityTableName() ?: error("ENTITY_TABLE_NAME is not set"))
        }
    }

    val viewPipeline: MaterializePipeline = MaterializePipeline.builder()
        .id(VIEW)
        .context(context)
        .eventFilter(EventFilters.onContent { it.eventType() in VIEW_EVENTS })
        .toUpdateRequest { uow -> heardUpdate(tableName, uow) }
        .build()

    val assembler: PipelineAssembler = PipelineAssembler.builder()
        .context(context)
        .addPipeline(viewPipeline)
        .build()

    val kinesisAdapter = KinesisAdapter(context, PaymentsEventCodec)
}
