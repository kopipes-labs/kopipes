package io.kopipes.core.sinks

import io.kopipes.core.UnitOfWork
import kotlinx.coroutines.flow.Flow

/** An entity (read-model item): attribute name to String, Number, Boolean, null, List, Set, Map or ByteArray. */
typealias Entity = Map<String, Any?>

/**
 * The store of entities an [io.kopipes.core.flavors.UpdatePipeline] queries, loads and updates, independent of the
 * database: `DynamoDbEntityStore` in `aws`, [EntityStoreInMemory] here.
 *
 * Like [EventsMicrostore], each operation reads its instruction from the unit of work ([entityQuery], [entityKeys],
 * [entityUpdate]) and writes the result back ([queriedEntities], [loadedEntities], [entityUpdated]). Units without the
 * instruction pass through unchanged. Entities are keyed by `pk` and `sk`, both strings.
 */
interface EntityStore {

    /** The primary key of an entity. */
    data class Key(val pk: String, val sk: String)

    /**
     * The entities whose [attribute] equals [value]: by default those under one partition key. With [index], the
     * secondary index whose partition key is [attribute]; index queries are never consistent reads.
     */
    data class Query @JvmOverloads constructor(
        val value: String,
        val attribute: String = "pk",
        val index: String? = null,
        val consistentRead: Boolean = true,
    )

    /**
     * Changes to one entity, created if it doesn't exist: [set] attributes (null stores a null), [add] to numeric
     * attributes (a missing one counts as 0) and [remove] attributes. An attribute appears in at most one of them.
     *
     * With [onlyIfNewer], the update applies only when the entity lacks that attribute or holds a smaller value than
     * the one in [set], so a late or replayed event can't overwrite newer state. A rejected update is not a fault:
     * [entityUpdated] is false.
     */
    data class Update @JvmOverloads constructor(
        val key: Key,
        val set: Map<String, Any?> = emptyMap(),
        val add: Map<String, Number> = emptyMap(),
        val remove: Set<String> = emptySet(),
        val onlyIfNewer: String? = null,
    ) {
        init {
            require(set.isNotEmpty() || add.isNotEmpty() || remove.isNotEmpty()) { "an update needs set, add or remove" }
            require(set.keys.intersect(add.keys).isEmpty() && set.keys.intersect(remove).isEmpty() &&
                    add.keys.intersect(remove).isEmpty()) { "an attribute appears in at most one of set, add and remove" }
            require(onlyIfNewer == null || set[onlyIfNewer] != null) { "onlyIfNewer '$onlyIfNewer' must be set to a value" }
        }
    }

    /** Sets [queriedEntities] from each unit's [entityQuery]. */
    fun query(flow: Flow<UnitOfWork>): Flow<UnitOfWork>

    /** Sets [loadedEntities] from each unit's [entityKeys], skipping keys with no entity. */
    fun get(flow: Flow<UnitOfWork>): Flow<UnitOfWork>

    /** Applies each unit's [entityUpdate] and sets [entityUpdated]. */
    fun update(flow: Flow<UnitOfWork>): Flow<UnitOfWork>
}
