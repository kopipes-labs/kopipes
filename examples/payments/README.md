# Payments example

An autonomous payments subsystem built with kopipes, on plain AWS Lambda (`core` + `aws`, no Spring). A customer
submits a payment through a BFF; a control service runs the payment process (withdraw, deposit, refund if the deposit
fails, a deadline per step); gateways stand between the subsystem and the outside systems.

- [PaymentsSystemSpecs.md](PaymentsSystemSpecs.md): the system (L1), and the external event catalog.
- [PaymentsServiceSpecs.md](PaymentsServiceSpecs.md): each service as a black box (L2). The code follows it.

Running example: payment `p-7`, 100 USD from checking account `C-1` to card `K-2`, for customer `u-42`.

## What is built

| Service | Status | Pattern | kopipes pieces |
|---|---|---|---|
| `payments-event-hub` | ✅ | Event hub | `EventHub` construct: bus, default channel `s1`, archive, logs |
| `payments-event-lake` | ✅ | Event lake | `EventLake` construct |
| `payments-event-fault-monitor` | ✅ | Fault monitor | `FaultMonitor` construct, `FaultMonitorTransform` |
| `payments-control-service` | ✅ | Control service | `CollectPipeline`, `CorrelatePipeline`, `EvaluatePipeline`, deadlines |
| `payments-bff` | ✅ | BFF | REST API, `MaterializePipeline`, `CdcPipeline` |
| `payments-core-checking-*-gateway` | stage 2 | ESG (legacy) | |
| `payments-card-processor-*-gateway` | stage 3 | ESG (third party) | |
| `payments-egress-gateway` | stage 4 | ESG (subsystem) | |

Until the gateways exist, the integration tests play them: they publish the gateways' events to the bus.

## How a payment flows

```
customer ─PUT─► BFF ─► views table ─(stream)─► BFF trigger ─► bus: PAYMENT_SUBMITTED
                                                              │
         ┌────────────────────────── default channel ◄────────┘
         ▼
control service listener ─► events table ─(stream)─► control service trigger ─► bus: WITHDRAWAL_REQUESTED
                                                                                 │
                     (stage 2: the core checking gateways; for now, the tests) ◄─┘
                     ─► bus: FUNDS_WITHDRAWN ─► … ─► DEPOSIT_REQUESTED ─► … ─► PAYMENT_COMPLETED
         ▲
BFF listener ─► views table: what each payment has heard ─► GET /payments/p-7
```

Every service is a handful of Lambda functions around one table, and they only talk through the bus. The rest of this
page walks through how each piece uses the framework.

## The code, service by service

Each service has an `app` (the functions) and an `infra` (its CDK stack), like the `sut` example. In an `app`:

- **Handlers** (`Handlers.kt`, `RestApi.kt`) build their container once per Lambda container and run a batch through
  it. That's all they do.
- **Containers** (`*Container.kt`) wire the pipelines from an `AwsKopipesContext`: the composition root, and the
  place to read first. Tests build them with in-memory fakes. See [docs/Wiring.md](../../docs/Wiring.md).
- **Plain functions** hold the business: `Rules.kt`, `PaymentView.kt`, `PaymentRequest.kt`. No framework in them.

### Events (`common-app`)

Internal events are one sealed class, `PaymentEvent`, encoded with the framework's `KotlinxEventCodec`. Every event
carries the whole `Payment`, so no consumer ever calls back; failures add a `reason`. Amounts are decimal strings.
See [docs/EventImplementationKotlin.md](../../docs/EventImplementationKotlin.md).

### Control service

```
default channel ─► listener ─► events table ─► (stream) ─► trigger
                   collect      ▲                          ├─ correlate          pk = p-7
                                │                          ├─ deadlines ×3       pk = p-7.<step>-deadline, ttl
                                └── written back ──────────├─ rules ×8           → bus
                                                           └─ expiry rules ×3    → bus
```

- **Collect, then act.** The listener only writes each consumed event to the events table. Everything else happens
  from the table's stream, so the service never acts on a fact it hasn't recorded, and a redelivered event writes
  the same item again, which changes nothing.
- **Correlate.** The trigger saves each event again under its payment (`pk = p-7`). Querying `p-7` gives everything
  heard for the payment, in any order.
- **One evaluate pipeline per rule.** [Rules.kt](payments-control-service/app/src/main/kotlin/org/myorg/payments/Rules.kt)
  has the spec's rules as plain functions; [TriggerContainer.kt](payments-control-service/app/src/main/kotlin/org/myorg/payments/TriggerContainer.kt)
  turns each into a pipeline: the event it fires on (`eventFilter`), what must hold (`expression`, over the payment's
  correlated events: conflict is checked first), and what it emits (`emit`).
- **Deterministic ids.** `oneEventPerCorrelationKey()` gives an emitted event the id `<payment>.<rule>`
  (`p-7.start`, `p-7.next-step`...), so a rule that fires again, in a replay say, emits the same event, and every
  consumer drops the repeat. That's also why a payment never gets two outcomes from one rule.
- **Faults.** `conflict` and `refund-failed` emit nothing: their `emit` throws, and the framework turns the unit of
  work, with the payment's events, into a `fault` for the fault monitor.
- **Deadlines.** The event that starts a step also writes a deadline item with a TTL. When DynamoDB deletes it, the
  step's `onExpired()` rule emits `PAYMENT_EXPIRED` if the step still has no answer.
  See [docs/Deadlines.md](../../docs/Deadlines.md). Locally they're two minutes; a day (core checking) or an hour
  (the card) otherwise.

### BFF

- **`PUT /payments/{id}`** checks only the request's shape, then writes the payment with a condition, so a repeated
  `PUT` writes nothing: the same body returns the stored view, another body is `409`.
- **Accepted means published.** `PAYMENT_SUBMITTED` (`p-7.submitted`) comes from the views table's stream through a
  `CdcPipeline`, never from the request handler: no dual write, and once the API answered `202` the event will be
  published, even if the bus was down at that moment.
- **The view.** The listener's `MaterializePipeline` records `at_<TYPE>` (the event's own timestamp) for each event
  heard, keeping the first. `status` is computed when read, from *which* events were heard, never their order: the
  spec's first-match table in [PaymentView.kt](payments-bff/app/src/main/kotlin/org/myorg/payments/PaymentView.kt).
- `X-Customer-Id` is a stand-in for an access token. **It is not authentication and must not ship.**

### Common components

The event hub, event lake and fault monitor are each a few lines on top of the [`cdk`](../../cdk/README.md) module's
constructs. The fault monitor's function is the framework's `FaultMonitorTransform`.

## Testing

- **Unit tests** test the plain functions in isolation: the rules, the status table, the request's validation.
- **In process.** [ControlServiceTest](payments-control-service/app/src/test/kotlin/org/myorg/payments/ControlServiceTest.kt)
  runs the control service's real pipelines, one test per cell of the spec's decision table, against
  `EventsTableSimulator`: an in-memory events table whose writes come back as stream records, the way the real table
  feeds the trigger. Seconds, no LocalStack.
- **End to end.** [PaymentsITest](integration-test/src/integrationTest/kotlin/org/myorg/payments/PaymentsITest.kt)
  runs against LocalStack: it submits through the BFF, plays the missing gateways on the bus, and watches the
  default channel, the BFF's views and the fault monitor's notifications.

## Running it locally

Install LocalStack, `awslocal` and `cdklocal` as in [the sut example](../sut/README.md). Then, from this directory,
starting from a clean LocalStack (a long-running one can hang mid-run):

```bash
docker compose down && docker compose up -d
./deploy_stacks.sh -d        # -d resets LocalStack's state first; builds, tests and deploys every stack
./run_itests.sh              # logs land in .awslocal_logs
docker compose down
```

`examples/sut` uses the same LocalStack container: test one example at a time.

Try it by hand:

```bash
API=$(awslocal apigateway get-rest-apis --query "items[?name=='payments-bff-local-api'].id" --output text)
URL=http://localhost:4566/restapis/$API/local/_user_request_

curl -X PUT $URL/payments/p-7 -H 'X-Customer-Id: u-42' -H 'Content-Type: application/json' \
  -d '{"from":{"kind":"checking","account":"C-1"},"to":{"kind":"card","account":"K-2"},"amount":"100.00","currency":"USD"}'
curl $URL/payments/p-7 -H 'X-Customer-Id: u-42'

# Every event on the bus is logged:
awslocal logs filter-log-events --log-group-name /aws/events/payments-event-hub-local-bus-events
```
