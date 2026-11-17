package io.kopipes.core.flavors

import io.kopipes.core.UnitOfWork
import io.kopipes.core.faults.FaultManager
import io.kopipes.core.flavors.EvaluatePipelineTest.DummyTableChangeEvent
import io.kopipes.core.flavors.EvaluatePipelineTest.SimpleEventCodec
import io.kopipes.core.flavors.EvaluatePipelineTest.TestEvent
import io.kopipes.core.sinks.EventPublisherInMemory
import io.kopipes.core.sinks.EventsMicrostoreInMemory
import io.kopipes.core.sinks.queryParams
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** Deterministic emitted ids, side partitions (suffixes) and deadlines (`onExpired`). */
class EvaluatePipelineDeadlinesTest {

    private val publisher = EventPublisherInMemory()
    private val fm = FaultManager(publisher)

    private fun pipeline(configure: EvaluatePipeline.Builder.() -> Unit): EvaluatePipeline =
        EvaluatePipeline.builder()
            .id("rule")
            .eventPublisher(publisher)
            .eventsMicrostore(EventsMicrostoreInMemory())
            .eventCodec(SimpleEventCodec())
            .emit { listOf(TestEvent(type = "DERIVED")) }
            .apply(configure)
            .build()

    private fun correlation(suffix: String? = null, expired: Boolean = false, ttl: String? = "1700000120") =
        DummyTableChangeEvent(
            id = "stream-record-1",
            pk = if (suffix == null) "p-7" else "p-7.$suffix",
            suffix = suffix,
            ttl = ttl,
            expired = expired,
        )

    @Test
    fun `oneEventPerCorrelationKey gives the emitted event the id key dot pipeline`(): Unit = runBlocking {
        // Arrange
        val rule = pipeline { oneEventPerCorrelationKey() }

        // Act
        rule.connect(fm, flowOf(UnitOfWork(event = correlation()))).toList()

        // Assert
        publisher.getUows().single().event!!.id shouldBe "p-7.rule"
    }

    @Test
    fun `emittedEventId computes the id from the normalized unit of work`(): Unit = runBlocking {
        // Arrange
        val rule = pipeline { emittedEventId { uow -> "${uow.correlationKey()}.custom" } }

        // Act
        rule.connect(fm, flowOf(UnitOfWork(event = correlation()))).toList()

        // Assert
        publisher.getUows().single().event!!.id shouldBe "p-7.custom"
    }

    @Test
    fun `a rule without a suffix ignores items in a side partition, even without an expression`(): Unit = runBlocking {
        // Arrange
        val rule = pipeline { }

        // Act
        val results = rule.connect(fm, flowOf(UnitOfWork(event = correlation(suffix = "deadline")))).toList()

        // Assert
        results.shouldBeEmpty()
    }

    @Test
    fun `a regular rule ignores an expired deadline`() {
        // Arrange
        val rule = pipeline { correlationKeySuffix("deadline") }

        // Act
        val accepted = rule.forEvents(UnitOfWork(event = correlation(suffix = "deadline", expired = true)))

        // Assert
        accepted shouldBe false
    }

    @Test
    fun `an onExpired rule ignores everything but expired deadlines`() {
        // Arrange
        val rule = pipeline { onExpired().correlationKeySuffix("deadline") }

        // Act
        val onInsert = rule.forEvents(UnitOfWork(event = correlation(suffix = "deadline")))
        val onExpiry = rule.forEvents(UnitOfWork(event = correlation(suffix = "deadline", expired = true)))
        val onPlainEvent = rule.forEvents(UnitOfWork(event = TestEvent()))

        // Assert
        onInsert shouldBe false
        onExpiry shouldBe true
        onPlainEvent shouldBe false
    }

    @Test
    fun `an expired deadline queries the whole key and dates the emitted event at the deadline`(): Unit = runBlocking {
        // Arrange
        val rule = pipeline {
            onExpired()
            correlationKeySuffix("deadline")
            expression { true }
            oneEventPerCorrelationKey()
        }
        val uow = UnitOfWork(event = correlation(suffix = "deadline", expired = true, ttl = "1700000120"))

        // Act
        val normalized = rule.normalize(uow)
        rule.connect(fm, flowOf(uow)).toList()

        // Assert
        normalized.queryParams!!.pk shouldBe "p-7"
        normalized.correlationKey() shouldBe "p-7"
        val emitted = publisher.getUows().single().event!!
        emitted.id shouldBe "p-7.rule"
        emitted.timestamp shouldBe 1_700_000_120_000L
    }

    @Test
    fun `an onCorrelated rule ignores collected events`() {
        // Arrange
        val rule = pipeline { onCorrelated() }
        val collected = DummyTableChangeEvent(pk = "evt-1", discriminator = "EVENT", suffix = "")

        // Act
        val onCollected = rule.forEvents(UnitOfWork(event = collected))
        val onCorrelation = rule.forEvents(UnitOfWork(event = correlation()))

        // Assert
        onCollected shouldBe false
        onCorrelation shouldBe true
    }

    @Test
    fun `an empty suffix leaves dots in the correlation key alone`() {
        // Arrange
        val rule = pipeline { }
        val uow = UnitOfWork(event = DummyTableChangeEvent(pk = "order.2024.7", suffix = ""))

        // Act
        val normalized = rule.normalize(uow)

        // Assert
        normalized.correlationKey() shouldBe "order.2024.7"
    }

    @Test
    fun `a regular rule in a side partition still queries that partition`() {
        // Arrange
        val rule = pipeline { correlationKeySuffix("deadline") }

        // Act
        val normalized = rule.normalize(UnitOfWork(event = correlation(suffix = "deadline")))

        // Assert
        normalized.queryParams!!.pk shouldBe "p-7.deadline"
        publisher.getUows() shouldHaveSize 0
    }
}
