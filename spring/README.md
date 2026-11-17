# kopipes Spring Boot support

Optional module. `core` and `aws` have no Spring dependency and no global state: an application builds an
`AwsKopipesContext` and passes it to the components it creates. Without Spring that is one line per Lambda container:

```kotlin
class ListenerContainer(val context: AwsKopipesContext) {
    val assembler = PipelineAssembler.builder().context(context).addPipeline(...).build()
    val kinesisAdapter = KinesisAdapter(context, MyEventCodec)

    companion object {
        fun build() = ListenerContainer(AwsKopipesContext())   // env vars, EventBridge
    }
}
```

With Spring Boot, add this module and the context becomes a bean you inject:

```kotlin
dependencies {
    implementation(project(":spring"))   // brings :aws and :core
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.2"))
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation(libs.aws.sdk.eventbridge) // enables the default EventBridgePublisher
}

@Bean
fun listenerContainer(kopipes: AwsKopipesContext) = ListenerContainer(kopipes)
```

## What it gives you

| Bean (replace it by declaring your own) | Default |
|---|---|
| `AwsEnvironmentConfig` | `SpringEnvironmentConfig`, which reads the Spring `Environment` |
| `DynamoDbClientFactory`, `S3ClientFactory`, `KmsClientFactory`, `CloudWatchClientFactory`, `EventBridgeClientFactory` | the `Default*ClientFactory`, each only when its AWS SDK is on the classpath |
| `EventPublisher` | `EventBridgePublisher`, only when the EventBridge SDK is on the classpath |
| `UnitOfWorkSnapshotter` | `DefaultUnitOfWorkSnapshotter` with the AWS record snapshotters plus any `RecordSnapshotter` beans |
| `FaultManager` | publishes faults through the `EventPublisher` bean; AWS throttling errors are retryable |
| `AwsKopipesContext` | assembled from the beans above |

To make more errors retryable (a gateway's "not known yet", say), declare your own `FaultManager` bean with
`FaultManager.fromConfig(envConfig, publisher, snapshotter, isRetryable = { it is NotKnownYet || isAwsRetryable(it) })`.
With several `EventPublisher` beans, mark the default one `@Primary`.

Turn it all off with `kopipes.enabled=false`.

## Spring Framework without Boot

Import `KopipesConfiguration` instead. It needs only `spring-context` (no Boot jars) and defines two beans: the
`AwsEnvironmentConfig`, read from the Spring `Environment` the same way, and the `AwsKopipesContext` built from it with
its defaults. To change a collaborator, declare your own `AwsKopipesContext` bean instead of importing it.

```kotlin
dependencies {
    implementation(project(":spring"))
    implementation("org.springframework:spring-context:7.0.10")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
}

@Configuration(proxyBeanMethods = false)
@Import(KopipesConfiguration::class)
class ControlServiceConfiguration { /* beans built from AwsKopipesContext */ }
```

Without Boot, `application.properties` is not loaded: the `kopipes.*` keys come from the env vars and system
properties, or from your own `@PropertySource`.

## Running on Lambda

Declare a `Consumer` bean per function, then a handler class per function that names your application:

```kotlin
@Configuration(proxyBeanMethods = false)
class ControlServiceConfiguration {
    @Bean
    fun listenerContainer(kopipes: AwsKopipesContext) = ListenerContainer(kopipes)

    @Bean
    fun listener(c: ListenerContainer): Consumer<KinesisEvent> = kinesisConsumer(c.assembler, c.kinesisAdapter)
}

@SpringBootApplication(proxyBeanMethods = false)
class ControlServiceApplication

class Listener : SpringKinesisHandler(ControlServiceApplication::class.java)   // handler: ...Listener::handleRequest
```

- `kinesisConsumer`, `dynamoDbConsumer`, `sqsConsumer` and the general `pipelineConsumer` turn an assembler and an
  adapter into a `java.util.function.Consumer`. That is also the shape Spring Cloud Function expects, if you later
  move to its `FunctionInvoker`.
- `SpringKinesisHandler`, `SpringDynamoDbHandler` and `SpringSqsHandler` start the application once per Lambda
  container, during init, and hand each event to the `Consumer<EventType>` bean. A `@SpringBootApplication` starts
  with Spring Boot; a plain `@Configuration` starts in an `AnnotationConfigApplicationContext`, without Boot. With several consumers of the same
  event type, pass the bean name: `SpringKinesisHandler(App::class.java, "audit")`.
- Set `spring.main.lazy-initialization=true` so each function builds only the beans its consumer needs.

No Spring Cloud dependency is needed: the handlers are plain `RequestHandler`s, so the Lambda runtime converts the
events exactly as it does for a kopipes function without Spring.

### Packaging: merge Spring's metadata in the shadow jar

Several jars ship `META-INF/spring.factories` and `META-INF/spring/*.imports`. A fat jar must keep every jar's
entries, and Shadow 9 drops duplicates before its transformers run unless told otherwise. Without this the app starts
without kopipes' (or Boot's) auto-configuration, or without loading `application.properties`:

```kotlin
tasks.shadowJar {
    filesMatching(listOf("META-INF/services/**", "META-INF/spring.factories", "META-INF/spring/**")) {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    mergeServiceFiles()
    append("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
    transform(PropertiesFileTransformer::class.java) {
        paths.addAll("META-INF/spring.factories", "META-INF/spring/aot.factories")
        mergeStrategy.set(PropertiesFileTransformer.MergeStrategy.Append)
        mergeSeparator.set(",")
    }
}
```

## Configuration

Each setting is read as a `kopipes.*` property first, then under its existing env var name, so deployed Lambdas need no change:

```yaml
kopipes:
  bus-name: payments-bus          # or env BUS_NAME, or KOPIPES_BUS_NAME
  event-table-name: payments-events
  stream-retry-enabled: true
  metrics: pipeline
```

See `SpringEnvironmentConfig` for the full key list.
