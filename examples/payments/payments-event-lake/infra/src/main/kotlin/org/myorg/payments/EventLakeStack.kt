package org.myorg.payments

import io.kopipes.cdk.EventLake
import io.kopipes.cdk.EventLakeProps
import io.kopipes.cdk.KopipesStack
import io.kopipes.cdk.ServiceProps
import io.kopipes.cdk.Stage
import software.amazon.awscdk.RemovalPolicy
import software.constructs.Construct

/** Keeps every event except faults, for audit and for replaying them to one service (see docs/EventLake.md). */
class EventLakeStack(scope: Construct, props: ServiceProps) : KopipesStack(scope, props) {
    val lake = EventLake(
        this, "Lake",
        EventLakeProps(removalPolicy = if (stage == Stage.LOCAL) RemovalPolicy.DESTROY else RemovalPolicy.RETAIN),
    )
}
