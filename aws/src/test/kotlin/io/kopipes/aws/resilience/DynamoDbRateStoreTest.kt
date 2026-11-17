package io.kopipes.aws.resilience

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

class DynamoDbRateStoreTest {

    @Test
    fun `taking counts the second's uses, up to the limit, and expires the item`() {
        // Arrange
        val store = DynamoDbRateStore(mockk<DynamoDbClient>(), "calls", "card-processor")

        // Act
        val request = store.takeRequest(epochSecond = 100, limit = 5)

        // Assert
        request.key!!["pk"] shouldBe AttributeValue.S("card-processor#100")
        request.conditionExpression shouldBe "attribute_not_exists(#u) OR #u < :limit"
        request.expressionAttributeValues!![":limit"] shouldBe AttributeValue.N("5")
        request.expressionAttributeValues!![":ttl"] shouldBe AttributeValue.N("160")
    }
}
