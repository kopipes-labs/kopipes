# kopipes CDK constructs

CDK constructs for the infrastructure every kopipes subsystem repeats: the event hub, the event lake, the fault
monitor, events tables, and the Lambda functions that read and write them. Each construct is named after the
pattern it builds (see [Autonomous Service Patterns](../docs/AutonomousServicePatterns.md)), so a stack reads like
the service's spec.

The module depends only on the CDK, not on `core` or `aws`: a stack's build doesn't pull in the runtime.

## A service's stack

```kotlin
class ControlServiceStack(scope: Construct, props: ServiceProps) : KopipesStack(scope, props) {
    init {
        val jar = Code.fromAsset("../app/build/libs/payments-control-service.jar")
        val events = EventsTable(this, "Events")

        // Kinesis default channel -> listener -> events table
        KopipesFunction(this, "listener", KopipesFunctionProps(jar, "org.myorg.payments.Listener::handleRequest"))
            .listenTo()
            .uses(events)

        // events table stream -> trigger -> bus
        KopipesFunction(this, "trigger", KopipesFunctionProps(jar, "org.myorg.payments.Trigger::handleRequest"))
            .triggeredBy(events)
            .uses(events)
    }
}
```

`cdk deploy payments-control-service-local` deploys it. The stack's id is `<service>-<stage>`.

## Constructs

| Construct | Builds | Notes |
|---|---|---|
| `KopipesStack` | The base stack | `name(suffix)` gives `<service>-<stage>-<suffix>`; `globalName()` gives `<org>-<service>-<stage>-<region>` for buckets. `importBus()` and `importChannel()` find the subsystem's hub by name |
| `KopipesFunction` | A Java 21 Lambda function running a kopipes handler | Sets `STAGE`, `SERVICE` and, when it publishes, `BUS_NAME` and `BUS_SRC`, and grants `events:PutEvents`. Then `listenTo(channel)`, `triggeredBy(table)` (any table with a stream), `receives(queue)`, `uses(table)`, `retryStream()` |
| `EventsTable` | A `pk`/`sk` table with `ttl`, a stream and the `gsi1` index | For an events microstore or a BFF's views |
| `EventHub` | The bus, the default Kinesis channel `s1`, the archive and log groups | Deploy it first: everything else finds it by name |
| `EventLake` | Bus → Firehose → S3, everything except faults | Gzipped, under `<region>/`, read by the replay tool |
| `FaultMonitor` | Bus → Firehose (with a transform) → S3, faults only; a FIFO SNS topic | The transform is your function; `verificationQueue` subscribes a queue for integration tests |
| `EventForwarding` | The hub's egress rule: external events with a type prefix → another subsystem's bus | Owned by the egress gateway that publishes them |
| `RestApi` | API Gateway proxying every request to one function | For a BFF's API or an ingress gateway's webhook |

## From Java

The constructs work from Java too; props and wiring methods with defaults have `@JvmOverloads`, and the
[urlshortener](../examples/urlshortener) example's stacks are written that way:

```java
EventsTable events = new EventsTable(this, "Events");
new KopipesFunction(this, "listener", new KopipesFunctionProps(jar, "org.myorg.urls.ControlListenerHandler::handleRequest"))
        .listenTo()
        .uses(events);
```

## Names

Services find each other's resources by convention, not by passing references between stacks:

| Resource | Name |
|---|---|
| Stack | `<service>-<stage>` |
| Bus | `<subsys>-event-hub-<stage>-bus` |
| Default channel | `<subsys>-event-hub-<stage>-s1` |
| Functions, tables, rules | `<service>-<stage>-<suffix>` |
| Buckets | `<org>-<service>-<stage>-<region>` |

## Env vars

`EnvVars` holds the names the runtime's `AwsEnvironmentConfig` reads, and `EnvVarsTest` checks them against it. A
construct that wires a resource sets its env var too, so a function and its infrastructure can't disagree:
`uses(table)` sets `EVENT_TABLE_NAME` (or `ENTITY_TABLE_NAME` for a BFF's views) and grants access.

## Escape hatches

Every construct exposes what it creates (`function`, `table`, `bus`, `defaultChannel`, `bucket`, `topic`, ...), so
anything the props don't cover can be set on the underlying CDK resource.
