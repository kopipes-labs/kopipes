package org.myorg.payments

import io.kopipes.cdk.FaultMonitor
import io.kopipes.cdk.FaultMonitorProps
import io.kopipes.cdk.KopipesStack
import io.kopipes.cdk.ServiceProps
import io.kopipes.cdk.Stage
import software.amazon.awscdk.RemovalPolicy
import software.amazon.awscdk.services.lambda.Code
import software.constructs.Construct

/**
 * Collects `fault` events for the operator: stored in S3 for resubmission (docs/FaultResubmission.md) and announced
 * on a FIFO topic. Locally a queue subscribes to the topic, so the integration tests can see the notifications.
 */
class FaultMonitorStack(scope: Construct, props: ServiceProps) : KopipesStack(scope, props) {
    val monitor = FaultMonitor(
        this, "Monitor",
        FaultMonitorProps(
            transformCode = Code.fromAsset("../app/build/libs/payments-event-fault-monitor.jar"),
            transformHandler = "io.kopipes.aws.faults.FaultMonitorTransform::handleRequest",
            verificationQueue = stage == Stage.LOCAL,
            removalPolicy = if (stage == Stage.LOCAL) RemovalPolicy.DESTROY else RemovalPolicy.RETAIN,
        ),
    )
}
