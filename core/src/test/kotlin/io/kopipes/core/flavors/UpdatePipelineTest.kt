package io.kopipes.core.flavors

import io.kopipes.core.*
import io.kopipes.core.faults.FaultManager
import io.kopipes.core.filters.EventFilter
import io.kopipes.core.filters.EventFilters
import io.kopipes.core.sinks.Entity
import io.kopipes.core.sinks.EntityStore
import io.kopipes.core.sinks.EntityStoreInMemory
import io.kopipes.core.sinks.EventPublisherInMemory
import io.kopipes.core.sinks.entity
import io.kopipes.core.sinks.entityUpdated
import io.kopipes.core.sinks.loadedEntities
import io.kopipes.core.sinks.queriedEntities
import io.kopipes.core.sinks.withQueriedEntities
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContainAll
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class UpdatePipelineTest {

    /** An events-table item, as a stream adapter would hand it over. */
    class EventsTableItem(
        private val attributes: Map<String, String?>,
        private val deleted: Boolean = false,
    ) : TableChangeEvent() {
        override val id: String? = "item-1"
        override val timestamp: Long? = 1_700_000_000_000L
        override val partitionKey: String? = attributes["pk"]
        override val tags: Map<String, String>? = null
        override val raw: RawRecord? = null
        override val eem: EnvelopeEncryptionMetadata? = null
        override val triggers: List<EventReference>? = null
        override val type: String? = null
        override fun getPk() = attributes["pk"]
        override fun getTtl() = attributes["ttl"]
        override fun getData() = attributes["data"]
        override fun getEvent() = attributes["event"]
        override fun getDiscriminator() = attributes["discriminator"]
        override fun getSuffix() = attributes["suffix"]
        override fun isDeleted() = deleted
        override fun latched() = false
        override fun getS(fieldName: String) = attributes[fieldName]
        override fun getDouble(fieldName: String): Double? = null
        override fun getLong(fieldName: String): Long? = null
        override fun copyEvent(
            id: String?, timestamp: Long?, partitionKey: String?, tags: Map<String, String>?,
            raw: RawRecord?, eem: EnvelopeEncryptionMetadata?, triggers: List<EventReference>?,
        ): Event = this
    }

    private fun event(id: String = "e1", type: String = "thing-updated", partitionKey: String = "group-1") =
        JsonEvent("""{"id":"$id","type":"$type","timestamp":1700000000000,"partitionKey":"$partitionKey"}""")

    private fun pipeline(
        store: EntityStore,
        toQuery: ((UnitOfWork) -> EntityStore.Query?)? = null,
        toGet: ((UnitOfWork) -> List<EntityStore.Key>?)? = null,
        eventFilter: EventFilter = EventFilter.Any,
        toUpdate: suspend (UnitOfWork) -> EntityStore.Update?,
    ) = UpdatePipeline.builder()
        .id("update")
        .entityStore(store)
        .eventFilter(eventFilter)
        .apply { toQuery?.let { toQuery(it) } }
        .apply { toGet?.let { toGet(it) } }
        .toUpdate(toUpdate)
        .build()

    private fun run(pipeline: UpdatePipeline, vararg uows: UnitOfWork): List<UnitOfWork> = runBlocking {
        pipeline.connect(FaultManager(EventPublisherInMemory()), flowOf(*uows)).toList()
    }

    @Test
    fun `normalize unwraps a collected event and keeps its ttl and data in meta`() {
        // Arrange
        val pipeline = pipeline(EntityStoreInMemory()) { null }
        val item = EventsTableItem(
            mapOf("pk" to "e1", "sk" to "EVENT", "ttl" to "1700", "data" to "group-1", "event" to event().toString())
        )

        // Act
        val result = pipeline.normalize(UnitOfWork(event = item, meta = mapOf("kept" to "yes")))

        // Assert
        result.event?.id shouldBe "e1"
        result.event?.eventType() shouldBe "thing-updated"
        result.meta shouldBe mapOf("kept" to "yes", "ttl" to "1700", "data" to "group-1")
    }

    @Test
    fun `normalize passes on units that are not collected events`() {
        // Arrange
        val pipeline = pipeline(EntityStoreInMemory()) { null }
        val plain = UnitOfWork(event = event())
        val notAnEvent = UnitOfWork(event = EventsTableItem(mapOf("pk" to "p", "sk" to "OTHER", "event" to "{}")))
        val deleted = UnitOfWork(event = EventsTableItem(mapOf("sk" to "EVENT", "event" to "{}"), deleted = true))

        // Act & Assert
        pipeline.normalize(plain) shouldBeSameInstanceAs plain
        pipeline.normalize(notAnEvent) shouldBeSameInstanceAs notAnEvent
        pipeline.normalize(deleted) shouldBeSameInstanceAs deleted
    }

    @Test
    fun `normalize uses a custom predicate and normalizer`() {
        // Arrange
        val pipeline = UpdatePipeline.builder()
            .id("update")
            .entityStore(EntityStoreInMemory())
            .toUpdate { null }
            .isCollectedEvent { uow -> uow.key == "collected" }
            .normalizer { uow -> uow.copy(meta = mapOf("normalized" to "true")) }
            .build()

        // Act
        val collected = pipeline.normalize(UnitOfWork(key = "collected"))
        val other = pipeline.normalize(UnitOfWork(key = "other"))

        // Assert
        collected.meta shouldBe mapOf("normalized" to "true")
        other.meta.shouldBeNull()
    }

    @Test
    fun `splitQueried makes one unit per entity without the whole result`() {
        // Arrange
        val pipeline = pipeline(EntityStoreInMemory()) { null }
        val entities: List<Entity> = listOf(mapOf("pk" to "a", "sk" to "1"), mapOf("pk" to "a", "sk" to "2"))
        val uow = UnitOfWork(event = event()).withQueriedEntities(entities)

        // Act
        val split = pipeline.splitQueried(uow)

        // Assert
        split.map { it.entity } shouldBe entities
        split.forEach { it.queriedEntities.shouldBeNull() }
    }

    @Test
    fun `splitQueried keeps the unit when the query found nothing`() {
        // Arrange
        val pipeline = pipeline(EntityStoreInMemory()) { null }
        val uow = UnitOfWork(event = event()).withQueriedEntities(emptyList())

        // Act & Assert
        pipeline.splitQueried(uow) shouldBe listOf(uow)
    }

    @Test
    fun `updates the entity an event names, without a query`() {
        // Arrange
        val store = EntityStoreInMemory()
        val pipeline = pipeline(store) { uow ->
            EntityStore.Update(EntityStore.Key("thing", uow.event!!.id!!), set = mapOf("status" to "seen"))
        }

        // Act
        val result = run(pipeline, UnitOfWork(event = event("e1")))

        // Assert
        result.single().entityUpdated shouldBe true
        store.get(EntityStore.Key("thing", "e1")) shouldBe mapOf("pk" to "thing", "sk" to "e1", "status" to "seen")
    }

    @Test
    fun `fans one event out to every entity its query finds`() {
        // Arrange
        val store = EntityStoreInMemory().apply {
            put(mapOf("pk" to "a", "sk" to "1", "group" to "group-1"))
            put(mapOf("pk" to "b", "sk" to "1", "group" to "group-1"))
            put(mapOf("pk" to "c", "sk" to "1", "group" to "group-2"))
        }
        val pipeline = pipeline(
            store,
            toQuery = { uow -> EntityStore.Query(uow.event!!.partitionKey!!, attribute = "group", index = "GroupIndex") },
        ) { uow ->
            val entity = uow.entity!!
            EntityStore.Update(EntityStore.Key(entity["pk"] as String, entity["sk"] as String), add = mapOf("hits" to 1))
        }

        // Act
        val result = run(pipeline, UnitOfWork(event = event(partitionKey = "group-1")))

        // Assert
        result shouldHaveSize 2
        store.get(EntityStore.Key("a", "1"))!!["hits"] shouldBe 1L
        store.get(EntityStore.Key("b", "1"))!!["hits"] shouldBe 1L
        store.get(EntityStore.Key("c", "1"))!!["hits"].shouldBeNull()
    }

    @Test
    fun `loads the entities toGet names before building the update`() {
        // Arrange
        val store = EntityStoreInMemory().apply {
            put(mapOf("pk" to "customer", "sk" to "c1", "name" to "Ada"))
        }
        val pipeline = pipeline(
            store,
            toGet = { listOf(EntityStore.Key("customer", "c1"), EntityStore.Key("customer", "missing")) },
        ) { uow ->
            EntityStore.Update(EntityStore.Key("order", "o1"), set = mapOf("customerName" to uow.loadedEntities!!.single()["name"]))
        }

        // Act
        val result = run(pipeline, UnitOfWork(event = event()))

        // Assert
        result.single().loadedEntities!! shouldHaveSize 1
        store.get(EntityStore.Key("order", "o1"))!! shouldContainAll mapOf("customerName" to "Ada")
    }

    @Test
    fun `a stale event leaves a newer entity alone`() {
        // Arrange
        val store = EntityStoreInMemory().apply {
            put(mapOf("pk" to "thing", "sk" to "1", "timestamp" to 200L, "status" to "new"))
        }
        val pipeline = pipeline(store) { uow ->
            EntityStore.Update(
                EntityStore.Key("thing", "1"),
                set = mapOf("timestamp" to uow.event!!.timestamp, "status" to "old"),
                onlyIfNewer = "timestamp",
            )
        }

        // Act
        val result = run(pipeline, UnitOfWork(event = JsonEvent("""{"id":"e1","type":"t","timestamp":100}""")))

        // Assert
        result.single().entityUpdated shouldBe false
        store.get(EntityStore.Key("thing", "1"))!!["status"] shouldBe "new"
    }

    @Test
    fun `units without an update or outside the filter write nothing`() {
        // Arrange
        val store = EntityStoreInMemory()
        val pipeline = pipeline(store, eventFilter = EventFilters.name("wanted")) { uow ->
            if (uow.event?.id == "skip") null
            else EntityStore.Update(EntityStore.Key("thing", uow.event!!.id!!), set = mapOf("status" to "seen"))
        }

        // Act
        val result = run(
            pipeline,
            UnitOfWork(event = event("skip", type = "wanted")),
            UnitOfWork(event = event("other", type = "unwanted")),
        )

        // Assert
        result.single().entityUpdated.shouldBeNull()
        store.getStored().keys.shouldBeEmpty()
    }

    @Test
    fun `build requires an entity store and toUpdate`() {
        // Act & Assert
        shouldThrow<IllegalArgumentException> {
            UpdatePipeline.builder().id("update").toUpdate { null }.build()
        }.message shouldBe "entityStore is required"
        shouldThrow<IllegalArgumentException> {
            UpdatePipeline.builder().id("update").entityStore(EntityStoreInMemory()).build()
        }.message shouldBe "toUpdate is required"
    }
}
