package org.myorg.payments

import aws.sdk.kotlin.services.sqs.SqsClient
import aws.smithy.kotlin.runtime.net.url.Url
import io.kopipes.aws.AwsEnvironmentConfig
import io.kopipes.aws.AwsKopipesContext
import io.kopipes.aws.from.KinesisAdapter
import io.kopipes.aws.isAwsRetryable
import io.kopipes.aws.resilience.DynamoDbRateStore
import io.kopipes.core.Event
import io.kopipes.core.JsonRaw
import io.kopipes.core.PipelineAssembler
import io.kopipes.core.filters.EventFilters
import io.kopipes.core.flavors.ActionPipeline
import io.kopipes.core.resilience.RateLimiter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/** The egress gateway's settings, read like the framework's: from env vars, through [env]. */
open class EgressConfig : AwsEnvironmentConfig() {
    /** Simulation mode: never on in production. */
    open fun simulate(): Boolean = env("SIMULATE") == "true"
    open fun simulatedDelaySeconds(): Int = env("SIMULATED_DELAY_SECONDS")?.toIntOrNull() ?: 5
    open fun simulatedLateDelaySeconds(): Int = env("SIMULATED_LATE_DELAY_SECONDS")?.toIntOrNull() ?: 150
    open fun simulationQueueUrl(): String = env("SIMULATION_QUEUE_URL") ?: error("SIMULATION_QUEUE_URL is not set")
    open fun cardProcessorUrl(): String = env("CARD_PROCESSOR_URL") ?: error("CARD_PROCESSOR_URL is not set")
    open fun cardProcessorApiKey(): String = env("CARD_PROCESSOR_API_KEY") ?: error("CARD_PROCESSOR_API_KEY is not set")

    /** Credits per second the processor allows us. */
    open fun cardProcessorRate(): Int = env("CARD_PROCESSOR_RATE")?.toIntOrNull() ?: 5

    /** Simulation: where the simulator posts the webhook, and the secret it signs it with. */
    open fun webhookUrl(): String = env("WEBHOOK_URL") ?: error("WEBHOOK_URL is not set")
    open fun webhookSecret(): String = env("WEBHOOK_SECRET") ?: error("WEBHOOK_SECRET is not set")
}

/**
 * The processor refused the request itself (`4xx`): no webhook will come, so the gateway answers for it, with an
 * abort, not a fault: `DEPOSIT_FAILED`, so the refund runs. Its id is the payment's: one refusal per payment.
 */
fun refused(payment: Payment, result: CreditResult.Refused, now: Long): Event =
    DepositFailed(
        payment,
        reason = Reasons.CARD_DECLINED,
        id = "${payment.id}.refused.DEPOSIT_FAILED",
        timestamp = now,
        partitionKey = payment.id,
        raw = JsonRaw(runCatching { Json.parseToJsonElement(result.body) }.getOrDefault(JsonPrimitive(result.body))),
    )

/** `DEPOSIT_REQUESTED` for a card. */
val CARD_DEPOSITS = EventFilters.allOf(
    EventFilters.name(PaymentEvent.DEPOSIT_REQUESTED),
    EventFilters.onContent { (it as? PaymentEvent)?.payment?.to?.kind == Party.CARD },
)

/**
 * The listener's wiring: the default channel → credit the card. Publishes nothing when the processor accepts: the
 * result comes by webhook, through the ingress gateway. The processor being down or limiting us is retryable: the
 * function runs with stream retry, so requests wait on the channel.
 */
class EgressContainer(
    val context: AwsKopipesContext,
    val cardProcessor: CardProcessor,
    val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        fun context(config: EgressConfig = EgressConfig()) = AwsKopipesContext(
            envConfig = config,
            isRetryable = { it is CardProcessorUnavailable || it is RateLimited || isAwsRetryable(it) },
        )

        fun build(): EgressContainer {
            val config = EgressConfig()
            val context = context(config)
            val processor = if (config.simulate()) {
                val sqs = SqsClient {
                    region = config.awsRegion()
                    config.endPointUrl()?.let { endpointUrl = Url.parse(it) }
                }
                SimulatedCardProcessor(
                    SqsDelayedDelivery(sqs, config.simulationQueueUrl()),
                    config.simulatedDelaySeconds(),
                    config.simulatedLateDelaySeconds(),
                )
            } else {
                HttpCardProcessor(config.cardProcessorUrl(), config.cardProcessorApiKey())
            }
            val rateTable = config.entityTableName() ?: error("ENTITY_TABLE_NAME is not set")
            val limiter = RateLimiter(
                DynamoDbRateStore(context.dynamoDbClientFactory.getClient("rate"), rateTable, "card-processor"),
                perSecond = config.cardProcessorRate(),
            )
            return EgressContainer(context, PacedCardProcessor(processor, limiter))
        }
    }

    val creditPipeline: ActionPipeline = ActionPipeline.builder()
        .id("credit")
        .eventFilter(CARD_DEPOSITS)
        .eventPublisher(context.eventPublisher)
        .actionEmitting { uow ->
            val payment = (uow.event as PaymentEvent).payment
            when (val result = cardProcessor.credit(payment)) {
                CreditResult.Accepted -> emptyList()
                is CreditResult.Refused -> listOf(refused(payment, result, clock()))
            }
        }
        .build()

    val assembler: PipelineAssembler = PipelineAssembler.builder()
        .context(context)
        .addPipeline(creditPipeline)
        .build()

    val kinesisAdapter = KinesisAdapter(context, PaymentsEventCodec)
}
