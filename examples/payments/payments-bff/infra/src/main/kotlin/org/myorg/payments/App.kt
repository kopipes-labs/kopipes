package org.myorg.payments

import io.kopipes.cdk.ServiceProps
import io.kopipes.cdk.Stage
import software.amazon.awscdk.App

/** One stack per stage: `payments-bff-local` for LocalStack, `payments-bff-dev` for AWS. */
fun main() {
    val app = App()
    val props = ServiceProps(org = "myorg", subsys = "payments", service = "payments-bff", stage = Stage.DEV, region = "us-east-1")
    BffStack(app, props)
    BffStack(app, props.copy(stage = Stage.LOCAL))
    app.synth()
}
