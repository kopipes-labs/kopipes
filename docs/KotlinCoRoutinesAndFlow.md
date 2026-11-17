# Coroutines and Flow for Serverless Pipelines

Kotlin's coroutines and Flow framework provide significant advantages when building serverless event-driven pipelines:

**Asynchronous Processing Without Blocking**: Coroutines enable efficient asynchronous I/O operations (like S3
reads/writes, DynamoDB queries, or SQS polling) without blocking threads. This is critical in Lambda environments where
compute resources are billed by execution time and memory usage.

**Backpressure Handling**: Flow provides built-in backpressure mechanisms through operators like `buffer()`. When
processing event streams (like the claim-check pattern in `ClaimCheckStore`), Flow automatically manages the rate at
which events are processed, preventing memory exhaustion when downstream consumers are slower than upstream producers.

**Composable Pipeline Stages**: Flow operators (`map`, `filter`, `mapNotNull`, etc.) allow building complex event
processing pipelines from small, testable, reusable components. For example, the `SqsAdapter.fromSqsEvent()`
demonstrates chaining decode → filter → redeem operations in a clear, declarative style.

**Structured Concurrency**: Coroutines enforce structured concurrency, ensuring all child operations complete or cancel
together. This prevents resource leaks in Lambda functions and simplifies error handling across pipeline stages.

**Efficient Batching**: Operators like `buffer(capacity)` enable controlled concurrent processing of batched events. The
`ClaimCheckStore.storeClaimCheck()` uses this to process multiple S3 uploads concurrently while maintaining ordering and
fault tolerance guarantees.

**Cold Flow Semantics**: Flows are cold streams—they don't execute until collected. This lazy evaluation is ideal for
Lambda's request-driven model, avoiding unnecessary work and enabling efficient resource usage.
