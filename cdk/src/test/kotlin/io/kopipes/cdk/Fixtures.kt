package io.kopipes.cdk

import software.amazon.awscdk.App
import software.amazon.awscdk.assertions.Template
import software.amazon.awscdk.services.lambda.Code
import java.nio.file.Files

internal fun serviceProps(service: String = "payments-bff") = ServiceProps(
    org = "myorg",
    subsys = "payments",
    service = service,
    stage = Stage.LOCAL,
    region = "us-east-1",
)

internal fun testStack(service: String = "payments-bff") = KopipesStack(App(), serviceProps(service))

private val assetDir by lazy {
    Files.createTempDirectory("kopipes-cdk-test").also { Files.writeString(it.resolve("handler.txt"), "placeholder") }
}

/**
 * An asset directory standing in for a service's jar: synthesizing only needs the path to exist. A new [Code] each
 * time, since an asset belongs to one stack.
 */
internal val testCode: Code get() = Code.fromAsset(assetDir.toString())

internal fun KopipesStack.template(): Template = Template.fromStack(this)
