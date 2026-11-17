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
import software.amazon.awscdk.services.dynamodb.TableV2
import software.amazon.awscdk.services.lambda.Code
import software.amazon.awscdk.services.sqs.Queue
import software.constructs.Construct

/**
 * Sends withdrawals and refunds to core checking, each once.
 *
 *     default channel ─► listener ─► core checking                    (breaker, calls table)
 *                          └─ simulation: ─► queue (delay) ─► simulator ─► the ingress gateway's ledger
 *
 * The listener retries its batch until it succeeds (`retryAttempts = -1`): while core checking is down or closed,
 * requests wait on the channel, in order, instead of being dropped.
 */
class CoreCheckingEgressStack(scope: Construct, props: ServiceProps) : KopipesStack(scope, props) {

    private val local = stage == Stage.LOCAL
    private val jar = Code.fromAsset("../app/build/libs/payments-core-checking-egress-gateway.jar")

    /** Claims (one per request) and the breaker's state. */
    val calls = EventsTable(
        this, "Calls",
        EventsTableProps(suffix = "calls", stream = false, removalPolicy = if (local) RemovalPolicy.DESTROY else RemovalPolicy.RETAIN),
    )

    val listener = KopipesFunction(
        this, "listener",
        KopipesFunctionProps(
            jar, "org.myorg.payments.Listener::handleRequest",
            environment = mapOf(
                "SIMULATE" to simulate().toString(),
                "CORE_CHECKING_CLOSED_HOURS" to (node.tryGetContext("coreCheckingClosedHours")?.toString() ?: ""),
            ) + (node.tryGetContext("coreCheckingUrl")?.let { mapOf("CORE_CHECKING_URL" to it.toString()) } ?: emptyMap()),
        ),
    ).listenTo(retryAttempts = -1).uses(calls, EnvVars.ENTITY_TABLE_NAME).retryStream()

    init {
        if (simulate()) {
            val queue = Queue.Builder.create(this, "Simulation")
                .queueName(name("simulation"))
                .visibilityTimeout(Duration.seconds(60))
                .build()
            queue.grantSendMessages(listener.function)
            listener.function.addEnvironment("SIMULATION_QUEUE_URL", queue.queueUrl)

            val ledger = TableV2.fromTableName(this, "Ledger", Partners.ledgerTableName(stage))
            KopipesFunction(this, "simulator", KopipesFunctionProps(jar, "org.myorg.payments.Simulator::handleRequest", publishes = false))
                .receives(queue)
                .uses(ledger, "LEDGER_TABLE_NAME")
        }
    }
}
