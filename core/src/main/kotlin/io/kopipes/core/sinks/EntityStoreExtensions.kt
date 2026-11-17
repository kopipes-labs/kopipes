package io.kopipes.core.sinks

import io.kopipes.core.UnitOfWork
import io.kopipes.core.serialization.Snapshottable

/** What an [EntityStore] was asked to do for a unit of work, and what it answered. */
data class EntityStoreExtensions(
    val query: EntityStore.Query? = null,
    val queried: List<Entity>? = null,
    val entity: Entity? = null,
    val keys: List<EntityStore.Key>? = null,
    val loaded: List<Entity>? = null,
    val update: EntityStore.Update? = null,
    val updated: Boolean? = null,
) : Snapshottable {
    override fun toSnapshot(): Any {
        return mapOf(
            "query" to query?.toString(),
            "queried" to queried?.size?.toString(),
            "entity" to entity?.toString(),
            "keys" to keys?.toString(),
            "loaded" to loaded?.size?.toString(),
            "update" to update?.toString(),
            "updated" to updated?.toString(),
        ).filterValues { it != null }
    }
}

val UnitOfWork.entityStore: EntityStoreExtensions
    get() = getExtension() ?: EntityStoreExtensions()

/** The query an [EntityStore] runs for this unit. */
val UnitOfWork.entityQuery: EntityStore.Query?
    get() = entityStore.query

/** The entities the [entityQuery] found. */
val UnitOfWork.queriedEntities: List<Entity>?
    get() = entityStore.queried

/** In an `UpdatePipeline`, the one queried entity this unit updates. */
val UnitOfWork.entity: Entity?
    get() = entityStore.entity

/** The keys of the entities an [EntityStore] loads for this unit. */
val UnitOfWork.entityKeys: List<EntityStore.Key>?
    get() = entityStore.keys

/** The entities loaded by [entityKeys]. */
val UnitOfWork.loadedEntities: List<Entity>?
    get() = entityStore.loaded

/** The update an [EntityStore] applies for this unit. */
val UnitOfWork.entityUpdate: EntityStore.Update?
    get() = entityStore.update

/** Whether the [entityUpdate] applied: false when its `onlyIfNewer` condition rejected it. */
val UnitOfWork.entityUpdated: Boolean?
    get() = entityStore.updated

fun UnitOfWork.withEntityQuery(query: EntityStore.Query?): UnitOfWork =
    withExtension(entityStore.copy(query = query))

fun UnitOfWork.withQueriedEntities(entities: List<Entity>?): UnitOfWork =
    withExtension(entityStore.copy(queried = entities))

fun UnitOfWork.withEntity(entity: Entity?): UnitOfWork =
    withExtension(entityStore.copy(entity = entity))

fun UnitOfWork.withEntityKeys(keys: List<EntityStore.Key>?): UnitOfWork =
    withExtension(entityStore.copy(keys = keys))

fun UnitOfWork.withLoadedEntities(entities: List<Entity>?): UnitOfWork =
    withExtension(entityStore.copy(loaded = entities))

fun UnitOfWork.withEntityUpdate(update: EntityStore.Update?): UnitOfWork =
    withExtension(entityStore.copy(update = update))

fun UnitOfWork.withEntityUpdated(updated: Boolean?): UnitOfWork =
    withExtension(entityStore.copy(updated = updated))
