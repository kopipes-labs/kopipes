# Events

Events are how services and subsystems communicate. Each one records a **fact**: something an actor did, in the
past tense (`PAYMENT_SUBMITTED`, `FUNDS_WITHDRAWN`). The service that produced it doesn't know or care who reacts;
downstream services decide that for themselves. This is what the book calls *inverting responsibility*, and it is
why a system can grow by adding consumers without touching producers (*Software Architecture Patterns for
Serverless Systems*, Ch. 2 › *Thinking about events first*).

## Table of Contents

- [The envelope](#the-envelope)
- [Carry the state](#carry-the-state)
- [Kinds of events](#kinds-of-events)
- [Evolving events](#evolving-events)
- [Implementing events](#implementing-events)

## The envelope

Every event has the same envelope, so the bus can route it, the lake can store it and any consumer can filter it
without knowing its type (Ch. 4 › *Event envelope*). kopipes's `Event` interface is that envelope:

| Field | Meaning |
|---|---|
| `id` | A unique, **deterministic** id: the same fact always gets the same id, so consumers can drop repeats (see [Idempotence](Idempotence.md)) |
| `type` | What happened: an entity and an action. External events get a namespace prefix (`PAYMENTS.PAYMENT_COMPLETED`) |
| `timestamp` | When the actor acted, in epoch milliseconds. Use it, not the processing time, for ordering, TTLs and deadlines |
| `partitionKey` | Usually the entity id, so a stream keeps one entity's events together and in order on one shard |
| `tags` | Standard metadata, set by kopipes: account, region, stage, source, function, pipeline |
| *your payload* | The state of the entity when it happened. See below |
| `raw` | What the source sent, in its own format: a full audit trail even for data you don't map yet. No compatibility promise |
| `eem` | Envelope encryption metadata, when fields are encrypted. See [Encryption](Encryption.md) |
| `triggers` | References to the events that caused this one, for tracing a chain of reactions |

```json
{
  "id": "p-7.submitted",
  "type": "PAYMENT_SUBMITTED",
  "timestamp": 1730900520000,
  "partitionKey": "p-7",
  "tags": { "source": "payments-bff", "functionname": "payments-bff-dev-trigger", "region": "us-east-1" },
  "payment": { "id": "p-7", "customerId": "u-42", "amount": "100.00", "currency": "USD" }
}
```

## Carry the state

An event carries a **snapshot** of the entity as it was when the fact happened, not just its id (the book's
*event-carried state transfer*, Ch. 4). A notification that says only "payment p-7 changed" forces every consumer to
call back for the data: that couples them to the producer's availability, and the data may have changed by the time
they ask. With the state in the event, consumers build their own views and keep working when the producer is down,
and the [event lake](EventLake.md) holds a true history: what the entity looked like at each moment.

Carry what the producer knows and owns. A consumer that needs data from several producers merges their events in its
own view.

## Kinds of events

### Domain events

Business facts, named for what happened. They are the contract between producers and consumers, so their payload
follows one canonical shape per entity: any producer can emit them, and a consumer can't tell (or care) which one did.

- **Internal domain events** stay inside one subsystem. They can change more freely, because every consumer is in the
  same subsystem and evolves with it.
- **External domain events** cross subsystem boundaries, through egress and ingress gateways. They are a public
  contract, with strong backward-compatibility guarantees. Gateways translate between the two, so a subsystem's
  internals can change without breaking its neighbours (Ch. 7).

### Change events

Technical notifications that a row changed, straight from a table's stream. A BFF's trigger turns them into domain
events with a `CdcPipeline`; the change events themselves stay internal. In kopipes they arrive as a
`TableChangeEvent`, with the row's old and new images.

### Higher-order events

Events a control service emits after looking at others: `PAYMENT_COMPLETED` once both the withdrawal and the
deposit are in. Their `triggers` point back at the events that led to them.

### Fault events and abort events

Two ways a step can fail, which the book keeps apart (Ch. 4, Ch. 8 › *Saga*):

| | Fault event | Abort event |
|---|---|---|
| Means | Something unexpected broke: bad data, a bug | The business can't go forward: insufficient funds, a refused card |
| Who handles it | An operator: fix the cause, then resubmit | The process: a control service compensates the steps already done |
| In kopipes | `FaultEvent`, published by the `FaultManager`. See [Fault Re-submission](FaultResubmission.md) | A domain event you define (`WITHDRAWAL_FAILED`), handled by evaluate rules |

## Evolving events

Events are contracts, and nothing stops the system while one changes. The book applies the **Robustness
principle**, *be conservative in what you send, be liberal in what you accept*, as a sequence of backward-compatible
deployments (Ch. 11 › *Achieving zero-downtime deployments*). To rename or reshape a field:

1. **The producer emits both** the old and the new field.
2. **Every consumer accepts both**, preferring the new one.
3. **The producer drops the old field**, once every consumer is deployed.

Adding a field needs no ceremony: kopipes's default JSON configuration ignores unknown fields, so consumers that don't
know it yet carry on. Removing or renaming one always takes the three steps. For external events, give consumers in
other subsystems time between steps, or publish a new event type.

## Implementing events

For how to implement events and event codecs, see:

* [Implementing Events (Kotlin)](EventImplementationKotlin.md)
* [Implementing Events (Java)](EventImplementationJava.md)
