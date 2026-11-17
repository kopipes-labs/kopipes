package io.kopipes.cdk

import software.amazon.awscdk.Duration
import software.amazon.awscdk.services.kinesis.IStream
import software.amazon.awscdk.services.lambda.Code
import software.amazon.awscdk.services.lambda.Function
import software.amazon.awscdk.services.lambda.Runtime
import software.amazon.awscdk.services.lambda.StartingPosition
import software.amazon.awscdk.services.lambda.eventsources.DynamoEventSource
import software.amazon.awscdk.services.lambda.eventsources.KinesisEventSource
import software.constructs.Construct

/**
 * @param code usually `Code.fromAsset("../app/build/libs/<service>.jar")`, the service's shadow jar.
 * @param handler e.g. `org.myorg.payments.Listener::handleRequest`.
 * @param publishes the function publishes to the subsystem's bus: sets `BUS_NAME` and `BUS_SRC` and grants
 *   `events:PutEvents`. Leave it on for any kopipes pipeline, even one that emits nothing: its faults go to the bus.
 * @param source the bus `source` of what it publishes; the service name, or [EnvVars.EXTERNAL_SOURCE] for an egress
 *   gateway's external events.
 */
data class KopipesFunctionProps(
    val code: Code,
    val handler: String,
    val environment: Map<String, String> = emptyMap(),
    val memorySize: Int = 1024,
    val timeout: Duration = Duration.seconds(50),
    val logLevel: String = "DEBUG",
    val publishes: Boolean = true,
    val source: String? = null,
)

/**
 * A Lambda function running a kopipes handler, named `<service>-<stage>-<id>`. It sets the env vars the runtime
 * reads (`STAGE`, `SERVICE`, and `BUS_NAME`/`BUS_SRC` when it publishes), and wires and grants what the function uses
 * through [listenTo], [triggeredBy] and [uses].
 */
class KopipesFunction(private val stack: KopipesStack, id: String, props: KopipesFunctionProps) : Construct(stack, id) {

    val function: Function = Function.Builder.create(this, "Function")
        .functionName(stack.name(id))
        .code(props.code)
        .handler(props.handler)
        .runtime(Runtime.JAVA_21)
        .memorySize(props.memorySize)
        .timeout(props.timeout)
        .environment(baseEnvironment(props) + props.environment)
        .build()

    init {
        if (props.publishes) {
            stack.importBus().grantPutEventsTo(function)
        }
    }

    /**
     * Reads a Kinesis channel of the hub at the function's own pace. A failed batch is split in two (bisect) and
     * retried [retryAttempts] times; `-1` retries until it succeeds or its records expire, holding this consumer
     * back while the others carry on. Starts from the oldest record kept, so a new consumer misses nothing.
     */
    fun listenTo(
        channel: IStream = stack.importChannel(),
        batchSize: Int = 100,
        retryAttempts: Int = 3,
        startingPosition: StartingPosition = StartingPosition.TRIM_HORIZON,
    ): KopipesFunction = apply {
        function.addEventSource(
            KinesisEventSource.Builder.create(channel)
                .startingPosition(startingPosition)
                .batchSize(batchSize)
                .maxBatchingWindow(Duration.seconds(1))
                .bisectBatchOnError(true)
                .retryAttempts(retryAttempts)
                .parallelizationFactor(1)
                .build()
        )
    }

    /** Reads a table's DynamoDB stream: the trigger of a control service, or the outbox of a BFF. */
    fun triggeredBy(table: EventsTable, batchSize: Int = 5, retryAttempts: Int = 3): KopipesFunction = apply {
        function.addEventSource(
            DynamoEventSource.Builder.create(table.table)
                .startingPosition(StartingPosition.TRIM_HORIZON)
                .batchSize(batchSize)
                .bisectBatchOnError(true)
                .retryAttempts(retryAttempts)
                .build()
        )
    }

    /**
     * Reads and writes [table], named to the runtime by [envVar]: `EVENT_TABLE_NAME` for an events microstore,
     * `ENTITY_TABLE_NAME` for a BFF's view.
     */
    fun uses(table: EventsTable, envVar: String = EnvVars.EVENT_TABLE_NAME): KopipesFunction = apply {
        function.addEnvironment(envVar, table.table.tableName)
        table.table.grantReadWriteData(function)
    }

    /** Lets the runtime retry a failed batch from the stream instead of turning retryable errors into faults. */
    fun retryStream(): KopipesFunction = apply {
        function.addEnvironment(EnvVars.STREAM_RETRY_ENABLED, "true")
    }

    private fun baseEnvironment(props: KopipesFunctionProps): Map<String, String> = buildMap {
        put("JAVA_TOOL_OPTIONS", JAVA_TOOL_OPTIONS)
        put("LOG_DEFAULT_LEVEL", props.logLevel)
        put(EnvVars.STAGE, stack.stage.id)
        put(EnvVars.SERVICE, stack.service)
        if (props.publishes) {
            put(EnvVars.BUS_NAME, stack.hub.busName)
            put(EnvVars.BUS_SRC, props.source ?: stack.service)
        }
    }

    companion object {
        /**
         * - The JSON logger, so CloudWatch gets one JSON line per log statement.
         * - Tiered compilation stopped at C1 and the serial GC, for faster, lighter cold starts.
         * - A capped heap: LocalStack doesn't limit function containers to their memory size, so an uncapped JVM
         *   sizes its heap from the whole Docker VM, and a few dozen functions run Docker out of memory.
         */
        const val JAVA_TOOL_OPTIONS =
            "-Dslf4j.provider=io.github.vitalijr2.aws.lambda.slf4j.AWSLambdaServiceProvider " +
                "-XX:+TieredCompilation -XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xmx384m"
    }
}
