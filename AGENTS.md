# AI Guidelines

## Project overview

This project provides Kotlin utilities and pipeline abstractions for AWS Lambda stream processing.

## Rules for AI assistants

- Prefer small, focused changes.
- Do not change public APIs unless explicitly requested.
- Preserve existing replay and fault-resubmission semantics.
- Add or update tests for behavior changes.
- Use Kotlin idioms and coroutines/Flow consistently.
- Avoid introducing new dependencies unless necessary.
- Keep the framework free of global state: components take their collaborators in constructors or builders,
  usually from an `AwsKopipesContext` (see docs/Wiring.md). Don't add registries, singletons or env var reads
  outside `EnvironmentConfig`.
- `core` and `aws` must not depend on Spring; Spring support belongs in the `spring` module.
- `cdk` (reusable CDK constructs) depends only on the CDK, never on `core` or `aws`; the env var names it sets are
  checked against `AwsEnvironmentConfig` in its tests.

## Unit tests
- Use arrange-act-assert style.
- Use kotest assertions.
- Use regular Junit tests annotations.
- test internal functions in isolation.

## Serialization
- Events need to be fully serializable/deserializable since they are used to communicate with other apps.
- UnitOfWork.record should be an instance of RawRecord
- UnitOfWork should be serializable with snapshots for diagnostic purposes.
- The framework uses kotlinx.serialization but consumers can choose to use other libraries.

## Implementing the Event interface
- If you need to implement Event subclasses, follow advice in docs/EventImplementationKotlin.md or docs/EventImplementationJava.md


## Running integration tests
Always start from a clean LocalStack: a long-running one can hang mid-run and fail tests for no code reason.
`examples/sut` and `examples/payments` share the `localstack-main` container, so test one system at a time,
restarting LocalStack in between.

```bash
cd examples/sut   # or examples/payments
docker compose down
docker compose up -d
# deploy all stacks
./deploy_stacks.sh -d

./run_itests.sh
# awslocal output is folder .awslocal_logs
ls .awslocal_logs
docker compose down
```


Use the Mac OS "say" command to inform e when tasks are complete or you need me to act.

