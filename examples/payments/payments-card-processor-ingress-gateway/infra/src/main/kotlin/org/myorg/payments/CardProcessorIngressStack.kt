package org.myorg.payments

import io.kopipes.cdk.EnvVars
import io.kopipes.cdk.EventsTable
import io.kopipes.cdk.EventsTableProps
import io.kopipes.cdk.KopipesFunction
import io.kopipes.cdk.KopipesFunctionProps
import io.kopipes.cdk.KopipesStack
import io.kopipes.cdk.RestApi
import io.kopipes.cdk.ServiceProps
import io.kopipes.cdk.Stage
import software.amazon.awscdk.CfnOutput
import software.amazon.awscdk.RemovalPolicy
import software.amazon.awscdk.services.lambda.Code
import software.amazon.awscdk.services.ssm.StringParameter
import software.constructs.Construct

/**
 * Hears the card processor through its webhook.
 *
 *     default channel ─► listener ─► payments table           remembers each card DEPOSIT_REQUESTED's payment
 *     card processor ─POST /webhooks/card-processor─► webhook ─► bus      FUNDS_DEPOSITED, DEPOSIT_FAILED
 *
 * Publishes the webhook URL and the signing secret as parameters: the processor is configured with them, and so is
 * the egress gateway's simulator. The secret comes from the `cardWebhookSecret` context; a real deployment keeps it in
 * a secrets store and reads it at runtime, an env var keeps the example small.
 */
class CardProcessorIngressStack(scope: Construct, props: ServiceProps) : KopipesStack(scope, props) {

    private val local = stage == Stage.LOCAL
    private val jar = Code.fromAsset("../app/build/libs/payments-card-processor-ingress-gateway.jar")
    private val secret = node.tryGetContext("cardWebhookSecret")?.toString() ?: "demo-signing-secret-$stage"

    val payments = EventsTable(
        this, "Payments",
        EventsTableProps(suffix = "payments", stream = false, removalPolicy = if (local) RemovalPolicy.DESTROY else RemovalPolicy.RETAIN),
    )

    val listener = KopipesFunction(this, "listener", KopipesFunctionProps(jar, "org.myorg.payments.Listener::handleRequest"))
        .listenTo()
        .uses(payments, EnvVars.ENTITY_TABLE_NAME)

    /** Stream retry: a webhook it can't handle yet is answered `5xx`, so the processor sends it again. */
    val webhook = KopipesFunction(
        this, "webhook",
        KopipesFunctionProps(jar, "org.myorg.payments.Webhook::handleRequest", environment = mapOf("WEBHOOK_SECRET" to secret)),
    ).uses(payments, EnvVars.ENTITY_TABLE_NAME).retryStream()

    val api = RestApi(this, "api", webhook)

    init {
        // From inside LocalStack, its Lambdas reach API Gateway by LocalStack's own host name.
        val url = if (local) {
            "http://localhost.localstack.cloud:4566/restapis/${api.api.restApiId}/$stage/_user_request_/webhooks/card-processor"
        } else {
            api.api.urlForPath("/webhooks/card-processor")
        }
        StringParameter.Builder.create(this, "WebhookUrl")
            .parameterName(Partners.cardWebhookUrlParameter(stage))
            .stringValue(url)
            .build()
        StringParameter.Builder.create(this, "WebhookSecret")
            .parameterName(Partners.cardWebhookSecretParameter(stage))
            .stringValue(secret)
            .build()
        CfnOutput.Builder.create(this, "WebhookUrlOutput").value(url).build()
    }
}
