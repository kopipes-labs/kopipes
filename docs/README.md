# kopipes documentation

Start with [How kopipes works](Concepts.md), then follow the path for your role.

## Start here

| Page | For |
|---|---|
| [How kopipes works](Concepts.md) | Everyone: the flow of a batch, the building blocks, a glossary |
| [Features](Features.md) | Evaluating the framework |
| [Installation](../README.md#installation) | Adding it to a build |

## Guides

| Page | For |
|---|---|
| [Software Architects Guide](SoftwareArchitects.md) | Benefits, how a system is shaped, trade-offs, when not to use it |
| [The book's patterns in kopipes](BookPatterns.md) | Every pattern from the book, mapped to what the framework provides |
| [Kotlin Developers Guide](KotlinDevelopers.md) | A first Kotlin function, with its test |
| [Java Developers Guide](JavaDevelopers.md) | A first Java function, with its test |

## Building services

| Page | What it covers |
|---|---|
| [Pipelines](Pipelines.md) | Every pipeline flavor, with samples |
| [Wiring a kopipes Lambda](Wiring.md) | The context, containers, tests, configuration, Spring Boot |
| [Events](Events.md) | Domain, internal, external and change events |
| [Implementing Events (Kotlin)](EventImplementationKotlin.md) / [(Java)](EventImplementationJava.md) | The `Event` interface and codecs |
| [Idempotence and Ordering](Idempotence.md) | Duplicates and out-of-order events, and the tools to handle them |
| [Deadlines](Deadlines.md) | Reacting when something *didn't* happen, with DynamoDB TTL |
| [Envelope Encryption](Encryption.md) | Field-level encryption with KMS |
| [Claim Check](ClaimCheck.md) | Large payloads through S3 |
| [Coroutines and Flow](KotlinCoRoutinesAndFlow.md) | Why the framework is built on them |
| [spring module](../spring/README.md) | Spring Boot auto-configuration and handlers |
| [cdk module](../cdk/README.md) | CDK constructs for hubs, lakes, fault monitors, tables and functions |

## Architecture and patterns

| Page | What it covers |
|---|---|
| [Architectural Approach](ArchitecturalApproach.md) | Systems, subsystems and autonomous services |
| [Autonomous Service Patterns](AutonomousServicePatterns.md) | Control services, event hubs, gateways, BFFs and more |
| [Events Microstore](EventsMicrostore.md) | A service's own events table: collect, correlate, evaluate |
| [Event Lake](EventLake.md) | Archiving every event in S3 |
| [Regional Health Check](RegionalHealthCheck.md) | One health signal per regional stack |

## Operations

| Page | What it covers |
|---|---|
| [Fault Re-submission](FaultResubmission.md) | Faults, the fault monitor, and the `resubmit-events` tool |
| [Replaying Events](ReplayEvents.md) | Sending archived events to a function with `replay-events` |
| [Observability](Observability.md) | Metrics kopipes emits, and what to alert on |

## Examples

| Example | Shows |
|---|---|
| [Payments](../examples/payments/README.md) | A complete subsystem in Kotlin. Start here |
| [SUT](../examples/sut) | A smaller Kotlin system, and the regional health check |
| [URL Shortener](../examples/urlshortener) | The framework and CDK constructs from Java |
