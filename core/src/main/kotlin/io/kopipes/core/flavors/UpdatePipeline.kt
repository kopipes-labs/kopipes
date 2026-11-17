package io.kopipes.core.flavors

import io.kopipes.core.EventCodec
import io.kopipes.core.JsonEventCodec
import io.kopipes.core.PipelineBuilder
import io.kopipes.core.TableChangeEvent
import io.kopipes.core.UnitOfWork
import io.kopipes.core.faults.FaultManager
import io.kopipes.core.filters.EventFilter
import io.kopipes.core.filters.filterEvents
import io.kopipes.core.sinks.EntityStore
import io.kopipes.core.sinks.queriedEntities
import io.kopipes.core.sinks.withEntity
import io.kopipes.core.sinks.withEntityKeys
import io.kopipes.core.sinks.withEntityQuery
import io.kopipes.core.sinks.withEntityUpdate
import io.kopipes.core.sinks.withQueriedEntities
import io.kopipes.core.utils.CompactRule
import io.kopipes.core.utils.compact
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.onEach

/**
 * Pipeline flavor for an event that affects many entities: [toQuery] finds them, each one found gets its own unit of
 * work (its [io.kopipes.core.sinks.entity]), [toGet] loads related entities, and [toUpdate] builds the update for it.
 * The [entityStore] does the reading and writing. A unit whose query finds nothing, or that has no query, is
 * updated once, without an entity.
 *
 * Collected events (events-table items, [isCollectedEvent]) are first unwrapped into the event they carry, decoded
 * with [eventCodec], or by a custom [normalizer].
 */
class UpdatePipeline(
    id: String,
    val entityStore: EntityStore,
    val toUpdate: suspend (UnitOfWork) -> EntityStore.Update?,
    val toQuery: ((UnitOfWork) -> EntityStore.Query?)? = null,
    val toGet: ((UnitOfWork) -> List<EntityStore.Key>?)? = null,
    val eventCodec: EventCodec = JsonEventCodec,
    val eventFilter: EventFilter = EventFilter.Any,
    val onContentType: (UnitOfWork) -> Boolean = { true },
    val compactRule: CompactRule? = null,
    val isCollectedEvent: ((UnitOfWork) -> Boolean)? = null,
    val normalizer: ((UnitOfWork) -> UnitOfWork)? = null,
) : Pipeline(id) {

    /** By default, an events-table item (`sk = EVENT`) that carries an event and wasn't deleted. */
    fun forCollectedEvents(uow: UnitOfWork): Boolean {
        if (isCollectedEvent != null) return isCollectedEvent.invoke(uow)
        val event = uow.event as? TableChangeEvent ?: return false
        return !event.isDeleted() && event.getS("sk") == "EVENT" && event.getEvent() != null
    }

    /** Replaces a collected event by the event it carries, its `ttl` and `data` going to meta; other units pass. */
    fun normalize(uow: UnitOfWork): UnitOfWork {
        if (!forCollectedEvents(uow)) return uow
        if (normalizer != null) return normalizer.invoke(uow)

        val tableChangeEvent = uow.event as? TableChangeEvent ?: return uow
        val event = eventCodec.decode(tableChangeEvent.getEvent() ?: "{}")
        if (event.id == null) {
            logger.warn { "Event id is null: ${tableChangeEvent.getEvent()}" }
        }
        val meta = listOfNotNull(
            tableChangeEvent.getTtl()?.let { "ttl" to it },
            tableChangeEvent.getData()?.let { "data" to it },
        ).toMap()
        return uow.copy(event = event, meta = (uow.meta ?: emptyMap()) + meta)
    }

    /** One unit per queried entity, without the whole query result; the unit itself when there are none. */
    internal fun splitQueried(uow: UnitOfWork): List<UnitOfWork> {
        val entities = uow.queriedEntities
        if (entities.isNullOrEmpty()) return listOf(uow)
        val withoutResult = uow.withQueriedEntities(null)
        return entities.map { withoutResult.withEntity(it) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun connect(fm: FaultManager, fromFlow: Flow<UnitOfWork>): Flow<UnitOfWork> {
        logger.info { "UpdatePipeline.connect: id=$id" }

        with(fm) {
            val queries = fromFlow
                .mapNotFaulty { uow -> normalize(uow) }
                .filterEvents(fm, eventFilter)
                .onEach { uow -> printStartPipeline(uow) }
                .filterNotFaulty { uow -> onContentType(uow) }
                .compact(compactRule)
                .mapNotFaulty { uow -> uow.withEntityQuery(toQuery?.invoke(uow)) }

            val gets = entityStore.query(queries)
                .flatMapConcat { uow -> splitQueried(uow).asFlow() }
                .mapNotFaulty { uow -> uow.withEntityKeys(toGet?.invoke(uow)) }

            val updates = entityStore.get(gets)
                .mapNotFaulty { uow -> uow.withEntityUpdate(toUpdate(uow)) }

            return entityStore.update(updates)
                .onEach { uow -> printEndPipeline(uow) }
        }
    }

    companion object {
        @JvmStatic
        fun builder() = Builder()
    }

    class Builder : PipelineBuilder<UpdatePipeline, Builder>() {
        private var entityStore: EntityStore? = null
        private var toUpdate: (suspend (UnitOfWork) -> EntityStore.Update?)? = null
        private var toQuery: ((UnitOfWork) -> EntityStore.Query?)? = null
        private var toGet: ((UnitOfWork) -> List<EntityStore.Key>?)? = null
        private var eventCodec: EventCodec = JsonEventCodec
        private var eventFilter: EventFilter = EventFilter.Any
        private var onContentType: (UnitOfWork) -> Boolean = { true }
        private var compactRule: CompactRule? = null
        private var isCollectedEvent: ((UnitOfWork) -> Boolean)? = null
        private var normalizer: ((UnitOfWork) -> UnitOfWork)? = null

        fun entityStore(entityStore: EntityStore) = apply { this.entityStore = entityStore }
        fun toUpdate(toUpdate: suspend (UnitOfWork) -> EntityStore.Update?) = apply { this.toUpdate = toUpdate }
        fun toUpdate(toUpdate: java.util.function.Function<UnitOfWork, EntityStore.Update?>) = apply { this.toUpdate = { uow -> toUpdate.apply(uow) } }
        fun toQuery(toQuery: (UnitOfWork) -> EntityStore.Query?) = apply { this.toQuery = toQuery }
        fun toQueryJava(toQuery: java.util.function.Function<UnitOfWork, EntityStore.Query?>) = apply { this.toQuery = { uow -> toQuery.apply(uow) } }
        fun toGet(toGet: (UnitOfWork) -> List<EntityStore.Key>?) = apply { this.toGet = toGet }
        fun toGetJava(toGet: java.util.function.Function<UnitOfWork, List<EntityStore.Key>?>) = apply { this.toGet = { uow -> toGet.apply(uow) } }
        fun eventCodec(eventCodec: EventCodec) = apply { this.eventCodec = eventCodec }
        fun eventFilter(eventFilter: EventFilter) = apply { this.eventFilter = eventFilter }
        fun onContentType(onContentType: (UnitOfWork) -> Boolean) = apply { this.onContentType = onContentType }
        fun onContentType(onContentType: java.util.function.Predicate<UnitOfWork>) = apply { this.onContentType = { uow -> onContentType.test(uow) } }
        fun compactRule(compactRule: CompactRule) = apply { this.compactRule = compactRule }
        fun isCollectedEvent(predicate: (UnitOfWork) -> Boolean) = apply { this.isCollectedEvent = predicate }
        fun normalizer(normalizer: (UnitOfWork) -> UnitOfWork) = apply { this.normalizer = normalizer }

        override fun build(): UpdatePipeline {
            return UpdatePipeline(
                id = id ?: throw IllegalArgumentException("id is required"),
                entityStore = entityStore ?: throw IllegalArgumentException("entityStore is required"),
                toUpdate = toUpdate ?: throw IllegalArgumentException("toUpdate is required"),
                toQuery = toQuery,
                toGet = toGet,
                eventCodec = eventCodec,
                eventFilter = eventFilter,
                onContentType = onContentType,
                compactRule = compactRule,
                isCollectedEvent = isCollectedEvent,
                normalizer = normalizer,
            )
        }
    }
}
