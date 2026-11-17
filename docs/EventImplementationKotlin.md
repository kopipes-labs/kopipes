# Implementing Events (Kotlin)

In the `aws-lambda-stream-kotlin` library, events must follow specific technical requirements to ensure predictable processing and compatibility with the framework's pipelines.

## Table of Contents

- [Implementation Details](#implementation-details)
    - [1. Immutability](#1-immutability)
    - [2. The `Event` Interface](#2-the-event-interface)
    - [3. Core Properties](#3-core-properties)
    - [4. Required Methods](#4-required-methods)
    - [5. Serialization](#5-serialization)
    - [6. The `copyEvent` Mechanism](#6-the-copyevent-mechanism)
    - [7. Recommended Pattern](#7-recommended-pattern)
    - [8. Event Codec](#8-event-codec)
    - [9. Higher Order Events](#9-higher-order-events)


## Implementation Details

### 1. Immutability
Events must be immutable. All properties should be declared using `val`. This ensures that events cannot be mutated accidentally during pipeline processing, leading to safer and more predictable side effects.

### 2. The `Event` Interface
All events must implement the `io.kopipes.core.Event` interface.

### 3. Core Properties
The `Event` interface requires the following metadata properties:
*   `id: String?`: A unique identifier for the event.
*   `timestamp: Long?`: The time the event occurred (milliseconds since epoch).
*   `partitionKey: String?`: Used for ordering (e.g., Kinesis shard key).
*   `tags: Map<String, String>?`: Metadata tags for filtering or categorization.
*   `raw: RawRecord?`: The raw payload or reference (e.g., a `ClaimCheck`).
*   `eem: EnvelopeEncryptionMetadata?`: Envelope Encryption Metadata used for field-level encryption. It contains:
    * `masterKeyAlias: String?`: The KMS key alias used to generate the data key.
    * `dataKeys: Map<String, String>?`: A map of region to Base64 encoded encrypted data key.
    * `fields: List<String>?`: The list of field names that are encrypted.
    * `algorithm: String?`: The encryption algorithm (default: `AES/GCM/NoPadding`).
*   `triggers: List<EventReference>?`: References to preceding events in the causal chain.

### 4. Required Methods
*   **`eventType()`**: Must return a unique `String` identifying the event type. Used for routing and filtering.
*   **`copyEvent(...)`**: Creates a new instance with updated metadata.

### 5. Serialization
Events use `kotlinx.serialization` for transport:
*   Use `@Serializable` on your event classes.
*   Use `@SerialName("TYPE_NAME")` to define a stable name for polymorphic serialization.


### 6. The `copyEvent` Mechanism
The `copyEvent` method allows the framework to update standard metadata on any `Event` without knowing its concrete type.

```kotlin
fun copyEvent(
    id: String? = this.id,
    timestamp: Long? = this.timestamp,
    partitionKey: String? = this.partitionKey,
    tags: Map<String, String>? = this.tags,
    raw: RawRecord? = this.raw,
    eem: EnvelopeEncryptionMetadata? = this.eem,
    triggers: List<EventReference>? = this.triggers
): Event
```

*   **`myEvent.copy(...)`**: Use Kotlin's generated data class `copy` for domain-specific fields.
*   **`event.copyEvent(...)`**: Use this to update metadata fields defined in the `Event` interface.

### 7. Recommended Pattern
The preferred way to implement events is using a `data class` implementing the `Event` interface:

```kotlin
@Serializable
@SerialName("ORDER_PLACED")
data class OrderPlacedEvent(
    override val id: String? = null,
    override val timestamp: Long? = null,
    override val partitionKey: String? = null,
    override val tags: Map<String, String>? = null,
    override val raw: RawRecord? = null,
    override val eem: EnvelopeEncryptionMetadata? = null,
    override val triggers: List<EventReference>? = null,
    val orderId: String
) : Event {
    override fun eventType() = "ORDER_PLACED"
    
    override fun toString(): String = ordersJson.encodeToString(this)

    override fun copyEvent(
        id: String?,
        timestamp: Long?,
        partitionKey: String?,
        tags: Map<String, String>?,
        raw: RawRecord?,
        eem: EnvelopeEncryptionMetadata?,
        triggers: List<EventReference>?
    ): Event = copy(
        id = id,
        timestamp = timestamp,
        partitionKey = partitionKey,
        tags = tags,
        raw = raw,
        eem = eem,
        triggers = triggers
    )
}
```

### 8. Event Codec
For every family of events, use the framework's `KotlinxEventCodec` to handle serialization.

```kotlin
import io.kopipes.aws.serialization.KotlinxEventCodec
import io.kopipes.aws.serialization.KotlinxSerializationStrategy

val ordersJson = KotlinxSerializationStrategy.defaultJson()

object OrdersEventCodec : EventCodec by KotlinxEventCodec(OrderPlacedEvent.serializer(), ordersJson)
```

### 9. Higher Order Events
Higher order events are events emitted as a result of evaluating one or more upstream events. The framework automatically copies standard metadata (id, timestamp, triggers, partitionKey, etc.) from the evaluation context to the new event.

Use the `emit` lambda in `EvaluatePipeline` to instantiate your events. The framework will apply the appropriate metadata after the lambda returns.

```kotlin
EvaluatePipeline(
    // ...
    emit = { uow ->
        val base = uow.event as ShipmentCreatedEvent
        // Standard metadata is applied automatically by the pipeline
        // You only need to initialize domain fields here:
        listOf(VerifyTargetAddressEvent(entity = base.entity))
    }
)
```

Standard metadata (id, timestamp, tags, triggers, raw, and eem) are propagated from the triggering unit of work automatically. If you want to override these fields on a specific event, you can call `copyEvent` within your lambda:

```kotlin
emit = { uow ->
    val base = uow.event as ShipmentCreatedEvent
    listOf(
        VerifyTargetAddressEvent(entity = base.entity).copyEvent(partitionKey = "custom-key")
    )
}
```
