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
import software.amazon.awscdk.RemovalPolicy
import software.amazon.awscdk.services.dynamodb.Attribute
import software.amazon.awscdk.services.dynamodb.AttributeType
import software.amazon.awscdk.services.dynamodb.GlobalSecondaryIndexPropsV2
import software.amazon.awscdk.services.dynamodb.ProjectionType
import software.amazon.awscdk.services.lambda.Code
import software.constructs.Construct

/**
 * The customer's way in: accepts payments, and shows their progress as facts arrive.
 *
 *     customer ─► REST API ─► views table ─► (stream) ─► trigger ─► bus      PAYMENT_SUBMITTED
 *     default channel ─► listener ─► views table                             what each payment has heard
 */
class BffStack(scope: Construct, props: ServiceProps) : KopipesStack(scope, props) {

    private val jar = Code.fromAsset("../app/build/libs/payments-bff.jar")

    val views = EventsTable(
        this, "Views",
        EventsTableProps(
            suffix = "views",
            removalPolicy = if (stage == Stage.LOCAL) RemovalPolicy.DESTROY else RemovalPolicy.RETAIN,
        ),
    ).apply {
        // GET /payments: a customer's payments, newest first. Only accepted payments have a customerId.
        table.addGlobalSecondaryIndex(
            GlobalSecondaryIndexPropsV2.builder()
                .indexName("byCustomer")
                .partitionKey(Attribute.builder().name("customerId").type(AttributeType.STRING).build())
                .sortKey(Attribute.builder().name("submittedAt").type(AttributeType.NUMBER).build())
                .projectionType(ProjectionType.ALL)
                .build()
        )
    }

    val restApiFunction = KopipesFunction(this, "restapi", KopipesFunctionProps(jar, "org.myorg.payments.RestApi::handleRequest"))
        .uses(views, EnvVars.ENTITY_TABLE_NAME)

    val api = RestApi(this, "api", restApiFunction)

    val listener = KopipesFunction(this, "listener", KopipesFunctionProps(jar, "org.myorg.payments.Listener::handleRequest"))
        .listenTo()
        .uses(views, EnvVars.ENTITY_TABLE_NAME)

    val trigger = KopipesFunction(this, "trigger", KopipesFunctionProps(jar, "org.myorg.payments.Trigger::handleRequest"))
        .triggeredBy(views)
}
