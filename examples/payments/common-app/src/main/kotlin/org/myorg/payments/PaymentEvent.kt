package org.myorg.payments

import io.kopipes.core.EnvelopeEncryptionMetadata
import io.kopipes.core.Event
import io.kopipes.core.EventReference
import io.kopipes.core.RawRecord
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializerOrNull

/**
 * The payments subsystem's **internal** events: they flow only between its own services, on its own hub.
 *
 * The envelope (`id`, `timestamp`, `partitionKey`, `tags`, `raw`, ...) is the framework's [Event]; `type` is the
 * serial name. Every event carries the [Payment]; failures add a `reason` ([Failure]). The partition key is the
 * payment id, so a consumer sees one payment's events in order on its channel, although it must not rely on it.
 *
 * See docs/EventImplementationKotlin.md for the pattern.
 */
@Serializable
sealed class PaymentEvent : Event {
    abstract val payment: Payment

    @OptIn(InternalSerializationApi::class)
    override fun eventType(): String = this::class.serializerOrNull()?.descriptor?.serialName ?: "unknown"

    override fun toString(): String = PaymentsEventCodec.encode(this)

    companion object {
        const val PAYMENT_SUBMITTED = "PAYMENT_SUBMITTED"
        const val WITHDRAWAL_REQUESTED = "WITHDRAWAL_REQUESTED"
        const val FUNDS_WITHDRAWN = "FUNDS_WITHDRAWN"
        const val WITHDRAWAL_FAILED = "WITHDRAWAL_FAILED"
        const val DEPOSIT_REQUESTED = "DEPOSIT_REQUESTED"
        const val FUNDS_DEPOSITED = "FUNDS_DEPOSITED"
        const val DEPOSIT_FAILED = "DEPOSIT_FAILED"
        const val REFUND_REQUESTED = "REFUND_REQUESTED"
        const val FUNDS_REFUNDED = "FUNDS_REFUNDED"
        const val REFUND_FAILED = "REFUND_FAILED"
        const val PAYMENT_COMPLETED = "PAYMENT_COMPLETED"
        const val PAYMENT_FAILED = "PAYMENT_FAILED"
        const val PAYMENT_EXPIRED = "PAYMENT_EXPIRED"
    }
}

/** An abort, or an outcome that copies one: `reason` is one of [Reasons]. */
interface Failure {
    val reason: String
}

// Each event below repeats the same envelope and copyEvent. The framework uses copyEvent to set the envelope
// (an emitted event's id, timestamp, tags...) without knowing the event's concrete type.

/** Fact: the customer submitted a payment. Produced by the BFF. */
@Serializable
@SerialName(PaymentEvent.PAYMENT_SUBMITTED)
data class PaymentSubmitted(
    override val payment: Payment,
    override val id: String? = null, override val timestamp: Long? = null, override val partitionKey: String? = null,
    override val tags: Map<String, String>? = null, override val raw: RawRecord? = null,
    override val eem: EnvelopeEncryptionMetadata? = null, override val triggers: List<EventReference>? = null,
) : PaymentEvent() {
    override fun toString() = super.toString()
    override fun copyEvent(
        id: String?, timestamp: Long?, partitionKey: String?, tags: Map<String, String>?,
        raw: RawRecord?, eem: EnvelopeEncryptionMetadata?, triggers: List<EventReference>?,
    ): Event = copy(id = id, timestamp = timestamp, partitionKey = partitionKey, tags = tags, raw = raw, eem = eem, triggers = triggers)
}

/** Entry of the withdrawal step. Produced by the control service. */
@Serializable
@SerialName(PaymentEvent.WITHDRAWAL_REQUESTED)
data class WithdrawalRequested(
    override val payment: Payment,
    override val id: String? = null, override val timestamp: Long? = null, override val partitionKey: String? = null,
    override val tags: Map<String, String>? = null, override val raw: RawRecord? = null,
    override val eem: EnvelopeEncryptionMetadata? = null, override val triggers: List<EventReference>? = null,
) : PaymentEvent() {
    override fun toString() = super.toString()
    override fun copyEvent(
        id: String?, timestamp: Long?, partitionKey: String?, tags: Map<String, String>?,
        raw: RawRecord?, eem: EnvelopeEncryptionMetadata?, triggers: List<EventReference>?,
    ): Event = copy(id = id, timestamp = timestamp, partitionKey = partitionKey, tags = tags, raw = raw, eem = eem, triggers = triggers)
}

/** Exit of the withdrawal step. Produced by the core checking ingress gateway. */
@Serializable
@SerialName(PaymentEvent.FUNDS_WITHDRAWN)
data class FundsWithdrawn(
    override val payment: Payment,
    override val id: String? = null, override val timestamp: Long? = null, override val partitionKey: String? = null,
    override val tags: Map<String, String>? = null, override val raw: RawRecord? = null,
    override val eem: EnvelopeEncryptionMetadata? = null, override val triggers: List<EventReference>? = null,
) : PaymentEvent() {
    override fun toString() = super.toString()
    override fun copyEvent(
        id: String?, timestamp: Long?, partitionKey: String?, tags: Map<String, String>?,
        raw: RawRecord?, eem: EnvelopeEncryptionMetadata?, triggers: List<EventReference>?,
    ): Event = copy(id = id, timestamp = timestamp, partitionKey = partitionKey, tags = tags, raw = raw, eem = eem, triggers = triggers)
}

/** Abort of the withdrawal step. Produced by the core checking ingress gateway. */
@Serializable
@SerialName(PaymentEvent.WITHDRAWAL_FAILED)
data class WithdrawalFailed(
    override val payment: Payment,
    override val reason: String,
    override val id: String? = null, override val timestamp: Long? = null, override val partitionKey: String? = null,
    override val tags: Map<String, String>? = null, override val raw: RawRecord? = null,
    override val eem: EnvelopeEncryptionMetadata? = null, override val triggers: List<EventReference>? = null,
) : PaymentEvent(), Failure {
    override fun toString() = super.toString()
    override fun copyEvent(
        id: String?, timestamp: Long?, partitionKey: String?, tags: Map<String, String>?,
        raw: RawRecord?, eem: EnvelopeEncryptionMetadata?, triggers: List<EventReference>?,
    ): Event = copy(id = id, timestamp = timestamp, partitionKey = partitionKey, tags = tags, raw = raw, eem = eem, triggers = triggers)
}

/** Entry of the deposit step. Produced by the control service. */
@Serializable
@SerialName(PaymentEvent.DEPOSIT_REQUESTED)
data class DepositRequested(
    override val payment: Payment,
    override val id: String? = null, override val timestamp: Long? = null, override val partitionKey: String? = null,
    override val tags: Map<String, String>? = null, override val raw: RawRecord? = null,
    override val eem: EnvelopeEncryptionMetadata? = null, override val triggers: List<EventReference>? = null,
) : PaymentEvent() {
    override fun toString() = super.toString()
    override fun copyEvent(
        id: String?, timestamp: Long?, partitionKey: String?, tags: Map<String, String>?,
        raw: RawRecord?, eem: EnvelopeEncryptionMetadata?, triggers: List<EventReference>?,
    ): Event = copy(id = id, timestamp = timestamp, partitionKey = partitionKey, tags = tags, raw = raw, eem = eem, triggers = triggers)
}

/** Exit of the deposit step. Produced by the card processor ingress gateway. */
@Serializable
@SerialName(PaymentEvent.FUNDS_DEPOSITED)
data class FundsDeposited(
    override val payment: Payment,
    override val id: String? = null, override val timestamp: Long? = null, override val partitionKey: String? = null,
    override val tags: Map<String, String>? = null, override val raw: RawRecord? = null,
    override val eem: EnvelopeEncryptionMetadata? = null, override val triggers: List<EventReference>? = null,
) : PaymentEvent() {
    override fun toString() = super.toString()
    override fun copyEvent(
        id: String?, timestamp: Long?, partitionKey: String?, tags: Map<String, String>?,
        raw: RawRecord?, eem: EnvelopeEncryptionMetadata?, triggers: List<EventReference>?,
    ): Event = copy(id = id, timestamp = timestamp, partitionKey = partitionKey, tags = tags, raw = raw, eem = eem, triggers = triggers)
}

/** Abort of the deposit step. Produced by the card processor gateways. */
@Serializable
@SerialName(PaymentEvent.DEPOSIT_FAILED)
data class DepositFailed(
    override val payment: Payment,
    override val reason: String,
    override val id: String? = null, override val timestamp: Long? = null, override val partitionKey: String? = null,
    override val tags: Map<String, String>? = null, override val raw: RawRecord? = null,
    override val eem: EnvelopeEncryptionMetadata? = null, override val triggers: List<EventReference>? = null,
) : PaymentEvent(), Failure {
    override fun toString() = super.toString()
    override fun copyEvent(
        id: String?, timestamp: Long?, partitionKey: String?, tags: Map<String, String>?,
        raw: RawRecord?, eem: EnvelopeEncryptionMetadata?, triggers: List<EventReference>?,
    ): Event = copy(id = id, timestamp = timestamp, partitionKey = partitionKey, tags = tags, raw = raw, eem = eem, triggers = triggers)
}

/** Compensation: give the money back. Produced by the control service. */
@Serializable
@SerialName(PaymentEvent.REFUND_REQUESTED)
data class RefundRequested(
    override val payment: Payment,
    override val id: String? = null, override val timestamp: Long? = null, override val partitionKey: String? = null,
    override val tags: Map<String, String>? = null, override val raw: RawRecord? = null,
    override val eem: EnvelopeEncryptionMetadata? = null, override val triggers: List<EventReference>? = null,
) : PaymentEvent() {
    override fun toString() = super.toString()
    override fun copyEvent(
        id: String?, timestamp: Long?, partitionKey: String?, tags: Map<String, String>?,
        raw: RawRecord?, eem: EnvelopeEncryptionMetadata?, triggers: List<EventReference>?,
    ): Event = copy(id = id, timestamp = timestamp, partitionKey = partitionKey, tags = tags, raw = raw, eem = eem, triggers = triggers)
}

/** Exit of the refund step. Produced by the core checking ingress gateway. */
@Serializable
@SerialName(PaymentEvent.FUNDS_REFUNDED)
data class FundsRefunded(
    override val payment: Payment,
    override val id: String? = null, override val timestamp: Long? = null, override val partitionKey: String? = null,
    override val tags: Map<String, String>? = null, override val raw: RawRecord? = null,
    override val eem: EnvelopeEncryptionMetadata? = null, override val triggers: List<EventReference>? = null,
) : PaymentEvent() {
    override fun toString() = super.toString()
    override fun copyEvent(
        id: String?, timestamp: Long?, partitionKey: String?, tags: Map<String, String>?,
        raw: RawRecord?, eem: EnvelopeEncryptionMetadata?, triggers: List<EventReference>?,
    ): Event = copy(id = id, timestamp = timestamp, partitionKey = partitionKey, tags = tags, raw = raw, eem = eem, triggers = triggers)
}

/** Abort of the refund step: the money is still owed. Produced by the core checking ingress gateway. */
@Serializable
@SerialName(PaymentEvent.REFUND_FAILED)
data class RefundFailed(
    override val payment: Payment,
    override val reason: String,
    override val id: String? = null, override val timestamp: Long? = null, override val partitionKey: String? = null,
    override val tags: Map<String, String>? = null, override val raw: RawRecord? = null,
    override val eem: EnvelopeEncryptionMetadata? = null, override val triggers: List<EventReference>? = null,
) : PaymentEvent(), Failure {
    override fun toString() = super.toString()
    override fun copyEvent(
        id: String?, timestamp: Long?, partitionKey: String?, tags: Map<String, String>?,
        raw: RawRecord?, eem: EnvelopeEncryptionMetadata?, triggers: List<EventReference>?,
    ): Event = copy(id = id, timestamp = timestamp, partitionKey = partitionKey, tags = tags, raw = raw, eem = eem, triggers = triggers)
}

/** Outcome: paid. Only the control service decides it. */
@Serializable
@SerialName(PaymentEvent.PAYMENT_COMPLETED)
data class PaymentCompleted(
    override val payment: Payment,
    override val id: String? = null, override val timestamp: Long? = null, override val partitionKey: String? = null,
    override val tags: Map<String, String>? = null, override val raw: RawRecord? = null,
    override val eem: EnvelopeEncryptionMetadata? = null, override val triggers: List<EventReference>? = null,
) : PaymentEvent() {
    override fun toString() = super.toString()
    override fun copyEvent(
        id: String?, timestamp: Long?, partitionKey: String?, tags: Map<String, String>?,
        raw: RawRecord?, eem: EnvelopeEncryptionMetadata?, triggers: List<EventReference>?,
    ): Event = copy(id = id, timestamp = timestamp, partitionKey = partitionKey, tags = tags, raw = raw, eem = eem, triggers = triggers)
}

/** Outcome: failed, and any money taken is back. `reason` is copied from the abort that caused it. */
@Serializable
@SerialName(PaymentEvent.PAYMENT_FAILED)
data class PaymentFailed(
    override val payment: Payment,
    override val reason: String,
    override val id: String? = null, override val timestamp: Long? = null, override val partitionKey: String? = null,
    override val tags: Map<String, String>? = null, override val raw: RawRecord? = null,
    override val eem: EnvelopeEncryptionMetadata? = null, override val triggers: List<EventReference>? = null,
) : PaymentEvent(), Failure {
    override fun toString() = super.toString()
    override fun copyEvent(
        id: String?, timestamp: Long?, partitionKey: String?, tags: Map<String, String>?,
        raw: RawRecord?, eem: EnvelopeEncryptionMetadata?, triggers: List<EventReference>?,
    ): Event = copy(id = id, timestamp = timestamp, partitionKey = partitionKey, tags = tags, raw = raw, eem = eem, triggers = triggers)
}

/**
 * A step's deadline passed without its exit or abort. **Not an outcome**: a late answer still moves the payment on.
 * `step` is one of [Steps].
 */
@Serializable
@SerialName(PaymentEvent.PAYMENT_EXPIRED)
data class PaymentExpired(
    override val payment: Payment,
    val step: String,
    override val id: String? = null, override val timestamp: Long? = null, override val partitionKey: String? = null,
    override val tags: Map<String, String>? = null, override val raw: RawRecord? = null,
    override val eem: EnvelopeEncryptionMetadata? = null, override val triggers: List<EventReference>? = null,
) : PaymentEvent() {
    override fun toString() = super.toString()
    override fun copyEvent(
        id: String?, timestamp: Long?, partitionKey: String?, tags: Map<String, String>?,
        raw: RawRecord?, eem: EnvelopeEncryptionMetadata?, triggers: List<EventReference>?,
    ): Event = copy(id = id, timestamp = timestamp, partitionKey = partitionKey, tags = tags, raw = raw, eem = eem, triggers = triggers)
}
