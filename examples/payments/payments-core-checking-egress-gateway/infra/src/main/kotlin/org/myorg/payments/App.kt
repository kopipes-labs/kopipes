package org.myorg.payments

import io.kopipes.cdk.ServiceProps
import io.kopipes.cdk.Stage
import software.amazon.awscdk.App

/** One stack per stage: `payments-core-checking-egress-gateway-local` for LocalStack, `payments-core-checking-egress-gateway-dev` for AWS. */
fun main() {
    val app = App()
    val props = ServiceProps(org = "myorg", subsys = "payments", service = "payments-core-checking-egress-gateway", stage = Stage.DEV, region = "us-east-1")
    CoreCheckingEgressStack(app, props)
    CoreCheckingEgressStack(app, props.copy(stage = Stage.LOCAL))
    app.synth()
}
