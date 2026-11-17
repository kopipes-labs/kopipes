package io.kopipes.aws.from

import io.kopipes.core.EnvelopeEncryptionMetadata
import io.kopipes.core.EventReference
import io.kopipes.core.RawRecord
import io.kopipes.core.TableChangeEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * DynamoDB implementation of [TableChangeEvent].
 * Encapsulates [RecordPair] and [RecordImage] access.
 */
@Serializable
data class DynamoDbChangeEvent(
    override val id: String? = null,
    override val timestamp: Long? = null,
    override val partitionKey: String? = null,
    override val tags: Map<String, String>? = null,
    override val raw: RawRecord? = null,
    override val eem: EnvelopeEncryptionMetadata? = null,
    override val triggers: List<EventReference>? = null,
    override val type: String? = null,
    /** A REMOVE of an item saved with `expire = true`; see [TableChangeEvent.isExpired]. */
    val expired: Boolean = false,
) : TableChangeEvent() {

    val recordPair: RecordPair?
        get() = raw as? RecordPair

    val newImage: RecordImage?
        get() = recordPair?.new

    val oldImage: RecordImage?
        get() = recordPair?.old

    /** The image the getters read: the new one, or for an expired item the one TTL deleted. */
    private val image: RecordImage?
        get() = if (expired) oldImage else newImage

    override fun getPk(): String? = image?.getPk()
    override fun getTtl(): String? = image?.getTtl()
    override fun getData(): String? = image?.getData()
    override fun getEvent(): String? = image?.getEvent()
    override fun getDiscriminator(): String? = image?.getDiscriminator()
    override fun getSuffix(): String? = image?.getSuffix()
    override fun isDeleted(): Boolean = image?.isDeleted() == true
    override fun latched(): Boolean = image?.latched() == true
    override fun getS(fieldName: String): String? = image?.getS(fieldName)
    override fun getDouble(fieldName: String): Double? = image?.getDouble(fieldName)
    override fun getLong(fieldName: String): Long? = image?.getLong(fieldName)
    override fun isExpired(): Boolean = expired

    override fun toString(): String {
        return Json.encodeToString(serializer(), this)
    }

    override fun copyEvent(
        id: String?,
        timestamp: Long?,
        partitionKey: String?,
        tags: Map<String, String>?,
        raw: RawRecord?,
        eem: EnvelopeEncryptionMetadata?,
        triggers: List<EventReference>?
    ): DynamoDbChangeEvent = copy(
        id = id,
        timestamp = timestamp,
        partitionKey = partitionKey,
        tags = tags,
        raw = raw,
        eem = eem,
        triggers = triggers
    )
}
