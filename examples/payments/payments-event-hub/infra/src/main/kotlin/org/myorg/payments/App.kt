package org.myorg.payments

import io.kopipes.cdk.ServiceProps
import io.kopipes.cdk.Stage
import software.amazon.awscdk.App

/** One stack per stage: `payments-event-hub-local` for LocalStack, `payments-event-hub-dev` for AWS. */
fun main() {
    val app = App()
    val props = ServiceProps(org = "myorg", subsys = "payments", service = "payments-event-hub", stage = Stage.DEV, region = "us-east-1")
    EventHubStack(app, props)
    EventHubStack(app, props.copy(stage = Stage.LOCAL))
    app.synth()
}
