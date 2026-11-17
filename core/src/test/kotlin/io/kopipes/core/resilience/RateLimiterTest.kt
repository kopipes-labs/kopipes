package io.kopipes.core.resilience

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class RateLimiterTest {

    @Test
    fun `within the budget it doesn't wait, beyond it waits for the next second`(): Unit = runBlocking {
        // Arrange
        var now = 10_250L
        val slept = mutableListOf<Long>()
        val limiter = RateLimiter(InMemoryRateStore(), perSecond = 2, clock = { now }, sleep = { slept += it; now += it })

        // Act
        repeat(3) { limiter.acquire() }

        // Assert
        slept shouldBe listOf(750L)
        now shouldBe 11_000L
    }
}
