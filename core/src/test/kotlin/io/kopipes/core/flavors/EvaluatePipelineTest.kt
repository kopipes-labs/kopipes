package io.kopipes.core.flavors

import io.kopipes.core.*
import io.kopipes.core.faults.FaultManager
import io.kopipes.core.filters.EventFilters
import io.kopipes.core.sinks.EventPublisherInMemory
import io.kopipes.core.sinks.EventsMicrostoreInMemory
import io.kopipes.core.sinks.queryParams
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class EvaluatePipelineTest {

    @Serializable
    data class TestEvent(
        override val id: String? = null,
        override val timestamp: Long? = 1_700_000_000_000L,
        override val partitionKey: String? = null,
        override val tags: Map<String, String>? = null,
        override val raw: RawRecord? = null,
        override val eem: EnvelopeEncryptionMetadata? = null,
        override val triggers: List<EventReference>? = null,
        val type: String = "TestEvent",
    ) : Event {
        override fun eventType() = type
        override fun toString() = """{"id":"$id","type":"$type"}"""
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

    class SimpleEventCodec : EventCodec {
        override fun decode(eventAsString: String): Event {
            return Json.decodeFromString<TestEvent>(eventAsString)
        }

        override fun encode(event: Event): String {
            return Json.encodeToString(TestEvent.serializer(), event as TestEvent)
        }
    }

    class DummyTableChangeEvent(
        override val id: String? = "tce-1",
        override val timestamp: Long? = 1_700_000_000_000L,
        override val partitionKey: String? = "pk-1",
        override val tags: Map<String, String>? = null,
        override val raw: RawRecord? = null,
        override val eem: EnvelopeEncryptionMetadata? = null,
        override val triggers: List<EventReference>? = null,
        override val type: String? = null,
        private val pk: String? = "pk-1",
        private val ttl: String? = "100",
        private val data: String? = "data-1",
        private val event: String? = """{"id":"inner-1","type":"TestEvent"}""",
        private val discriminator: String? = "CORREL",
        private val suffix: String? = "v1",
        private val deleted: Boolean = false,
        private val latched: Boolean = false,
        private val expired: Boolean = false,
    ) : TableChangeEvent() {
        override fun isExpired(): Boolean = expired
        override fun getPk(): String? = pk
        override fun getTtl(): String? = ttl
        override fun getData(): String? = data
        override fun getEvent(): String? = event
        override fun getDiscriminator(): String? = discriminator
        override fun getSuffix(): String? = suffix
        override fun isDeleted(): Boolean = deleted
        override fun latched(): Boolean = latched
        override fun getS(fieldName: String): String? = null
        override fun getDouble(fieldName: String): Double? = null
        override fun getLong(fieldName: String): Long? = null

        override fun copyEvent(
            id: String?,
            timestamp: Long?,
            partitionKey: String?,
            tags: Map<String, String>?,
            raw: RawRecord?,
            eem: EnvelopeEncryptionMetadata?,
            triggers: List<EventReference>?
        ): Event = DummyTableChangeEvent(
            id = id,
            timestamp = timestamp,
            partitionKey = partitionKey,
            tags = tags,
            raw = raw,
            eem = eem,
            triggers = triggers,
            type = type,
            pk = pk,
            ttl = ttl,
            data = data,
            event = event,
            discriminator = discriminator,
            suffix = suffix,
            deleted = deleted,
            latched = latched,
            expired = expired,
        )
    }

    private val eventPublisher = EventPublisherInMemory()
    private val eventsMicrostore = EventsMicrostoreInMemory()
    private val eventCodec = SimpleEventCodec()

    private fun createPipeline(
        id: String = "evaluate-1",
        correlationKeySuffix: String = "",
        index: String? = null,
        isEvaluateEvent: ((UnitOfWork) -> Boolean)? = null,
        normalizer: ((UnitOfWork) -> UnitOfWork)? = null,
        expression: ((UnitOfWork) -> Boolean)? = null,
        emit: ((UnitOfWork) -> List<Event>)? = null,
    ): EvaluatePipeline {
        return EvaluatePipeline.builder()
            .id(id)
            .eventPublisher(eventPublisher)
            .eventsMicrostore(eventsMicrostore)
            .eventCodec(eventCodec)
            .correlationKeySuffix(correlationKeySuffix)
            .index(index)
            .apply {
                isEvaluateEvent?.let { isEvaluateEvent(it) }
                normalizer?.let { normalizer(it) }
                expression?.let { expression(it) }
                emit?.let { emit(it) }
            }
            .build()
    }

    @Test
    fun `builder should fail when required parameters are missing`() {
        shouldThrow<IllegalArgumentException> {
            EvaluatePipeline.builder().build()
        }
    }

    @Test
    fun `forEvents should accept valid TableChangeEvent, Event, or custom predicate`() {
        val pipeline = createPipeline()

        val correlEvent = DummyTableChangeEvent(discriminator = "CORREL", deleted = false)
        val dataEvent = DummyTableChangeEvent(discriminator = "DATA", event = """{"id":"1"}""", deleted = false)
        val deletedEvent = DummyTableChangeEvent(discriminator = "CORREL", deleted = true)
        val nonCorrelNoEvent = DummyTableChangeEvent(discriminator = null, event = null, deleted = false)
        val regularEvent = TestEvent(id = "evt-1")
        val nullEventUow = UnitOfWork(event = null)

        pipeline.forEvents(UnitOfWork(event = correlEvent)) shouldBe true
        pipeline.forEvents(UnitOfWork(event = dataEvent)) shouldBe true
        pipeline.forEvents(UnitOfWork(event = deletedEvent)) shouldBe false
        pipeline.forEvents(UnitOfWork(event = nonCorrelNoEvent)) shouldBe false
        pipeline.forEvents(UnitOfWork(event = regularEvent)) shouldBe true
        pipeline.forEvents(nullEventUow) shouldBe false

        val customPipeline = createPipeline(isEvaluateEvent = { it.event is TestEvent })
        customPipeline.forEvents(UnitOfWork(event = regularEvent)) shouldBe true
        customPipeline.forEvents(UnitOfWork(event = correlEvent)) shouldBe false
    }

    @Test
    fun `normalize should parse event, compute queryParams and metadata from TableChangeEvent`() {
        val pipeline = createPipeline(id = "eval-p", index = "CustomIndex")
        val tce = DummyTableChangeEvent(
            id = "tce-123",
            pk = "order#42.v1",
            data = "item#99",
            discriminator = "CORREL",
            suffix = "v1",
            event = """{"id":"evt-55","type":"OrderPlaced"}"""
        )
        val uow = UnitOfWork(event = tce)

        val normalized = pipeline.normalize(uow)

        val queryParams = normalized.queryParams.shouldNotBeNull()
        queryParams.pk shouldBe "order#42.v1"
        queryParams.correlation shouldBe true
        queryParams.data shouldBe "item#99"
        queryParams.index shouldBe "CustomIndex"

        val meta = normalized.meta.shouldNotBeNull()
        meta["eventId"] shouldBe "tce-123.eval-p"
        meta["partitionKey"] shouldBe "order#42"
        meta["suffix"] shouldBe "v1"

        val event = normalized.event.shouldNotBeNull()
        event.id shouldBe "evt-55"
        event.eventType() shouldBe "OrderPlaced"
    }

    @Test
    fun `normalize should use custom normalizer when configured`() {
        val pipeline = createPipeline(
            normalizer = { uow -> uow.copy(meta = mapOf("custom" to "normalized")) }
        )
        val uow = UnitOfWork(event = DummyTableChangeEvent())

        val result = pipeline.normalize(uow)
        result.meta?.get("custom") shouldBe "normalized"
    }

    @Test
    fun `onCorrelationKeySuffix should filter matching and mismatching suffixes`() {
        val pipelineNoSuffix = createPipeline(correlationKeySuffix = "")
        val pipelineWithSuffix = createPipeline(correlationKeySuffix = "archived")

        val emptySuffixUow = UnitOfWork(meta = mapOf("suffix" to ""))
        val matchingSuffixUow = UnitOfWork(meta = mapOf("suffix" to "archived"))
        val differentSuffixUow = UnitOfWork(meta = mapOf("suffix" to "active"))

        pipelineNoSuffix.onCorrelationKeySuffix(emptySuffixUow) shouldBe true
        pipelineNoSuffix.onCorrelationKeySuffix(differentSuffixUow) shouldBe false

        pipelineWithSuffix.onCorrelationKeySuffix(matchingSuffixUow) shouldBe true
        pipelineWithSuffix.onCorrelationKeySuffix(differentSuffixUow) shouldBe false
        pipelineWithSuffix.onCorrelationKeySuffix(emptySuffixUow) shouldBe false
    }

    @Test
    fun `complex should set triggers without expression and evaluate expression when present`(): Unit = runBlocking {
        val fm = FaultManager(EventPublisherInMemory())
        val uow1 = UnitOfWork(event = TestEvent(id = "e1"))
        val uow2 = UnitOfWork(event = TestEvent(id = "e2"))

        val pipelineNoExpr = createPipeline()
        val resultNoExpr = with(pipelineNoExpr) { flowOf(uow1, uow2).complex(fm).toList() }

        resultNoExpr shouldHaveSize 2
        resultNoExpr[0].triggers?.map { it.id } shouldBe listOf("e1")
        resultNoExpr[1].triggers?.map { it.id } shouldBe listOf("e2")

        val pipelineWithExpr = createPipeline(
            correlationKeySuffix = "v1",
            expression = { uow -> uow.meta?.get("allow") == "true" }
        )
        val passUow = UnitOfWork(
            meta = mapOf("suffix" to "v1", "allow" to "true"),
            event = TestEvent(id = "pass-e")
        )
        val failUow = UnitOfWork(
            meta = mapOf("suffix" to "v1", "allow" to "false"),
            event = TestEvent(id = "fail-e")
        )
        val wrongSuffixUow = UnitOfWork(
            meta = mapOf("suffix" to "v2", "allow" to "true"),
            event = TestEvent(id = "wrong-suffix")
        )

        val resultExpr = with(pipelineWithExpr) { flowOf(passUow, failUow, wrongSuffixUow).complex(fm).toList() }
        resultExpr shouldHaveSize 1
        resultExpr[0].event?.id shouldBe "pass-e"
    }

    @Test
    fun `toHigherOrderEvents should aggregate tags omitting region and source and copy triggers`() {
        val trigger1 = TestEvent(id = "trig-1", timestamp = 1_000L, tags = mapOf("tagA" to "1", "region" to "us-east-1"))
        val trigger2 = TestEvent(id = "trig-2", timestamp = 2_000L, tags = mapOf("tagB" to "2", "source" to "dynamo"))

        val pipeline = createPipeline(
            emit = { uow ->
                listOf(TestEvent(type = "HigherOrderEvent"))
            }
        )

        val uow = UnitOfWork(
            event = trigger2,
            meta = mapOf("eventId" to "higher-1", "partitionKey" to "pk-agg"),
            triggers = listOf(trigger1, trigger2)
        )

        val result = pipeline.toHigherOrderEvents(uow)
        result shouldHaveSize 1

        val higherEvent = result.first().event.shouldNotBeNull()
        higherEvent.id shouldBe "higher-1"
        higherEvent.partitionKey shouldBe "pk-agg"
        higherEvent.timestamp shouldBe 2_000L
        higherEvent.tags shouldBe mapOf("tagA" to "1", "tagB" to "2")
        higherEvent.triggers?.map { it.id } shouldBe listOf("trig-1", "trig-2")
    }

    @Test
    fun `connect should process stream and publish higher order events end-to-end`(): Unit = runBlocking {
        val pub = EventPublisherInMemory()
        val store = EventsMicrostoreInMemory()
        val fm = FaultManager(pub)

        val pipeline = EvaluatePipeline.builder()
            .id("pipeline-e2e")
            .eventPublisher(pub)
            .eventsMicrostore(store)
            .eventCodec(eventCodec)
            .eventFilter(EventFilters.classes(TestEvent::class))
            .emit { uow ->
                listOf(
                    TestEvent(type = "DerivedEvent")
                )
            }
            .build()

        val tce = DummyTableChangeEvent(
            id = "tce-e2e",
            pk = "order#99",
            data = "d1",
            discriminator = "CORREL",
            suffix = null, // the main partition: a pipeline without a suffix reacts only to these
            event = """{"id":"init-1","type":"TestEvent"}"""
        )
        val uow = UnitOfWork(event = tce)

        val results = pipeline.connect(fm, flowOf(uow)).toList()

        results shouldHaveSize 1
        val published = pub.getUows()
        published shouldHaveSize 1
        published.first().event?.id shouldBe "tce-e2e.pipeline-e2e"
        published.first().event?.eventType() shouldBe "DerivedEvent"
    }

    @Test
    fun `normalize should populate queryParams with consistentRead true by default`() {
        val pipeline = EvaluatePipeline.builder()
            .id("pipeline-consistent-default")
            .eventsMicrostore(eventsMicrostore)
            .eventCodec(eventCodec)
            .build()

        val tce = DummyTableChangeEvent(
            pk = "order#1",
            data = "d1",
            discriminator = "CORREL",
            event = """{"id":"init-1","type":"TestEvent"}"""
        )
        val uow = UnitOfWork(event = tce)

        val normalized = pipeline.normalize(uow)

        val queryParams = normalized.queryParams.shouldNotBeNull()
        queryParams.consistentRead shouldBe true
    }

    @Test
    fun `normalize should populate queryParams with configured consistentRead value`() {
        val pipeline = EvaluatePipeline.builder()
            .id("pipeline-consistent-false")
            .eventsMicrostore(eventsMicrostore)
            .eventCodec(eventCodec)
            .consistentRead(false)
            .build()

        val tce = DummyTableChangeEvent(
            pk = "order#1",
            data = "d1",
            discriminator = "CORREL",
            event = """{"id":"init-1","type":"TestEvent"}"""
        )
        val uow = UnitOfWork(event = tce)

        val normalized = pipeline.normalize(uow)

        val queryParams = normalized.queryParams.shouldNotBeNull()
        queryParams.consistentRead shouldBe false
    }
}
