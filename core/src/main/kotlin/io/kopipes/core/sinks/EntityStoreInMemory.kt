package io.kopipes.core.sinks

import io.kopipes.core.UnitOfWork
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/** [EntityStore] kept in memory, for tests and local runs. Index queries match the attribute on every entity. */
class EntityStoreInMemory : EntityStore {

    private val entities = ConcurrentHashMap<EntityStore.Key, Entity>()

    /** Stores [entity] as is; it must have string `pk` and `sk` attributes. */
    fun put(entity: Entity) {
        val pk = entity["pk"] as? String ?: throw IllegalArgumentException("entity needs a string pk")
        val sk = entity["sk"] as? String ?: throw IllegalArgumentException("entity needs a string sk")
        entities[EntityStore.Key(pk, sk)] = entity
    }

    fun get(key: EntityStore.Key): Entity? = entities[key]

    fun getStored(): Map<EntityStore.Key, Entity> = entities.toMap()

    fun clear() {
        entities.clear()
    }

    override fun query(flow: Flow<UnitOfWork>): Flow<UnitOfWork> =
        flow.map { uow ->
            val query = uow.entityQuery ?: return@map uow
            val found = entities.entries
                .filter { (_, entity) -> entity[query.attribute] == query.value }
                .sortedWith(compareBy({ it.key.pk }, { it.key.sk }))
                .map { it.value }
            uow.withQueriedEntities(found)
        }

    override fun get(flow: Flow<UnitOfWork>): Flow<UnitOfWork> =
        flow.map { uow ->
            val keys = uow.entityKeys ?: return@map uow
            uow.withLoadedEntities(keys.mapNotNull { entities[it] })
        }

    override fun update(flow: Flow<UnitOfWork>): Flow<UnitOfWork> =
        flow.map { uow ->
            val update = uow.entityUpdate ?: return@map uow
            uow.withEntityUpdated(apply(update))
        }

    /** Applies [update] atomically; false when its `onlyIfNewer` condition rejects it. */
    internal fun apply(update: EntityStore.Update): Boolean {
        var applied = false
        entities.compute(update.key) { key, current ->
            val newer = update.onlyIfNewer
            if (current != null && newer != null && !isNewer(update.set[newer], current[newer])) {
                return@compute current
            }
            applied = true
            val next = (current ?: mapOf("pk" to key.pk, "sk" to key.sk)).toMutableMap()
            next.putAll(update.set)
            update.add.forEach { (name, delta) -> next[name] = plus(next[name] as Number?, delta) }
            update.remove.forEach { next.remove(it) }
            next
        }
        return applied
    }

    companion object {
        /** True when [current] is absent or smaller than [candidate]. */
        internal fun isNewer(candidate: Any?, current: Any?): Boolean {
            if (current == null) return true
            if (candidate is Number && current is Number) return candidate.toBigDecimal() > current.toBigDecimal()
            if (candidate is String && current is String) return candidate > current
            throw IllegalArgumentException("can't compare $candidate with $current")
        }

        internal fun plus(current: Number?, delta: Number): Number {
            val base = current ?: 0L
            if (base.isIntegral() && delta.isIntegral()) return base.toLong() + delta.toLong()
            return base.toBigDecimal() + delta.toBigDecimal()
        }

        private fun Number.isIntegral() = this is Long || this is Int || this is Short || this is Byte

        private fun Number.toBigDecimal(): BigDecimal = this as? BigDecimal ?: BigDecimal(toString())
    }
}
