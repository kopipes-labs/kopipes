package org.myorg.payments

import io.kopipes.core.EnvelopeEncryptionMetadata
import io.kopipes.core.Event
import io.kopipes.core.EventCodec
import io.kopipes.core.EventReference
import io.kopipes.core.RawRecord
import io.kopipes.core.serialization.KotlinxEventCodec
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializerOrNull

/*
 * The payments subsystem's **external** events: the contract with the rest of the bank (PaymentsSystemSpecs.md ›
 * External event catalog), published as a JSON Schema in ../../contract. This gateway owns it; nothing else in the
 * subsystem uses these classes, so the internal events can change without touching the contract.
 */

/** The payment as other subsystems may see it: no account or card numbers. */
@Serializable
data class PublicPayment(
    val id: String,
    /** So Notifications can reach the customer. */
    val customerId: String,
    val amount: String,
    val currency: String,
    val submittedAt: Long,
)

@Serializable
sealed class PaymentsExternalEvent : Event {
    abstract val payment: PublicPayment

    @OptIn(InternalSerializationApi::class)
    override fun eventType(): String = this::class.serializerOrNull()?.descriptor?.serialName ?: "unknown"

    override fun toString(): String = PaymentsExternalEventCodec.encode(this)

    companion object {
        const val PAYMENT_COMPLETED = "PAYMENTS.PAYMENT_COMPLETED"
        const val PAYMENT_FAILED = "PAYMENTS.PAYMENT_FAILED"
    }
}

/** The money reached the card. */
@Serializable
@SerialName(PaymentsExternalEvent.PAYMENT_COMPLETED)
data class ExternalPaymentCompleted(
    override val payment: PublicPayment,
    override val id: String? = null, override val timestamp: Long? = null, override val partitionKey: String? = null,
    override val tags: Map<String, String>? = null, override val raw: RawRecord? = null,
    override val eem: EnvelopeEncryptionMetadata? = null, override val triggers: List<EventReference>? = null,
) : PaymentsExternalEvent() {
    override fun toString() = super.toString()
    override fun copyEvent(
        id: String?, timestamp: Long?, partitionKey: String?, tags: Map<String, String>?,
        raw: RawRecord?, eem: EnvelopeEncryptionMetadata?, triggers: List<EventReference>?,
    ): Event = copy(id = id, timestamp = timestamp, partitionKey = partitionKey, tags = tags, raw = raw, eem = eem, triggers = triggers)
}

/** The payment ended without reaching the card, and any money taken was returned. `reason` is public. */
@Serializable
@SerialName(PaymentsExternalEvent.PAYMENT_FAILED)
data class ExternalPaymentFailed(
    override val payment: PublicPayment,
    /** `insufficient-funds` or `rejected`. */
    val reason: String,
    override val id: String? = null, override val timestamp: Long? = null, override val partitionKey: String? = null,
    override val tags: Map<String, String>? = null, override val raw: RawRecord? = null,
    override val eem: EnvelopeEncryptionMetadata? = null, override val triggers: List<EventReference>? = null,
) : PaymentsExternalEvent() {
    override fun toString() = super.toString()
    override fun copyEvent(
        id: String?, timestamp: Long?, partitionKey: String?, tags: Map<String, String>?,
        raw: RawRecord?, eem: EnvelopeEncryptionMetadata?, triggers: List<EventReference>?,
    ): Event = copy(id = id, timestamp = timestamp, partitionKey = partitionKey, tags = tags, raw = raw, eem = eem, triggers = triggers)
}

object PaymentsExternalEventCodec : EventCodec by KotlinxEventCodec(PaymentsExternalEvent.serializer(), paymentsJson)
