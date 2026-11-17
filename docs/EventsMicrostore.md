# Events Microstore

## Table of Contents

- [What is an Events Microstore?](#what-is-an-events-microstore)
- [Benefits of an Events Microstore](#benefits-of-an-events-microstore)
- [How does it work?](#how-does-it-work)
    - [Collection](#collection)
    - [Correlation](#correlation)
    - [Evaluation](#evaluation)

## What is an Events Microstore?

An **Events Microstore** is a small, purpose-built event store owned by a specific service or pipeline. It maintains a queryable copy of the events that the service needs to process, correlate, evaluate, or recover event-driven workflows.

Unlike a central event lake or long-term archive, an Events Microstore is optimized for **operational use**. It stores events with access patterns that support the local processing model, such as finding all events related to a specific entity, correlation key, partition key, or pipeline execution.

In this framework, the Events Microstore acts as a lightweight persistence layer for events flowing through serverless pipelines. It allows later pipeline stages to look up previously collected or correlated events without replaying an entire stream or depending on another service's internal database.

The Events Microstore is primarily used for:

- **Correlation**: Grouping events that belong to the same business entity or workflow.
- **Evaluation**: Querying related events to decide whether a new event should trigger additional processing.
- **Recovery**: Retaining enough event history to support retries, reprocessing, and fault investigation.
- **Decoupling**: Allowing pipelines to query event history without coupling directly to upstream services.
- **Auditability**: Preserving raw or transformed event payloads alongside metadata used for routing and lookup.

The store is intentionally narrow in scope. It is not the system of record for business entities, nor is it a general-purpose reporting database. Its primary responsibility is to support reliable event processing close to the pipelines that consume and produce events.

## Benefits of an Events Microstore

Implementing an Events Microstore provides several operational and architectural advantages:

- **Optimized for Operations**: The store is designed for the specific access patterns of a single service, enabling high-performance lookups by entity ID or correlation key.
- **Enhanced Reliability**: By keeping a local copy of relevant events, pipelines can recover from failures or reprocess specific workflows without needing to replay the entire upstream event stream.
- **Service Decoupling**: Services can query their own event history to make decisions during evaluation, reducing the need for synchronous calls to external databases.
- **Improved Observability**: Having a queryable record of how events were collected and correlated makes it easier to debug complex workflows and investigate state transitions.
- **Cost-Effective Scalability**: By using Time to Live (TTL) for records, the microstore provides a sliding window of operationally relevant data without the overhead of permanent storage.

## How does it work?

The following sections describe how an event is processed through the microstore, starting with an incoming event from a Kinesis stream, SQS queue, or other event source.
```json
{
    "id": "ev-001",
    "timestamp": 1775658343000,
    "partitionKey": "thing-005",
    "tags": { 
        "awsregion": "us-east-1"
    },
    "eventType": "thing-created",
    "entity": {
        "id": "thing-005"
    }
}
```

## Collection

When an event is collected, it is stored in the Events Microstore (implemented using DynamoDB) with the following properties:

| Property       | Value         | Description                                                                     |
|----------------|---------------|---------------------------------------------------------------------------------|
| pk             | ev-001        | The partition key, which stores the unique event ID.                            |
| sk             | EVENT         | The sort key, set to the constant "EVENT" to identify the record type.          |
| discriminator  | EVENT         | Used to distinguish between different types of stored records.                  |
| timestamp      | 1775658343000 | The event's timestamp, in epoch milliseconds.                                  |
| awsregion      | us-east-1     | The AWS region where the event was collected.                                   |
| sequenceNumber | ...0121231223 | A monotonically increasing number, typically from the Kinesis record.           |
| ttl            | 1778509543    | When DynamoDB may delete the item, in epoch seconds (default: timestamp + 33 days). |
| expire         | false         | `true` turns the TTL deletion into an *expired* event a rule can react to (see [Deadlines](Deadlines.md)). |
| suffix         | ""            | Not used for standard event records.                                            |
| data           | thing-005     | The correlation key (the entity the event is about).                            |
| pipelineId     | col1          | The ID of the pipeline that performed the collection.                           |
| event          | {...}         | The raw event payload.                                                          |

## Correlation

After an event is collected, a DynamoDB Stream (using the outbox pattern) triggers a correlation pipeline. This pipeline creates new **CORREL** records, which are also inserted into the Events Microstore.

| Property       | Value         | Description                                                                                         |
|----------------|---------------|-----------------------------------------------------------------------------------------------------|
| pk             | thing-005     | The partition key, storing the correlation key (e.g., the entity ID).                               |
| sk             | ev-001        | The sort key, storing the ID of the event that was correlated.                                      |
| discriminator  | CORREL        | Set to "CORREL" to indicate this is a correlation record.                                           |
| timestamp      | 1775658343000 | The original event's timestamp, in epoch milliseconds.                                              |
| awsregion      | us-east-1     | The AWS region where the event was collected.                                                       |
| sequenceNumber | ...0121231223 | The sequence number inherited from the original record.                                             |
| ttl            | 1778509543    | When DynamoDB may delete the item, in epoch seconds.                                                |
| expire         | false         | `true` for deadline items, whose deletion triggers an expiry rule.                                  |
| suffix         | ""            | Set for side partitions such as deadlines (`pk = thing-005.<suffix>`).                              |
| data           | null          | Typically null for correlation records.                                                             |
| pipelineId     | corr1         | The ID of the correlation pipeline that created this record.                                        |
| event          | {...}         | The raw event payload (copied from the original event).                                             |

## Evaluation

The **Evaluation** pipeline processes both collected events and correlation records to drive business logic.

When a record is inserted or updated in the Events Microstore, the evaluation pipeline queries the store to gather context:

- **For a CORREL record**: It finds all events that share the same correlation key (partition key).
  ```sql
  -- Find all events related to the entity
  SELECT * FROM events WHERE pk = 'thing-005'
  ```

- **For an EVENT record**: It can find events with the same `data` field (optionally using a Global Secondary Index).
  ```sql
  -- Find all events with the same data reference
  SELECT * FROM events WHERE data = 'thing-005'
  ```

The evaluation pipeline then applies business rules to these related events and may emit new events to trigger actions in other services.



