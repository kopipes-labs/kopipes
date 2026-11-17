package io.kopipes.cdk

import io.kotest.matchers.maps.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import software.amazon.awscdk.assertions.Match

class KopipesFunctionTest {

    @Test
    fun `is named after the service and sets the env vars the runtime reads`() {
        // Arrange
        val stack = testStack("payments-bff")

        // Act
        KopipesFunction(stack, "listener", KopipesFunctionProps(code = testCode, handler = "org.myorg.Listener"))

        // Assert
        val function = stack.template().findResources("AWS::Lambda::Function").values.single()
        @Suppress("UNCHECKED_CAST")
        val properties = function["Properties"] as Map<String, Any>
        properties["FunctionName"] shouldBe "payments-bff-local-listener"
        properties["Runtime"] shouldBe "java21"
        @Suppress("UNCHECKED_CAST")
        val env = (properties["Environment"] as Map<String, Map<String, Any>>)["Variables"]!!
        env.shouldContain(EnvVars.STAGE, "local")
        env.shouldContain(EnvVars.SERVICE, "payments-bff")
        env.shouldContain(EnvVars.BUS_NAME, "payments-event-hub-local-bus")
        env.shouldContain(EnvVars.BUS_SRC, "payments-bff")
    }

    @Test
    fun `a function that doesn't publish gets no bus`() {
        // Arrange
        val stack = testStack()

        // Act
        KopipesFunction(stack, "fn", KopipesFunctionProps(code = testCode, handler = "h", publishes = false))

        // Assert
        stack.template().hasResourceProperties(
            "AWS::Lambda::Function",
            mapOf("Environment" to mapOf("Variables" to Match.objectLike(mapOf(EnvVars.BUS_NAME to Match.absent())))),
        )
    }

    @Test
    fun `an egress gateway publishes external events`() {
        // Arrange
        val stack = testStack("payments-egress-gateway")

        // Act
        KopipesFunction(
            stack, "listener",
            KopipesFunctionProps(code = testCode, handler = "h", source = EnvVars.EXTERNAL_SOURCE),
        )

        // Assert
        stack.template().hasResourceProperties(
            "AWS::Lambda::Function",
            mapOf("Environment" to mapOf("Variables" to Match.objectLike(mapOf(EnvVars.BUS_SRC to "external")))),
        )
    }

    @Test
    fun `listens to the default channel from the oldest record, bisecting failed batches`() {
        // Arrange
        val stack = testStack()

        // Act
        KopipesFunction(stack, "listener", KopipesFunctionProps(code = testCode, handler = "h")).listenTo()

        // Assert
        stack.template().hasResourceProperties(
            "AWS::Lambda::EventSourceMapping",
            mapOf(
                "StartingPosition" to "TRIM_HORIZON",
                "BisectBatchOnFunctionError" to true,
                "MaximumRetryAttempts" to 3,
            ),
        )
        stack.template().toJSON().toString().contains("stream/payments-event-hub-local-s1") shouldBe true
    }

    @Test
    fun `uses a table under the env var the runtime reads, and is triggered by its stream`() {
        // Arrange
        val stack = testStack("payments-control-service")
        val table = EventsTable(stack, "Events")

        // Act
        KopipesFunction(stack, "trigger", KopipesFunctionProps(code = testCode, handler = "h"))
            .uses(table)
            .triggeredBy(table)
            .retryStream()

        // Assert
        val template = stack.template()
        template.hasResourceProperties(
            "AWS::Lambda::Function",
            mapOf(
                "Environment" to mapOf(
                    "Variables" to Match.objectLike(
                        mapOf(
                            EnvVars.EVENT_TABLE_NAME to Match.anyValue(),
                            EnvVars.STREAM_RETRY_ENABLED to "true",
                        )
                    )
                )
            ),
        )
        template.hasResourceProperties(
            "AWS::Lambda::EventSourceMapping",
            mapOf("BatchSize" to 5, "BisectBatchOnFunctionError" to true),
        )
    }
}

class KopipesFunctionSourcesTest {

    @Test
    fun `reads a queue, and any table's stream`() {
        // Arrange
        val stack = testStack("payments-core-checking-egress-gateway")
        val queue = software.amazon.awscdk.services.sqs.Queue.Builder.create(stack, "Queue").build()
        val ledger = software.amazon.awscdk.services.dynamodb.TableV2.Builder.create(stack, "Ledger")
            .partitionKey(software.amazon.awscdk.services.dynamodb.Attribute.builder().name("ENTRY_ID")
                .type(software.amazon.awscdk.services.dynamodb.AttributeType.STRING).build())
            .dynamoStream(software.amazon.awscdk.services.dynamodb.StreamViewType.NEW_AND_OLD_IMAGES)
            .build()

        // Act
        KopipesFunction(stack, "simulator", KopipesFunctionProps(code = testCode, handler = "h")).receives(queue)
        KopipesFunction(stack, "ledger", KopipesFunctionProps(code = testCode, handler = "h"))
            .triggeredBy(ledger, retryAttempts = -1)
            .uses(ledger, "LEDGER_TABLE_NAME")

        // Assert
        val template = stack.template()
        template.resourceCountIs("AWS::Lambda::EventSourceMapping", 2)
        template.hasResourceProperties("AWS::Lambda::EventSourceMapping", mapOf("MaximumRetryAttempts" to -1))
        template.hasResourceProperties(
            "AWS::Lambda::Function",
            mapOf("Environment" to mapOf("Variables" to Match.objectLike(mapOf("LEDGER_TABLE_NAME" to Match.anyValue())))),
        )
    }
}

class EventsTableTest {

    @Test
    fun `has the keys, ttl, stream and index kopipes uses`() {
        // Arrange
        val stack = testStack("payments-control-service")

        // Act
        EventsTable(stack, "Events")

        // Assert
        stack.template().hasResourceProperties(
            "AWS::DynamoDB::GlobalTable",
            mapOf(
                "TableName" to "payments-control-service-local-events",
                "KeySchema" to listOf(
                    mapOf("AttributeName" to "pk", "KeyType" to "HASH"),
                    mapOf("AttributeName" to "sk", "KeyType" to "RANGE"),
                ),
                "TimeToLiveSpecification" to mapOf("AttributeName" to "ttl", "Enabled" to true),
                "StreamSpecification" to mapOf("StreamViewType" to "NEW_AND_OLD_IMAGES"),
                "GlobalSecondaryIndexes" to Match.arrayWith(listOf(Match.objectLike(mapOf("IndexName" to "gsi1")))),
            ),
        )
    }

    @Test
    fun `can have no stream`() {
        // Arrange
        val stack = testStack()

        // Act
        EventsTable(stack, "Views", EventsTableProps(suffix = "views", stream = false))

        // Assert
        stack.template().hasResourceProperties(
            "AWS::DynamoDB::GlobalTable",
            mapOf("TableName" to "payments-bff-local-views", "StreamSpecification" to Match.absent()),
        )
    }
}

class EventHubTest {

    @Test
    fun `routes internal events except faults to the default channel, by partition key`() {
        // Arrange
        val stack = testStack("payments-event-hub")

        // Act
        EventHub(stack, "Hub")

        // Assert
        val template = stack.template()
        template.hasResourceProperties("AWS::Events::EventBus", mapOf("Name" to "payments-event-hub-local-bus"))
        template.hasResourceProperties("AWS::Kinesis::Stream", mapOf("Name" to "payments-event-hub-local-s1"))
        template.hasResourceProperties(
            "AWS::Events::Rule",
            mapOf(
                "Name" to "payments-event-hub-local-bus-s1-rule",
                "EventPattern" to mapOf(
                    "source" to listOf(mapOf("anything-but" to listOf("external"))),
                    "detail-type" to listOf(mapOf("anything-but" to listOf("fault"))),
                ),
                "Targets" to listOf(
                    Match.objectLike(
                        mapOf(
                            "InputPath" to "$.detail",
                            "KinesisParameters" to mapOf("PartitionKeyPath" to "$.detail.partitionKey"),
                        )
                    )
                ),
            ),
        )
    }

    @Test
    fun `archive and logs are optional`() {
        // Arrange
        val stack = testStack("payments-event-hub")

        // Act
        EventHub(stack, "Hub", EventHubProps(archiveRetention = null, logEvents = false))

        // Assert
        val template = stack.template()
        template.resourceCountIs("AWS::Events::Archive", 0)
        template.resourceCountIs("AWS::Logs::LogGroup", 0)
        template.resourceCountIs("AWS::Events::Rule", 1)
    }
}

class EventForwardingTest {

    @Test
    fun `forwards external events with the prefix to the other subsystem's bus`() {
        // Arrange
        val stack = testStack("payments-egress-gateway")

        // Act
        EventForwarding(stack, "notifications", "PAYMENTS.", "notifications-event-hub-local-bus")

        // Assert
        stack.template().hasResourceProperties(
            "AWS::Events::Rule",
            mapOf(
                "EventBusName" to "payments-event-hub-local-bus",
                "EventPattern" to mapOf(
                    "source" to listOf("external"),
                    "detail-type" to listOf(mapOf("prefix" to "PAYMENTS.")),
                ),
            ),
        )
        stack.template().toJSON().toString().contains("notifications-event-hub-local-bus") shouldBe true
    }
}

class EventLakeTest {

    @Test
    fun `stores every event but faults in a bucket`() {
        // Arrange
        val stack = testStack("payments-event-lake")

        // Act
        EventLake(stack, "Lake")

        // Assert
        val template = stack.template()
        template.hasResourceProperties(
            "AWS::S3::Bucket",
            mapOf("BucketName" to "myorg-payments-event-lake-local-us-east-1"),
        )
        template.hasResourceProperties(
            "AWS::Events::Rule",
            mapOf(
                "EventBusName" to "payments-event-hub-local-bus",
                "EventPattern" to mapOf("detail-type" to listOf(mapOf("anything-but" to listOf("fault")))),
            ),
        )
        template.hasResourceProperties(
            "AWS::KinesisFirehose::DeliveryStream",
            mapOf(
                "ExtendedS3DestinationConfiguration" to Match.objectLike(
                    mapOf("Prefix" to "us-east-1/", "CompressionFormat" to "GZIP")
                )
            ),
        )
    }
}

class FaultMonitorTest {

    @Test
    fun `stores faults through the transform and notifies a fifo topic`() {
        // Arrange
        val stack = testStack("payments-event-fault-monitor")

        // Act
        FaultMonitor(
            stack, "Monitor",
            FaultMonitorProps(transformCode = testCode, transformHandler = "h", verificationQueue = true),
        )

        // Assert
        val template = stack.template()
        template.hasResourceProperties(
            "AWS::Events::Rule",
            mapOf("EventPattern" to mapOf("detail-type" to listOf("fault"))),
        )
        template.hasResourceProperties(
            "AWS::SNS::Topic",
            mapOf("TopicName" to "payments-event-fault-monitor-local.fifo", "FifoTopic" to true),
        )
        template.hasResourceProperties(
            "AWS::SQS::Queue",
            mapOf("QueueName" to "payments-event-fault-monitor-local-notification-verification.fifo"),
        )
        template.hasResourceProperties(
            "AWS::Lambda::Function",
            mapOf(
                "FunctionName" to "payments-event-fault-monitor-local-transform",
                "Environment" to mapOf("Variables" to Match.objectLike(mapOf("TOPIC_ARN" to Match.anyValue()))),
            ),
        )
    }
}

class RestApiTest {

    @Test
    fun `proxies every request to the handler`() {
        // Arrange
        val stack = testStack()
        val handler = KopipesFunction(stack, "restapi", KopipesFunctionProps(code = testCode, handler = "h"))

        // Act
        RestApi(stack, "api", handler)

        // Assert
        val template = stack.template()
        template.hasResourceProperties("AWS::ApiGateway::RestApi", mapOf("Name" to "payments-bff-local-api"))
        template.hasResourceProperties("AWS::ApiGateway::Resource", mapOf("PathPart" to "{proxy+}"))
        template.findOutputs("*").keys.any { it.startsWith("api") } shouldBe true
    }
}

class KopipesStackTest {

    @Test
    fun `names resources after the service and finds the hub by name`() {
        // Arrange
        val stack = testStack("payments-bff")

        // Act
        val bus = stack.importBus()
        val again = stack.importBus()

        // Assert
        stack.stackName shouldBe "payments-bff-local"
        stack.name("views") shouldBe "payments-bff-local-views"
        stack.globalName() shouldBe "myorg-payments-bff-local-us-east-1"
        stack.hub.busName shouldBe "payments-event-hub-local-bus"
        (again === bus) shouldBe true
        stack.hub.channelName(Channels.DEFAULT) shouldBe "payments-event-hub-local-s1"
    }
}
