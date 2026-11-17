package io.kopipes.aws.from

import com.amazonaws.services.lambda.runtime.events.DynamodbEvent
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.StreamRecord
import io.kopipes.core.EnvironmentConfig
import io.kopipes.core.TableChangeEvent
import io.kopipes.core.faults.FaultManager
import io.kopipes.core.sinks.EventPublisherInMemory
import io.mockk.spyk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.*
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue as EventAV

class DynamodbAdapterTest {

    private val envConfig = spyk(EnvironmentConfig())
    private val faultManager = FaultManager(eventPublisher = EventPublisherInMemory())
    private val adapter = DynamodbAdapter(faultManager)

    // ============================================================================
    // Tests for calculateEventTypeSuffix
    // ============================================================================

    @Test
    fun `calculateEventTypeSuffix should map event names correctly`() {
        val insertRecord = createDynamodbRecord(eventName = "INSERT")
        val modifyRecord = createDynamodbRecord(eventName = "MODIFY")
        val removeRecord = createDynamodbRecord(eventName = "REMOVE")

        assertEquals("created", adapter.calculateEventTypeSuffix(insertRecord))
        assertEquals("updated", adapter.calculateEventTypeSuffix(modifyRecord))
        assertEquals("deleted", adapter.calculateEventTypeSuffix(removeRecord))
    }

    @Test
    fun `calculateEventTypeSuffix should return empty string for unknown event names`() {
        val unknownRecord = createDynamodbRecord(eventName = "UNKNOWN")

        assertEquals("", adapter.calculateEventTypeSuffix(unknownRecord))
    }

    @Test
    fun `calculateEventTypeSuffix should return deleted when new image has deleted flag true`() {
        val newImage = mapOf(
            "id" to EventAV().withS("123"),
            "deleted" to EventAV().withBOOL(true)
        )
        val record = createDynamodbRecord(eventName = "MODIFY", newImage = newImage)

        assertEquals("deleted", adapter.calculateEventTypeSuffix(record))
    }

    @Test
    fun `calculateEventTypeSuffix should return undeleted when old image has deleted flag true but new is false`() {
        val newImage = mapOf(
            "id" to EventAV().withS("123"),
            "deleted" to EventAV().withBOOL(false)
        )
        val oldImage = mapOf(
            "id" to EventAV().withS("123"),
            "deleted" to EventAV().withBOOL(true)
        )
        val record = createDynamodbRecord(eventName = "MODIFY", newImage = newImage, oldImage = oldImage)

        assertEquals("undeleted", adapter.calculateEventTypeSuffix(record))
    }

    @Test
    fun `calculateEventTypeSuffix should handle missing deleted flag gracefully`() {
        val newImage = mapOf(
            "id" to EventAV().withS("123"),
            "name" to EventAV().withS("test")
        )
        val record = createDynamodbRecord(eventName = "MODIFY", newImage = newImage)

        assertEquals("updated", adapter.calculateEventTypeSuffix(record))
    }

    @Test
    fun `calculateEventTypeSuffix should prefer deleted suffix over updated for MODIFY events`() {
        val newImage = mapOf(
            "id" to EventAV().withS("123"),
            "deleted" to EventAV().withBOOL(true)
        )
        val record = createDynamodbRecord(eventName = "MODIFY", newImage = newImage)

        assertEquals("deleted", adapter.calculateEventTypeSuffix(record))
    }

    // ============================================================================
    // Tests for buildEvent
    // ============================================================================

    @Test
    fun `buildEvent should set all required fields from dynamodb record`() {
        val eventId = "event-123"
        val region = "us-east-1"
        val pkValue = "partition-key-value"
        val timestamp = Date()
        val newImage = mapOf(
            "pk" to EventAV().withS(pkValue),
            "discriminator" to EventAV().withS("OrderEvent"),
            "timestamp" to EventAV().withN("1609459200")
        )

        val record = createDynamodbRecord(
            eventId = eventId,
            region = region,
            newImage = newImage,
            timestamp = timestamp,
            eventName = "INSERT",
            partitionKeyValue = pkValue
        )

        val event = adapter.buildEvent(record)

        assertEquals(eventId, event.id)
        assertEquals(region, event.tags?.get("region"))
        assertEquals(pkValue, event.partitionKey)
        assertNotNull(event.timestamp)
    }

    @Test
    fun `buildEvent should include raw data with new and old images`() {
        val newImage = RecordImage(mapOf<String, EventAV>(
            "pk" to EventAV().withS("key1"),
            "data" to EventAV().withS("new-value")
        ))
        val oldImage = RecordImage( mapOf(
            "pk" to EventAV().withS("key1"),
            "data" to EventAV().withS("old-value")
        ))

        val record = createDynamodbRecord(
            newImage = newImage,
            oldImage = oldImage,
            eventName = "MODIFY"
        )

        val event = adapter.buildEvent(record)

        val raw = event.raw as? RecordPair
        assertEquals(newImage.map, raw?.new?.map)
        assertEquals(oldImage.map, raw?.old?.map)
    }

    @Test
    fun `buildEvent should calculate correct event type`() {
        val newImage = mapOf(
            "pk" to EventAV().withS("key1"),
            "discriminator" to EventAV().withS("UserEvent")
        )

        val record = createDynamodbRecord(newImage = newImage, eventName = "INSERT")

        val event = adapter.buildEvent(record)

        assertEquals("UserEvent-created", event.type)
    }

    // ============================================================================
    // Tests for fromDynamoDB (integration)
    // ============================================================================

    @Test
    fun `fromDynamoDB should process valid dynamodb records`() = runBlocking {
        val newImage = mapOf(
            "pk" to EventAV().withS("key1"),
            "discriminator" to EventAV().withS("TestEvent")
        )

        val records = listOf(
            createDynamodbRecord(eventId = "event-1", newImage = newImage, eventName = "INSERT"),
            createDynamodbRecord(eventId = "event-2", newImage = newImage, eventName = "MODIFY")
        )

        val dynamodbEvent = DynamodbEvent().apply {
            this.records = records.map { record ->
                DynamodbEvent.DynamodbStreamRecord().apply {
                    eventID = record.eventID
                    eventName = record.eventName
                    awsRegion = record.awsRegion
                    dynamodb = record.dynamodb
                }
            }
        }

        val results = adapter.fromDynamoDB(dynamodbEvent).toList()

        assertEquals(2, results.size)
        assertEquals("event-1", results[0].event?.id)
        assertEquals("event-2", results[1].event?.id)
        assertTrue(results.all { it.event is TableChangeEvent })
    }

    @Test
    fun `fromDynamoDB should handle empty event records`() = runBlocking {
        val dynamodbEvent = DynamodbEvent().apply {
            records = emptyList()
        }

        val results = adapter.fromDynamoDB( dynamodbEvent).toList()

        assertEquals(0, results.size)
    }

    @Test
    fun `fromDynamoDB should preserve record reference in unit of work`() = runBlocking {
        val newImage = mapOf(
            "pk" to EventAV().withS("key1"),
            "discriminator" to EventAV().withS("Event")
        )

        val dynamodbRecord = createDynamodbRecord(eventId = "test-id", newImage = newImage, eventName = "INSERT")
        val dynamodbEvent = DynamodbEvent().apply {
            records = listOf(
                DynamodbEvent.DynamodbStreamRecord().apply {
                    eventID = dynamodbRecord.eventID
                    eventName = dynamodbRecord.eventName
                    awsRegion = dynamodbRecord.awsRegion
                    dynamodb = dynamodbRecord.dynamodb
                }
            )
        }

        val results = adapter.fromDynamoDB(dynamodbEvent).toList()

        assertEquals(1, results.size)
        assertNotNull(results[0].record)
        assertNotNull(results[0].event)
    }

    // ============================================================================
    // Tests for forEvaluateEvents
    // ============================================================================

    @Test
    fun `forEvaluateEvents should return true for INSERT with EVENT sk or CORREL discriminator`() {
        val eventInsertRecord = createDynamodbRecord(
            eventName = "INSERT",
            partitionKeyValue = "pk-1"
        ).apply {
            dynamodb.keys = mapOf("sk" to EventAV().withS("EVENT"))
        }
        val correlInsertRecord = createDynamodbRecord(
            eventName = "INSERT",
            newImage = mapOf("discriminator" to EventAV().withS("CORREL"))
        )
        val correlModifyRecord = createDynamodbRecord(
            eventName = "MODIFY",
            newImage = mapOf("discriminator" to EventAV().withS("CORREL"))
        )
        val otherInsertRecord = createDynamodbRecord(
            eventName = "INSERT",
            newImage = mapOf("discriminator" to EventAV().withS("OTHER"))
        ).apply {
            dynamodb.keys = mapOf("sk" to EventAV().withS("OTHER"))
        }

        assertTrue(DynamodbAdapter.forEvaluateEvents(io.kopipes.core.UnitOfWork(record = eventInsertRecord)))
        assertTrue(DynamodbAdapter.forEvaluateEvents(io.kopipes.core.UnitOfWork(record = correlInsertRecord)))
        assertTrue(DynamodbAdapter.forEvaluateEvents(io.kopipes.core.UnitOfWork(record = correlModifyRecord)))
        assertFalse(DynamodbAdapter.forEvaluateEvents(io.kopipes.core.UnitOfWork(record = otherInsertRecord)))
        assertFalse(DynamodbAdapter.forEvaluateEvents(io.kopipes.core.UnitOfWork(record = Any())))
    }

    // ============================================================================
    // Expired deadlines
    // ============================================================================

    private fun deadlineImage(expire: Boolean) = mapOf<String, EventAV?>(
        "pk" to EventAV().withS("p-7.deadline"),
        "sk" to EventAV().withS("p-7.submitted"),
        "discriminator" to EventAV().withS("CORREL"),
        "suffix" to EventAV().withS("deadline"),
        "ttl" to EventAV().withN("1700000120"),
        "expire" to EventAV().withBOOL(expire),
        "event" to EventAV().withS("""{"id":"p-7.submitted"}"""),
    )

    @Test
    fun `a REMOVE of an item saved with expire is an expired deadline, read from the old image`() {
        // Arrange
        val record = createDynamodbRecord(eventName = "REMOVE", oldImage = deadlineImage(expire = true))

        // Act
        val event = adapter.buildEvent(record)

        // Assert
        assertTrue(event.isExpired())
        assertEquals("p-7.deadline", event.getPk())
        assertEquals("CORREL", event.getDiscriminator())
        assertEquals("deadline", event.getSuffix())
        assertEquals("1700000120", event.getTtl())
        assertEquals("""{"id":"p-7.submitted"}""", event.getEvent())
    }

    @Test
    fun `other removes and inserts are not expiries`() {
        // Arrange
        val removeWithoutExpire = createDynamodbRecord(eventName = "REMOVE", oldImage = deadlineImage(expire = false))
        val insert = createDynamodbRecord(eventName = "INSERT", newImage = deadlineImage(expire = true))

        // Act
        val removed = adapter.buildEvent(removeWithoutExpire)
        val inserted = adapter.buildEvent(insert)

        // Assert
        assertFalse(removed.isExpired())
        assertNull(removed.getPk())
        assertFalse(inserted.isExpired())
        assertEquals("p-7.deadline", inserted.getPk())
    }

    // ============================================================================
    // Helper functions
    // ============================================================================

    private fun createDynamodbRecord(
        eventId: String = UUID.randomUUID().toString(),
        eventName: String = "INSERT",
        region: String = "us-east-1",
        newImage: Map<String, EventAV?>? = null,
        oldImage: Map<String, EventAV?>? = null,
        timestamp: Date? = Date(),
        partitionKeyValue: String = "default-pk"
    ): DynamodbEvent.DynamodbStreamRecord {
        val streamRecord = StreamRecord()
            .withNewImage(newImage)
            .withOldImage(oldImage)
            .withKeys(
                mapOf("pk" to EventAV().withS(partitionKeyValue)
            ))
            .withApproximateCreationDateTime(timestamp)

        return DynamodbEvent.DynamodbStreamRecord().apply {
            this.eventID = eventId
            this.eventName = eventName
            this.awsRegion = region
            dynamodb = streamRecord
        }
    }
}