# The Book's Patterns in kopipes

kopipes implements the patterns in John Gilbert's *Software Architecture Patterns for Serverless Systems* (2nd ed.,
Packt). This page maps each pattern to what the framework gives you, and says plainly where a pattern is yours to
apply. Chapter references point to the book for the full argument.

## The idea everything rests on

**Autonomous services with fortified boundaries, connected only by events.** Each service owns the data it needs and
talks to other services only through events on a hub, so it keeps working when they are down. The book calls the
resulting data flow **CPCQ**: a service handles a *command* against its own store and *publishes* a fact; downstream
services *consume* it into their own views and answer *queries* from them. No service ever calls another to read its
data (Ch. 1, Ch. 2).

Two bulkheads make that work, and kopipes supplies the plumbing for both:

| Bulkhead | Protects | kopipes |
|---|---|---|
| **Outbound**: event sourcing through the hub | A producer from its consumers' outages: it publishes and forgets | `EventBridgePublisher`, `CdcPipeline`, the `EventHub` construct |
| **Inbound**: materialized views (CQRS) | A consumer from its producers' outages: it answers from its own copy | `MaterializePipeline`, `CollectPipeline`, the events microstore |

## Pattern by pattern

### Structure

| Pattern | What it says | In kopipes |
|---|---|---|
| **Autonomous service** (Ch. 1, 2) | A service is a repository, a stack and a datastore, with a few functions: a *listener* (consumes events), a *trigger* (publishes from its own changes), *command/query* (its API) | A container per function, wired from a context ([Wiring](Wiring.md)); `KopipesFunction` with `listenTo()`, `triggeredBy()`, `uses()` ([cdk](../cdk/README.md)) |
| **Autonomous subsystem** (Ch. 2) | Group services into subsystems, each with its own hub and cloud account; they share only external events | One `EventHub` per subsystem; `EventForwarding` for external events. See [Architectural Approach](ArchitecturalApproach.md) |
| **Hexagonal architecture at three levels** (Ch. 2, 6, 7) | Ports and adapters in each function, each service and each subsystem | Adapters and connectors around your rules; listener/trigger around the service's table; gateways around the subsystem's core |

### Events and data

| Pattern | What it says | In kopipes |
|---|---|---|
| **Event hub** (Ch. 4) | Producers know only the bus; rules route events to channels (streams), which consumers read. Start with one channel | `EventHub` construct: bus, channel `s1`, archive, logs. See [Event Hub](AutonomousServicePatterns.md#event-hubs) |
| **Event-first** (Ch. 1, 2) | Upstream services record facts and don't care who reacts; the system grows by adding consumers | The whole model: publishers never name their consumers |
| **System-wide event sourcing** (Ch. 4, 5) | Each service keeps only current state and publishes each change as a fact. *Database-first*: write, then publish from the change stream. *Stream-first*: publish, then everyone (yourself included) saves | Database-first: `CdcPipeline` on the table's stream. Stream-first: `TranslatePipeline` and `EvaluatePipeline` publish, and `CollectPipeline` saves |
| **Event lake** (Ch. 4) | Keep every event forever, as the system's audit log and the source of replays. Replay to one listener, never back onto the bus | `EventLake` construct; `replay-events` tool. See [Event Lake](EventLake.md), [Replaying Events](ReplayEvents.md) |
| **CQRS / materialized views** (Ch. 5) | Consumers keep lean, prejoined views of upstream data, updated by events | `MaterializePipeline`, `MaterializeS3Pipeline`, `UpdatePipeline` |
| **Idempotence and order tolerance** (Ch. 4, 5, 7) | Delivery is at least once and not reliably in order; every consumer must cope | Event ids, deterministic emitted ids, `timestampCondition()`, latching, soft deletes. See [Idempotence and Ordering](Idempotence.md) |
| **Stream processing pipelines** (Ch. 4) | Pull-based pipelines inside a function: normalize records into a unit of work, filter, map, call connectors; several pipelines share one channel | The core of kopipes: adapters, `UnitOfWork`, flavors, `PipelineAssembler`. See [Pipelines](Pipelines.md) |
| **Fault events** (Ch. 4, 12) | Catch the error, publish a fault event with the unit of work, and keep going: the hub is the dead-letter queue | `FaultManager`, `FaultEvent`, the `FaultMonitor` construct, `resubmit-events`. See [Fault Re-submission](FaultResubmission.md) |
| **Single table design** (Ch. 5) | Store an aggregate's records together under one `pk`, with `sk`, `discriminator` and `data` for other access paths | The events microstore layout and `EventsTable` construct. See [Events Microstore](EventsMicrostore.md) |

### Services

| Pattern | What it says | In kopipes |
|---|---|---|
| **Backend for frontend** (Ch. 6) | One service per user activity, owning its data, with a command/query API, an optional listener (views) and an optional trigger (publishes changes) | `MaterializePipeline` (listener), `CdcPipeline` (trigger), `RestApi` construct |
| **External service gateway** (Ch. 7) | The anti-corruption layer: ingress gateways turn outside data into internal events, egress gateways turn events into outside calls | `TranslatePipeline` (ingress), `ActionPipeline` (egress), `ResubmittedRecords` for webhooks |
| **Control service** (Ch. 8) | A purely asynchronous core service: collect, correlate, collate, evaluate, emit, expire | `CollectPipeline`, `CorrelatePipeline`, `EvaluatePipeline`, [Deadlines](Deadlines.md). Collate is yours: sort `uow.correlated` by timestamp in the rule |

### Collaboration

| Pattern | What it says | In kopipes |
|---|---|---|
| **Choreography vs orchestration** (Ch. 8) | Let services react to each other for simple flows; move real process logic into a control service that emits *entry* events and waits for *exit* events | `EvaluatePipeline` rules emit entry events; correlation sees the exits |
| **Saga** (Ch. 8) | When a step can't go forward, it emits an *abort* event (expected, unlike a fault), and the control service asks only the completed steps to compensate | Rules on abort events emit compensations. The payments example refunds a withdrawal when the deposit fails |
| **ACID 2.0 snapshots** (Ch. 8) | A control service reduces correlated events into a snapshot and emits it, so nobody recomputes state | Write it as an evaluate rule over `uow.correlated` |
| **Complex event processing** (Ch. 8) | Rules across several events, including events that never arrived, using timers | Correlate plus evaluate; timers are [Deadlines](Deadlines.md) |

### Integration

| Pattern | What it says | In kopipes |
|---|---|---|
| **Third-party flows** (Ch. 7) | Egress: call the provider, rate-limited, and wait for its webhook rather than announcing success. Ingress: webhook → translate → publish | `ActionPipeline` with `RateLimiter`; `TranslatePipeline` behind the `RestApi` construct |
| **Subsystem to subsystem** (Ch. 7) | Egress gateways translate internal events to external ones, and a rule forwards them to the other subsystem's bus | `EventForwarding` construct |
| **Legacy flows** (Ch. 7) | Ingress from the legacy database's change log; egress by calling or writing to it, behind a circuit breaker | `TranslatePipeline` over CDC; `ActionPipeline` with `CircuitBreaker` |
| **Circuit breaker** (Ch. 7, 9) | Only in egress gateways, never between your own services. While open, events wait in the stream | `CircuitBreaker` with `DynamoDbBreakerStore`: callers hold requests by letting the stream retry |
| **Latching** (Ch. 5, 7) | In two-way synchronization, mark records written by a listener so the trigger doesn't publish them back | `CdcPipeline` skips rows with `latched = true` |

### Resilience, security and operations

| Pattern | What it says | In kopipes |
|---|---|---|
| **Multi-regional deployment** (Ch. 9) | Run every subsystem in two or more regions; replicate change events at the datastore, not domain events at the bus | Region tags on every event and microstore item. The topology, routing and replication are yours |
| **Regional health check** (Ch. 9) | One signal per region, from error alarms and a tracer that crosses every service the region uses | [Regional Health Check](RegionalHealthCheck.md), built in the SUT example |
| **Envelope encryption** (Ch. 10) | Encrypt sensitive fields with a data key per event, so neither the bus nor the lake holds them in the clear | `EncryptionOptions`, `eem`, `CdcPipeline.encryptionOptions()`, `decrypt()`. See [Encryption](Encryption.md) |
| **Observability** (Ch. 12) | Resource metrics explain causes, work metrics show symptoms, fault events tell the story; alert on symptoms | EMF pipeline metrics and fault events. See [Observability](Observability.md) |
| **Robustness principle** (Ch. 2, 11) | Be conservative in what you send and liberal in what you accept; change contracts in backward-compatible steps | Codecs that ignore unknown fields. See [Evolving events](Events.md#evolving-events) |

## What kopipes leaves to you

The book covers the whole delivery of a system. These parts are outside the framework:

- **The frontend:** micro frontends, offline-first, live updates (Ch. 3).
- **Security in depth** beyond data: accounts, CI/CD roles, the perimeter, JWT checks in BFFs (Ch. 10).
- **Delivery practices:** feature flags, task branches, regional canaries, the testing honeycomb (Ch. 11).
- **Multi-regional routing and replication** (Ch. 9).
- **Organization and migration:** funding products, the strangler pattern (Ch. 13).

## Anti-patterns the framework steers you away from

| Anti-pattern | What kopipes does instead |
|---|---|
| Services calling each other synchronously (the "death star") | Services publish to a hub and read from their own stores |
| Anemic events that force a callback to fetch data | Events carry their state; see [Events](Events.md#carry-the-state) |
| Events treated as throwaway messages | The event lake keeps them all |
| A poison record retrying forever and blocking its batch | It becomes a fault; the batch moves on |
| Replaying by republishing to the bus | `replay-events` invokes one function |
| Hard deletes that late events can resurrect | `TableChangeEvent.isDeleted()` for soft deletes, TTL for clean-up |
| Replicating domain events across regions | Microstore items carry their region; replicate the table |
