package io.kopipes.core.resilience

import mu.KotlinLogging
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A [CircuitBreaker]'s state, as stored. Several Lambda containers share it, so writes are optimistic. */
data class BreakerState(
    val state: String = CLOSED,
    /** Consecutive failures while closed. */
    val failures: Int = 0,
    /** While open: when a trial call may be made. While half-open: when the trial started. */
    val until: Long = 0,
    /** How many times the breaker has opened: what an operator looks at. */
    val opens: Int = 0,
    /** Every write must be based on the version it read. */
    val version: Long = 0,
) {
    companion object {
        const val CLOSED = "CLOSED"
        const val OPEN = "OPEN"
        const val HALF_OPEN = "HALF_OPEN"
    }
}

/** Where a breaker keeps its state, shared by every container of a function. */
interface BreakerStore {
    suspend fun read(): BreakerState

    /** Writes [next] only if the stored version is still [expectedVersion]; `false` if someone else wrote first. */
    suspend fun write(next: BreakerState, expectedVersion: Long): Boolean
}

/** Keeps the state in memory: one container only, or tests. */
class InMemoryBreakerStore(initial: BreakerState = BreakerState()) : BreakerStore {
    private val state = AtomicReference(initial)
    override suspend fun read(): BreakerState = state.get()
    override suspend fun write(next: BreakerState, expectedVersion: Long): Boolean =
        state.get().let { current -> current.version == expectedVersion && state.compareAndSet(current, next) }
}

/**
 * A circuit breaker in front of an outside system that is sometimes down.
 *
 * - **Closed:** calls go through; consecutive failures are counted. After [threshold] of them it opens.
 * - **Open:** no calls for [openFor]. Callers should hold their requests (let the stream retry them), not drop them.
 * - **Half-open:** one trial call goes through; success closes the breaker, failure opens it again.
 * - [unavailable] makes it open with no failures at all, for known closed hours ("closed at night").
 *
 * Call [admit] before calling, then [succeeded] or [failed].
 */
class CircuitBreaker(
    private val store: BreakerStore,
    private val threshold: Int = 3,
    private val openFor: Duration = 30.seconds,
    private val unavailable: (epochMillis: Long) -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val logger = KotlinLogging.logger { }

    /** May a call be made now? `null` if yes; otherwise when to try again, in epoch milliseconds. */
    suspend fun admit(): Long? {
        val now = clock()
        if (unavailable(now)) return now + openFor.inWholeMilliseconds

        val current = store.read()
        return when (current.state) {
            BreakerState.CLOSED -> null
            BreakerState.OPEN ->
                if (now < current.until) current.until
                // The cool-down is over: whoever wins this write makes the trial call; the others keep waiting.
                else if (store.write(current.copy(state = BreakerState.HALF_OPEN, until = now, version = current.version + 1), current.version)) null
                else now + openFor.inWholeMilliseconds
            // A trial is under way. If it got lost (its function died), allow another after a cool-down.
            else ->
                if (now - current.until <= openFor.inWholeMilliseconds) current.until + openFor.inWholeMilliseconds
                else if (store.write(current.copy(until = now, version = current.version + 1), current.version)) null
                else now + openFor.inWholeMilliseconds
        }
    }

    suspend fun succeeded() {
        val current = store.read()
        if (current.state == BreakerState.CLOSED && current.failures == 0) return
        val next = current.copy(state = BreakerState.CLOSED, failures = 0, version = current.version + 1)
        if (store.write(next, current.version) && current.state != BreakerState.CLOSED) logger.warn { "breaker closed: the system is back" }
    }

    suspend fun failed() {
        val current = store.read()
        val failures = current.failures + 1
        val opens = current.state == BreakerState.HALF_OPEN || (current.state == BreakerState.CLOSED && failures >= threshold)
        val next = when {
            opens -> current.copy(
                state = BreakerState.OPEN, failures = 0, until = clock() + openFor.inWholeMilliseconds,
                opens = current.opens + 1, version = current.version + 1,
            )
            current.state == BreakerState.CLOSED -> current.copy(failures = failures, version = current.version + 1)
            else -> return
        }
        if (store.write(next, current.version) && opens) logger.warn { "breaker open until ${next.until}" }
    }
}
