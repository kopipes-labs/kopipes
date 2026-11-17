package org.myorg.payments

import io.kopipes.cdk.ServiceProps
import io.kopipes.cdk.Stage
import software.amazon.awscdk.App

/** One stack per stage: `payments-core-checking-ingress-gateway-local` for LocalStack, `payments-core-checking-ingress-gateway-dev` for AWS. */
fun main() {
    val app = App()
    val props = ServiceProps(org = "myorg", subsys = "payments", service = "payments-core-checking-ingress-gateway", stage = Stage.DEV, region = "us-east-1")
    CoreCheckingIngressStack(app, props)
    CoreCheckingIngressStack(app, props.copy(stage = Stage.LOCAL))
    app.synth()
}
