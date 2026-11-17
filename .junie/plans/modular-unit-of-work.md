# Modular UnitOfWork Design Plan

## Goal
The goal is to modularize the `UnitOfWork` class to avoid the "God Object" pattern. Instead of having hardcoded fields for every AWS service (S3, DynamoDB, EventBridge, etc.), we will introduce a generic extension mechanism that allows service-specific data to be attached as needed.

## Design Principles

1.  **Decoupling**: The core `UnitOfWork` should not depend on service-specific SDK classes.
2.  **Extensibility**: Users and new modules should be able to attach custom metadata without modifying the core framework.
3.  **Backward Compatibility**: Existing code using `uow.updateRequest` or similar fields should continue to work through extension properties.
4.  **Type Safety**: Extensions should be easily retrievable in a type-safe manner.

---

## Proposed Architecture

### 1. Generic Extension Storage
`UnitOfWork` will hold a map of extensions, keyed by their class type.

```kotlin
data class UnitOfWork(
    val pipeline: Pipeline? = null,
    val record: Any? = null,
    val event: Event? = null,
    // ... other core fields ...
    private val extensions: Map<KClass<*>, Any> = emptyMap()
) {
    /** Retrieves an extension of the specified type. */
    inline fun <reified T : Any> getExtension(): T? = extensions[T::class] as? T

    /** Returns a new UnitOfWork with the given extension attached. */
    fun withExtension(extension: Any): UnitOfWork {
        return copy(extensions = extensions + (extension::class to extension))
    }
}
```

### 2. Service-Specific Extension Classes
Group fields that are currently top-level in `UnitOfWork` into dedicated data classes. For example:

#### DynamoDbExtensions
```kotlin
data class DynamoDbExtensions(
    val batchGetRequest: BatchGetItemRequest? = null,
    val batchGetResponse: BatchGetItemResponse? = null,
    val putRequest: PutItemRequest? = null,
    val putResponse: PutItemResponse? = null,
    val queryRequest: QueryRequest? = null,
    val queryResponse: QueryResponse? = null,
    val scanRequest: ScanRequest? = null,
    val updateRequest: UpdateItemRequest? = null,
    val updateResponse: UpdateItemResponse? = null
)
```

#### EventBridgeExtensions
```kotlin
data class EventBridgeExtensions(
    val publishRequest: PutEventsRequest? = null,
    val publishRequestEntry: PutEventsRequestEntry? = null,
    val publishResponse: ConnectorResponse? = null
)
```

### 3. Maintain Backward Compatibility
Use Kotlin extension properties to provide access to the modularized fields.

```kotlin
// In DynamoDb extensions file
val UnitOfWork.dynamoDb: DynamoDbExtensions
    get() = getExtension() ?: DynamoDbExtensions()

val UnitOfWork.updateRequest: UpdateItemRequest? 
    get() = dynamoDb.updateRequest

fun UnitOfWork.withUpdateResponse(response: UpdateItemResponse): UnitOfWork {
    val updated = dynamoDb.copy(updateResponse = response)
    return withExtension(updated)
}
```

### 4. Modular Serialization
Update `SerializableUnitOfWork` to support dynamic extension serialization.

-   **Interface for Serializability**: Extensions can implement a `Snapshottable` interface or similar to provide their serializable representation.
-   **Dynamic Collection**: `SerializableUnitOfWork` will iterate over extensions and collect their snapshots into a generic map or a list of type-tagged snapshots.

---

## Implementation Roadmap

### Phase 1: Core Changes
-   Add `extensions` map to `UnitOfWork`.
-   Implement `getExtension<T>()` and `withExtension(Any)`.

### Phase 2: Create Extension Groups
-   Define `DynamoDbExtensions`, `EventBridgeExtensions`, `MicrostoreExtensions`, etc.
-   Refactor `S3UnitOfWork` to be used via the extension mechanism (it is already encapsulated, just needs to be moved to the map).

### Phase 3: Compatibility Layer
-   Implement extension properties for all moved fields to ensure existing code remains functional.
-   Deprecate the old fields in `UnitOfWork`.

### Phase 4: Serialization Update
-   Refactor `SerializableUnitOfWork` to handle the new extension mechanism.
-   Ensure fault events and resubmission tools can still see the necessary data.

### Phase 5: Cleanup
-   Remove deprecated fields from core `UnitOfWork`.
-   Update all connectors and sinks to use the new extension-based API.
