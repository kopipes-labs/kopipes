package org.myorg.payments

import io.kopipes.cdk.ServiceProps
import io.kopipes.cdk.Stage
import software.amazon.awscdk.App

/** One stack per stage: `notifications-event-hub-local` for LocalStack, `notifications-event-hub-dev` for AWS. */
fun main() {
    val app = App()
    val props = ServiceProps(org = "myorg", subsys = "notifications", service = "notifications-event-hub", stage = Stage.DEV, region = "us-east-1")
    NotificationsEventHubStack(app, props)
    NotificationsEventHubStack(app, props.copy(stage = Stage.LOCAL))
    app.synth()
}
