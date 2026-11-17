package org.myorg.payments

import com.amazonaws.services.lambda.runtime.events.DynamodbEvent
import io.kopipes.aws.AwsKopipesContext
import io.kopipes.aws.flavors.MaterializePipeline
import io.kopipes.aws.from.DynamodbAdapter
import io.kopipes.aws.from.KinesisAdapter
import io.kopipes.aws.isAwsRetryable
import io.kopipes.core.PipelineAssembler
import io.kopipes.core.filters.EventFilters
import io.kopipes.core.flavors.TranslatePipeline

/**
 * The listener's wiring: the default channel → remember each payment we asked core checking about
 * (`WITHDRAWAL_REQUESTED`), so later answers can carry the whole payment. Publishes nothing.
 */
class ListenerContainer(val context: AwsKopipesContext, val tableName: String) {
    companion object {
        fun build(): ListenerContainer {
            val context = AwsKopipesContext()
            return ListenerContainer(context, context.envConfig.entityTableName() ?: error("ENTITY_TABLE_NAME is not set"))
        }
    }

    val assembler: PipelineAssembler = PipelineAssembler.builder()
        .context(context)
        .addPipeline(
            MaterializePipeline.builder()
                .id("remember")
                .context(context)
                .eventFilter(EventFilters.name(PaymentEvent.WITHDRAWAL_REQUESTED))
                .toUpdateRequest { uow -> rememberPayment(tableName, uow) }
                .build()
        )
        .build()

    val kinesisAdapter = KinesisAdapter(context, PaymentsEventCodec)
}

/**
 * The ledger function's wiring: core checking's ledger changes (change data capture) → translate → the bus.
 *
 * A row heard before its request waits: [PaymentNotKnownYet] is retryable, and the function runs with stream retry,
 * so the batch is delivered again rather than becoming a fault. A ledger change counts as handled only once its event
 * is published; after an outage the stream resumes from the first change not handled.
 */
class LedgerContainer(val context: AwsKopipesContext, val lookup: PaymentLookup) {
    companion object {
        fun context() = AwsKopipesContext(isRetryable = { it is PaymentNotKnownYet || isAwsRetryable(it) })

        fun build(): LedgerContainer {
            val context = context()
            val table = context.envConfig.entityTableName() ?: error("ENTITY_TABLE_NAME is not set")
            return LedgerContainer(context, DynamoDbPaymentLookup(context.dynamoDbClientFactory.getClient("lookup"), table))
        }
    }

    val translatePipeline: TranslatePipeline = TranslatePipeline.builder()
        .id("ledger")
        .eventPublisher(context.eventPublisher)
        .onContentType { uow ->
            val record = uow.record as? DynamodbEvent.DynamodbStreamRecord
            record?.eventName == "INSERT" || record?.eventName == "MODIFY"
        }
        .toEvents { uow -> eventsOf(rowOf((uow.record as DynamodbEvent.DynamodbStreamRecord).dynamodb.newImage), lookup) }
        .build()

    val assembler: PipelineAssembler = PipelineAssembler.builder()
        .context(context)
        .addPipeline(translatePipeline)
        .build()

    val dynamoDbAdapter = DynamodbAdapter(context)
}
