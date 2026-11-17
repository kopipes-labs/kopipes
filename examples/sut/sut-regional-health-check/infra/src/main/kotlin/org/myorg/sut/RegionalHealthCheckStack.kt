package org.myorg.sut

import io.kopipes.cdk.KopipesFunction
import io.kopipes.cdk.KopipesFunctionProps
import io.kopipes.cdk.KopipesStack
import io.kopipes.cdk.ServiceProps
import software.amazon.awscdk.CfnCondition
import software.amazon.awscdk.Fn
import software.amazon.awscdk.services.apigateway.CfnApiKey
import software.amazon.awscdk.services.events.EventBus
import software.amazon.awscdk.services.kinesis.Stream
import software.amazon.awscdk.services.lambda.Code
import software.amazon.awscdk.services.lambda.Function
import software.amazon.awscdk.services.s3.Bucket
import software.amazon.awscdk.services.sns.Topic
import software.amazon.awscdk.services.sqs.Queue
import software.constructs.Construct

/*
Tracer flow diagram:

Check Health API
    -> Dynamo DB Table
        -> Dynamo DB Stream
            -> Dynamo DB Trigger Lambda
                -> S3 bucket
                    -> SNS Topic
                        -> SQS Trigger Queue
                            -> S3 Trigger Lambda
                                -> Event Bus
                                    -> Kinesis Stream 1
                                        -> Kinesis Trigger Lambda
                                            -> Dynamo DB Table.

*/

/**
 * A regional canary rather than a domain service: it has its own bus and stream, so a tracer crosses every kind of
 * resource in this region without depending on the subsystem's hub. It takes the shared base stack and functions from
 * the `cdk` module, and wires the rest itself (stream filters, SNS from S3, Synthetics).
 */
class RegionalHealthCheckStack(scope: Construct, serviceProps: ServiceProps) : KopipesStack(scope, serviceProps) {

    internal val topic: Topic = newTopic()
    internal val triggerQueue: Queue = newTriggerQueue()
    internal val bucket: Bucket = newBucket()
    internal val entitiesTable = newEntitiesTable()
    internal val bus: EventBus = newBus()
    internal val stream1: Stream = newStream1()

    private val jar = Code.fromAsset("../app/build/libs/sut-regional-health-check.jar")

    /** Its own bus, not the hub's: so the functions don't publish through [KopipesFunctionProps.publishes]. */
    private val runtimeEnvironment = mapOf(
        "ENTITY_TABLE_NAME" to entitiesTable.tableName,
        "BUS_NAME" to bus.eventBusName,
        "BUS_SRC" to "regional-health-check-trigger",
        "REGION" to regionName,
        "BUCKET_NAME" to bucket.bucketName,
    )

    init {
        // Check Health API  -> Dynamo DB Table
        val restApiLambda = newFunction("checkHealthApi", "org.myorg.sut.CheckHealthApi::handleRequest")
        grantAccessToTable(restApiLambda, entitiesTable)

        // Dynamo DB Table -> Dynamo DB Stream -> Dynamo DB Trigger Lambda -> S3 bucket
        val dynamoDbTriggerLambda = newFunction("dynamoDbTrigger", "org.myorg.sut.DynamoDbTrigger::handleRequest")
        consumeFromTable(dynamoDbTriggerLambda, entitiesTable)
        grantAccessToBucket(dynamoDbTriggerLambda, bucket)

        // S3 Bucket -> SNS Topic
        publishToTopic(bucket, topic)

        // SNS Topic -> SQS Trigger Queue
        publishToQueue(
            topic = topic,
            triggerQueue = triggerQueue,
        )

        // SQS Trigger Queue -> S3Trigger Lambda -> Event Bus
        val s3TriggerLambda = newFunction("s3trigger", "org.myorg.sut.S3Trigger::handleRequest")
        consumeFromQueue(s3TriggerLambda, triggerQueue)
        grantAccessToBus(s3TriggerLambda, bus)

        // Event Bus -> Kinesis Stream 1
        publishToKinesis(bus, stream1)

        // Event Bus -> CloudWatch Log Groups
        logToCloudWatch(bus)

        // Kinesis Stream 1 -> Kinesis Trigger Lambda -> Dynamo DB Table
        val kinesisTriggerLambda = newFunction("kinesisTrigger", "org.myorg.sut.KinesisTrigger::handleRequest")
        consumeFromKinesis(kinesisTriggerLambda, stream1)

        addApiGatewayApiKeys()
        createRegionalHealthCheck()
        createSyntheticsCanary(
            bucket = bucket,
            healthCheckEndpoint = healthCheckEndpoint(),
            apiKey = apiKey(),
        )

        // addKmsPermissions(s3TriggerLambda)

    }

    /** `sut-regional-health-check-<stage>-<id>`. */
    private fun newFunction(id: String, handler: String): Function =
        KopipesFunction(this, id, KopipesFunctionProps(jar, handler, environment = runtimeEnvironment, publishes = false))
            .function

    fun RegionalHealthCheckStack.isWestCondition(): CfnCondition =
        CfnCondition.Builder.create(this, "IsWest")
            .expression(Fn.conditionEquals(regionName, "us-west-2"))
            .build()

    fun RegionalHealthCheckStack.isEastCondition(): CfnCondition =
        CfnCondition.Builder.create(this, "IsEast")
            .expression(Fn.conditionEquals(regionName, "us-east-1"))
            .build()

    fun RegionalHealthCheckStack.addApiGatewayApiKeys(): List<CfnApiKey> =
        apiKeys().mapIndexed { index, apiKey ->
            CfnApiKey.Builder.create(this, "ApiGatewayApiKey${index + 1}")
                .name(apiKey.name)
                .value(apiKey.value)
                .enabled(true)
                .build()
        }
}