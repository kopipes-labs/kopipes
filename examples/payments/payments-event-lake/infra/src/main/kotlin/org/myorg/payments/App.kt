package org.myorg.payments

import io.kopipes.cdk.ServiceProps
import io.kopipes.cdk.Stage
import software.amazon.awscdk.App

/** One stack per stage: `payments-event-lake-local` for LocalStack, `payments-event-lake-dev` for AWS. */
fun main() {
    val app = App()
    val props = ServiceProps(org = "myorg", subsys = "payments", service = "payments-event-lake", stage = Stage.DEV, region = "us-east-1")
    EventLakeStack(app, props)
    EventLakeStack(app, props.copy(stage = Stage.LOCAL))
    app.synth()
}
