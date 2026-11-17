package org.myorg.sut

import io.kopipes.cdk.FaultMonitor
import io.kopipes.cdk.FaultMonitorProps
import io.kopipes.cdk.KopipesStack
import io.kopipes.cdk.ServiceProps
import io.kopipes.cdk.Stage
import software.amazon.awscdk.services.lambda.Code
import software.constructs.Construct

/**
 * Collects `fault` events: stored in S3 for resubmission (docs/FaultResubmission.md) and announced on a FIFO topic,
 * by sut's own transform. Locally a queue subscribes to the topic, so the integration tests can see the
 * notifications.
 */
class EventFaultMonitorStack(scope: Construct, serviceProps: ServiceProps) : KopipesStack(scope, serviceProps) {
    val monitor = FaultMonitor(
        this, "Monitor",
        FaultMonitorProps(
            transformCode = Code.fromAsset("../app/build/libs/sut-event-fault-monitor.jar"),
            transformHandler = "org.myorg.sut.Transform::handleRequest",
            verificationQueue = stage == Stage.LOCAL,
        ),
    )
}
