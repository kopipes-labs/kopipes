package org.myorg.sut

import io.kopipes.cdk.ServiceProps
import io.kopipes.cdk.Stage
import software.amazon.awscdk.App

fun main() {
    val app = App()

    println("Working Directory = " + System.getProperty("user.dir"))
    println("Starting CDK Event Fault Monitor App")
    val serviceProps = ServiceProps(
        org = "myorg",
        subsys = "sut",
        service = "sut-event-fault-monitor",
        stage = Stage.DEV,
        region = "us-east-1")
    EventFaultMonitorStack(app, serviceProps)
    EventFaultMonitorStack(app, serviceProps.copy(stage = Stage.LOCAL))
    app.synth()
    println("Finished CDK App")
}
