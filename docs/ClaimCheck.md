# Claim Check

A **claim check** keeps events small: the publisher stores the full event in S3 and sends a lightweight event that
carries a reference to it. A consumer that needs the full event *redeems* the claim check and reads it back. The
name comes from a coat check: you leave the coat and keep the ticket.

## Why

EventBridge, SQS and SNS take at most 256 KB per message, and Kinesis records are capped too. Large events also
cost more everywhere they travel: in queues, streams, retries, faults and logs. Use a claim check when events are:

- large enough to approach those limits (documents, snapshots, third-party webhook bodies, reports);
- needed in full by only some consumers;
- better governed in S3: encryption, access policies, retention.

Skip it when events are small, or when every consumer needs the full payload right away: it adds an S3 write per
event and an S3 read per redemption.

## How kopipes does it

### Publishing

Give the `EventBridgePublisher` a `ClaimCheckStore`, and set `CLAIMCHECK_BUCKET_NAME`. Every event the publisher
sends is then stored in that bucket and replaced on the bus by a claim-check event. With no bucket name, the store
does nothing.

```kotlin
class PublishingContainer(envConfig: AwsEnvironmentConfig = AwsEnvironmentConfig()) {
    private val s3 = DefaultS3ClientFactory(envConfig)
    private val eventBridge = DefaultEventBridgeClientFactory(envConfig)

    val context: AwsKopipesContext = AwsKopipesContext(
        envConfig = envConfig,
        eventBridgeClientFactory = eventBridge,
        s3ClientFactory = s3,
        eventPublisher = EventBridgePublisher(
            envConfig,
            eventBridge,
            claimCheckStore = ClaimCheckStore(envConfig, s3, faultManager = { context.faultManager }),
        ),
    )
}
```

The fault manager is passed as a function because it publishes through this same publisher (see the
[Wiring pitfalls](Wiring.md#pitfalls)).

The store writes the encoded event to `<region>/claimchecks/YYYY/MM/DD/HH/<event id>` and publishes an event that
keeps the envelope, so routing, filtering and logging still work without the payload:

```json
{
  "id": "event-123",
  "type": "ShipmentCreated",
  "partitionKey": "shipment-456",
  "timestamp": 1716820000000,
  "tags": { "source": "shipping" },
  "s3": {
    "bucket": "my-claim-check-bucket",
    "key": "us-east-1/claimchecks/2026/05/28/14/event-123"
  }
}
```

Each event in a batch is stored separately. A failed S3 write turns that unit of work into a
[fault](FaultResubmission.md), like any other step.

### Redeeming

Pass a `ClaimCheckRedeemer` to the adapter (`KinesisAdapter`, `SqsAdapter` or `FirehoseAdapter`). It fetches each
claim-checked event from S3 and decodes it with your codec, so the pipelines see the original event:

```kotlin
val redeemer = ClaimCheckRedeemer(
    s3ConnectorOptions = S3Connector.Options(context.s3ClientFactory),
    faultManager = context.faultManager,
    eventCodec = MyEventCodec,
)
val kinesisAdapter = KinesisAdapter(context, MyEventCodec, redeemer)
```

Events without a claim check pass through unchanged. A consumer that only needs the envelope can skip the redeemer
and never pay for the read. A failed read (missing object, no permission) becomes a fault.

## Operating it

- **Retention.** Keep objects at least as long as your longest retry, resubmission and replay window. A lifecycle
  rule that deletes them sooner leaves consumers with references to nothing.
- **Encryption.** Use SSE-KMS on the bucket for sensitive domains, or [envelope encryption](Encryption.md) on the
  event's fields before it's stored.
- **Least privilege.** Only publishers write to the bucket; only consumers that redeem can read it.
- **Immutable objects.** An object belongs to one event id and is never rewritten, so a redemption always reads what
  was published.
- **Watch it.** S3 write and read errors show up as faults; also watch S3 latency and object-not-found errors.

## Trade-offs

| Gain | Cost |
|---|---|
| Small messages, no size limits | An S3 write per published event, an S3 read per redemption |
| Consumers that only need metadata never load the payload | A second service (S3) that can fail, and its permissions to manage |
| Payloads governed with S3 controls | Retention must be coordinated with replay and resubmission windows |
