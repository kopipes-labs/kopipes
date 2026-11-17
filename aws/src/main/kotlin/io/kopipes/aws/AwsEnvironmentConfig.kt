package io.kopipes.aws

import io.kopipes.core.EnvironmentConfig

/** AWS-specific environment configuration. */
open class AwsEnvironmentConfig : EnvironmentConfig() {

    open fun tableName(): String? {
        return eventTableName() ?: entityTableName()
    }

    open fun eventTableName(): String? {
        return env("EVENT_TABLE_NAME")
    }

    open fun entityTableName(): String? {
        return env("ENTITY_TABLE_NAME")
    }

    override fun awsRegion(): String {
        return env("AWS_REGION") ?: env("AWS_DEFAULT_REGION") ?: "us-east-1"
    }

    override fun region(): String? {
        return env("AWS_REGION") ?: env("AWS_DEFAULT_REGION")
    }

    override fun accountName(): String? {
        return env("ACCOUNT_NAME")
    }

    override fun stage(): String? {
        return env("STAGE")
    }

    override fun serverlessStage(): String? {
        return env("SERVERLESS_STAGE")
    }

    override fun service(): String? {
        return env("SERVICE")
    }

    open fun awsDefaultRegion(): String? {
        return env("AWS_DEFAULT_REGION")
    }

    open fun endPointUrl(): String? {
        return env("AWS_ENDPOINT_URL")
    }

    open fun busName(): String? {
        return env("BUS_NAME")
    }

    open fun busSource(): String? {
        return env("BUS_SRC")
    }

    open fun busEndPointId(): String? {
        return env("BUS_ENDPOINT_ID")
    }

    open fun busTimeout(): Long? {
        return env("BUS_TIMEOUT")?.toLongOrNull()
    }

    open fun cloudWatchParallel(): Int? {
        return env("CW_PARALLEL")?.toIntOrNull()
    }

    open fun cloudWatchTimeout(): Long? {
        return env("CW_TIMEOUT")?.toLongOrNull()
    }

    open fun dynamodbTimeout(): Long? {
        return env("DYNAMODB_TIMEOUT")?.toLongOrNull()
    }

    override fun project(): String? {
        return env("PROJECT")
    }

    override fun serverlessProject(): String? {
        return env("SERVERLESS_PROJECT")
    }

    open fun bucketName(): String? {
        return env("BUCKET_NAME")
    }

    open fun nameSpace(): String? {
        return env("NAMESPACE")
    }

    /** The fault monitor's SNS topic. */
    open fun topicArn(): String? {
        return env("TOPIC_ARN")
    }

    /** Running inside LocalStack (or SAM local), whose events miss some fields AWS sets. */
    open fun isLocalStack(): Boolean {
        return env("LOCALSTACK_HOSTNAME") != null || env("AWS_SAM_LOCAL") == "true"
    }

    open fun claimCheckBucketName(): String? {
        return env("CLAIMCHECK_BUCKET_NAME")
    }
}

