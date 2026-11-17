# Kotlin Developers Guide

AWS Lambda Stream for the JVM is a Kotlin-first framework, leveraging modern language features to build reliable, serverless, event-driven applications on AWS.

This framework leverages **Kotlin Coroutines and Flow** to provide a type-safe, non-blocking, and highly composable developer experience for building AWS Lambda functions that process event streams.

## Why use this?

*   **Type-Safe Pipelines**: Catch errors at compile time, not runtime.
*   **Non-Blocking I/O**: Efficiently handle AWS service calls (DynamoDB, S3, EventBridge) using coroutines.
*   **Built-in Reliability**: Idempotency, retries, and fault-handling are first-class citizens.
*   **Composable**: Small, focused pipeline "flavors" (Collect, Correlate, Evaluate, Materialize, CDC...) that you
    combine in one function or across functions.
*   **Observability**: Automatic EMF metrics and detailed "Unit of Work" snapshots for debugging.

New to the framework? Read [How kopipes works](Concepts.md) first: one diagram and a glossary. To add it to your
build, see [Installation](../README.md#installation).

## Quick Start

### 1. Define your Event

Implementing the `Event` interface ensures standard metadata (ID, timestamp, correlation tags) is propagated automatically.

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
    override fun copyEvent(
        id: String?, timestamp: Long?, partitionKey: String?, 
        tags: Map<String, String>?, raw: RawRecord?, 
        eem: EnvelopeEncryptionMetadata?, triggers: List<EventReference>?
    ) = copy(id = id, timestamp = timestamp, partitionKey = partitionKey, tags = tags, raw = raw, eem = eem, triggers = triggers)
}

/** Turns stream records into events and back. */
object OrderEventCodec : EventCodec by KotlinxEventCodec(OrderPlacedEvent.serializer())
```

The envelope fields look like ceremony, but each one earns its place: `id` and `timestamp` make processing idempotent
and ordered, `tags` carry the standard metadata (account, stage, function) into every fault, `raw` keeps the original
record for fault resubmission, `eem` holds envelope-encryption keys, and `triggers` links an emitted event to the
events that caused it. `copyEvent` lets the framework set them without knowing your class. When you have several
events, put them under a sealed class with one codec (see [Implementing Events](EventImplementationKotlin.md)).

### 2. Configure your Pipeline

Choose a "flavor" that fits your use case. For example, use `CollectPipeline` to store events in a Microstore. The
`AwsKopipesContext` holds the collaborators every pipeline shares (fault manager, publisher, AWS clients);
`AwsKopipesContext()` reads the usual env vars. See [Wiring a kopipes Lambda](Wiring.md).

```kotlin
class OrderContainer(
    val context: AwsKopipesContext,
    val eventsMicrostore: EventsMicrostore = DynamoDbEventsMicrostore(context),
) {
    companion object {
        fun build() = OrderContainer(AwsKopipesContext())
    }

    val assembler: PipelineAssembler by lazy {
        PipelineAssembler.builder()
            .context(context)
            .addPipeline(
                CollectPipeline.builder()
                    .id("order-collection")
                    .eventFilter(EventFilters.classes(OrderPlacedEvent::class))
                    .eventsMicrostore(eventsMicrostore)
                    .build()
            )
            .build()
    }

    val kinesisAdapter by lazy { KinesisAdapter(context, OrderEventCodec) }
}
```

### 3. Assemble and Run in Lambda

Adapters turn AWS Lambda events into a Kotlin `Flow`. The `PipelineAssembler` runs them through your configured pipelines.

```kotlin
class OrderHandler(
    containerFactory: () -> OrderContainer = { OrderContainer.build() },
) : RequestHandler<KinesisEvent, Void?> {

    private val container by lazy(containerFactory)

    override fun handleRequest(input: KinesisEvent, context: Context): Void? = runBlocking {
        val flow = container.kinesisAdapter.fromKinesis(input)

        container.assembler
            .assemble(flow)
            .collect { uow ->
                println("Processed ${uow.event?.id}")
            }
        null
    }
}
```

On Spring Boot, the [spring module](../spring/README.md) provides the context as a bean and handler classes that run
a Spring application as a Lambda.

## Core Concepts for Developers

### Unit of Work (UOW)
Everything in the framework revolves around the `UnitOfWork`. It wraps your event and provides context, such as:
*   **Snapshots**: Useful for debugging failures.
*   **Metrics**: Automatic EMF reporting.
*   **Extensions**: Attach custom state to a processing unit.

### Adapters (`from`)
Zero-boilerplate integration with AWS event sources:
*   `KinesisAdapter`, `SqsAdapter`, `SnsAdapter`, `S3Adapter`, `DynamodbAdapter`.

### Pipeline Flavors
Each flavor does one job; you supply the rules. Collect, Correlate and Evaluate make a control service; Materialize
and CDC a BFF; Translate and Action the gateways. [Pipelines](Pipelines.md) has the whole catalogue, with a sample
of each.

## Testing
Test your logic without mocks or complex infrastructure: give the container a context with an in-memory publisher,
and in-memory fakes for its ports.

```kotlin
@Test
fun `should collect the order`() {
    // Arrange
    val publisher = EventPublisherInMemory()
    val context = AwsKopipesContext(eventPublisher = publisher)
    val microstore = EventsMicrostoreInMemory()
    val handler = OrderHandler { OrderContainer(context, microstore) }
    val event = OrderPlacedEvent(id = "e-1", timestamp = 1_730_900_520_000, orderId = "123")

    // Act
    handler.handleRequest(kinesisEventOf(event), TestContext())   // io.kopipes.aws.testsupport.TestContext

    // Assert
    microstore.getStored().keys shouldBe setOf("e-1")
    publisher.faults() shouldBe emptyList()
}

/** A Kinesis batch holding the given events, as the stream would deliver it. */
private fun kinesisEventOf(vararg events: OrderPlacedEvent) = KinesisEvent().apply {
    records = events.mapIndexed { i, event ->
        KinesisEvent.KinesisEventRecord().apply {
            eventID = "shard-0:$i"
            kinesis = KinesisEvent.Record().apply {
                data = ByteBuffer.wrap(OrderEventCodec.encode(event).toByteArray())
                partitionKey = event.orderId
                sequenceNumber = "$i"
            }
        }
    }
}
```

## Documentation for Developers

* [How kopipes works](Concepts.md): the flow of a batch, the building blocks, a glossary
* [Pipelines](Pipelines.md): every flavor, with samples
* [Wiring a kopipes Lambda](Wiring.md)
* [Idempotence and Ordering](Idempotence.md)
* [Deadlines](Deadlines.md)
* [Observability](Observability.md)
* [Fault Re-submission](FaultResubmission.md) and [Replaying Events](ReplayEvents.md)
* [Framework Features](Features.md)
* [Understanding Events and Event Types](Events.md)
* [Envelope Encryption](Encryption.md)
* [Benefits of Kotlin CoRoutines and the Flow Framework](KotlinCoRoutinesAndFlow.md)
* [Implementing Events](EventImplementationKotlin.md)

## Examples

* [Payments](../examples/payments/README.md): the most complete example. A whole subsystem (control service, BFF,
  four gateways, hub, lake, fault monitor) with deadlines, a circuit breaker and a rate limiter, runnable on
  LocalStack.
* [Serialized Unit Tracking (SUT)](../examples/sut): a smaller Kotlin system, plus a regional health check.

---

*Looking for the architectural deep-dive? Check out the [Software Architects Guide](SoftwareArchitects.md).*
