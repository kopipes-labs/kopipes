package io.kopipes.core

import kotlinx.serialization.json.Json

/**
 * Abstract event representing a change in a datastore table.
 * Encapsulates record image data access without exposing transport/datastore-specific record pairs.
 */
abstract class TableChangeEvent : Event {
    abstract val type: String?

    override fun eventType(): String {
        return type ?: "table_change"
    }

    abstract fun getPk(): String?
    abstract fun getTtl(): String?
    abstract fun getData(): String?
    abstract fun getEvent(): String?
    abstract fun getDiscriminator(): String?
    abstract fun getSuffix(): String?
    abstract fun isDeleted(): Boolean
    abstract fun latched(): Boolean
    abstract fun getS(fieldName: String): String?
    abstract fun getDouble(fieldName: String): Double?
    abstract fun getLong(fieldName: String): Long?

    /**
     * The table's TTL deleted an item saved with `expire = true`: a deadline passed. The getters then read the item
     * as it was before the deletion.
     */
    open fun isExpired(): Boolean = false

    inline fun <reified T> getDecodedObject(fieldName: String): T? {
        return getS(fieldName)?.let {
            Json.decodeFromString<T>(it)
        }
    }
}
