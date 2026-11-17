# Software Architects Guide

kopipes (AWS Lambda Stream for the JVM) is a framework and a set of patterns for serverless, event-driven systems on
AWS. This guide covers what it gives an architecture, how a system built with it is shaped, what it costs, and when
it is the wrong choice.

## The problem it solves

Event-driven systems are easy to start and hard to keep reliable. Without a shared foundation, each team solves the
same problems its own way: what to do with a record that fails, how to retry without duplicating, how to recover
after a bug, how one service learns what another did without calling it. The result is services that fail
together, recovery done by hand, and no two services that look alike.

kopipes makes those decisions once, in code, and every service built with it gets them.

## What it gives your architecture

| Benefit | How |
|---|---|
| **Services fail alone** | Services talk only through events on a hub. Each keeps what it needs in its own [events microstore](EventsMicrostore.md) or read model, so it keeps working when the services around it are down |
| **No lost or stuck records** | A record that fails becomes a [fault event](FaultResubmission.md) with a full snapshot; the stream moves on. After the fix, the record is resubmitted to the function that failed it |
| **Safe retries and replays** | Events carry ids, rules can emit events with deterministic ids, and writes are idempotent. Retrying, redelivering or [replaying](ReplayEvents.md) a window of history doesn't duplicate effects |
| **Act only on recorded facts** | A control service writes what it heard first and acts from the table's stream (outbox), so its state and the events it emits can't disagree |
| **Timeouts as data** | [Deadlines](Deadlines.md) come from DynamoDB TTL: no schedulers, no timers to keep alive |
| **Clean boundaries with outside systems** | Ingress and egress gateways translate between internal events and outside APIs, with a circuit breaker and a rate limiter in front of fragile systems |
| **Audit and recovery from history** | An [event lake](EventLake.md) keeps every event in S3 |
| **Sensitive and large data** | Field-level [envelope encryption](Encryption.md) with KMS; [claim checks](ClaimCheck.md) for payloads too big for the bus |
| **Consistency across teams** | Every service has the same shape, the same faults, metrics and tags, and the same CDK constructs, so anyone can read anyone's service |
| **Testability** | No global state: a whole service runs in a unit test with in-memory fakes, and a whole subsystem on LocalStack |

## How a system is shaped

An *autonomous system* is split into *subsystems*, each with its own event hub. Inside a subsystem, *autonomous
services* communicate only through internal domain events; subsystems communicate through external events or APIs
with stable contracts. See [Architectural Approach](ArchitecturalApproach.md).

Each service follows one of a few patterns ([Autonomous Service Patterns](AutonomousServicePatterns.md)), and each
pattern maps to a few [pipeline flavors](Pipelines.md):

| Pattern | Role | Built from |
|---|---|---|
| Event hub | EventBridge bus plus Kinesis channels | `EventHub` construct |
| Control service | Runs a business process: listens, correlates, decides, emits | Collect, Correlate, Evaluate pipelines; an events table |
| BFF | Serves an API from its own read model | Materialize and CDC pipelines |
| Ingress gateway | Outside system → internal events | Translate pipeline |
| Egress gateway | Internal events → outside system | Action pipeline, circuit breaker, rate limiter |
| Event lake | Archive of every event | `EventLake` construct |
| Fault monitor | Stores faults for resubmission | `FaultMonitor` construct |
| Regional health check | One health signal per region | [Regional Health Check](RegionalHealthCheck.md) |

The [payments example](../examples/payments/README.md) builds a whole subsystem this way, with written
[system](../examples/payments/PaymentsSystemSpecs.md) and [service](../examples/payments/PaymentsServiceSpecs.md)
specs you can use as templates.

## Trade-offs

Know these before you commit:

- **Eventual consistency.** A read model or a decision lags the event that caused it by the time it takes to flow
  through a stream (usually well under a second, longer under load or after a failure). APIs must be designed for
  it: acknowledge, then let the client follow the outcome.
- **More moving parts per service.** A control service is two functions, a table and its stream, not one endpoint.
  The CDK constructs and the shared patterns keep it manageable, but there is more to deploy and observe.
- **Data is duplicated on purpose.** Each service keeps its own copy of what it needs. It costs storage and needs
  TTLs and contracts; it buys independence.
- **Deadlines are lower bounds.** DynamoDB TTL deletes an item after its deadline, usually within minutes but
  sometimes much later. Good for "this is taking too long", not for "at 9:00 exactly".
- **Ordering is per key, not global.** Kinesis keeps one partition key's events in order; nothing orders events
  across keys. Rules should not depend on it.
- **Idempotency is a design duty.** The framework makes it easy (ids, deterministic emitted ids, idempotent writes),
  but your own side effects, such as calls to outside systems, must be idempotent too.
- **The JVM on Lambda.** Cold starts are slower than Node.js or Python. Stream consumers barely notice; for
  latency-sensitive APIs, size memory generously and consider SnapStart or provisioned concurrency.
- **AWS-specific.** The `aws` module, the patterns and the constructs assume EventBridge, Kinesis, DynamoDB, S3 and
  Lambda. `core` is cloud-neutral, but a move off AWS means new adapters and sinks.
- **Young framework.** It is a work in progress, not yet published to a repository. Expect API changes.

## When not to use it

- A simple CRUD application or a single synchronous API, with no cross-service workflows.
- Workloads that need strong consistency across services, or global ordering.
- Long-running or CPU-heavy jobs that don't fit Lambda's limits.
- A team that doesn't want to run event-driven systems: the patterns pay off only if services really are autonomous.

## Relation to aws-lambda-stream

kopipes follows John Gilbert's [book](https://a.co/d/0cgkIieB) and uses his TypeScript
[aws-lambda-stream](https://github.com/jgilbert01/aws-lambda-stream) as its reference: the patterns, the pipeline
flavors, the event envelope, the fault events and the microstore layout are modeled on it, so its documentation and
the book apply. The differences are those of the platform: typed events, Kotlin coroutines and `Flow` instead of
Highland.js streams, constructor wiring instead of module-level state, and Java interoperability.

## Further reading

* [The book's patterns in kopipes](BookPatterns.md): every pattern, mapped to the framework
* [How kopipes works](Concepts.md): the flow of a batch, and a glossary
* [Features](Features.md)
* [Architectural Approach](ArchitecturalApproach.md)
* [Autonomous Service Patterns](AutonomousServicePatterns.md)
* [Events](Events.md)
* [Events Microstore](EventsMicrostore.md)
* [Idempotence and Ordering](Idempotence.md)
* [Deadlines](Deadlines.md)
* [Fault Re-submission](FaultResubmission.md) and [Replaying Events](ReplayEvents.md)
* [Event Lake](EventLake.md)
* [Regional Health Check](RegionalHealthCheck.md)
* [Observability](Observability.md)
* [Envelope Encryption](Encryption.md)
* [Claim Check](ClaimCheck.md)
* [CDK constructs](../cdk/README.md)
