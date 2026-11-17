package org.myorg.payments

import io.kopipes.core.resilience.CircuitBreaker
import io.kopipes.core.resilience.InMemoryBreakerStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.net.http.HttpTimeoutException
import kotlin.time.Duration.Companion.seconds

class InMemoryClaims : Claims {
    val states = mutableMapOf<String, String>()
    override suspend fun claim(key: String, now: Long) = if (key in states) false else { states[key] = "CALLING"; true }
    override suspend fun markSent(key: String) { states[key] = "SENT" }
    override suspend fun release(key: String) { states.remove(key) }
}

/** A core checking whose next calls fail as told, and that counts the calls that reached it. */
class FakeCoreChecking : CoreChecking {
    val calls = mutableListOf<String>()
    var failWith: (() -> Exception)? = null

    override suspend fun debit(payment: Payment) = call("debit ${payment.id}")
    override suspend fun credit(payment: Payment) = call("credit ${payment.id}")

    private fun call(what: String) {
        failWith?.let { fail -> if (fail() !is CallNotSent) calls += what; throw fail() }
        calls += what
    }
}

class CalledOnceTest {

    private val p7 = Payment("p-7", "u-42", Party(Party.CHECKING, "C-1"), Party(Party.CARD, "K-2"), "100.00", "USD", 1L)
    private val coreChecking = FakeCoreChecking()
    private val claims = InMemoryClaims()
    private val breakerStore = InMemoryBreakerStore()
    private val calledOnce = CalledOnce(coreChecking, claims, CircuitBreaker(breakerStore, threshold = 2, openFor = 30.seconds))

    @Test
    fun `a request is called once, even when its event is delivered twice`(): Unit = runBlocking {
        // Act
        val first = calledOnce.debit(p7)
        val again = calledOnce.debit(p7)

        // Assert
        first shouldBe Outcome.SENT
        again shouldBe Outcome.ALREADY_CALLED
        coreChecking.calls shouldBe listOf("debit p-7")
        claims.states["p-7#PMT"] shouldBe "SENT"
    }

    @Test
    fun `the withdrawal and the refund of one payment are two requests`(): Unit = runBlocking {
        // Act
        calledOnce.debit(p7)
        calledOnce.credit(p7)

        // Assert
        coreChecking.calls shouldBe listOf("debit p-7", "credit p-7")
    }

    @Test
    fun `a timed out call is in doubt - a fault for the operator, and never called again`(): Unit = runBlocking {
        // Arrange
        coreChecking.failWith = { HttpTimeoutException("request timed out") }

        // Act
        val doubt = shouldThrow<CallInDoubt> { calledOnce.debit(p7) }
        coreChecking.failWith = null
        val redelivered = calledOnce.debit(p7)

        // Assert
        doubt.message!! shouldContain "Check core checking's ledger for reference p-7"
        redelivered shouldBe Outcome.ALREADY_CALLED
        coreChecking.calls shouldBe listOf("debit p-7")
    }

    @Test
    fun `a call that never left waits, and the next delivery calls`(): Unit = runBlocking {
        // Arrange
        coreChecking.failWith = { CallNotSent("connection refused") }

        // Act
        shouldThrow<CallNotSent> { calledOnce.debit(p7) }
        coreChecking.failWith = null
        val retried = calledOnce.debit(p7)

        // Assert
        retried shouldBe Outcome.SENT
        coreChecking.calls shouldBe listOf("debit p-7")
    }

    @Test
    fun `a call core checking refused is a fault, not retried`(): Unit = runBlocking {
        // Arrange
        coreChecking.failWith = { CallRefused("400 malformed") }

        // Act
        shouldThrow<CallRefused> { calledOnce.debit(p7) }
        coreChecking.failWith = null
        val redelivered = calledOnce.debit(p7)

        // Assert
        redelivered shouldBe Outcome.ALREADY_CALLED
    }

    @Test
    fun `while core checking is down, the breaker opens and requests wait without calling`(): Unit = runBlocking {
        // Arrange
        coreChecking.failWith = { CallNotSent("connection refused") }
        repeat(2) { shouldThrow<CallNotSent> { calledOnce.debit(p7.copy(id = "p-$it")) } }
        coreChecking.failWith = null

        // Act
        val waiting = shouldThrow<CoreCheckingUnavailable> { calledOnce.debit(p7) }

        // Assert
        waiting.message!! shouldContain "unavailable"
        coreChecking.calls shouldBe emptyList()
        claims.states.isEmpty() shouldBe true
    }
}
