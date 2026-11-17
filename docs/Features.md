# Framework Features

What kopipes gives you, grouped by concern. Each item links to the page that explains it.

## Pipelines

Ready-made pipeline flavors you configure with a builder. See [Pipelines](Pipelines.md).

- **Control services:** `CollectPipeline`, `CorrelatePipeline` and `EvaluatePipeline` record events in a service's
  own [events microstore](EventsMicrostore.md), group them by entity, and run rules that emit higher-order events.
- **Deadlines without schedulers:** a correlated item with a TTL fires an expiry rule when DynamoDB deletes it. See
  [Deadlines](Deadlines.md).
- **Read models:** `MaterializePipeline` (DynamoDB), `MaterializeS3Pipeline` (S3) and `UpdatePipeline` (one event,
  many entities, through an `EntityStore`: DynamoDB or in memory).
- **Outbox:** `CdcPipeline` publishes events from a table's own changes.
- **Gateways:** `TranslatePipeline` turns an outside system's records into internal events; `ActionPipeline` calls
  an outside system for each event.
- **Event filters:** pick events by class, type name, regex or content, and combine filters with `anyOf` and `allOf`.

## Reliability

- **Faults instead of stuck streams:** a record that fails becomes a `FaultEvent`, with the error and a snapshot of
  the unit of work and its original record, and the rest of the batch carries on. See
  [Fault Re-submission](FaultResubmission.md).
- **Stream retries for transient errors:** with `STREAM_RETRY_ENABLED`, retryable errors (AWS throttling, plus your
  own rules) fail the batch so the stream delivers it again; with `ITEM_LEVEL_RETRY_ENABLED`, a Kinesis handler
  reports only the failed records as batch item failures (`kinesisRetryableFailures()`).
- **Retries in connectors:** DynamoDB batch gets and EventBridge puts retry the items AWS left unprocessed, with
  exponential backoff and jitter.
- **Idempotency:** events carry ids; evaluate pipelines can emit events with deterministic ids, so a rule that fires
  again emits the same event.
- **Circuit breaker and rate limiter** for calls to outside systems, with their state in DynamoDB so it holds across
  every Lambda container.

## Recovery

- **Resubmit faults:** the `resubmit-events` tool sends failed records back to the function that failed them, once
  the bug is fixed. See [Fault Re-submission](FaultResubmission.md).
- **Replay from the event lake:** the `replay-events` tool sends archived events to one function, to fix the effects
  of a bug or fill a new service. See [Replaying Events](ReplayEvents.md).

## Data protection and large payloads

- **Envelope encryption:** field-level encryption of events and DynamoDB items with AWS KMS and AES-GCM, with data
  keys for several regions. See [Envelope Encryption](Encryption.md).
- **Claim check:** large payloads go to S3 and the event carries a reference. See [Claim Check](ClaimCheck.md).

## Observability

- **EMF metrics:** pipeline and step metrics (counts, latency, batch utilization) as CloudWatch Embedded Metric
  Format, switched on with the `METRICS` env var. See [Observability](Observability.md).
- **Unit of work snapshots** in fault events and debug logs: the event, the original record, and the AWS requests
  and responses made for it.
- **Standard tags** on every event and fault (account, stage, service, function, pipeline), and `triggers` linking
  an emitted event to the events that caused it.

## AWS integration

- **Adapters (event sources):** Kinesis, DynamoDB Streams, SQS, SNS, S3, EventBridge, Firehose, CloudWatch alarms,
  Cognito triggers, and fault resubmissions to HTTP functions.
- **Sinks and connectors:** DynamoDB, S3, EventBridge, CloudWatch metrics and KMS, on the AWS SDK for Kotlin. The
  SDK clients are `compileOnly`: a function ships only the ones it uses.
- **CDK constructs:** the event hub, event lake, fault monitor, events tables, functions and REST APIs as reusable
  constructs. See the [cdk module](../cdk/README.md).

## Developer experience

- **Kotlin first:** coroutines and `Flow` for non-blocking, batched processing. See
  [Coroutines and Flow](KotlinCoRoutinesAndFlow.md).
- **Java friendly:** builders take `java.util.function` types, `PipelineRunner` runs a flow from a plain handler.
  See the [Java guide](JavaDevelopers.md).
- **No global state:** components take their collaborators in constructors, from an `AwsKopipesContext`, so the
  same code runs on Lambda, in Spring, or in a test with in-memory fakes. See [Wiring](Wiring.md).
- **In-memory test doubles:** `EventPublisherInMemory`, `EventsMicrostoreInMemory`, `EntityStoreInMemory`,
  `EventsTableSimulator` and `TestContext`.
- **Pluggable serialization:** your events use any library behind an `EventCodec` (kotlinx.serialization in the
  Kotlin examples, Jackson in the Java one); the framework's own types use kotlinx.serialization.
- **Optional Spring Boot support:** the context as an auto-configured bean, and handlers that run a Spring
  application as a Lambda. See the [spring module](../spring/README.md).
- **Extensible `UnitOfWork`:** attach your own data to a unit of work with `withExtension`.
