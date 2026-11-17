package io.kopipes.core.flavors

import io.kopipes.core.*
import io.kopipes.core.faults.FaultManager
import io.kopipes.core.filters.EventFilters
import io.kopipes.core.sinks.EventPublisherInMemory
import io.kopipes.core.sinks.EventsMicrostoreInMemory
import io.kopipes.core.sinks.saveOptions
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CorrelatePipelineTest {

    data class TestEvent(
        override val id: String? = "event-1",
        override val timestamp: Long? = Clock.System.now().toEpochMilliseconds(),
        override val partitionKey: String? = "pk-1",
        override val tags: Map<String, String>? = null,
        override val raw: RawRecord? = null,
        override val eem: EnvelopeEncryptionMetadata? = null,
        override val triggers: List<EventReference>? = null,
    ) : Event {
        override fun eventType() = "TestEvent"
        override fun toString() = """{"id":"$id"}"""
        override fun copyEvent(
            id: String?,
            timestamp: Long?,
            partitionKey: String?,
            tags: Map<String, String>?,
            raw: RawRecord?,
            eem: EnvelopeEncryptionMetadata?,
            triggers: List<EventReference>?
        ): Event = copy(
            id = id,
            timestamp = timestamp,
            partitionKey = partitionKey,
            tags = tags,
            raw = raw,
            eem = eem,
            triggers = triggers
        )
    }

    @Test
    fun `save should map UnitOfWork to include SaveOptions with correct pk and sk`(): Unit = runBlocking {
        val microstore = EventsMicrostoreInMemory()
        val pipeline = CorrelatePipeline.builder()
            .id("correlate-1")
            .eventsMicrostore(microstore)
            .correlationKeySupplier { uow -> uow.event?.partitionKey }
            .correlationKeySuffix("suffix")
            .expire(true)
            .ttlDays(10)
            .build()

        val event = TestEvent(id = "evt-100", partitionKey = "entity-42", timestamp = 1_000_000L)
        val uow = UnitOfWork(event = event)
        val fm = FaultManager(EventPublisherInMemory())

        val result = with(pipeline) { flowOf(uow).save(fm) }.toList()

        result shouldHaveSize 1
        val savedUow = result.first()
        val saveOptions = savedUow.saveOptions
        saveOptions.shouldNotBeNull()
        saveOptions.pk shouldBe "entity-42.suffix"
        saveOptions.sk shouldBe "evt-100"
        saveOptions.discriminator shouldBe "CORREL"
        saveOptions.ttl shouldBe 1_000L + 10 * 24 * 60 * 60
        saveOptions.expire shouldBe true
        saveOptions.suffix shouldBe "suffix"
        saveOptions.data shouldBe "evt-100"
        saveOptions.pipelineId shouldBe "correlate-1"
        saveOptions.awsRegion shouldBe null // the microstore fills in its own region
    }

    @Test
    fun `a ttl function sets the deadline, ahead of the ttl in meta`(): Unit = runBlocking {
        // Arrange
        val pipeline = CorrelatePipeline.builder()
            .id("deadline")
            .eventsMicrostore(EventsMicrostoreInMemory())
            .correlationKeySuffix("deadline")
            .expire(true)
            .ttl { uow -> uow.event!!.timestamp!! / 1000 + 120 }
            .build()
        val uow = UnitOfWork(
            event = TestEvent(id = "evt-1", partitionKey = "p-7", timestamp = 1_000_000L),
            meta = mapOf("ttl" to "999"),
        )

        // Act
        val saved = with(pipeline) { flowOf(uow).save(FaultManager(EventPublisherInMemory())) }.toList().single()

        // Assert
        saved.saveOptions!!.pk shouldBe "p-7.deadline"
        saved.saveOptions!!.ttl shouldBe 1_120L
        saved.saveOptions!!.expire shouldBe true
    }

    @Test
    fun `connect should execute isCollectedEvent, normalizer, eventFilter, and save`(): Unit = runBlocking {
        val microstore = EventsMicrostoreInMemory()
        var collectedCheckCalled = false
        var normalizerCalled = false

        val pipeline = CorrelatePipeline.builder()
            .id("correlate-full")
            .eventsMicrostore(microstore)
            .isCollectedEvent { uow ->
                collectedCheckCalled = true
                uow.event != null
            }
            .normalizer { uow ->
                normalizerCalled = true
                val original = uow.event as TestEvent
                uow.copy(
                    event = original.copy(id = "normalized-${original.id}"),
                    meta = mapOf("ttl" to "999", "sequenceNumber" to "seq-1")
                )
            }
            .eventFilter(EventFilters.classes(TestEvent::class))
            .correlationKeySupplier { uow -> uow.event?.partitionKey }
            .build()

        val event = TestEvent(id = "raw-id", partitionKey = "entity-1")
        val uow = UnitOfWork(event = event)
        val fm = FaultManager(EventPublisherInMemory())

        val result = pipeline.connect(fm, flowOf(uow)).toList()

        collectedCheckCalled.shouldBeTrue()
        normalizerCalled.shouldBeTrue()
        result shouldHaveSize 1
        val processed = result.first()
        processed.event?.id shouldBe "normalized-raw-id"
        processed.saveOptions?.pk shouldBe "entity-1"
        processed.saveOptions?.sk shouldBe "normalized-raw-id"
        processed.saveOptions?.ttl shouldBe 999L
        processed.saveOptions?.sequenceNumber shouldBe "seq-1"
    }

    @Test
    fun `connect should filter out records rejected by isCollectedEvent`(): Unit = runBlocking {
        val microstore = EventsMicrostoreInMemory()
        val pipeline = CorrelatePipeline.builder()
            .id("correlate-filter")
            .eventsMicrostore(microstore)
            .isCollectedEvent { false }
            .build()

        val event = TestEvent(id = "evt-1")
        val uow = UnitOfWork(event = event)
        val fm = FaultManager(EventPublisherInMemory())

        val result = pipeline.connect(fm, flowOf(uow)).toList()
        result.shouldBeEmpty()
    }
}
