package io.kopipes.core.flavors

import io.kopipes.core.Event
import io.kopipes.core.EventCodec
import io.kopipes.core.PipelineBuilder
import io.kopipes.core.UnitOfWork
import io.kopipes.core.faults.FaultManager
import io.kopipes.core.filters.EventFilter
import io.kopipes.core.filters.filterEvents
import io.kopipes.core.sinks.EventsMicrostore
import io.kopipes.core.sinks.withSaveOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach

/**
 * Pipeline flavor for correlating stream events: saves each collected event again under its correlation key
 * (`pk = <key>`, `sk = <event id>`), so an [EvaluatePipeline] can query everything heard for that key.
 *
 * With a [correlationKeySuffix] the events go to a side partition, `<key>.<suffix>`, that only evaluate pipelines with
 * the same suffix react to. With [expire] and a [ttl], an item there is a **deadline**: when DynamoDB's TTL deletes
 * it, evaluate pipelines built with `onExpired()` run (see docs/Deadlines.md).
 */
class CorrelatePipeline(
    id: String,
    val eventsMicrostore: EventsMicrostore,
    val correlationKeySupplier: (UnitOfWork) -> String? = { it.event?.partitionKey },
    val correlationKeySuffix: String = "",
    val onContentType: (UnitOfWork) -> Boolean = { true },
    val eventFilter: EventFilter = EventFilter.Any,
    val ttlDays: Int? = null,
    val expire: Boolean = false,
    val eventCodec: EventCodec? = null,
    val isCollectedEvent: ((UnitOfWork) -> Boolean)? = null,
    val normalizer: ((UnitOfWork) -> UnitOfWork)? = null,
    /** When the item expires, in epoch seconds. Wins over the `ttl` in the unit's meta and over [ttlDays]. */
    val ttl: ((UnitOfWork) -> Long?)? = null,
) : Pipeline(id) {

    fun forCollectedEvents(uow: UnitOfWork): Boolean {
        if (isCollectedEvent != null) return isCollectedEvent.invoke(uow)
        return uow.event != null
    }

    fun normalize(uow: UnitOfWork): UnitOfWork {
        if (normalizer != null) return normalizer.invoke(uow)
        return uow
    }

    /** `<key>`, or `<key>.<suffix>` with a [correlationKeySuffix]: the shape [EvaluatePipeline] strips back to `<key>`. */
    internal fun suffixed(correlationKey: String): String =
        if (correlationKeySuffix.isEmpty()) correlationKey else "$correlationKey.$correlationKeySuffix"

    private fun addCorrelationKey(uow: UnitOfWork): UnitOfWork {
        return uow.copy(key = correlationKeySupplier(uow)?.let(::suffixed))
    }

    /** Epoch seconds: [ttl], else the ttl the normalizer put in meta, else [ttlDays] after the event. */
    internal fun ttlOf(uow: UnitOfWork): Long {
        ttl?.invoke(uow)?.let { return it }
        uow.meta?.get("ttl")?.toLongOrNull()?.let { return it }
        val days = (ttlDays ?: CollectPipeline.DEFAULT_TTL_DAYS).toLong()
        val from = (uow.event?.timestamp ?: System.currentTimeMillis()) / 1000
        return from + days * 24 * 60 * 60
    }

    fun Flow<UnitOfWork>.save(fm: FaultManager): Flow<UnitOfWork> {
        val flow = with(fm) {
            this@save.mapNotFaulty { uow ->
                val key = uow.key ?: correlationKeySupplier(uow)?.let(::suffixed) ?: uow.event?.id ?: "unknown"
                val event: Event? = uow.event
                val eventId = event?.id ?: "unknown"
                val saveOptions = EventsMicrostore.SaveOptions(
                    pk = key,
                    sk = eventId,
                    discriminator = "CORREL",
                    timeStamp = event?.timestamp ?: System.currentTimeMillis(),
                    sequenceNumber = uow.meta?.get("sequenceNumber")?.toString() ?: uow.sequenceNumber,
                    ttl = ttlOf(uow),
                    includeRaw = false,
                    expire = expire,
                    suffix = correlationKeySuffix,
                    data = eventId,
                    pipelineId = id,
                )
                uow.withSaveOptions(saveOptions)
            }
        }
        return eventsMicrostore.save(flow)
    }

    override fun connect(fm: FaultManager, fromFlow: Flow<UnitOfWork>): Flow<UnitOfWork> {
        logger.info { "CorrelatePipeline.connect: id=$id" }
        with(fm) {
            return fromFlow
                .filterNotFaulty { uow -> forCollectedEvents(uow) }
                .mapNotFaulty { uow -> normalize(uow) }
                .filterEvents(fm, eventFilter)
                .onEach { printStartPipeline(it) }
                .filterNotFaulty { uow -> onContentType(uow) }
                .mapNotFaulty { uow -> addCorrelationKey(uow) }
                .save(fm)
                .onEach { printEndPipeline(it) }
        }
    }

    companion object {
        @JvmStatic
        fun builder() = Builder()
    }

    class Builder : PipelineBuilder<CorrelatePipeline, Builder>() {
        private var eventsMicrostore: EventsMicrostore? = null
        private var correlationKeySupplier: (UnitOfWork) -> String? = { it.event?.partitionKey }
        private var correlationKeySuffix: String = ""
        private var onContentType: (UnitOfWork) -> Boolean = { true }
        private var eventFilter: EventFilter = EventFilter.Any
        private var ttlDays: Int? = null
        private var expire: Boolean = false
        private var eventCodec: EventCodec? = null
        private var isCollectedEvent: ((UnitOfWork) -> Boolean)? = null
        private var normalizer: ((UnitOfWork) -> UnitOfWork)? = null
        private var ttl: ((UnitOfWork) -> Long?)? = null

        fun eventsMicrostore(microstore: EventsMicrostore) = apply { this.eventsMicrostore = microstore }
        fun correlationKeySupplier(supplier: (UnitOfWork) -> String?) = apply { this.correlationKeySupplier = supplier }
        fun correlationKey(key: (UnitOfWork) -> String?) = apply { this.correlationKeySupplier = key }
        fun correlationKeySuffix(suffix: String) = apply { this.correlationKeySuffix = suffix }
        fun onContentType(predicate: (UnitOfWork) -> Boolean) = apply { this.onContentType = predicate }
        fun eventFilter(filter: EventFilter) = apply { this.eventFilter = filter }
        fun ttlDays(days: Int?) = apply { this.ttlDays = days }
        fun expire(expire: Boolean) = apply { this.expire = expire }
        fun eventCodec(codec: EventCodec) = apply { this.eventCodec = codec }
        fun isCollectedEvent(predicate: (UnitOfWork) -> Boolean) = apply { this.isCollectedEvent = predicate }
        fun normalizer(normalizer: (UnitOfWork) -> UnitOfWork) = apply { this.normalizer = normalizer }
        /** When the item expires, in epoch seconds; with [expire], the deadline an `onExpired()` rule reacts to. */
        fun ttl(ttl: (UnitOfWork) -> Long?) = apply { this.ttl = ttl }

        override fun build(): CorrelatePipeline {
            val pipelineId = id ?: error("pipeline id is required")
            val microstore = eventsMicrostore ?: error("eventsMicrostore is required")
            return CorrelatePipeline(
                id = pipelineId,
                eventsMicrostore = microstore,
                correlationKeySupplier = correlationKeySupplier,
                correlationKeySuffix = correlationKeySuffix,
                onContentType = onContentType,
                eventFilter = eventFilter,
                ttlDays = ttlDays,
                expire = expire,
                eventCodec = eventCodec,
                isCollectedEvent = isCollectedEvent,
                normalizer = normalizer,
                ttl = ttl,
            )
        }
    }
}
