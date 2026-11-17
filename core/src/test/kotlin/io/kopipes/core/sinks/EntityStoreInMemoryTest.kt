package io.kopipes.core.sinks

import io.kopipes.core.UnitOfWork
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class EntityStoreInMemoryTest {

    private val key = EntityStore.Key("thing", "1")

    @Test
    fun `apply creates a missing entity with its key`() {
        // Arrange
        val store = EntityStoreInMemory()

        // Act
        val applied = store.apply(EntityStore.Update(key, set = mapOf("status" to "new", "note" to null)))

        // Assert
        applied shouldBe true
        store.get(key) shouldBe mapOf("pk" to "thing", "sk" to "1", "status" to "new", "note" to null)
    }

    @Test
    fun `apply sets, adds and removes attributes`() {
        // Arrange
        val store = EntityStoreInMemory().apply {
            put(mapOf("pk" to "thing", "sk" to "1", "status" to "new", "count" to 2L, "total" to 1.5, "latched" to true))
        }

        // Act
        store.apply(
            EntityStore.Update(
                key,
                set = mapOf("status" to "done"),
                add = mapOf("count" to 3, "total" to 1, "fresh" to 4),
                remove = setOf("latched"),
            )
        )

        // Assert
        store.get(key) shouldBe mapOf(
            "pk" to "thing", "sk" to "1", "status" to "done", "count" to 5L, "total" to BigDecimal("2.5"), "fresh" to 4L,
        )
    }

    @Test
    fun `apply with onlyIfNewer applies only over an older or missing value`() {
        // Arrange
        val store = EntityStoreInMemory().apply { put(mapOf("pk" to "thing", "sk" to "1", "timestamp" to 200L)) }
        fun at(timestamp: Long) = EntityStore.Update(key, set = mapOf("timestamp" to timestamp), onlyIfNewer = "timestamp")

        // Act & Assert
        store.apply(at(100)) shouldBe false
        store.apply(at(200)) shouldBe false
        store.apply(at(300)) shouldBe true
        store.get(key)!!["timestamp"] shouldBe 300L
        store.apply(at(1).copy(key = EntityStore.Key("thing", "2"))) shouldBe true
    }

    @Test
    fun `isNewer compares numbers of any type and strings`() {
        // Act & Assert
        EntityStoreInMemory.isNewer(2, 1L) shouldBe true
        EntityStoreInMemory.isNewer(BigDecimal("1.5"), 2) shouldBe false
        EntityStoreInMemory.isNewer("2026-11-17", "2026-11-16") shouldBe true
        EntityStoreInMemory.isNewer("a", null) shouldBe true
        shouldThrow<IllegalArgumentException> { EntityStoreInMemory.isNewer("a", 1) }
    }

    @Test
    fun `query finds the entities whose attribute matches, in key order`() = runBlocking<Unit> {
        // Arrange
        val store = EntityStoreInMemory().apply {
            put(mapOf("pk" to "b", "sk" to "1", "group" to "g1"))
            put(mapOf("pk" to "a", "sk" to "2", "group" to "g1"))
            put(mapOf("pk" to "a", "sk" to "1", "group" to "g2"))
        }
        val uow = UnitOfWork().withEntityQuery(EntityStore.Query("g1", attribute = "group"))

        // Act
        val result = store.query(flowOf(uow)).single()

        // Assert
        result.queriedEntities!!.map { it["pk"] to it["sk"] } shouldBe listOf("a" to "2", "b" to "1")
    }

    @Test
    fun `get loads the entities that exist`() = runBlocking<Unit> {
        // Arrange
        val store = EntityStoreInMemory().apply { put(mapOf("pk" to "thing", "sk" to "1")) }
        val uow = UnitOfWork().withEntityKeys(listOf(key, EntityStore.Key("thing", "missing")))

        // Act
        val result = store.get(flowOf(uow)).single()

        // Assert
        result.loadedEntities shouldBe listOf(mapOf("pk" to "thing", "sk" to "1"))
    }

    @Test
    fun `operations pass on units without an instruction`() = runBlocking<Unit> {
        // Arrange
        val store = EntityStoreInMemory()
        val uow = UnitOfWork(key = "untouched")

        // Act & Assert
        store.query(flowOf(uow)).single() shouldBe uow
        store.get(flowOf(uow)).single() shouldBe uow
        store.update(flowOf(uow)).single().entityUpdated.shouldBeNull()
    }

    @Test
    fun `an update needs an operation and a set onlyIfNewer attribute`() {
        // Act & Assert
        shouldThrow<IllegalArgumentException> { EntityStore.Update(key) }
        shouldThrow<IllegalArgumentException> {
            EntityStore.Update(key, set = mapOf("status" to "x"), onlyIfNewer = "timestamp")
        }
        shouldThrow<IllegalArgumentException> {
            EntityStore.Update(key, set = mapOf("status" to "x"), remove = setOf("status"))
        }
    }
}
