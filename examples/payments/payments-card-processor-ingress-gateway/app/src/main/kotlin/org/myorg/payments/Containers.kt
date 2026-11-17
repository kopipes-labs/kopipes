package org.myorg.payments

import io.kopipes.aws.AwsEnvironmentConfig
import io.kopipes.aws.AwsKopipesContext
import io.kopipes.aws.flavors.MaterializePipeline
import io.kopipes.aws.from.KinesisAdapter
import io.kopipes.aws.isAwsRetryable
import io.kopipes.core.JsonRaw
import io.kopipes.core.PipelineAssembler
import io.kopipes.core.filters.EventFilters
import io.kopipes.core.flavors.TranslatePipeline
import kotlinx.serialization.json.JsonPrimitive

/** The ingress gateway's own setting, read like the framework's: from env vars, through [env]. */
open class IngressConfig : AwsEnvironmentConfig() {
    /** The secret the card processor signs its webhooks with. A real deployment reads it from a secrets store. */
    open fun webhookSecret(): String = env("WEBHOOK_SECRET") ?: error("WEBHOOK_SECRET is not set")
}

/** `DEPOSIT_REQUESTED` for a card: the deposits this gateway will hear about. */
val CARD_DEPOSITS = EventFilters.allOf(
    EventFilters.name(PaymentEvent.DEPOSIT_REQUESTED),
    EventFilters.onContent { (it as? PaymentEvent)?.payment?.to?.kind == Party.CARD },
)

/** The listener's wiring: the default channel → remember each card deposit's payment. Publishes nothing. */
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
                .eventFilter(CARD_DEPOSITS)
                .toUpdateRequest { uow -> rememberPayment(tableName, uow) }
                .build()
        )
        .build()

    val kinesisAdapter = KinesisAdapter(context, PaymentsEventCodec)
}

/**
 * The webhook's wiring: a verified webhook body → translate → the bus. The body travels as the unit of work's record,
 * so a body that can't be read becomes a fault with the body in it, for the operator to resubmit.
 *
 * Stream retry is on, and [PaymentNotKnownYet] and AWS errors are retryable: instead of becoming faults they fail the
 * request, so the webhook answers `5xx` and the processor sends it again later.
 */
class WebhookContainer(
    val context: AwsKopipesContext,
    val secret: String,
    val lookup: PaymentLookup,
    val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        fun context(config: AwsEnvironmentConfig = IngressConfig()) =
            AwsKopipesContext(envConfig = config, isRetryable = { it is PaymentNotKnownYet || isAwsRetryable(it) })

        fun build(): WebhookContainer {
            val config = IngressConfig()
            val context = context(config)
            val table = config.entityTableName() ?: error("ENTITY_TABLE_NAME is not set")
            return WebhookContainer(
                context,
                config.webhookSecret(),
                DynamoDbPaymentLookup(context.dynamoDbClientFactory.getClient("lookup"), table),
            )
        }

        /** How a webhook body travels: as the unit of work's raw record. */
        fun recordOf(body: String) = JsonRaw(JsonPrimitive(body))
    }

    val translatePipeline: TranslatePipeline = TranslatePipeline.builder()
        .id("webhook")
        .eventPublisher(context.eventPublisher)
        .toEvents { uow -> eventsOf(((uow.record as JsonRaw).value as JsonPrimitive).content, lookup) }
        .build()

    val assembler: PipelineAssembler = PipelineAssembler.builder()
        .context(context)
        .addPipeline(translatePipeline)
        .build()
}
