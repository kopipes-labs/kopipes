# Replaying Events

The `replay-events` tool reads events from the [Event Lake](EventLake.md) and sends them to one Lambda function, as
if they had just arrived on its Kinesis channel.

Use it to:

- **Fix the effects of a bug.** Deploy the fix, then replay the window when the bug was live.
- **Fill a new service.** Replay history into a new listener so its read model or events table starts complete.
- **Rebuild a read model** after changing its shape.

Replay and [fault resubmission](FaultResubmission.md) are different tools:

| | Replay | Resubmit |
|---|---|---|
| Reads from | The event lake: every event except faults | The fault monitor's bucket: faults only |
| Sends | Events you select by date and type | The records that failed |
| To | The one function you name | The function that failed each record |
| For | Bugs that produced wrong results without failing; new services | Records that failed |

## Why replays are safe

Replaying sends events a function has already seen, so the function has to be idempotent. kopipes services are,
by design:

- Collect and correlate write the same item again for the same event id.
- Evaluate pipelines with a deterministic `emittedEventId` (or `oneEventPerCorrelationKey()`) emit the same event
  again, and consumers drop the repeat.
- Materialize pipelines write the same view again.

Replay to **one service**, never to the whole bus: other services already have these events.

## Running it

```bash
export AWS_REGION=us-east-1
export BUCKET_NAME=myorg-payments-event-lake-dev-us-east-1
export PREFIX=us-east-1/2026/11/17/          # the lake's objects are under <region>/YYYY/MM/DD/HH/
export TYPE='PAYMENT_*'                      # optional
export FUNCTION_NAME=payments-bff-dev-listener
export DRY_RUN=true                          # count first
./gradlew :tools:replay-events:run
```

Set `AWS_ENDPOINT_URL=http://localhost:4566` to replay against LocalStack.

### Settings

Environment variables win over a `.eventsrc` (or `.eventsrc.json`) file, which the tool looks for in the current
directory and its parents.

| Env var | `.eventsrc` key | Meaning | Default |
|---|---|---|---|
| `BUCKET_NAME` | `bucket` | The event lake's bucket | |
| `AWS_REGION` | `region` | Region of the bucket and the function | |
| `PREFIX` | `prefix` | S3 prefix to read | today, `YYYY/MM/DD/` |
| `TYPE` | `type` | Event types: `*`, a prefix like `PAYMENT_*`, an exact type, or `regex:<pattern>` | `*` |
| `FUNCTION_NAME` | `functionname` | The function to invoke | |
| `DRY_RUN` | `dry` | Count matching events without invoking | `false` |
| `MARKER` | `continuationToken` | Resume a listing where a previous run stopped | |
| `BATCH_TIMEOUT` | `batchTimeout` | Milliseconds to wait before sending a partial batch | `5000` |
| `RATE` | `rate` | Invocations per window, so downstream services aren't flooded | `3` |
| | `window` | The rate window, in milliseconds | `500` |
| | `batch` | Events per invocation | `25` |
| | `parallel` | Objects read in parallel | `16` |
| | `qualifier` | Function version or alias | `$LATEST` |
| | `async` | Invoke asynchronously | `false` |

The default prefix has no region. With the `EventLake` construct the objects are under `<region>/`, so set `PREFIX`.

## What the function receives

A batch in the shape of a Kinesis event, `{"Records": [{"kinesis": {"data": ...}, "eventSource": "aws:kinesis"}, ...]}`,
for the function's Kinesis handler: replay targets listeners, the functions that read the subsystem's channel.

## Guidelines

- **Dry run first**, and check the count.
- **Narrow the window**: an hour's prefix (`us-east-1/2026/11/17/09/`) and a type filter, rather than a day of
  everything.
- **Mind the rate.** A replay competes with live traffic for the function's concurrency and its downstream services.
