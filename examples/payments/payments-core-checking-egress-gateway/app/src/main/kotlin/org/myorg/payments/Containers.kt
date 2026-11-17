package org.myorg.payments

import aws.sdk.kotlin.services.sqs.SqsClient
import aws.smithy.kotlin.runtime.net.url.Url
import io.kopipes.aws.AwsKopipesContext
import io.kopipes.aws.from.KinesisAdapter
import io.kopipes.aws.isAwsRetryable
import io.kopipes.aws.resilience.DynamoDbBreakerStore
import io.kopipes.core.PipelineAssembler
import io.kopipes.core.filters.EventFilters
import io.kopipes.core.flavors.ActionPipeline
import io.kopipes.core.resilience.CircuitBreaker
import kotlin.time.Duration.Companion.seconds

/**
 * The listener's wiring: the default channel → withdraw or refund → core checking, called once.
 *
 * Publishes nothing when a call goes through: the result comes from the ledger, through the ingress gateway. While
 * core checking is down ([CallNotSent], [CoreCheckingUnavailable], both retryable), the function runs with stream
 * retry, so requests wait on the channel instead of becoming faults.
 */
class EgressContainer(val context: AwsKopipesContext, val calledOnce: CalledOnce) {
    companion object {
        fun context(config: EgressConfig = EgressConfig()) = AwsKopipesContext(
            envConfig = config,
            isRetryable = { it is CallNotSent || it is CoreCheckingUnavailable || isAwsRetryable(it) },
        )

        /** The production wiring: the real core checking, or the simulated one. */
        fun build(): EgressContainer {
            val config = EgressConfig()
            val context = context(config)
            val dynamoDb = context.dynamoDbClientFactory.getClient("calls")
            val callsTable = config.entityTableName() ?: error("ENTITY_TABLE_NAME is not set")
            val coreChecking = if (config.simulate()) {
                val sqs = SqsClient {
                    region = config.awsRegion()
                    config.endPointUrl()?.let { endpointUrl = Url.parse(it) }
                }
                SimulatedCoreChecking(SqsDelayedDelivery(sqs, config.simulationQueueUrl()), config.simulatedDelaySeconds())
            } else {
                HttpCoreChecking(config.coreCheckingUrl())
            }
            val breaker = CircuitBreaker(
                store = DynamoDbBreakerStore(dynamoDb, callsTable, "core-checking"),
                threshold = config.breakerThreshold(),
                openFor = config.breakerOpenSeconds().seconds,
                unavailable = { now -> config.closedHours()?.isClosed(now) == true },
            )
            return EgressContainer(context, CalledOnce(coreChecking, DynamoDbClaims(dynamoDb, callsTable), breaker))
        }
    }

    val withdrawPipeline: ActionPipeline = ActionPipeline.builder()
        .id("withdraw")
        .eventFilter(EventFilters.name(PaymentEvent.WITHDRAWAL_REQUESTED))
        .action { uow -> calledOnce.debit((uow.event as PaymentEvent).payment) }
        .build()

    val refundPipeline: ActionPipeline = ActionPipeline.builder()
        .id("refund")
        .eventFilter(EventFilters.name(PaymentEvent.REFUND_REQUESTED))
        .action { uow -> calledOnce.credit((uow.event as PaymentEvent).payment) }
        .build()

    val assembler: PipelineAssembler = PipelineAssembler.builder()
        .context(context)
        .addPipeline(withdrawPipeline)
        .addPipeline(refundPipeline)
        .build()

    val kinesisAdapter = KinesisAdapter(context, PaymentsEventCodec)
}
