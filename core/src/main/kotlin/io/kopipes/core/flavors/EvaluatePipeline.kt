package io.kopipes.core.flavors

import io.kopipes.core.*
import io.kopipes.core.faults.FaultManager
import io.kopipes.core.filters.EventFilter
import io.kopipes.core.filters.filterEvents
import io.kopipes.core.sinks.EventPublisher
import io.kopipes.core.sinks.EventsMicrostore
import io.kopipes.core.sinks.withQueryParams
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

/**
 * Pipeline flavor that evaluates collected and correlated events and publishes higher-order events.
 *
 * - [emittedEventId]: the id of each emitted event. Without it the id is derived from the stream record, which a
 *   replay changes; with a deterministic id (such as `<correlation key>.<pipeline id>`), a rule that fires again
 *   emits the same event, and consumers drop the repeat.
 * - [correlationKeySuffix]: the pipeline reacts only to items in the side partition `<key>.<suffix>`.
 * - [onCorrelated]: the pipeline reacts only to correlation items (a [CorrelatePipeline]'s inserts), not to the
 *   collected events too. Use it when a rule judges what's correlated under a key, so it fires once per event.
 * - [onExpired]: the pipeline reacts only to deadlines, items a [CorrelatePipeline] saved with `expire` and a ttl,
 *   when the table's TTL deletes them. It evaluates the events correlated under the key without the suffix (the
 *   whole entity, not the deadline's side partition), and the emitted events are dated at the deadline.
 */
class EvaluatePipeline(
    id: String,
    val eventPublisher: EventPublisher? = null,
    val eventsMicrostore: EventsMicrostore,
    val onContentType: (UnitOfWork) -> Boolean = { true },
    val eventFilter: EventFilter = EventFilter.Any,
    val correlationKeySuffix: String = "",
    val index: String? = null,
    val bufferCapacity: Int = Channel.BUFFERED,
    val eventCodec: EventCodec,
    val expression: ((UnitOfWork) -> Boolean)? = null,
    val emit: ((UnitOfWork) -> List<Event>)? = null,
    val isEvaluateEvent: ((UnitOfWork) -> Boolean)? = null,
    val normalizer: ((UnitOfWork) -> UnitOfWork)? = null,
    val consistentRead: Boolean = true,
    val emittedEventId: ((UnitOfWork) -> String)? = null,
    val onExpired: Boolean = false,
    val onCorrelated: Boolean = false,
) : Pipeline(id) {

    fun forEvents(uow: UnitOfWork): Boolean {
        if (isEvaluateEvent != null) return isEvaluateEvent.invoke(uow)
        val event = uow.event
        if (event is TableChangeEvent) {
            if (event.isExpired()) return onExpired && event.getDiscriminator() == "CORREL"
            if (onExpired) return false
            if (onCorrelated) return !event.isDeleted() && event.getDiscriminator() == "CORREL"
            return !event.isDeleted() && (event.getDiscriminator() == "CORREL" || event.getEvent() != null)
        }
        return !onExpired && !onCorrelated && event != null
    }

    fun defaultUnmarshall(eventAsString: String): Event {
        return eventCodec.decode(eventAsString)
    }

    fun normalize(uow: UnitOfWork): UnitOfWork {
        if (normalizer != null) return normalizer.invoke(uow)

        val tableChangeEvent = uow.event as? TableChangeEvent ?: return uow

        val eventAsString = tableChangeEvent.getEvent() ?: "{}"
        val eventAsObject = defaultUnmarshall(eventAsString)
        val correlation = tableChangeEvent.getDiscriminator() == "CORREL"
        val pk = tableChangeEvent.getPk()
        val data = tableChangeEvent.getData()
        val suffix = tableChangeEvent.getSuffix()
        val queryParams = EventsMicrostore.QueryParams(
            pk = pk,
            correlation = correlation,
            data = data,
            index = index,
            consistentRead = consistentRead,
        )

        val correlationKey = if (correlation) pk else data
        // The key without the side partition's `.<suffix>`; the microstore writes "no suffix" as "".
        val partitionKey = if (suffix.isNullOrEmpty()) correlationKey else correlationKey?.removeSuffix(".$suffix")
        val expiredAt = if (tableChangeEvent.isExpired()) tableChangeEvent.getTtl()?.toLongOrNull()?.times(1000) else null

        return uow.withQueryParams(
            if (expiredAt != null) queryParams.copy(pk = partitionKey) else queryParams
        ).copy(
            event = eventAsObject,
            meta = (uow.meta ?: emptyMap()) + mapOf(
                "eventId" to "${tableChangeEvent.id}.${id}",
                "partitionKey" to partitionKey,
                "suffix" to (suffix ?: ""),
            ) + (expiredAt?.let { mapOf(EXPIRED_AT to it.toString()) } ?: emptyMap())
        )
    }

    fun onCorrelationKeySuffix(uow: UnitOfWork): Boolean {
        val uowSuffix = uow.meta?.get("suffix") ?: ""
        return correlationKeySuffix == uowSuffix
    }

    fun Flow<UnitOfWork>.queryCorrelated(): Flow<UnitOfWork> {
        // queryByPK already has a fault manager.
        return eventsMicrostore.queryByPk(this)
    }

    fun Flow<UnitOfWork>.complex(fm: FaultManager): Flow<UnitOfWork> {
        return if (expression == null) {
            this
                .filter { uow -> fm.faulty(uow) { onCorrelationKeySuffix(uow) } == true }
                .map { uow ->
                    uow.copy(
                        triggers = listOfNotNull(uow.event)
                    )
                }
        } else {
            this
                .filter { uow -> fm.faulty(uow) { onCorrelationKeySuffix(uow) } == true }
                .queryCorrelated()
                .mapNotNull { uow ->
                    val result = fm.faulty(uow) { expression.invoke(uow) }
                    if (result == true) {
                        uow.copy(triggers = listOfNotNull(uow.event))
                    } else {
                        null
                    }
                }
        }
    }

    fun toHigherOrderEvents(uow: UnitOfWork): List<UnitOfWork> {
        val emit = this.emit ?: return emptyList()
        val triggeringEvent = uow.event ?: return emptyList()

        val eventId = emittedEventId?.invoke(uow) ?: uow.meta?.get("eventId")
        val partitionKey = uow.meta?.get("partitionKey")
        val expiredAt = uow.meta?.get(EXPIRED_AT)?.toLongOrNull()
        val trigger = uow.triggers?.lastOrNull()
        val aggregatedTags = aggregateTags(uow)
        val mappedTriggers = uow.triggers?.map {
            EventReference(it.id, it.eventType(), it.timestamp)
        }

        val resultEvents: List<Event> = emit(uow)

        return resultEvents.map { e ->
            val event = e.copyEvent(
                id = eventId,
                timestamp = expiredAt ?: trigger?.timestamp,
                partitionKey = partitionKey,
                tags = aggregatedTags,
                triggers = mappedTriggers,
                raw = e.raw ?: triggeringEvent.raw,
                eem = e.eem ?: triggeringEvent.eem
            )
            uow.copy(event = event)
        }
    }

    private fun aggregateTags(uow: UnitOfWork): MutableMap<String, String>? {
        // reduce + merge + omit(['region', 'source'])
        val aggregatedTags = uow.triggers
            ?.mapNotNull { it.tags }
            ?.fold(mutableMapOf<String, String>()) { acc, currentTags ->
                acc.apply { putAll(currentTags) }
            }?.apply {
                remove("region")
                remove("source")
            }
        return aggregatedTags
    }

    fun Flow<UnitOfWork>.publish(): Flow<UnitOfWork> {
        return eventPublisher?.publish(this) ?: this
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun connect(fm: FaultManager, fromFlow: Flow<UnitOfWork>): Flow<UnitOfWork> {
        logger.info { "EvaluatePipeline.connect: id=$id" }
        with(fm) {
            val flow = fromFlow
                .filterNotFaulty { uow -> forEvents(uow) }
                .mapNotFaulty { uow -> normalize(uow) }
                .filterEvents(fm, eventFilter)
                .onEach { uow -> printStartPipeline(uow) }
                .filterNotFaulty { uow -> onContentType(uow) }
                .complex(fm)
                .flatMapMerge { uow ->
                    faulty(uow) { toHigherOrderEvents(uow) }?.asFlow() ?: emptyFlow()
                }
                .buffer(bufferCapacity)
                .publish()
                .onEach { uow -> printEndPipeline(uow) }
            return flow
        }
    }

    companion object {
        /** The meta key of an expired deadline's time, in epoch milliseconds. */
        const val EXPIRED_AT = "expiredAt"

        @JvmStatic
        fun builder() = Builder()
    }

    class Builder : PipelineBuilder<EvaluatePipeline, Builder>() {
        private var eventPublisher: EventPublisher? = null
        private var eventsMicrostore: EventsMicrostore? = null
        private var onContentType: (UnitOfWork) -> Boolean = { true }
        private var eventFilter: EventFilter = EventFilter.Any
        private var correlationKeySuffix: String = ""
        private var index: String? = null
        private var bufferCapacity: Int = Channel.BUFFERED
        private var eventCodec: EventCodec? = null
        private var expression: ((UnitOfWork) -> Boolean)? = null
        private var emit: ((UnitOfWork) -> List<Event>)? = null
        private var isEvaluateEvent: ((UnitOfWork) -> Boolean)? = null
        private var normalizer: ((UnitOfWork) -> UnitOfWork)? = null
        private var consistentRead: Boolean = true
        private var emittedEventId: ((UnitOfWork) -> String)? = null
        private var onExpired: Boolean = false
        private var oneEventPerCorrelationKey: Boolean = false
        private var onCorrelated: Boolean = false

        fun eventPublisher(eventPublisher: EventPublisher?) = apply { this.eventPublisher = eventPublisher }
        fun eventsMicrostore(eventsMicrostore: EventsMicrostore) = apply { this.eventsMicrostore = eventsMicrostore }
        fun onContentType(onContentType: (UnitOfWork) -> Boolean) = apply { this.onContentType = onContentType }
        fun onContentType(onContentType: java.util.function.Predicate<UnitOfWork>) = apply { this.onContentType = { uow -> onContentType.test(uow) } }
        fun eventFilter(eventFilter: EventFilter) = apply { this.eventFilter = eventFilter }
        fun correlationKeySuffix(correlationKeySuffix: String) = apply { this.correlationKeySuffix = correlationKeySuffix }
        fun index(index: String?) = apply { this.index = index }
        fun bufferCapacity(bufferCapacity: Int) = apply { this.bufferCapacity = bufferCapacity }
        fun eventCodec(eventCodec: EventCodec) = apply { this.eventCodec = eventCodec }
        fun expression(expression: (UnitOfWork) -> Boolean) = apply { this.expression = expression }
        fun expressionJava(expression: java.util.function.Predicate<UnitOfWork>) = apply { this.expression = { uow -> expression.test(uow) } }
        fun emit(emit: (UnitOfWork) -> List<Event>) = apply { this.emit = emit }
        fun emitJava(emit: java.util.function.Function<UnitOfWork, List<Event>>) = apply { this.emit = { uow -> emit.apply(uow) } }
        fun isEvaluateEvent(predicate: (UnitOfWork) -> Boolean) = apply { this.isEvaluateEvent = predicate }
        fun isEvaluateEventJava(predicate: java.util.function.Predicate<UnitOfWork>) = apply { this.isEvaluateEvent = { uow -> predicate.test(uow) } }
        fun normalizer(normalizer: (UnitOfWork) -> UnitOfWork) = apply { this.normalizer = normalizer }
        fun normalizerJava(normalizer: java.util.function.Function<UnitOfWork, UnitOfWork>) = apply { this.normalizer = { uow -> normalizer.apply(uow) } }
        fun consistentRead(consistentRead: Boolean) = apply { this.consistentRead = consistentRead }
        /** The id of each emitted event, from the normalized unit of work; see [correlationKey]. */
        fun emittedEventId(emittedEventId: (UnitOfWork) -> String) = apply { this.emittedEventId = emittedEventId }
        /** `<correlation key>.<pipeline id>`: the rule emits at most one event per key, however often it fires. */
        fun oneEventPerCorrelationKey() = apply { this.oneEventPerCorrelationKey = true }
        /** React only to correlation items, not to collected events; see [EvaluatePipeline]. */
        fun onCorrelated() = apply { this.onCorrelated = true }
        /** React only to expired deadlines; see [EvaluatePipeline]. */
        fun onExpired() = apply { this.onExpired = true }

        override fun build(): EvaluatePipeline {
            val pipelineId = id ?: throw IllegalArgumentException("id is required")
            val perKey: ((UnitOfWork) -> String)? = if (oneEventPerCorrelationKey) {
                { uow -> "${uow.correlationKey() ?: error("No correlation key for $pipelineId")}.$pipelineId" }
            } else {
                null
            }
            return EvaluatePipeline(
                id = pipelineId,
                eventPublisher = eventPublisher,
                eventsMicrostore = eventsMicrostore ?: throw IllegalArgumentException("eventsMicrostore is required"),
                onContentType = onContentType,
                eventFilter = eventFilter,
                correlationKeySuffix = correlationKeySuffix,
                index = index,
                bufferCapacity = bufferCapacity,
                eventCodec = eventCodec ?: throw IllegalArgumentException("eventCodec is required"),
                expression = expression,
                emit = emit,
                isEvaluateEvent = isEvaluateEvent,
                normalizer = normalizer,
                consistentRead = consistentRead,
                emittedEventId = emittedEventId ?: perKey,
                onExpired = onExpired,
                onCorrelated = onCorrelated,
            )
        }
    }
}

/** The key the triggering event was correlated under, without the pipeline's suffix. Set by the normalizer. */
fun UnitOfWork.correlationKey(): String? = meta?.get("partitionKey")
