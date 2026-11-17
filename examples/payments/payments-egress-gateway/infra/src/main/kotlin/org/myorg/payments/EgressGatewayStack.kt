package org.myorg.payments

import io.kopipes.cdk.EnvVars
import io.kopipes.cdk.EventForwarding
import io.kopipes.cdk.HubNames
import io.kopipes.cdk.KopipesFunction
import io.kopipes.cdk.KopipesFunctionProps
import io.kopipes.cdk.KopipesStack
import io.kopipes.cdk.ServiceProps
import software.amazon.awscdk.services.lambda.Code
import software.constructs.Construct

/**
 * Decides what payments tells the rest of the bank, and hides everything else.
 *
 *     default channel ─► listener ─► our hub (source: external) ─► egress rule ─► the Notifications hub
 *
 * It owns the contract (../contract) and the egress rule, one target per downstream subsystem.
 */
class EgressGatewayStack(scope: Construct, props: ServiceProps) : KopipesStack(scope, props) {

    private val jar = Code.fromAsset("../app/build/libs/payments-egress-gateway.jar")

    /** Publishes external events: the default channel doesn't carry them back in. */
    val listener = KopipesFunction(
        this, "listener",
        KopipesFunctionProps(jar, "org.myorg.payments.Listener::handleRequest", source = EnvVars.EXTERNAL_SOURCE),
    ).listenTo()

    val toNotifications = EventForwarding(this, "notifications", "PAYMENTS.", HubNames("notifications", stage).busName)
}
