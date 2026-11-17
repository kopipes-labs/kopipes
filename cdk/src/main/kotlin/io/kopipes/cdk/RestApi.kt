package io.kopipes.cdk

import software.amazon.awscdk.CfnOutput
import software.amazon.awscdk.services.apigateway.LambdaRestApi
import software.amazon.awscdk.services.apigateway.StageOptions
import software.constructs.Construct

/**
 * A REST API that sends every request to one function, which routes by method and path itself: a BFF's API, or an
 * ingress gateway's webhook. Named `<service>-<stage>-<id>`, with its URL as a stack output.
 */
class RestApi(stack: KopipesStack, id: String, handler: KopipesFunction) : Construct(stack, id) {

    val api: LambdaRestApi = LambdaRestApi.Builder.create(this, "Api")
        .restApiName(stack.name(id))
        .handler(handler.function)
        .proxy(true)
        .deployOptions(StageOptions.builder().stageName(stack.stage.id).build())
        .build()

    init {
        CfnOutput.Builder.create(this, "Url").value(api.url).build()
    }
}
