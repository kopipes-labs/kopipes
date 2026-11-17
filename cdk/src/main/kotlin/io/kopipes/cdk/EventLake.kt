package io.kopipes.cdk

import software.amazon.awscdk.CfnOutput
import software.amazon.awscdk.Duration
import software.amazon.awscdk.RemovalPolicy
import software.amazon.awscdk.Size
import software.amazon.awscdk.services.events.EventPattern
import software.amazon.awscdk.services.events.Match
import software.amazon.awscdk.services.events.Rule
import software.amazon.awscdk.services.events.targets.FirehoseDeliveryStream
import software.amazon.awscdk.services.kinesisfirehose.Compression
import software.amazon.awscdk.services.kinesisfirehose.DeliveryStream
import software.amazon.awscdk.services.kinesisfirehose.EnableLogging
import software.amazon.awscdk.services.kinesisfirehose.S3Bucket
import software.amazon.awscdk.services.kinesisfirehose.S3BucketProps
import software.amazon.awscdk.services.logs.LogGroup
import software.amazon.awscdk.services.logs.RetentionDays
import software.amazon.awscdk.services.s3.BlockPublicAccess
import software.amazon.awscdk.services.s3.Bucket
import software.amazon.awscdk.services.s3.BucketEncryption
import software.amazon.awscdk.services.s3.LifecycleRule
import software.constructs.Construct

/**
 * @param expiration how long the lake keeps events; `null` keeps them forever.
 * @param bufferingInterval how long Firehose collects events before writing an object.
 */
data class EventLakeProps(
    val expiration: Duration? = Duration.days(92),
    val bufferingInterval: Duration = Duration.seconds(60),
    val logRetention: RetentionDays = RetentionDays.THREE_DAYS,
    val removalPolicy: RemovalPolicy = RemovalPolicy.RETAIN,
)

/**
 * The event lake: every event on the subsystem's bus except faults, kept in S3 for audit and replay (see
 * docs/EventLake.md). Objects are gzipped EventBridge events, under `<region>/` and Firehose's date prefix, in a
 * bucket named `<org>-<service>-<stage>-<region>`; the replay tool reads them from there.
 */
class EventLake(stack: KopipesStack, id: String, props: EventLakeProps = EventLakeProps()) : Construct(stack, id) {

    val bucket: Bucket = Bucket.Builder.create(this, "Bucket")
        .bucketName(stack.globalName())
        .encryption(BucketEncryption.S3_MANAGED)
        .blockPublicAccess(BlockPublicAccess.BLOCK_ALL)
        .versioned(true)
        .apply {
            props.expiration?.let { lifecycleRules(listOf(LifecycleRule.builder().expiration(it).build())) }
        }
        .removalPolicy(props.removalPolicy)
        .build()

    val deliveryStream: DeliveryStream = DeliveryStream.Builder.create(this, "DeliveryStream")
        .deliveryStreamName(stack.name("DeliveryStream"))
        .destination(
            S3Bucket(
                bucket,
                S3BucketProps.builder()
                    .dataOutputPrefix("${stack.regionName}/")
                    .bufferingInterval(props.bufferingInterval)
                    .bufferingSize(Size.mebibytes(50))
                    .compression(Compression.GZIP)
                    .loggingConfig(
                        EnableLogging(
                            LogGroup.Builder.create(this, "LogGroup")
                                .logGroupName("/aws/kinesisfirehose/${stack.name("DeliveryStream")}")
                                .retention(props.logRetention)
                                .removalPolicy(RemovalPolicy.DESTROY)
                                .build()
                        )
                    )
                    .build()
            )
        )
        .build()

    init {
        Rule.Builder.create(this, "Rule")
            .ruleName(stack.name("rule"))
            .eventBus(stack.importBus())
            .eventPattern(EventPattern.builder().detailType(Match.anythingBut(EventHub.FAULT)).build())
            .targets(listOf(FirehoseDeliveryStream.Builder.create(deliveryStream).build()))
            .build()

        CfnOutput.Builder.create(this, "BucketName").value(bucket.bucketName).build()
    }
}
