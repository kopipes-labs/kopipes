package org.myorg.sut

import software.amazon.awscdk.Duration
import software.amazon.awscdk.services.events.CfnRule
import software.amazon.awscdk.services.events.EventBus
import software.amazon.awscdk.services.iam.*
import software.amazon.awscdk.services.kinesis.Stream
import software.amazon.awscdk.services.kinesis.StreamEncryption
import software.amazon.awscdk.services.lambda.Function
import software.amazon.awscdk.services.lambda.StartingPosition
import software.amazon.awscdk.services.lambda.eventsources.KinesisEventSource

fun RegionalHealthCheckStack.newStream1(): Stream {
    return Stream.Builder.create(this, "Stream1")
        .streamName("${service}-${stage}-s1")
        .retentionPeriod(Duration.hours(24))
        .shardCount(shardCount())
        .encryption(StreamEncryption.MANAGED)
        .build()
}

fun RegionalHealthCheckStack.newKinesisBusRole(stream1: Stream): Role =
    Role.Builder.create(this, "BusRole")
        .roleName("${service}-${stage}-${regionName}-kinesis-role")
        .assumedBy(ServicePrincipal("events.amazonaws.com"))
        .inlinePolicies(
            mapOf(
                "${service}-${stage}-internal" to PolicyDocument.Builder.create()
                    .statements(
                        listOf(
                            PolicyStatement.Builder.create()
                                .effect(Effect.ALLOW)
                                .actions(
                                    listOf(
                                        "kinesis:PutRecord",
                                        "kinesis:PutRecords",
                                    )
                                )
                                .resources(listOf(stream1.streamArn))
                                .build()
                        )
                    )
                    .build()
            )
        )
        .build()

fun RegionalHealthCheckStack.publishToKinesis(
    bus: EventBus,
    stream1: Stream,
) {
    val busRole = newKinesisBusRole(stream1)
    CfnRule.Builder.create(this, "Stream1EventRule")
        .eventBusName(bus.eventBusName)
        .eventPattern(
            mapOf(
                "detail" to mapOf(
                    "type" to listOf(
                        mapOf("anything-but" to "fault")
                    )
                )
            )
        )
        .state("ENABLED")
        .targets(
            listOf(
                CfnRule.TargetProperty.builder()
                    .id("Stream1")
                    .arn(stream1.streamArn)
                    .roleArn(busRole.roleArn)
                    .kinesisParameters(
                        CfnRule.KinesisParametersProperty.builder()
                            .partitionKeyPath("$.detail.partitionKey")
                            .build()
                    )
                    .inputPath("$.detail")
                    .build()
            )
        )
        .build()
}

fun RegionalHealthCheckStack.kinesisTraceFilterPatterns(): List<Map<String, Any>> =
    listOf(
        mapOf(
            "data" to mapOf(
                "type" to listOf(
                    mapOf("prefix" to "trace-")
                )
            )
        )
    )

fun RegionalHealthCheckStack.consumeFromKinesis(listener: Function, stream: Stream) {

    listener.addEventSource(
        KinesisEventSource.Builder.create(stream)
            .startingPosition(StartingPosition.LATEST)
            .batchSize(100) // up to 10,000; default is 100
            .maxBatchingWindow(Duration.seconds(1)) // up to 5 min
            .bisectBatchOnError(true)
            .retryAttempts(3)
            .parallelizationFactor(1) // up to 10 per shard
            // .onFailure(SqsDlq(dlq))
            // .consumer(consumer) // uncomment if using enhanced fan-out
            // .reportBatchItemFailures(true) // requires special response object in handler
            .build()
    )

}