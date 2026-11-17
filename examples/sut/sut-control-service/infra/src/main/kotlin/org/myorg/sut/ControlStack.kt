package org.myorg.sut

import io.kopipes.cdk.EnvVars
import io.kopipes.cdk.EventsTable
import io.kopipes.cdk.KopipesFunction
import io.kopipes.cdk.KopipesFunctionProps
import io.kopipes.cdk.KopipesStack
import io.kopipes.cdk.ServiceProps
import software.amazon.awscdk.services.lambda.Code
import software.constructs.Construct

/**
 * The shipment process. It listens to the default channel and publishes to the bus.
 *
 *     default channel ─► listener ─► events table ─► (stream) ─► trigger ─► bus
 */
class ControlStack(scope: Construct, serviceProps: ServiceProps) : KopipesStack(scope, serviceProps) {

    private val jar = Code.fromAsset("../app/build/libs/sut-control-service.jar")

    val events = EventsTable(this, "Events")

    // METRICS=*: the listener reports its pipeline metrics as EMF (see docs/Observability.md).
    val listener = KopipesFunction(
        this,
        "listener",
        KopipesFunctionProps(jar, "org.myorg.sut.Listener::handleRequest", environment = mapOf(EnvVars.METRICS to "*")),
    )
        .listenTo()
        .uses(events)

    val trigger = KopipesFunction(this, "trigger", KopipesFunctionProps(jar, "org.myorg.sut.Trigger::handleRequest"))
        .triggeredBy(events)
        .uses(events)
}
