# Wiring a kopipes Lambda

kopipes keeps **no global state**. A Lambda builds its collaborators once, at start-up, and passes them to the
components it creates. The same code then runs as a plain Lambda, inside Spring Boot, or in a unit test with
in-memory fakes, with nothing to reset between tests.

## The context

`AwsKopipesContext` holds what an application's pipelines share:

| Property | Default |
|---|---|
| `envConfig` | `AwsEnvironmentConfig()`: reads the env vars listed under [Configuration](#configuration) |
| `dynamoDbClientFactory`, `s3ClientFactory`, `kmsClientFactory`, `cloudWatchClientFactory`, `eventBridgeClientFactory` | the `Default*ClientFactory` for that service, using `envConfig`'s region and endpoint |
| `unitOfWorkSnapshotter` | snapshots units of work for fault events and debug logs, including Kinesis, DynamoDB and SQS records |
| `eventPublisher` | `EventBridgePublisher` on `BUS_NAME` |
| `faultManager` | publishes faults through `eventPublisher`; retry switches from `STREAM_RETRY_ENABLED` and `ITEM_LEVEL_RETRY_ENABLED`; AWS throttling errors are retryable |

Every argument's default is built from the arguments before it. `AwsKopipesContext()` is a complete production
setup, and replacing one piece rewires what depends on it: pass an `eventPublisher` and the default `faultManager`
publishes faults through it.

Clients are created lazily, on the first call that needs them, so a context costs nothing for the services a
function doesn't use, and their SDKs need not be on the classpath.

`core` has the same idea without AWS: `KopipesContext` and `BasicKopipesContext`, whose default publisher keeps events
in memory.

## Building components

Every component takes its collaborators in its constructor. Most also have a constructor, or a builder method, that
takes them from the context:

```kotlin
val kinesisAdapter = KinesisAdapter(context, MyEventCodec)        // fault manager, batch size
val dynamodbAdapter = DynamodbAdapter(context)
val microstore = DynamoDbEventsMicrostore(context)                 // table, region, parallelism, step metrics
val sink = DynamoDbSink(context)
val publisher = EventBridgePublisher(context.envConfig, context.eventBridgeClientFactory)

val assembler = PipelineAssembler.builder()
    .context(context)                                              // fault manager, pipeline metrics
    .addPipeline(
        UpdatePipeline.builder()
            .id("update")
            .context(context)                                      // DynamoDB client, parallelism, step metrics
            .eventCodec(MyEventCodec)
            .toUpdateRequest(::toUpdateRequest)
            .build()
    )
    .build()
```

Builders fail at `build()` when a required collaborator is missing (for example
`dynamoDbConnectorOptions (or context) is required`), so a wiring mistake shows up when the Lambda starts, not halfway
through a batch.

## A plain Lambda

Keep the wiring in a container class: it takes the context and builds everything else, and its `build()` is the
production composition root. The handler only builds the container, once per Lambda container.

```kotlin
class ListenerContainer(
    val context: AwsKopipesContext,
    val eventsMicrostore: EventsMicrostore = DynamoDbEventsMicrostore(context),
) {
    companion object {
        fun build() = ListenerContainer(AwsKopipesContext())
    }

    val assembler: PipelineAssembler by lazy {
        PipelineAssembler.builder()
            .context(context)
            .addPipeline(CollectPipeline.builder().id("collect").eventsMicrostore(eventsMicrostore).build())
            .build()
    }

    val kinesisAdapter by lazy { KinesisAdapter(context, MyEventCodec) }
}

class Listener(
    containerFactory: () -> ListenerContainer = { ListenerContainer.build() },
) : RequestHandler<KinesisEvent, Void?> {
    private val container by lazy(containerFactory)

    override fun handleRequest(event: KinesisEvent, context: Context): Void? = runBlocking {
        container.assembler.assemble(container.kinesisAdapter.fromKinesis(event)).collect()
        null
    }
}
```

Java works the same way: `new AwsKopipesContext()`, `new KinesisAdapter(context, codec)`,
`PipelineAssembler.builder().context(context)`. See the [Java guide](JavaDevelopers.md).

## Tests

Build a context with the pieces you want to observe, and hand the container fakes for its ports:

```kotlin
val publisher = EventPublisherInMemory()
val context = AwsKopipesContext(
    eventPublisher = publisher,
    faultManager = FaultManager(publisher, skipErrorLogging = true),
)
val listener = Listener { ListenerContainer(context, eventsMicrostore = EventsMicrostoreInMemory()) }

listener.handleRequest(kinesisEvent, TestContext())
```

Each test gets its own context, so tests don't share state and nothing needs resetting afterwards.

A bare `FaultManager(publisher)` snapshots units of work without their AWS records. When a test asserts on a fault's
`uow.record`, use the context's own fault manager: `AwsKopipesContext(eventPublisher = publisher).faultManager`.

## Custom retry rules

To make an application's own errors retryable by the stream (with `STREAM_RETRY_ENABLED=true`) instead of becoming
faults, pass `isRetryable` when building the context:

```kotlin
fun gatewayContext() = AwsKopipesContext(
    isRetryable = { it is PaymentNotKnownYet || isAwsRetryable(it) },
)
```

## Configuration

`AwsEnvironmentConfig` reads these env vars. Subclass it and override a method to supply a value some other way,
or use Spring, which also reads them as `kopipes.*` properties.

| Env var | Used for |
|---|---|
| `BUS_NAME`, `BUS_SRC`, `BUS_ENDPOINT_ID`, `TIMEOUT` | the EventBridge publisher |
| `PUBLISH_BATCH_SIZE`, `PUBLISH_MAX_REQ_SIZE`, `PUBLISH_PARALLEL` (falling back to `BATCH_SIZE`, `MAX_REQ_SIZE`, `PARALLEL`) | publisher batching |
| `EVENT_TABLE_NAME` (else `ENTITY_TABLE_NAME`) | the events microstore's table |
| `BATCH_SIZE` | the `stream.batch.utilization` gauge of the adapters |
| `PARALLEL`, `CW_PARALLEL` | sinks' parallelism |
| `BUCKET_NAME`, `CLAIMCHECK_BUCKET_NAME` | S3 sink, claim checks |
| `STREAM_RETRY_ENABLED`, `ITEM_LEVEL_RETRY_ENABLED` | the fault manager |
| `METRICS` (`pipeline`, `step`, `emf`, or `*`), `NAMESPACE` | metrics; see [Observability](Observability.md) |
| `AWS_REGION` / `AWS_DEFAULT_REGION`, `AWS_ENDPOINT_URL` | clients |
| `ACCOUNT_NAME`, `STAGE`, `SERVICE`, `AWS_LAMBDA_FUNCTION_NAME`, `SKIP` | the standard tags on every event and fault |

`TTL` is not read: collect and correlate pipelines take `.ttlDays(...)` (default 33).

## Spring Boot

Add the [`spring`](../spring/README.md) module and the context becomes an auto-configured bean, read from
`application.yml` as well as from the env vars above. Spring Framework apps without Boot `@Import(KopipesConfiguration::class)` for the
same context bean. `SpringKinesisHandler` and its siblings run a Spring application
as a Lambda. The containers stay the same: Spring only provides the context.

## Pitfalls

- **Fat jars must merge `META-INF/services`.** The AWS raw record types (`RawRecord` variants in faults and claim
  checks) register through `META-INF/services/io.kopipes.core.RawRecordTypeProvider`. Spring apps have more files to
  merge; see the [spring README](../spring/README.md#packaging-merge-springs-metadata-in-the-shadow-jar).
- **A claim-check store inside the publisher.** `ClaimCheckStore` takes its fault manager as a function, because the
  fault manager publishes through the publisher that holds the store:
  `ClaimCheckStore(envConfig, s3ClientFactory, faultManager = { context.faultManager })`.
- **One context per Lambda container, not per invocation.** Build it in the container, which the handler creates once.
