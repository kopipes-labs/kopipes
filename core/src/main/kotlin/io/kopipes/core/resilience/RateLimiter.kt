package io.kopipes.core.resilience

import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Counts uses of a per-second budget. Shared by every container of a function, so the budget holds for all. */
interface RateStore {
    /** Takes one unit of second [epochSecond]'s budget if fewer than [limit] were taken. */
    suspend fun take(epochSecond: Long, limit: Int): Boolean
}

/** Keeps the budget in memory: one container only, or tests. */
class InMemoryRateStore : RateStore {
    private val used = ConcurrentHashMap<Long, AtomicInteger>()
    override suspend fun take(epochSecond: Long, limit: Int): Boolean =
        used.computeIfAbsent(epochSecond) { AtomicInteger() }.incrementAndGet() <= limit
}

/**
 * Paces calls to an outside system at its allowed rate: a fixed budget of [perSecond] calls per second. With none
 * left this second, [acquire] waits for the next; the backlog waits on the stream. Unlike `Flow.rateLimit`, the
 * budget lives in a [RateStore], so it holds across every container reading every shard.
 */
class RateLimiter(
    private val store: RateStore,
    private val perSecond: Int,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun acquire() {
        while (true) {
            val now = clock()
            val second = now / 1000
            if (store.take(second, perSecond)) return
            sleep((second + 1) * 1000 - now)
        }
    }
}
