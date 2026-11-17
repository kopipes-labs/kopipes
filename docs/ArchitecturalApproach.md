# Architectural Approach

kopipes follows the architecture in John Gilbert's *Software Architecture Patterns for Serverless Systems*. Its goal
is to let autonomous teams change a system quickly and with confidence, by limiting the *blast radius* of any
change or failure to the part that changed (Ch. 1). The book's way to do that fits in one sentence: **autonomous
services with fortified boundaries, connected only by events**.

## Event-first

Design around the **facts** a system produces, not the calls it makes. A service records what happened as a domain
event and doesn't decide what happens next; the services downstream decide how to react. New features become new
consumers, and existing services stay untouched (Ch. 1, Ch. 2). See [Events](Events.md).

## Autonomous services

A service is **autonomous** when it owns every resource it needs and talks to other services only through events, so
it keeps working while they are down. Inside a service, calls are synchronous: it reads and writes its own datastore.
Between services, everything is asynchronous, through the event hub.

A service is a handful of functions around its own datastore (Ch. 2 › *Dissecting an autonomous service*):

| Function | Does | kopipes |
|---|---|---|
| **Listener** | Consumes events from a channel into the service's datastore | Adapter + `CollectPipeline` or `MaterializePipeline` |
| **Trigger** | Reacts to the datastore's change stream: publishes events, runs rules | `DynamodbAdapter` + `CdcPipeline`, `CorrelatePipeline`, `EvaluatePipeline` |
| **Command / query** | The service's API, for its own frontend | Your handler, behind the `RestApi` construct |

Every service is one of three kinds: a **BFF** serves a human actor, an **external service gateway** serves an outside
system, and a **control service** holds the business process. See [Autonomous Service Patterns](AutonomousServicePatterns.md).

### Command, publish, consume, query

Two services share data without calling each other in four steps, which the book calls **CPCQ**:

1. **Command:** an actor changes data in service A, which writes its own datastore.
2. **Publish:** A's trigger publishes the change as a domain event.
3. **Consume:** service B's listener saves what it needs from the event in B's own datastore.
4. **Query:** B answers its users from its own copy, even while A is down.

## Fortified boundaries

The book defines a system's architecture by its **bulkheads**: the boundaries where a failure, a mistake or an
attack stops (Ch. 1 › *Fortified boundaries*). There are several layers:

| Bulkhead | Protects | How | kopipes |
|---|---|---|---|
| **Outbound** | A producer from its consumers | It publishes to a bus and forgets | `EventBridgePublisher`, the `EventHub` construct |
| **Inbound** | A consumer from its producers | It answers from its own materialized views | Materialize and collect pipelines |
| **Subsystem** | Subsystems from each other | Separate cloud accounts; only external events cross | `EventForwarding` |
| **External** | Your system from outside systems, and them from you | Gateways with circuit breakers and backpressure | `ActionPipeline`, `CircuitBreaker`, `RateLimiter` |
| **Regional** | The system from a region's outage | Multi-regional deployment and failover | [Regional Health Check](RegionalHealthCheck.md) |
| **Security** | The system from intruders | Defence in depth, down to encrypted fields | [Envelope Encryption](Encryption.md) |

## Systems and subsystems

![Autonomous System](images/AutonomousSystem.svg)

An **autonomous system** is made of **autonomous subsystems**, and each subsystem of **autonomous services**.
Services in a subsystem communicate through **internal domain events** on the subsystem's own event hub. Subsystems
communicate through **external domain events** (or APIs) with stable, versioned contracts, and each runs in its own
cloud accounts. A failure or a change inside one subsystem stays inside it.

The book suggests dividing a system along these lines (Ch. 2 › *Dividing a system into autonomous subsystems*):

- **By actor:** the people the system serves, such as customers, drivers and back office staff.
- **By business unit**, following the organization that owns the work.
- **By business capability**, what the business does. This is the most durable split.
- **By data life cycle**: the create, use, analyze and archive phases of the data.
- **By legacy system:** one subsystem per legacy system being integrated or replaced.

![Autonomous Subsystem](images/AutonomousSubsystem.svg)

Inside a subsystem, the shape repeats ports and adapters at a larger scale: **internal domain events are the
ports**, **gateways are the adapters**, and the BFFs and control services are the core.

## Ports and adapters at three levels

The book applies hexagonal architecture three times (Ch. 2, 6, 7):

| Level | Core | Adapters | In kopipes |
|---|---|---|---|
| **Function** | Your business rules | The Lambda handler, and connectors to AWS | Your rule functions; adapters, sinks and connectors; the container that wires them |
| **Service** | The service's datastore | Listener and trigger (events), command and query (frontend) | The listener and trigger containers |
| **Subsystem** | BFFs and control services | Ingress and egress gateways | `TranslatePipeline` and `ActionPipeline` in gateways |

Keeping rules in plain functions, with AWS behind adapters and connectors, is what lets kopipes run a whole service
in a unit test with in-memory fakes (see [Wiring](Wiring.md#tests)).

## The role of the architect

The architect draws the boundaries: which subsystems exist, which services each one has, and which events cross
between them. Each subsystem and service needs a clear purpose, a clear interface (the events it consumes and
produces) and a clear scope.

## Further reading

- [The book's patterns in kopipes](BookPatterns.md): every pattern, mapped to the framework
- [SUT example architecture](../examples/sut/architecture.md)
- [Payments example](../examples/payments/README.md), with system and service specs
