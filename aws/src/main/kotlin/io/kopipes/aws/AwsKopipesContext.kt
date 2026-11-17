package io.kopipes.aws

import aws.smithy.kotlin.runtime.SdkBaseException
import io.kopipes.aws.connectors.*
import io.kopipes.aws.extensions.*
import io.kopipes.aws.serialization.snapshots.DynamoDbRecordSnapshotter
import io.kopipes.aws.serialization.snapshots.KinesisRecordSnapshotter
import io.kopipes.aws.serialization.snapshots.SqsRecordSnapshotter
import io.kopipes.aws.sinks.EventBridgePublisher
import io.kopipes.core.*
import io.kopipes.core.faults.FaultManager
import io.kopipes.core.serialization.snapshots.AwsOperationSnapshot
import io.kopipes.core.serialization.snapshots.DefaultUnitOfWorkSnapshotter
import io.kopipes.core.serialization.snapshots.JsonRawRecordSnapshotter
import io.kopipes.core.serialization.snapshots.RecordSnapshotter
import io.kopipes.core.serialization.snapshots.UnitOfWorkSnapshotter
import io.kopipes.core.sinks.EventPublisher
import kotlinx.serialization.KSerializer

/**
 * The shared collaborators of a kopipes application on AWS.
 *
 * Build one per Lambda (or one Spring bean) and hand it to the components you create. Every argument has a default
 * derived from the ones before it, so `AwsKopipesContext()` reads the usual env vars and wires EventBridge, while tests
 * replace just what they need, e.g. `AwsKopipesContext(envConfig = cfg, eventPublisher = EventPublisherInMemory())`.
 * Clients are created lazily, on the first call that needs them.
 */
class AwsKopipesContext @JvmOverloads constructor(
    override val envConfig: AwsEnvironmentConfig = AwsEnvironmentConfig(),
    val dynamoDbClientFactory: DynamoDbClientFactory = DefaultDynamoDbClientFactory(envConfig),
    val eventBridgeClientFactory: EventBridgeClientFactory = DefaultEventBridgeClientFactory(envConfig),
    val s3ClientFactory: S3ClientFactory = DefaultS3ClientFactory(envConfig),
    val kmsClientFactory: KmsClientFactory = DefaultKmsClientFactory(envConfig),
    val cloudWatchClientFactory: CloudWatchClientFactory = DefaultCloudWatchClientFactory(envConfig),
    recordSnapshotters: List<RecordSnapshotter> = defaultRecordSnapshotters(),
    override val unitOfWorkSnapshotter: UnitOfWorkSnapshotter = DefaultUnitOfWorkSnapshotter(
        recordSnapshotters = recordSnapshotters,
        awsOperationCapturer = ::captureAwsOperations,
    ),
    override val eventPublisher: EventPublisher = EventBridgePublisher(envConfig, eventBridgeClientFactory),
    isRetryable: (Throwable) -> Boolean = ::isAwsRetryable,
    override val faultManager: FaultManager = FaultManager.fromConfig(
        envConfig = envConfig,
        eventPublisher = eventPublisher,
        unitOfWorkSnapshotter = unitOfWorkSnapshotter,
        isRetryable = isRetryable,
    ),
) : KopipesContext {

    /** KMS connectors in this context's region, for the encryption steps. */
    val kmsConnectors: KmsConnectors by lazy { KmsConnectors(kmsClientFactory, envConfig.awsRegion()) }

    /** True when the `step` metric is configured. */
    val stepMetrics: Boolean get() = envConfig.isMetricEnabled("step")

    companion object {
        @JvmStatic
        fun defaultRecordSnapshotters(): List<RecordSnapshotter> =
            listOf(KinesisRecordSnapshotter(), DynamoDbRecordSnapshotter(), SqsRecordSnapshotter(), JsonRawRecordSnapshotter())
    }
}

/** True when [throwable] is an AWS SDK error the SDK marks as retryable (throttling, transient service errors). */
fun isAwsRetryable(throwable: Throwable): Boolean =
    throwable is SdkBaseException && throwable.sdkErrorMetadata.isRetryable

/** Lists the AWS calls recorded on [uow], for the diagnostic snapshot in a fault event. */
fun captureAwsOperations(uow: UnitOfWork): List<AwsOperationSnapshot>? {
    val ops = mutableListOf<AwsOperationSnapshot>()

    uow.publishResponse?.let { ops.add(AwsOperationSnapshot(service = "EventBridge", operation = "PutEvents")) }
    uow.putMetricDataResponse?.let { ops.add(AwsOperationSnapshot(service = "CloudWatch", operation = "PutMetricData")) }
    uow.putResponse?.let { ops.add(AwsOperationSnapshot(service = "DynamoDB", operation = "PutItem")) }
    uow.updateResponse?.let { ops.add(AwsOperationSnapshot(service = "DynamoDB", operation = "UpdateItem")) }
    uow.queryResponse?.let { ops.add(AwsOperationSnapshot(service = "DynamoDB", operation = "Query")) }
    uow.scanRequest?.let { ops.add(AwsOperationSnapshot(service = "DynamoDB", operation = "Scan")) }
    uow.batchGetResponse?.let { ops.add(AwsOperationSnapshot(service = "DynamoDB", operation = "BatchGetItem")) }

    return if (ops.isEmpty()) null else ops
}

/** Registers the AWS [RawRecord] variants with [RawRecordSerializer] (found through META-INF/services). */
class AwsRawRecordTypes : RawRecordTypeProvider {
    override fun rawRecordTypes(): Map<String, KSerializer<out RawRecord>> = mapOf(
        RAW_DYNAMODB to DynamodbRaw.serializer(),
        RAW_IMAGES to ImagesRaw.serializer(),
        RAW_KINESIS to KinesisRaw.serializer(),
        RAW_SQS to SqsRaw.serializer(),
        RAW_CLAIM_CHECK to ClaimCheck.serializer(),
    )
}
