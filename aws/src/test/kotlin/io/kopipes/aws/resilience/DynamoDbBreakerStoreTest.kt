package io.kopipes.aws.resilience

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import io.kopipes.core.resilience.BreakerState
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

class DynamoDbBreakerStoreTest {

    private val store = DynamoDbBreakerStore(mockk<DynamoDbClient>(), "calls", "core-checking")

    @Test
    fun `a write is conditional on the version it read`() {
        // Arrange
        val next = BreakerState(state = BreakerState.OPEN, until = 99, opens = 1, version = 4)

        // Act
        val request = store.putRequest(next, expectedVersion = 3)

        // Assert
        request.conditionExpression shouldBe "attribute_not_exists(pk) OR #v = :expected"
        request.expressionAttributeValues shouldBe mapOf(":expected" to AttributeValue.N("3"))
        request.item!!["pk"] shouldBe AttributeValue.S("core-checking")
        request.item!!["state"] shouldBe AttributeValue.S("OPEN")
    }

    @Test
    fun `what it writes it reads back`() {
        // Arrange
        val state = BreakerState(state = BreakerState.HALF_OPEN, failures = 2, until = 77, opens = 3, version = 9)

        // Act
        val read = store.stateOf(store.putRequest(state, 8).item!!)

        // Assert
        read shouldBe state
    }
}
