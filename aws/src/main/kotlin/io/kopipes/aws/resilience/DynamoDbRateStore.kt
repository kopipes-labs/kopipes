package io.kopipes.aws.resilience

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.ConditionalCheckFailedException
import aws.sdk.kotlin.services.dynamodb.model.UpdateItemRequest
import io.kopipes.core.resilience.RateStore

/**
 * A [io.kopipes.core.resilience.RateLimiter]'s per-second budgets in DynamoDB: one item per second,
 * `pk = <name>#<second>`, `sk = RATE`, counting what was taken. Items expire after a minute (the table's `ttl`).
 * At high rates one item per second gets hot; leasing tokens in blocks is the refinement (not built).
 */
class DynamoDbRateStore(
    private val client: DynamoDbClient,
    private val tableName: String,
    private val name: String,
) : RateStore {

    override suspend fun take(epochSecond: Long, limit: Int): Boolean = try {
        client.updateItem(takeRequest(epochSecond, limit))
        true
    } catch (e: ConditionalCheckFailedException) {
        false
    }

    internal fun takeRequest(epochSecond: Long, limit: Int) = UpdateItemRequest {
        tableName = this@DynamoDbRateStore.tableName
        key = mapOf("pk" to AttributeValue.S("$name#$epochSecond"), "sk" to AttributeValue.S("RATE"))
        updateExpression = "ADD #u :one SET #ttl = :ttl"
        conditionExpression = "attribute_not_exists(#u) OR #u < :limit"
        expressionAttributeNames = mapOf("#u" to "used", "#ttl" to "ttl")
        expressionAttributeValues = mapOf(
            ":one" to AttributeValue.N("1"),
            ":limit" to AttributeValue.N(limit.toString()),
            ":ttl" to AttributeValue.N((epochSecond + 60).toString()),
        )
    }
}
