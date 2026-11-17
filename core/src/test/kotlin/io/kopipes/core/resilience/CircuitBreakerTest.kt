package io.kopipes.core.resilience

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class CircuitBreakerTest {

    private var now = 1_000_000L
    private val store = InMemoryBreakerStore()
    private fun breaker(unavailable: (Long) -> Boolean = { false }) =
        CircuitBreaker(store, threshold = 3, openFor = 30.seconds, unavailable = unavailable, clock = { now })

    @Test
    fun `closed, it admits calls, and after threshold failures in a row it opens`(): Unit = runBlocking {
        // Arrange
        val breaker = breaker()

        // Act
        val before = breaker.admit()
        repeat(3) { breaker.failed() }
        val after = breaker.admit()

        // Assert
        before.shouldBeNull()
        after shouldBe now + 30_000
        store.read().opens shouldBe 1
    }

    @Test
    fun `a success resets the count of failures`(): Unit = runBlocking {
        // Arrange
        val breaker = breaker()

        // Act
        repeat(2) { breaker.failed() }
        breaker.succeeded()
        breaker.failed()

        // Assert
        breaker.admit().shouldBeNull()
    }

    @Test
    fun `after the cool-down one trial goes through, and its success closes the breaker`(): Unit = runBlocking {
        // Arrange
        val breaker = breaker()
        repeat(3) { breaker.failed() }
        now += 31_000

        // Act
        val trial = breaker.admit()
        val another = breaker.admit()
        breaker.succeeded()
        val afterSuccess = breaker.admit()

        // Assert
        trial.shouldBeNull()
        another.shouldNotBeNull()
        afterSuccess.shouldBeNull()
        store.read().state shouldBe BreakerState.CLOSED
    }

    @Test
    fun `a failed trial opens it again`(): Unit = runBlocking {
        // Arrange
        val breaker = breaker()
        repeat(3) { breaker.failed() }
        now += 31_000
        breaker.admit()

        // Act
        breaker.failed()

        // Assert
        store.read().state shouldBe BreakerState.OPEN
        store.read().opens shouldBe 2
    }

    @Test
    fun `during closed hours it admits nothing, without any failure`(): Unit = runBlocking {
        // Arrange
        val breaker = breaker(unavailable = { true })

        // Act
        val retryAt = breaker.admit()

        // Assert
        retryAt shouldBe now + 30_000
    }

    @Test
    fun `a write based on a stale version loses`(): Unit = runBlocking {
        // Arrange
        val stale = store.read()
        store.write(stale.copy(failures = 1, version = 1), expectedVersion = 0)

        // Act
        val written = store.write(stale.copy(failures = 5, version = 1), expectedVersion = 0)

        // Assert
        written shouldBe false
        store.read().failures shouldBe 1
    }
}
