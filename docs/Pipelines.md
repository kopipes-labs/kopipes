# Pipelines

A pipeline *flavor* is a ready-made pipeline for one job. You pick the flavor, give it an id, and fill in the parts
that are yours (which events, which key, what to write or emit) with a builder. The flavor does the plumbing:
filtering, batching, parallel AWS calls, retries, metrics and fault handling.

Several pipelines can share one function: register them with a `PipelineAssembler`, and each one picks the events it
wants with `eventFilter(...)` (by class, type name, regex or content; see `EventFilters`).

## Choosing a flavor

| Flavor | Module | Input | What it does | Output | Use it in |
|---|---|---|---|---|---|
| [`CollectPipeline`](#collect) | `core` | Events from a channel | Saves each event in the events microstore, under its id | A microstore item | A control service's listener |
| [`CorrelatePipeline`](#correlate) | `core` | The microstore's stream | Saves each collected event again under its correlation key; with a TTL, starts a deadline | A correlation item | A control service's trigger |
| [`EvaluatePipeline`](#evaluate) | `core` | The microstore's stream | Queries everything heard for the key, runs your rule, emits higher-order events | Events on the bus | A control service's trigger |
| [`MaterializePipeline`](#materialize) | `aws` | Events from a channel | Turns each event into a DynamoDB update | A read-model item | A BFF's listener |
| [`MaterializeS3Pipeline`](#materialize) | `aws` | Events from a channel | Turns each event into an S3 put or delete | An S3 object | A read model in S3 |
| [`UpdatePipeline`](#update) | `aws` | Events | Queries the items an event affects, then updates each one | DynamoDB updates | Fanning one event out to many items |
| [`CdcPipeline`](#cdc) | `aws` | A table's stream | Turns a row change into an event, optionally encrypting it | Events on the bus | A BFF's trigger (outbox) |
| [`TranslatePipeline`](#translate) | `core` | An outside system's record | Turns it into internal events | Events on the bus | An ingress gateway |
| [`ActionPipeline`](#action) | `core` | Events from a channel | Calls an outside system | Nothing, or events for outright refusals | An egress gateway |

The [payments example](../examples/payments/README.md) uses every one of them except `UpdatePipeline` and
`MaterializeS3Pipeline`.

## Collect

Saves each event as `pk = <event id>, sk = EVENT`, with the correlation key in `data`. A redelivered event writes
the same item again. The table's stream then drives the trigger.

```kotlin
CollectPipeline.builder()
    .id("collect")
    .eventFilter(EventFilters.onContent { it.eventType() in CONSUMED })
    .correlationKey { uow -> (uow.event as PaymentEvent).payment.id }
    .eventsMicrostore(eventsMicrostore)
    .build()
```

Items expire after `ttlDays` (33 by default). See [Events Microstore](EventsMicrostore.md).

## Correlate

Reads the microstore's stream and saves each collected event again as `pk = <correlation key>, sk = <event id>`, so
a rule can query everything heard for a payment, an order, a shipment.

With `correlationKeySuffix(...)`, `expire(true)` and a `ttl`, the item goes to a side partition and becomes a
deadline. See [Deadlines](Deadlines.md).

```kotlin
CorrelatePipeline.builder()
    .id("correlate")
    .eventsMicrostore(eventsMicrostore)
    .isCollectedEvent(DynamodbAdapter::forCollectedEvents)
    .normalizer(DynamodbAdapter.normalize(codec))
    .correlationKey { uow -> (uow.event as PaymentEvent).payment.id }
    .build()
```

## Evaluate

Reacts to items in the microstore, queries the events correlated under the same key (`uow.correlated`), and when
your `expression` holds, publishes what `emit` returns.

```kotlin
EvaluatePipeline.builder()
    .id("complete")
    .onCorrelated()                                   // judge what's correlated, once per event
    .eventFilter(EventFilters.name("FUNDS_DEPOSITED"))
    .expression { uow -> uow.correlated.asPaymentEvents().any { it is FundsWithdrawn } }
    .emit { uow -> listOf(PaymentCompleted(payment = (uow.event as PaymentEvent).payment)) }
    .oneEventPerCorrelationKey()                      // id p-7.complete: a repeat is the same event
    .eventsMicrostore(eventsMicrostore)
    .eventPublisher(context.eventPublisher)
    .eventCodec(codec)
    .build()
```

- `oneEventPerCorrelationKey()` or `emittedEventId { ... }` give emitted events deterministic ids, so a replay or a
  redelivery emits the same event and consumers drop the repeat.
- `onCorrelated()` reacts only to correlation items; `onExpired()` only to deadlines.

## Materialize

Keeps a read model up to date. Return an `UpdateItemRequest` (or `null` to skip) for each event:

```kotlin
MaterializePipeline.builder()
    .id("view")
    .context(context)
    .eventFilter(EventFilters.classes(PaymentEvent::class))
    .toUpdateRequest { uow -> viewUpdate(uow.event as PaymentEvent) }
    .build()
```

`MaterializeS3Pipeline` does the same for S3 objects, with `toPutRequest`, `toDeleteRequest` and `bucketName`.

## Update

For an event that affects many items: `toQueryRequest` finds them, `toGetRequest` loads them, and `toUpdateRequest`
builds each update.

## CDC

Change data capture: publishes events from a table's own changes, the transactional outbox. The row is the source
of truth; the event follows it, so the two can't disagree.

```kotlin
CdcPipeline.builder()
    .id("publish")
    .context(context)
    .eventPublisher(context.eventPublisher)
    .toEvent { uow -> toPaymentSubmitted(uow) }   // reads the row change; null publishes nothing
    .build()
```

`toQueryRequest` or `toBatchGetRequest` load related items first; `encryptionOptions(...)` encrypts fields before
publishing (see [Encryption](Encryption.md)).

## Translate

For an ingress gateway: the unit of work carries the outside system's record (a changed row, a webhook body, a line
of a file), not an event yet. `toEvents` returns the internal events it means, or none when the record isn't ours.

```kotlin
TranslatePipeline.builder()
    .id("ledger")
    .toEvents { uow -> ledgerEventsOf(uow.record) }
    .eventPublisher(context.eventPublisher)
    .build()
```

## Action

For an egress gateway: calls an outside system for each event and publishes nothing. The answer comes back through
the ingress partner. When the outside system refuses a request outright, no answer will ever come, so
`actionEmitting` returns the events that say so.

```kotlin
ActionPipeline.builder()
    .id("withdraw")
    .eventFilter(EventFilters.name("WITHDRAWAL_REQUESTED"))
    .action { uow -> coreChecking.withdraw((uow.event as PaymentEvent).payment) }
    .eventPublisher(context.eventPublisher)
    .build()
```

Pair it with `CircuitBreaker` (stop calling a system that is down, and let the stream hold the requests) and
`RateLimiter` (stay inside the system's calls-per-second budget across every container). Both keep their state in
DynamoDB (`DynamoDbBreakerStore`, `DynamoDbRateStore`) or in memory for tests.

## Errors, in every flavor

- **A non-retryable error** turns that unit of work into a fault; the rest of the batch carries on. See
  [Fault Re-submission](FaultResubmission.md).
- **A retryable error**, with `STREAM_RETRY_ENABLED=true`, fails the batch so the stream delivers it again. AWS
  throttling is retryable by default; add your own with `isRetryable` (see [Wiring](Wiring.md#custom-retry-rules)).

## Java

Builders that take functions have Java-friendly variants (`toEventJava`, `expressionJava`, `emitJava`, ...) that
accept `java.util.function` types. See the [Java guide](JavaDevelopers.md).
