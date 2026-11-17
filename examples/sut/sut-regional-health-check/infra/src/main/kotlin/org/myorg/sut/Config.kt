package org.myorg.sut

import io.kopipes.cdk.Stage

import software.amazon.awscdk.Aws

data class ApiKeyConfig(
    val name: String,
    val value: String,
)

fun RegionalHealthCheckStack.accountName(): String =
    when (stage) {
        Stage.PROD -> "prod"
        else -> "np"
    }

fun RegionalHealthCheckStack.debug(): String =
    when (stage) {
        Stage.PROD -> ""
        else -> "*"
    }

fun RegionalHealthCheckStack.logRetentionInDays(): Int =
    when (stage) {
        Stage.PROD -> 30
        else -> 3
    }

fun RegionalHealthCheckStack.shardCount(): Int =
    when (stage) {
        Stage.PROD -> 1
        else -> 1
    }

fun RegionalHealthCheckStack.apiKeys(): List<ApiKeyConfig> =
    when (stage) {
        Stage.PROD -> listOf(
            ApiKeyConfig(
                name = "${service}-${stage}-bar",
                value = "99749605-b27f-4feb-b306-d6437ff14c3d",
            )
        )

        else -> listOf(
            ApiKeyConfig(
                name = "${service}-${stage}-bar",
                value = "57913ada-45ac-49f2-b587-7f579f883758",
            )
        )
    }

fun RegionalHealthCheckStack.apiKey(): String =
    apiKeys().first().value

fun RegionalHealthCheckStack.partition(): String =
    "aws"

fun RegionalHealthCheckStack.tableArnFromConfig(): String =
    "arn:${partition()}:dynamodb:${regionName}:${Aws.ACCOUNT_ID}:table/${entityTableName()}"

fun RegionalHealthCheckStack.tableStreamArnFromConfig(): String =
    "arn:${partition()}:dynamodb:${regionName}:${Aws.ACCOUNT_ID}:table/${entityTableName()}/stream/*"

fun RegionalHealthCheckStack.healthCheckEndpoint(): String {
    val sourceRegion = when (regionName) {
        "us-east-1" -> "us-west-2"
        "us-west-2" -> "us-east-1"
        else -> return "not-deployed/check"
    }

    return "\${cf($sourceRegion):${subsys}-regional-health-check-${stage}.ServiceEndpoint, 'not-deployed'}/check"
}

fun RegionalHealthCheckStack.environment(): Map<String, String> =
    mapOf(
        "ACCOUNT_NAME" to accountName(),
        "PROJECT" to service,
        "STAGE" to stage.toString(),
        "DEBUG" to debug(),
        "AWS_NODEJS_CONNECTION_REUSE_ENABLED" to "1",
        "ENTITY_TABLE_NAME" to entityTableName(),
        "BUCKET_NAME" to "${org}-${service}-${stage}-${regionName}",
        "BUS_NAME" to "${service}-${stage}-bus",
        "BUS_ARN" to "arn:${partition()}:events:${regionName}:${Aws.ACCOUNT_ID}:event-bus/${service}-${stage}-bus",
    )