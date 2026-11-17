package org.myorg.sut

import io.kopipes.cdk.EventLake
import io.kopipes.cdk.KopipesStack
import io.kopipes.cdk.ServiceProps
import software.constructs.Construct

/** Every event on the bus except faults, kept in S3 for 92 days for audit and replay. See docs/EventLake.md. */
class EventLakeS3Stack(scope: Construct, serviceProps: ServiceProps) : KopipesStack(scope, serviceProps) {
    val lake = EventLake(this, "Lake")
}
