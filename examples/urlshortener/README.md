# URL Shortener Example (Java)

This is an example project demonstrating how to use the `aws-lambda-stream-kotlin` framework with **Java** as the implementation language.

## Architecture

The example follows a typical serverless architecture:

1.  **urls-url-bff**: A REST API to create, delete, and update URLs. It also provides a redirection endpoint.
2.  **urls-event-hub**: A Kinesis-based event distribution hub.
3.  **urls-control-service**: Processes events to track usage statistics.

```mermaid
graph LR
    User[User/Client] --(CRUD/Redirect)--> BFF[urls-url-bff]
    BFF --> Table[(DynamoDB Table)]
    BFF --(Emit Accessed)--> Hub[urls-event-hub]
    Table -- DynamoDB Stream --> Trigger[BFF Trigger]
    Trigger -- Emits CRUD Events --> Hub
    Hub -- Kinesis Stream --> Listener[Control Listener]
    Listener --> Microstore[(Events Microstore)]
    Microstore -- Event Stream --> CtrlTrigger[Control Trigger]
    CtrlTrigger -- Update Stats --> Table
    CtrlTrigger -- Emits Actions --> Hub
```

## Key Features

-   **Java 21** implementation using Java Records for events and entities.
-   **Jackson** for JSON serialization/deserialization.
-   **Kotlin AWS SDK** usage from Java via coroutine bridges.
-   **CDK** infrastructure defined in Java, with the [`cdk`](../../cdk/README.md) module's constructs.
-   Uses `PipelineRunner` and `Handlers` for Java-friendly pipeline execution.

## Building

To build the entire example:

```bash
./gradlew :examples:urlshortener:common-app:build
./gradlew :examples:urlshortener:urls-url-bff:app:build
./gradlew :examples:urlshortener:urls-control-service:app:build
```

## Deployment

Each service has its own CDK infrastructure module, built from the [`cdk`](../../cdk/README.md) module's constructs.
Each defines a `-dev` and a `-local` stack; deploy the event hub first, then the BFF (the control service uses its
table), then the control service. Build the apps' jars first.

```bash
cd examples/urlshortener/urls-event-hub/infra
cdklocal deploy urls-event-hub-local    # or: cdk deploy urls-event-hub-dev
```

(Note: Ensure you have the necessary AWS credentials and environment variables configured).
