# Deadlines

A control service often has to act when something *didn't* happen: a step that got no answer by its deadline. kopipes
builds deadlines from two pieces it already has, the events table and DynamoDB's TTL. No timers, no schedulers.

It is the *expire* step of the book's control service, and the timer behind its complex event processing: a rule can't
fire on an event that never arrived, so a timer fires instead and the rule checks what is missing (*Software
Architecture Patterns for Serverless Systems*, Ch. 8).

## How it works

1. **Start the clock.** A `CorrelatePipeline` saves the event that starts a step again, in a *side partition*
   `<key>.<suffix>`, with `expire(true)` and a `ttl` at the deadline:

   ```kotlin
   CorrelatePipeline.builder()
       .id("deposit-deadline")
       .eventsMicrostore(eventsMicrostore)
       .isCollectedEvent(DynamodbAdapter::forCollectedEvents)
       .normalizer(DynamodbAdapter.normalize(codec))
       .eventFilter(EventFilters.name("FUNDS_WITHDRAWN"))      // the deposit starts now
       .correlationKey { uow -> (uow.event as PaymentEvent).payment.id }
       .correlationKeySuffix("deposit-deadline")              // pk = p-7.deposit-deadline
       .expire(true)
       .ttl { uow -> uow.event!!.timestamp!! / 1000 + 3600 }  // epoch seconds: in an hour
       .build()
   ```

2. **TTL deletes the item** once its `ttl` has passed. The deletion comes down the table's stream as a `REMOVE`, and
   `DynamodbAdapter` marks a removed item that had `expire = true` as expired (`TableChangeEvent.isExpired()`). Its
   getters then read the item as it was.

3. **The expiry rule runs.** An `EvaluatePipeline` built with `onExpired()` and the same suffix reacts only to that:

   ```kotlin
   EvaluatePipeline.builder()
       .id("expired-deposit")
       .onExpired()
       .correlationKeySuffix("deposit-deadline")
       .eventFilter(EventFilters.name("FUNDS_WITHDRAWN"))
       .expression { uow -> /* uow.correlated: neither FUNDS_DEPOSITED nor DEPOSIT_FAILED */ }
       .emit { uow -> listOf(PaymentExpired(...)) }
       .oneEventPerCorrelationKey()                           // id p-7.expired-deposit
       // eventPublisher, eventsMicrostore, eventCodec ...
       .build()
   ```

   It queries the events correlated under the key **without** the suffix (`p-7`, everything heard for the payment),
   so the rule can see whether the step was answered. The emitted event is dated at the deadline, not at the time
   TTL got round to it.

The rules that judge the main partition (no suffix) ignore the side partition, both its inserts and its expiries, so
starting a clock never triggers them.

## What to expect

- **Never early, sometimes late.** TTL deletes an item after its `ttl`, usually within minutes, but AWS allows itself up
  to a couple of days. A deadline is a lower bound. That suits "this is taking too long", not "at 9:00 exactly".
- **A deadline is not an outcome.** The other rules keep running after it fires; a late answer is handled as usual.
- **One per step.** Each step has its own suffix, so its own deadline item and its own expiry rule.

## Locally

LocalStack's TTL sweep is slow and unreliable, so tests delete the expired item themselves, as TTL would, and the
service reacts the same way. In process, `EventsTableSimulator.expire { ... }` does it; against LocalStack, a
`DeleteItem` of the deadline item does. See `examples/payments`.
