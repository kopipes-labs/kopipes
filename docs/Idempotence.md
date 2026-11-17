# Idempotence and Ordering

Streams deliver **at least once** and **not reliably in order**. A Lambda retry, a replay from the event lake, a
resubmitted fault or a regional failover can all hand a function an event it has seen before, or an older event after
a newer one. Every consumer has to give the same result anyway.

*Software Architecture Patterns for Serverless Systems* (Ch. 4 and Ch. 5, *Implementing idempotence and order
tolerance*; Ch. 7 for gateways) gives a toolkit for it. This page maps each technique to kopipes.

## Where duplicates and disorder come from

| Source | What happens |
|---|---|
| Lambda retries a batch | Records before the failing one are processed again |
| Stream retry (`STREAM_RETRY_ENABLED`) | The whole batch is delivered again |
| [Replay](ReplayEvents.md) | Events from hours or days ago arrive again |
| [Fault resubmission](FaultResubmission.md) | An old event arrives after the newer ones that followed it |
| Publishing retries | The bus may receive the same event twice |
| Several channels or regions | Two events about one entity can arrive in either order |

## The toolkit

### Deterministic identifiers

Give everything an id that is the same every time it is produced, so a repeat writes the same item or is recognized
as a repeat.

- **Events** carry an `id`. Consumers key on it: a collect pipeline saves `pk = <event id>`, so a redelivered event
  writes the same item again.
- **Emitted events** get deterministic ids from `EvaluatePipeline.oneEventPerCorrelationKey()` (`<key>.<pipeline
  id>`) or `emittedEventId { ... }`. A rule that fires twice emits the same event twice, and consumers drop the repeat.
  Without them, the id comes from the stream record, which a replay changes.
- **Entities** should get their ids from the client or the command (a UUID chosen up front, a `PUT` rather than a
  `POST`), never from a counter that a retry would advance.

### Immutable event triggers

Insert events into a store keyed by event id, and react to the store's stream rather than to the incoming event.
The insert happens once, so the trigger fires once, and the rule can wait until every event it needs has arrived.

This is the control service: `CollectPipeline` writes, the table's stream drives `CorrelatePipeline` and
`EvaluatePipeline`. See [Events Microstore](EventsMicrostore.md).

### Inverse optimistic locking

Drop an update older than what is stored. A conditional write keeps the newest event's timestamp and rejects
anything older:

```kotlin
val condition = timestampCondition()   // attribute_not_exists(#timestamp) OR #timestamp < :timestamp

UpdateItemRequest {
    key = keyOf(view)
    updateExpression = expression.updateExpression
    expressionAttributeNames = expression.expressionAttributeNames
    expressionAttributeValues = expression.expressionAttributeValues   // includes :timestamp = event.timestamp
    conditionExpression = condition["ConditionExpression"]
}
```

The DynamoDB connector treats a failed condition as "nothing to do", not as an error, so a stale event is dropped
quietly. When several event types update different fields of one view, keep a timestamp per event type, so a newer
event of one type doesn't block an older event of another. `pkCondition()` (`attribute_not_exists(pk)`) is the
insert-once variant.

### Collate before you decide

`uow.correlated` holds the events heard for a key, in key order, not time order. When a rule depends on order,
sort by `timestamp` first, and judge by what is present rather than by what arrived last:

```kotlin
.expression { uow ->
    val events = uow.correlated.asPaymentEvents().sortedBy { it.timestamp }
    events.any { it is FundsWithdrawn } && events.none { it is WithdrawalFailed }
}
```

### Soft deletes

A hard delete loses the record, and a late update can bring it back to life. Mark it deleted instead
(`deleted = true`), let a TTL remove it later, and publish the deletion like any other change.
`TableChangeEvent.isDeleted()` reads the flag in a trigger.

### Latching

In two-way synchronization (two systems, or two versions of one), a listener's write must not be published back,
or the systems echo each other forever. A listener sets `latched = true` on the rows it writes; `CdcPipeline` skips
latched rows. A user's own command clears the latch, so real changes are still published.

### Event time, not processing time

Base TTLs, deadlines and ordering on the event's `timestamp`, never on the time the function ran. A replayed or
resubmitted event is then judged as of when it happened. Collect and correlate pipelines compute their default
TTL from the event's timestamp, and a deadline's `ttl` should be too (see [Deadlines](Deadlines.md)).

## Watch the window

A microstore item is how a control service recognizes a repeat. Once its TTL removes it (33 days by default), a
replay of that event looks new. Keep `ttlDays` longer than your longest replay or resubmission window.

## Side effects outside your store

Calls to outside systems need the same care. An egress gateway can be invoked twice for one event, so either the
outside system accepts an idempotency key (send the event id), or the gateway records what it has done before
calling, as the payments example's `CalledOnce` does for core checking.
