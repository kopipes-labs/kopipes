package io.kopipes.cdk

import software.amazon.awscdk.Duration
import software.amazon.awscdk.RemovalPolicy
import software.amazon.awscdk.Size
import software.amazon.awscdk.services.events.EventPattern
import software.amazon.awscdk.services.events.Rule
import software.amazon.awscdk.services.events.targets.FirehoseDeliveryStream
import software.amazon.awscdk.services.kinesisfirehose.Compression
import software.amazon.awscdk.services.kinesisfirehose.DeliveryStream
import software.amazon.awscdk.services.kinesisfirehose.EnableLogging
import software.amazon.awscdk.services.kinesisfirehose.LambdaFunctionProcessor
import software.amazon.awscdk.services.kinesisfirehose.S3Bucket
import software.amazon.awscdk.services.kinesisfirehose.S3BucketProps
import software.amazon.awscdk.services.lambda.Code
import software.amazon.awscdk.services.logs.LogGroup
import software.amazon.awscdk.services.logs.RetentionDays
import software.amazon.awscdk.services.s3.BlockPublicAccess
import software.amazon.awscdk.services.s3.Bucket
import software.amazon.awscdk.services.s3.BucketEncryption
import software.amazon.awscdk.services.sns.Topic
import software.amazon.awscdk.services.sns.subscriptions.SqsSubscription
import software.amazon.awscdk.services.sqs.Queue
import software.constructs.Construct

/**
 * @param transformCode the jar of the Firehose transform that stores each fault and notifies the topic.
 * @param transformHandler its handler; kopipes has one: `io.kopipes.aws.faults.FaultMonitorTransform::handleRequest`.
 * @param verificationQueue subscribes a FIFO queue to the topic, so integration tests can see the notifications.
 */
data class FaultMonitorProps(
    val transformCode: Code,
    val transformHandler: String,
    val verificationQueue: Boolean = false,
    val logRetention: RetentionDays = RetentionDays.ONE_MONTH,
    val removalPolicy: RemovalPolicy = RemovalPolicy.RETAIN,
)

/**
 * The fault monitor: every `fault` on the subsystem's bus goes through Firehose to S3, where the resubmit tool finds
 * it (see docs/FaultResubmission.md). On the way, a transform function notifies a FIFO topic; its deduplication id
 * holds back repeats of the same fault within the hour, so an outage doesn't flood the operator.
 */
class FaultMonitor(stack: KopipesStack, id: String, props: FaultMonitorProps) : Construct(stack, id) {

    val bucket: Bucket = Bucket.Builder.create(this, "Bucket")
        .bucketName(stack.globalName())
        .encryption(BucketEncryption.S3_MANAGED)
        .blockPublicAccess(BlockPublicAccess.BLOCK_ALL)
        .versioned(true)
        .removalPolicy(props.removalPolicy)
        .build()

    val topic: Topic = Topic.Builder.create(this, "Topic")
        .topicName("${stack.service}-${stack.stage}.fifo")
        .fifo(true)
        .build()

    val transform: KopipesFunction = KopipesFunction(
        stack,
        "transform",
        KopipesFunctionProps(
            code = props.transformCode,
            handler = props.transformHandler,
            environment = mapOf(EnvVars.TOPIC_ARN to topic.topicArn),
            timeout = Duration.seconds(60),
            publishes = false,
        ),
    )

    val deliveryStream: DeliveryStream = DeliveryStream.Builder.create(this, "DeliveryStream")
        .deliveryStreamName(stack.name("DeliveryStream"))
        .destination(
            S3Bucket(
                bucket,
                S3BucketProps.builder()
                    .dataOutputPrefix("${stack.regionName}/")
                    .bufferingInterval(Duration.seconds(60))
                    .bufferingSize(Size.mebibytes(50))
                    .compression(Compression.GZIP)
                    .processors(
                        listOf(
                            LambdaFunctionProcessor.Builder.create(transform.function)
                                .bufferInterval(Duration.seconds(60))
                                .bufferSize(Size.mebibytes(0.5))
                                .build()
                        )
                    )
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

    val verificationQueue: Queue? = if (props.verificationQueue) {
        Queue.Builder.create(this, "VerificationQueue")
            .queueName("${stack.service}-${stack.stage}-notification-verification.fifo")
            .fifo(true)
            .contentBasedDeduplication(true)
            .retentionPeriod(Duration.days(1))
            .removalPolicy(RemovalPolicy.DESTROY)
            .build()
            .also { topic.addSubscription(SqsSubscription.Builder.create(it).rawMessageDelivery(true).build()) }
    } else {
        null
    }

    init {
        topic.grantPublish(transform.function)

        Rule.Builder.create(this, "Rule")
            .ruleName(stack.name("faults-rule"))
            .eventBus(stack.importBus())
            .eventPattern(EventPattern.builder().detailType(listOf(EventHub.FAULT)).build())
            .targets(listOf(FirehoseDeliveryStream.Builder.create(deliveryStream).build()))
            .build()
    }
}
