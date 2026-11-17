# Java Developers Guide

While written in Kotlin, AWS Lambda Stream for the JVM is designed to be Java-friendly. It provides Java-compatible APIs and abstractions to allow Java developers to build reliable, serverless, event-driven applications on AWS.

## Why use this in Java?

*   **JVM Interoperability**: Leverage a robust Kotlin framework directly from your Java projects.
*   **Reliability for Java**: First-class support for idempotency, retries, and fault-handling.
*   **Modern APIs**: Clean, builder-based configuration for pipelines.
*   **Seamless Integration**: Use standard Java 17+ features like Records and Sealed Classes for your event models.

New to the framework? Read [How kopipes works](Concepts.md) first: one diagram and a glossary. To add it to your
build, see [Installation](../README.md#installation).

## Quick Start

### 1. Define your Event

Use Java Records for a concise and immutable event definition, and Jackson (or any JSON library) for
serialization. A record's accessors are `id()`, `timestamp()`..., so it also implements the `Event` getters.

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
public record OrderPlacedEvent(
    String id,
    Long timestamp,
    String partitionKey,
    Map<String, String> tags,
    RawRecord raw,
    EnvelopeEncryptionMetadata eem,
    List<EventReference> triggers,
    String orderId
) implements Event {
    @Override public String eventType() { return "ORDER_PLACED"; }

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

### 2. Configure your Pipeline

Use the `PipelineAssembler` builder to configure your processing logic. kopipes keeps no global state: an
`AwsKopipesContext` holds the shared collaborators (env config, client factories, event publisher, fault manager), and
you pass it to what you build. `new AwsKopipesContext()` reads the usual env vars and publishes to EventBridge.

```java
public class OrderProcessingContainer {
    public final AwsKopipesContext context;
    public final PipelineAssembler assembler;
    public final DynamodbAdapter dynamoDbAdapter;

    public OrderProcessingContainer(AwsKopipesContext context) {
        this.context = context;
        CdcPipeline cdcPipeline = CdcPipeline.builder()
                .id("order-cdc")
                .eventPublisher(context.getEventPublisher())
                .toEventJava(this::toEvent)
                .build();

        this.assembler = PipelineAssembler.builder()
                .context(context)
                .addPipeline(cdcPipeline)
                .build();
        this.dynamoDbAdapter = new DynamodbAdapter(context);
    }

    /** Maps a row change of the orders table to an event; null publishes nothing. */
    Event toEvent(UnitOfWork uow) {
        if (!(uow.getEvent().getRaw() instanceof RecordPair change) || change.getOld() != null || change.getNew() == null) {
            return null;                                 // only new rows are placed orders
        }
        return orderPlacedOf(change.getNew());
    }

    static OrderPlacedEvent orderPlacedOf(RecordImage row) {
        String orderId = row.getS("pk");
        return new OrderPlacedEvent(UUID.randomUUID().toString(), System.currentTimeMillis(), orderId,
                Map.of(), null, null, List.of(), orderId);
    }
}
```

### 3. Assemble and Run in Lambda

The `PipelineRunner` provides a Java-friendly way to execute your pipelines within a standard `RequestHandler`.

```java
public class OrderHandler implements RequestHandler<DynamodbEvent, Void> {
    private final OrderProcessingContainer container = new OrderProcessingContainer(new AwsKopipesContext());

    @Override
    public Void handleRequest(DynamodbEvent input, Context context) {
        new PipelineRunner<DynamodbEvent>(container.assembler)
                .headFlow(container.dynamoDbAdapter::fromDynamoDB)
                .transformer(flow -> Handlers.collectMetrics(flow, container.context))
                .run(input);
        return null;
    }
}
```

`Handlers.collectMetrics(flow, context)` reports the batch's metrics in CloudWatch Embedded Metric Format when
`METRICS` includes `emf`; see [Observability](Observability.md).

For tests, custom retry rules and the full list of settings, see [Wiring a kopipes Lambda](Wiring.md).

## Core Concepts for Java Developers

### Unit of Work (UOW)
The `UnitOfWork` is a standard Java-compatible data class. You can access the event and context using standard getters:
*   `uow.getEvent()`: Access the event being processed.
*   `uow.getPipeline()`: Access pipeline metadata.
*   `uow.getExtensions()`: Access custom state.

### Java-Friendly Flavors
Pipeline "flavors" like `CdcPipeline` or `EvaluatePipeline` provide `toEventJava`, `expressionJava`, `emitJava` and
other methods that accept standard `java.util.function` types instead of Kotlin lambdas. See
[Pipelines](Pipelines.md) for what each flavor does.

### Handlers and PipelineRunner
*   `PipelineRunner`: Orchestrates the execution of the flow in a blocking manner suitable for Lambda.
*   `Handlers`: Provides static utility methods like `collectMetrics(flow, context)` that can be used in your pipeline runner.

## Testing
You can test your pipeline logic using standard JUnit 5.

Keep the business mapping in plain methods and test them directly, with JUnit 5 and no AWS:

```java
@Test
void mapsANewOrderRowToOrderPlaced() {
    // Arrange
    RecordImage row = new RecordImage(Map.of("pk", new AttributeValue().withS("order-123")));

    // Act
    OrderPlacedEvent event = OrderProcessingContainer.orderPlacedOf(row);

    // Assert
    assertEquals("order-123", event.orderId());
    assertEquals("order-123", event.getPartitionKey());
}
```

`RecordImage` wraps a DynamoDB stream image; `AttributeValue` is
`com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue`. To run a whole batch through the
pipelines, build the container with an `EventPublisherInMemory` in its context and call the handler with a
`DynamodbEvent`; the [URL Shortener](../examples/urlshortener) and the Kotlin guide show the pattern.

## Documentation for Developers

* [How kopipes works](Concepts.md): the flow of a batch, the building blocks, a glossary
* [Pipelines](Pipelines.md): every flavor, with samples
* [Wiring a kopipes Lambda](Wiring.md)
* [Framework Features](Features.md)
* [Understanding Events and Event Types](Events.md)
* [Envelope Encryption](Encryption.md)
* [Implementing Events](EventImplementationJava.md)

## Examples

The [URL Shortener](../examples/urlshortener) example demonstrates how to use the framework in a Java-based project,
CDK stacks included. For a larger system, read the [Payments](../examples/payments/README.md) example: it is Kotlin,
but the wiring translates one to one.

---

*Looking for the architectural deep-dive? Check out the [Software Architects Guide](SoftwareArchitects.md).*
