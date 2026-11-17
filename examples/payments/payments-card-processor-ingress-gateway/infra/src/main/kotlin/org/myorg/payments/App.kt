package org.myorg.payments

import io.kopipes.cdk.ServiceProps
import io.kopipes.cdk.Stage
import software.amazon.awscdk.App

/** One stack per stage: `payments-card-processor-ingress-gateway-local` for LocalStack, `payments-card-processor-ingress-gateway-dev` for AWS. */
fun main() {
    val app = App()
    val props = ServiceProps(org = "myorg", subsys = "payments", service = "payments-card-processor-ingress-gateway", stage = Stage.DEV, region = "us-east-1")
    CardProcessorIngressStack(app, props)
    CardProcessorIngressStack(app, props.copy(stage = Stage.LOCAL))
    app.synth()
}
