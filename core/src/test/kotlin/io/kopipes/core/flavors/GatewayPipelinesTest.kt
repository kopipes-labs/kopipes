package io.kopipes.core.flavors

import io.kopipes.core.Event
import io.kopipes.core.UnitOfWork
import io.kopipes.core.faults.FaultManager
import io.kopipes.core.filters.EventFilters
import io.kopipes.core.flavors.EvaluatePipelineTest.TestEvent
import io.kopipes.core.sinks.EventPublisherInMemory
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** The flavors of the gateways at a subsystem's edge: [ActionPipeline] (egress) and [TranslatePipeline] (ingress). */
class GatewayPipelinesTest {

    private val publisher = EventPublisherInMemory()
    private val fm = FaultManager(publisher, skipErrorLogging = true)

    @Test
    fun `an action pipeline acts on the events it filters, and publishes nothing`(): Unit = runBlocking {
        // Arrange
        val acted = mutableListOf<String?>()
        val pipeline = ActionPipeline.builder()
            .id("call")
            .eventFilter(EventFilters.name("REQUESTED"))
            .action { uow -> acted += uow.event?.id }
            .build()
        val events = listOf(TestEvent(id = "e-1", type = "REQUESTED"), TestEvent(id = "e-2", type = "OTHER"))

        // Act
        pipeline.connect(fm, flowOf(*events.map { UnitOfWork(event = it) }.toTypedArray())).toList()

        // Assert
        acted shouldBe listOf("e-1")
        publisher.getUows() shouldHaveSize 0
    }

    @Test
    fun `an action that throws becomes a fault, and the others still run`(): Unit = runBlocking {
        // Arrange
        val acted = mutableListOf<String?>()
        val pipeline = ActionPipeline.builder()
            .id("call")
            .action { uow -> if (uow.event?.id == "e-1") error("boom") else acted += uow.event?.id }
            .build()

        // Act
        pipeline.connect(fm, flowOf(UnitOfWork(event = TestEvent(id = "e-1")), UnitOfWork(event = TestEvent(id = "e-2")))).toList()
        fm.flushFaults()

        // Assert
        acted shouldBe listOf("e-2")
        publisher.faults() shouldHaveSize 1
    }

    @Test
    fun `an emitting action publishes the events it returns`(): Unit = runBlocking {
        // Arrange
        val pipeline = ActionPipeline.builder()
            .id("call")
            .eventPublisher(publisher)
            .actionEmitting { uow -> if (uow.event?.id == "refused") listOf(TestEvent(id = "failed", type = "FAILED")) else emptyList() }
            .build()

        // Act
        pipeline.connect(fm, flowOf(UnitOfWork(event = TestEvent(id = "ok")), UnitOfWork(event = TestEvent(id = "refused")))).toList()

        // Assert
        publisher.getUows().map { it.event!!.id } shouldBe listOf("failed")
    }

    @Test
    fun `a translate pipeline turns a raw record into events and publishes them`(): Unit = runBlocking {
        // Arrange
        val pipeline = TranslatePipeline.builder()
            .id("translate")
            .eventPublisher(publisher)
            .onContentType { it.record is String }
            .toEvents { uow ->
                val row = uow.record as String
                if (row.startsWith("ours")) listOf<Event>(TestEvent(id = row, type = "TRANSLATED")) else emptyList()
            }
            .build()
        val records = listOf("ours-1", "theirs-1", 42).map { UnitOfWork(record = it) }

        // Act
        val results = pipeline.connect(fm, flowOf(*records.toTypedArray())).toList()

        // Assert
        results shouldHaveSize 1
        publisher.getUows().single().event!!.id shouldBe "ours-1"
    }
}
