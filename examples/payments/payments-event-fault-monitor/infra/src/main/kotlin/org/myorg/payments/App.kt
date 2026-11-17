package org.myorg.payments

import io.kopipes.cdk.ServiceProps
import io.kopipes.cdk.Stage
import software.amazon.awscdk.App

/** One stack per stage: `payments-event-fault-monitor-local` for LocalStack, `payments-event-fault-monitor-dev` for AWS. */
fun main() {
    val app = App()
    val props = ServiceProps(org = "myorg", subsys = "payments", service = "payments-event-fault-monitor", stage = Stage.DEV, region = "us-east-1")
    FaultMonitorStack(app, props)
    FaultMonitorStack(app, props.copy(stage = Stage.LOCAL))
    app.synth()
}
