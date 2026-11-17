package org.myorg.payments

import io.kopipes.cdk.EnvVars
import io.kopipes.cdk.EventsTable
import io.kopipes.cdk.EventsTableProps
import io.kopipes.cdk.KopipesFunction
import io.kopipes.cdk.KopipesFunctionProps
import io.kopipes.cdk.KopipesStack
import io.kopipes.cdk.ServiceProps
import io.kopipes.cdk.Stage
import software.amazon.awscdk.Duration
import software.amazon.awscdk.RemovalPolicy
import software.amazon.awscdk.services.lambda.Code
import software.amazon.awscdk.services.sqs.Queue
import software.amazon.awscdk.services.ssm.StringParameter
import software.constructs.Construct

/**
 * Credits cards for `DEPOSIT_REQUESTED`, at the processor's allowed rate.
 *
 *     default channel ─► listener ─► card processor                 (paced: the rate table)
 *                          └─ simulation: ─► queue (delay) ─► simulator ─POST, signed─► the ingress gateway's webhook
 *
 * The listener reads small batches (each call waits for its share of the rate) and retries until it succeeds, so
 * while the processor is down or limiting us, requests wait on the channel.
 */
class CardProcessorEgressStack(scope: Construct, props: ServiceProps) : KopipesStack(scope, props) {

    private val local = stage == Stage.LOCAL
    private val jar = Code.fromAsset("../app/build/libs/payments-card-processor-egress-gateway.jar")

    /** The per-second budgets of the processor's rate, shared by every container. */
    val rate = EventsTable(
        this, "Rate",
        EventsTableProps(suffix = "rate", stream = false, removalPolicy = if (local) RemovalPolicy.DESTROY else RemovalPolicy.RETAIN),
    )

    val listener = KopipesFunction(
        this, "listener",
        KopipesFunctionProps(
            jar, "org.myorg.payments.Listener::handleRequest",
            timeout = Duration.seconds(60),
            environment = mapOf(
                "SIMULATE" to simulate().toString(),
                "CARD_PROCESSOR_RATE" to (node.tryGetContext("cardProcessorRate")?.toString() ?: "5"),
            ) + (node.tryGetContext("cardProcessorUrl")?.let { mapOf("CARD_PROCESSOR_URL" to it.toString()) } ?: emptyMap()),
        ),
    ).listenTo(batchSize = 10, retryAttempts = -1).uses(rate, EnvVars.ENTITY_TABLE_NAME).retryStream()

    init {
        if (simulate()) {
            val queue = Queue.Builder.create(this, "Simulation")
                .queueName(name("simulation"))
                .visibilityTimeout(Duration.seconds(60))
                .build()
            queue.grantSendMessages(listener.function)
            listener.function.addEnvironment("SIMULATION_QUEUE_URL", queue.queueUrl)
            // K-7 is credited after the deposit's deadline: two minutes locally.
            listener.function.addEnvironment("SIMULATED_LATE_DELAY_SECONDS", if (local) "150" else "4000")

            KopipesFunction(
                this, "simulator",
                KopipesFunctionProps(
                    jar, "org.myorg.payments.Simulator::handleRequest", publishes = false,
                    environment = mapOf(
                        "WEBHOOK_URL" to StringParameter.valueForStringParameter(this, Partners.cardWebhookUrlParameter(stage)),
                        "WEBHOOK_SECRET" to StringParameter.valueForStringParameter(this, Partners.cardWebhookSecretParameter(stage)),
                    ),
                ),
            ).receives(queue, batchSize = 1)
        }
    }
}
