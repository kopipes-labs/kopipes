# Event Lake

## Table of Contents

- [Purpose](#purpose)
- [How it works](#how-it-works)
    - [Architecture](#architecture)
- [Keeping it safe](#keeping-it-safe)
- [Finding and replaying events](#finding-and-replaying-events)
- [Event Lake vs. Fault Monitoring](#event-lake-vs-fault-monitoring)
- [Benefits](#benefits)

## Purpose

An **Event Lake** provides long-term, immutable storage for all events flowing through a subsystem. While an [Events Microstore](EventsMicrostore.md) is optimized for short-term operational needs (correlation, evaluation, recovery), the Event Lake serves as the permanent record of everything that has happened within the system.

The Event Lake is typically used for:

- **Auditing and Compliance**: Maintaining a full history of system state changes for regulatory requirements.
- **Analytics and Business Intelligence**: Providing a data source for large-scale analysis, reporting, and machine learning.
- **Disaster Recovery**: Reconstructing system state by replaying events from a specific point in time.
- **Fault Investigation**: Analyzing historical events to understand the root cause of complex failures.
- **Replay**: Feeding events back into pipelines to fix bugs in processing logic or to populate new services with historical data.

*Software Architecture Patterns for Serverless Systems* (Ch. 4 › *Event lake*) calls the lake the subsystem's record
of truth: because events carry the state of their entities, the lake works like a database's transaction log for the
whole system. **Each subsystem has its own lake**, so its team can replay from it whenever it needs to, without
asking anyone. An analytics subsystem can then collect every subsystem's external events into a lake of its own,
which plays the role of the corporate data lake.

The lake is **not a query store**. Services never read it while processing; they use their own streams and tables.

## How it works

The Event Lake is implemented using **Amazon S3** as the storage layer and **Amazon Kinesis Data Firehose** as the delivery mechanism.

### Architecture

1.  **Event Source**: Events are published to the subsystem's **Event Hub** (Amazon EventBridge).
2.  **Filtering**: An EventBridge **Rule** is configured to match events that should be archived. Typically, this includes all domain events but might exclude technical events like `fault` events if they are handled separately.
3.  **Delivery**: The rule targets a **Kinesis Data Firehose Delivery Stream**.
4.  **Buffering and Compression**: Firehose buffers the events based on size (e.g., 50MB) or time (e.g., 60 seconds) and optionally compresses them (e.g., GZIP) to optimize S3 storage.
5.  **Persistence**: Firehose writes the buffered events to an **S3 Bucket**, typically using a date-based prefix structure (e.g., `YYYY/MM/DD/HH/`) for efficient partitioning.

## Keeping it safe

The lake is only useful if it is never lost and never leaks:

- **Durable, tiered storage.** S3, with lifecycle rules that move older objects to Glacier.
- **A copy elsewhere.** Replicate the bucket to another region and another account, for disaster recovery.
- **Least privilege.** One lake per subsystem limits who can read what.
- **No sensitive data in the clear.** Encrypt sensitive fields before they are published (see
  [Envelope Encryption](Encryption.md)); the lake then stores ciphertext.

## Finding and replaying events

To investigate an incident or pick events to replay, index the lake: Athena over the S3 objects is the simplest
option, and a search engine or a log tool works too.

To **replay**, send events straight to the one function that needs them, wrapped as its channel would deliver them.
Never republish to the bus: every consumer would receive them again. There is no need to filter precisely, because a
listener already ignores the types it doesn't handle; a date range is usually enough. A replay brings duplicates and
out-of-order events, so the function must be [idempotent and order-tolerant](Idempotence.md). See
[Replaying Events](ReplayEvents.md) for the tool.

## Event Lake vs. Fault Monitoring

It is important to distinguish the **Event Lake** from the **Fault Monitoring Service**. 

While the Event Lake archives all successful domain events, it typically **excludes** technical `fault` events. Fault events are instead captured and persisted by a dedicated [Fault Monitoring Service](AutonomousServicePatterns.md#fault-monitoring-services).

This separation ensures that:
- The Event Lake remains a clean record of domain activity.
- Fault events, which contain large snapshots of the `UnitOfWork` for debugging and recovery, are stored in a purpose-built bucket for [Fault Re-submission](FaultResubmission.md).
- The `resubmit-events` tool targets the dedicated fault bucket, not the general-purpose Event Lake.

## Benefits

- **Durability**: Leverages S3's high durability.
- **Scalability**: Firehose and S3 automatically scale to handle varying event volumes.
- **Cost-Effective**: S3 offers low-cost storage for large volumes of data, especially when using lifecycle policies to transition older data to cheaper storage classes.
- **Separation of Concerns**: Decouples long-term archiving from operational event processing.

## Example

See the [Event Lake section](../examples/sut/architecture.md#event-lake) in the SUT Example Architecture for a practical implementation scenario.
