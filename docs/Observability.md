# Observability

Teams move fast only when they can see what their services are doing and trust that they'll hear about a problem
before their users do. *Software Architecture Patterns for Serverless Systems* (Ch. 12) sorts what to watch into
three kinds of data, and suggests a few monitors for stream processors. This page maps them to kopipes.

## Three kinds of data

| Kind | Tells you | Method | For a kopipes service |
|---|---|---|---|
| **Work metrics** | The **symptoms**: is the system doing its job? | RED: rate, errors, duration | Rate of domain events by type; BFF API requests, errors and latency |
| **Resource metrics** | The **causes**: what is saturated or failing | USE: utilization, saturation, errors | Batch utilization, iterator age, throttles, fault events |
| **System events** | The story in between | | Deployments, alarms crossing thresholds, failovers |

The book's rule: **page people on symptoms, not causes.** A drop in the rate of `PAYMENT_COMPLETED` events means
something is wrong for customers; an iterator age spike may recover on its own. Record every threshold crossing,
alert a few key work metrics with anomaly detection, and use the rest to find the root cause.

## What kopipes emits

### Pipeline metrics

With metrics on, each batch is reported in CloudWatch Embedded Metric Format: plain log lines that CloudWatch turns
into metrics, with no API calls.

| Metric | Meaning |
|---|---|
| `stream.uow.count` | Units of work processed in the batch |
| `stream.pipeline.time` | Time each pipeline spent on the batch (with `pipeline`) |
| `stream.batch.utilization` | Batch size relative to the event source's configured batch size: a saturation signal |
| Step timings | Time per step inside a pipeline, such as a DynamoDB update or a publish (with `step`) |

Metrics carry the standard tags as dimensions: `functionname`, `source`, `stage`, `region`, `account`, plus
`pipeline` and `step` where they apply. The namespace is `lambda-stream/metrics`, or `NAMESPACE` when set.

Turn them on with the `METRICS` env var (`pipeline`, `step`, `emf`, or `*` for all), and report them at the end of
the handler's flow:

```kotlin
container.assembler
    .assemble(container.kinesisAdapter.fromKinesis(input))
    .collectMetrics(reporter = EmfReporter(container.context.envConfig))   // io.kopipes.core.metrics, io.kopipes.aws.metrics
    .collect()
```

`EmfReporter` writes only when `METRICS` includes `emf` (or `*`). From Java, use
`.transformer(flow -> Handlers.collectMetrics(flow, container.context))` on the `PipelineRunner`.

### Fault events

Every fault is an event on the bus, with its error, the unit of work and the tags of the function and pipeline that
failed. The fault monitor keeps them, and the `EventHub` construct logs them to `/aws/events/<bus>-faults` (with
`logEvents`, on by default), where a metric filter can count them by function and pipeline. See [Fault Re-submission](FaultResubmission.md).

### Domain events

The book counts domain events by `type` in a small service that consumes everything (`domain.event.count`, with
the envelope's tags). In a kopipes subsystem, the `EventHub` construct already logs every event except faults to
`/aws/events/<bus>-events`, so CloudWatch metric filters or Logs Insights over it give you the rate per type without a
new service. The construct's log retention is a month by default: the event lake is the permanent record.

## Monitors to start with

The book's examples for stream processors (Ch. 12 › *Tracking system events*), with where the data comes from:

| Monitor | Source |
|---|---|
| **Fault count > 0** | Fault events. Any fault needs a look: it is a record that didn't get processed |
| **Iterator age > 10 minutes** | Lambda's `IteratorAge` metric for each listener and trigger. High age *and* function errors means an error is escaping instead of becoming a fault |
| **Utilization at 30% and 60%** | Lambda concurrency and other account limits; `stream.batch.utilization` for batches |
| **Error rate > 10%** | Lambda `Errors` / `Invocations`; API Gateway 5XX for BFFs |
| **BFF p95 latency > 1 second** | API Gateway latency |
| **Anomaly in key event rates** | Domain event counts, with CloudWatch anomaly detection |

## Tuning with the numbers

Pipeline and step timings show where a batch spends its time. The usual dials, in the order the book suggests
trying them (Ch. 4 › *Optimizing throughput*):

1. **Parallelism** of non-blocking calls inside the function (`PARALLEL`, and each sink's `parallel`).
2. **Batch size** of the event source.
3. **Shards** of the channel, with a well-spread `partitionKey`.
4. **Batching and grouping** writes (the publisher's `PUBLISH_BATCH_SIZE`).
5. **Rate limiting** calls to slow systems (`RateLimiter`), to apply backpressure instead of failing.

Watch iterator age as you change them: it is the measure of whether the function keeps up.
