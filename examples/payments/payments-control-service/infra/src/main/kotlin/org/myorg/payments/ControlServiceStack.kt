package org.myorg.payments

import io.kopipes.cdk.EventsTable
import io.kopipes.cdk.EventsTableProps
import io.kopipes.cdk.KopipesFunction
import io.kopipes.cdk.KopipesFunctionProps
import io.kopipes.cdk.KopipesStack
import io.kopipes.cdk.ServiceProps
import io.kopipes.cdk.Stage
import software.amazon.awscdk.RemovalPolicy
import software.amazon.awscdk.services.lambda.Code
import software.constructs.Construct

/**
 * The payment process. Nothing calls it: it listens to the default channel and publishes to the bus.
 *
 *     default channel ─► listener ─► events table ─► (stream) ─► trigger ─► bus
 *                         collect       │  ▲                    correlate, deadlines, rules
 *                                       └──┘ correlations and deadlines are written back to the table
 */
class ControlServiceStack(scope: Construct, props: ServiceProps) : KopipesStack(scope, props) {

    private val local = stage == Stage.LOCAL
    private val jar = Code.fromAsset("../app/build/libs/payments-control-service.jar")

    /** Deadlines per step: about two minutes in a demo; a day for core checking and an hour for the card otherwise. */
    private val deadlines = if (local) {
        mapOf("WITHDRAWAL_DEADLINE" to "PT2M", "DEPOSIT_DEADLINE" to "PT2M", "REFUND_DEADLINE" to "PT2M")
    } else {
        mapOf("WITHDRAWAL_DEADLINE" to "P1D", "DEPOSIT_DEADLINE" to "PT1H", "REFUND_DEADLINE" to "P1D")
    }

    val events = EventsTable(
        this, "Events",
        EventsTableProps(removalPolicy = if (local) RemovalPolicy.DESTROY else RemovalPolicy.RETAIN),
    )

    val listener = KopipesFunction(
        this, "listener",
        KopipesFunctionProps(jar, "org.myorg.payments.Listener::handleRequest"),
    ).listenTo().uses(events)

    val trigger = KopipesFunction(
        this, "trigger",
        KopipesFunctionProps(jar, "org.myorg.payments.Trigger::handleRequest", environment = deadlines),
    ).triggeredBy(events).uses(events)
}
