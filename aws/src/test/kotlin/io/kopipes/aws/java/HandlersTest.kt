package io.kopipes.aws.java

import io.kopipes.aws.AwsEnvironmentConfig
import io.kopipes.aws.AwsKopipesContext
import io.kopipes.core.UnitOfWork
import io.kopipes.core.metrics.MetricsReporter
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class HandlersTest {

    private class RecordingReporter : MetricsReporter {
        val reports = mutableListOf<Map<String, Any>>()
        override fun report(metrics: Map<String, Any>) {
            reports.add(metrics)
        }
    }

    private fun batch() = flowOf(UnitOfWork(key = "a"), UnitOfWork(key = "b"))

    @Test
    fun `collectMetrics with a reporter reports the batch`(): Unit = runBlocking {
        // Arrange
        val reporter = RecordingReporter()

        // Act
        val uows = Handlers.collectMetrics(batch(), reporter).toList()

        // Assert
        uows.map { it.key } shouldBe listOf("a", "b")
        reporter.reports shouldHaveSize 1
        reporter.reports.single() shouldContain ("stream.uow.count" to 2.0)
    }

    @Test
    fun `collectMetrics with a reporter includes the function metrics`(): Unit = runBlocking {
        // Arrange
        val reporter = RecordingReporter()

        // Act
        Handlers.collectMetrics(batch(), mapOf("cold.start" to 1.0), reporter).toList()

        // Assert
        reporter.reports.single() shouldContain ("cold.start" to 1.0)
    }

    @Test
    fun `collectMetrics with a context passes the batch through`(): Unit = runBlocking {
        // Arrange
        val context = AwsKopipesContext(envConfig = object : AwsEnvironmentConfig() {
            override fun env(name: String): String? = if (name == "METRICS") "emf" else null
        })

        // Act
        val uows = Handlers.collectMetrics(batch(), context).toList()

        // Assert
        uows.map { it.key } shouldBe listOf("a", "b")
    }
}
