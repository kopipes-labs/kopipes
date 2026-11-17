package org.myorg.sut

import io.kopipes.cdk.ServiceProps
import io.kopipes.cdk.Stage
import software.amazon.awscdk.App

fun main() {
    val app = App()

    println("Working Directory = " + System.getProperty("user.dir"))
    println("Starting CDK App")
    val serviceProps = ServiceProps(
        org = "myorg",
        subsys = "sut",
        service = "sut-control-service",
        stage = Stage.DEV,
        region = "us-east-1")
    ControlStack(app, serviceProps)
    ControlStack(app, serviceProps.copy(stage = Stage.LOCAL))
    app.synth()
    println("Finished CDK App")
}
