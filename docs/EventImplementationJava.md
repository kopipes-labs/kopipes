# Implementing Events (Java)

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
Events must be immutable. In Java 17+, the recommended way to achieve this is by using **Records**. Records are immutable by default and provide a concise syntax for data-holding classes.

### 2. The `Event` Interface
All events must implement the `io.kopipes.core.Event` interface.

### 3. Core Properties
The `Event` interface requires the following metadata properties (accessible via standard getters in Java):
*   `getId()`: A unique identifier for the event.
*   `getTimestamp()`: The time the event occurred (milliseconds since epoch).
*   `getPartitionKey()`: Used for ordering (e.g., Kinesis shard key).
*   `getTags()`: Metadata tags for filtering or categorization.
*   `getRaw()`: The raw payload or reference (e.g., a `ClaimCheck`).
*   `getEem()`: Envelope Encryption Metadata used for field-level encryption. It contains:
    * `getMasterKeyAlias()`: The KMS key alias used to generate the data key.
    * `getDataKeys()`: A map of region to Base64 encoded encrypted data key.
    * `getFields()`: The list of field names that are encrypted.
    * `getAlgorithm()`: The encryption algorithm (default: `AES/GCM/NoPadding`).
*   `getTriggers()`: References to preceding events in the causal chain.

### 4. Required Methods
*   **`eventType()`**: Must return a unique `String` identifying the event type. Used for routing and filtering.
*   **`copyEvent(...)`**: Creates a new instance with updated metadata.

### 5. Serialization
While the framework uses `kotlinx.serialization` internally, Java consumers typically use **Jackson**.
*   Use `@JsonTypeInfo` and `@JsonSubTypes` on a base interface or abstract class to handle polymorphic serialization.
*   Records work seamlessly with Jackson (2.12+).

### 6. The `copyEvent` Mechanism
The `copyEvent` method allows the framework to update standard metadata on any `Event` without knowing its concrete type.

```java
@Override
public Event copyEvent(
    String id,
    Long timestamp,
    String partitionKey,
    Map<String, String> tags,
    RawRecord raw,
    EnvelopeEncryptionMetadata eem,
    List<EventReference> triggers
) {
    return new MyEvent(id, timestamp, partitionKey, tags, raw, eem, triggers, this.domainField);
}
```

### 7. Recommended Pattern
The preferred way to implement events in Java is using a `sealed interface` for the event family and `record` for concrete events:

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = OrderPlacedEvent.class, name = "OrderPlacedEvent")
})
public sealed interface OrderEvent extends Event permits OrderPlacedEvent {
    @Override
    default String eventType() {
        return this.getClass().getSimpleName();
    }
}

public record OrderPlacedEvent(
    String id,
    Long timestamp,
    String partitionKey,
    Map<String, String> tags,
    RawRecord raw,
    EnvelopeEncryptionMetadata eem,
    List<EventReference> triggers,
    String orderId
) implements OrderEvent {
    @Override public String getId() { return id; }
    @Override public Long getTimestamp() { return timestamp; }
    @Override public String getPartitionKey() { return partitionKey; }
    @Override public Map<String, String> getTags() { return tags; }
    @Override public RawRecord getRaw() { return raw; }
    @Override public EnvelopeEncryptionMetadata getEem() { return eem; }
    @Override public List<EventReference> getTriggers() { return triggers; }

    @Override
    public Event copyEvent(String id, Long timestamp, String partitionKey, 
                          Map<String, String> tags, RawRecord raw, 
                          EnvelopeEncryptionMetadata eem, List<EventReference> triggers) {
        return new OrderPlacedEvent(id, timestamp, partitionKey, tags, raw, eem, triggers, orderId);
    }
}
```

### 8. Event Codec
For Java projects using Jackson, you can implement a `JacksonEventCodec` to handle serialization:

```java
public class JacksonEventCodec implements EventCodec {
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Override
    public Event decode(String s) {
        try {
            return objectMapper.readValue(s, OrderEvent.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String encode(Event event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
```

### 9. Higher Order Events
Higher order events are events emitted as a result of evaluating one or more upstream events. The framework automatically copies standard metadata (id, timestamp, triggers, partitionKey, etc.) from the evaluation context to the new event.

When using `EvaluatePipeline` in Java, use the `emitJava` method:

```java
EvaluatePipeline.builder()
    .id("order-evaluation")
    .emitJava(uow -> {
        OrderPlacedEvent base = (OrderPlacedEvent) uow.getEvent();
        // You only need to initialize domain fields here:
        return List.of(new ProcessOrderEvent(base.orderId()));
    })
    .build();
```

Standard metadata are propagated from the triggering unit of work automatically. If you want to override these fields, you can call `copyEvent` within your lambda:

```java
.emitJava(uow -> {
    OrderPlacedEvent base = (OrderPlacedEvent) uow.getEvent();
    return List.of(
        ((ProcessOrderEvent) new ProcessOrderEvent(base.orderId()))
            .copyEvent(...) // override metadata here
    );
})
```
