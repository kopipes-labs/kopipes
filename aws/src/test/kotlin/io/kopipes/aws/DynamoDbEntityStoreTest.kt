package io.kopipes.aws

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.BatchGetItemRequest
import aws.sdk.kotlin.services.dynamodb.model.BatchGetItemResponse
import aws.sdk.kotlin.services.dynamodb.model.ConditionalCheckFailedException
import aws.sdk.kotlin.services.dynamodb.model.QueryRequest
import aws.sdk.kotlin.services.dynamodb.model.QueryResponse
import aws.sdk.kotlin.services.dynamodb.model.UpdateItemRequest
import aws.sdk.kotlin.services.dynamodb.model.UpdateItemResponse
import io.kopipes.aws.connectors.DynamoDbClientFactory
import io.kopipes.aws.connectors.DynamoDbConnector
import io.kopipes.aws.sinks.DynamoDbEntityStore
import io.kopipes.aws.utils.attributeValueToEntityValue
import io.kopipes.aws.utils.entityValueToAttributeValue
import io.kopipes.core.UnitOfWork
import io.kopipes.core.faults.FaultManager
import io.kopipes.core.sinks.EntityStore
import io.kopipes.core.sinks.EventPublisherInMemory
import io.kopipes.core.sinks.entityUpdated
import io.kopipes.core.sinks.loadedEntities
import io.kopipes.core.sinks.queriedEntities
import io.kopipes.core.sinks.withEntityKeys
import io.kopipes.core.sinks.withEntityQuery
import io.kopipes.core.sinks.withEntityUpdate
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class DynamoDbEntityStoreTest {

    private val dynamoDbClient = mockk<DynamoDbClient>()
    private val dynamoDbClientFactory = spyk<DynamoDbClientFactory>().also {
        every { it.getClient(any()) } returns dynamoDbClient
    }
    private val store = DynamoDbEntityStore(
        connector = DynamoDbConnector(DynamoDbConnector.Options(dynamoDbClientFactory)),
        faultManager = FaultManager(EventPublisherInMemory()),
        tableName = "things",
    )
    private val key = EntityStore.Key("thing", "1")

    @Test
    fun `toQueryRequest queries a partition with a consistent read`() {
        // Act
        val request = store.toQueryRequest(EntityStore.Query("thing"))

        // Assert
        request.tableName shouldBe "things"
        request.indexName shouldBe null
        request.keyConditionExpression shouldBe "#k = :v"
        request.expressionAttributeNames shouldBe mapOf("#k" to "pk")
        request.expressionAttributeValues shouldBe mapOf(":v" to AttributeValue.S("thing"))
        request.consistentRead shouldBe true
    }

    @Test
    fun `toQueryRequest queries an index without a consistent read`() {
        // Act
        val request = store.toQueryRequest(EntityStore.Query("g1", attribute = "data", index = "DataIndex"))

        // Assert
        request.indexName shouldBe "DataIndex"
        request.expressionAttributeNames shouldBe mapOf("#k" to "data")
        request.consistentRead shouldBe false
    }

    @Test
    fun `toUpdateItemRequest builds the expression and the onlyIfNewer condition`() {
        // Act
        val request = store.toUpdateItemRequest(
            EntityStore.Update(
                key,
                set = mapOf("status" to "done", "timestamp" to 200L),
                add = mapOf("count" to 1),
                remove = setOf("latched"),
                onlyIfNewer = "timestamp",
            )
        )

        // Assert
        request.tableName shouldBe "things"
        request.key shouldBe mapOf("pk" to AttributeValue.S("thing"), "sk" to AttributeValue.S("1"))
        request.updateExpression shouldBe "SET #status = :status, #timestamp = :timestamp REMOVE #latched ADD #count :count"
        request.expressionAttributeValues shouldBe mapOf(
            ":status" to AttributeValue.S("done"),
            ":timestamp" to AttributeValue.N("200"),
            ":count" to AttributeValue.N("1"),
        )
        request.conditionExpression shouldBe "attribute_not_exists(#timestamp) OR #timestamp < :timestamp"
    }

    @Test
    fun `toUpdateItemRequest of a remove only sends no attribute values`() {
        // Act
        val request = store.toUpdateItemRequest(EntityStore.Update(key, remove = setOf("latched")))

        // Assert
        request.updateExpression shouldBe "REMOVE #latched"
        request.expressionAttributeValues shouldBe null
        request.conditionExpression shouldBe null
    }

    @Test
    fun `entity values round trip through attribute values`() {
        // Arrange
        val value = mapOf(
            "s" to "text", "long" to 5L, "decimal" to BigDecimal("1.25"), "double" to 1e-7, "bool" to true,
            "null" to null, "list" to listOf("a", 1L), "strings" to setOf("x", "y"), "numbers" to setOf(1L, 2L),
        )

        // Act
        val attributeValue = entityValueToAttributeValue(value)
        val back = attributeValueToEntityValue(attributeValue)

        // Assert
        (attributeValue as AttributeValue.M).value["double"] shouldBe AttributeValue.N("0.0000001")
        back shouldBe value + mapOf("double" to BigDecimal("0.0000001"))
    }

    @Test
    fun `query sets the entities found`() = runTest {
        // Arrange
        val request = slot<QueryRequest>()
        coEvery { dynamoDbClient.query(capture(request)) } returns QueryResponse {
            items = listOf(mapOf("pk" to AttributeValue.S("thing"), "sk" to AttributeValue.S("1"), "n" to AttributeValue.N("3")))
        }
        val uow = UnitOfWork().withEntityQuery(EntityStore.Query("thing"))

        // Act
        val result = store.query(flowOf(uow)).single()

        // Assert
        request.captured.tableName shouldBe "things"
        result.queriedEntities shouldBe listOf(mapOf("pk" to "thing", "sk" to "1", "n" to 3L))
    }

    @Test
    fun `get loads the entities in key order`() = runTest {
        // Arrange
        val request = slot<BatchGetItemRequest>()
        fun item(sk: String) = mapOf("pk" to AttributeValue.S("thing"), "sk" to AttributeValue.S(sk))
        coEvery { dynamoDbClient.batchGetItem(capture(request)) } returns BatchGetItemResponse {
            responses = mapOf("things" to listOf(item("2"), item("1")))
        }
        val uow = UnitOfWork().withEntityKeys(listOf(key, EntityStore.Key("thing", "2"), key))

        // Act
        val result = store.get(flowOf(uow)).single()

        // Assert
        request.captured.requestItems!!["things"]!!.keys!!.size shouldBe 2
        result.loadedEntities shouldBe listOf(mapOf("pk" to "thing", "sk" to "1"), mapOf("pk" to "thing", "sk" to "2"))
    }

    @Test
    fun `update reports whether the condition let it apply`() = runTest {
        // Arrange
        val update = EntityStore.Update(key, set = mapOf("timestamp" to 1L), onlyIfNewer = "timestamp")
        coEvery { dynamoDbClient.updateItem(any<UpdateItemRequest>()) } returns UpdateItemResponse {} andThenThrows
                ConditionalCheckFailedException { message = "stale" }
        val uow = UnitOfWork().withEntityUpdate(update)

        // Act
        val first = store.update(flowOf(uow)).single()
        val second = store.update(flowOf(uow)).single()

        // Assert
        first.entityUpdated shouldBe true
        second.entityUpdated shouldBe false
    }
}
