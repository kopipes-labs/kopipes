package io.kopipes.aws.resilience

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.ConditionalCheckFailedException
import aws.sdk.kotlin.services.dynamodb.model.GetItemRequest
import aws.sdk.kotlin.services.dynamodb.model.PutItemRequest
import io.kopipes.core.resilience.BreakerState
import io.kopipes.core.resilience.BreakerStore

/**
 * Keeps a [io.kopipes.core.resilience.CircuitBreaker]'s state in one DynamoDB item, `pk = <name>`, `sk = BREAKER`,
 * so every container of a function sees the same breaker. Writes are conditional on the version they read.
 */
class DynamoDbBreakerStore(
    private val client: DynamoDbClient,
    private val tableName: String,
    private val name: String,
) : BreakerStore {

    override suspend fun read(): BreakerState =
        client.getItem(getRequest()).item?.let(::stateOf) ?: BreakerState()

    override suspend fun write(next: BreakerState, expectedVersion: Long): Boolean = try {
        client.putItem(putRequest(next, expectedVersion))
        true
    } catch (e: ConditionalCheckFailedException) {
        false
    }

    internal fun key() = mapOf("pk" to AttributeValue.S(name), "sk" to AttributeValue.S(SK))

    internal fun getRequest() = GetItemRequest {
        tableName = this@DynamoDbBreakerStore.tableName
        key = key()
        consistentRead = true
    }

    internal fun putRequest(next: BreakerState, expectedVersion: Long) = PutItemRequest {
        tableName = this@DynamoDbBreakerStore.tableName
        item = key() + mapOf(
            "state" to AttributeValue.S(next.state),
            "failures" to AttributeValue.N(next.failures.toString()),
            "until" to AttributeValue.N(next.until.toString()),
            "opens" to AttributeValue.N(next.opens.toString()),
            "version" to AttributeValue.N(next.version.toString()),
        )
        conditionExpression = "attribute_not_exists(pk) OR #v = :expected"
        expressionAttributeNames = mapOf("#v" to "version")
        expressionAttributeValues = mapOf(":expected" to AttributeValue.N(expectedVersion.toString()))
    }

    internal fun stateOf(item: Map<String, AttributeValue>): BreakerState {
        fun n(name: String) = (item[name] as? AttributeValue.N)?.value?.toLong() ?: 0
        return BreakerState(
            state = (item["state"] as? AttributeValue.S)?.value ?: BreakerState.CLOSED,
            failures = n("failures").toInt(),
            until = n("until"),
            opens = n("opens").toInt(),
            version = n("version"),
        )
    }

    companion object {
        const val SK = "BREAKER"
    }
}
