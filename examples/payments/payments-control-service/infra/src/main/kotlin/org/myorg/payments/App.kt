package org.myorg.payments

import io.kopipes.cdk.ServiceProps
import io.kopipes.cdk.Stage
import software.amazon.awscdk.App

/** One stack per stage: `payments-control-service-local` for LocalStack, `payments-control-service-dev` for AWS. */
fun main() {
    val app = App()
    val props = ServiceProps(org = "myorg", subsys = "payments", service = "payments-control-service", stage = Stage.DEV, region = "us-east-1")
    ControlServiceStack(app, props)
    ControlServiceStack(app, props.copy(stage = Stage.LOCAL))
    app.synth()
}
