package io.kopipes.cdk

import software.amazon.awscdk.Duration
import software.amazon.awscdk.services.events.Archive
import software.amazon.awscdk.services.events.EventBus
import software.amazon.awscdk.services.events.EventPattern
import software.amazon.awscdk.services.events.Match
import software.amazon.awscdk.services.events.Rule
import software.amazon.awscdk.services.events.RuleTargetInput
import software.amazon.awscdk.services.events.targets.CloudWatchLogGroup
import software.amazon.awscdk.services.events.targets.KinesisStream
import software.amazon.awscdk.services.kinesis.Stream
import software.amazon.awscdk.services.kinesis.StreamEncryption
import software.amazon.awscdk.services.logs.LogGroup
import software.amazon.awscdk.services.logs.RetentionDays
import software.constructs.Construct

/**
 * @param channelRetention how long the default channel keeps events, so a consumer that's down catches up.
 * @param archiveRetention how long the bus's archive keeps events; `null` for no archive.
 * @param logEvents logs every event and every fault to CloudWatch, to watch the bus while developing.
 */
data class EventHubProps @JvmOverloads constructor(
    val channelRetention: Duration = Duration.days(1),
    val shardCount: Int = 1,
    val archiveRetention: Duration? = Duration.days(14),
    val logEvents: Boolean = true,
    val logRetention: RetentionDays = RetentionDays.ONE_MONTH,
)

/**
 * A subsystem's event hub: the one bus every service publishes to, and the default channel every domain service
 * listens to. Services find both by name ([KopipesStack.importBus], [KopipesStack.importChannel]), so deploy the
 * hub first.
 *
 * Routing:
 * - **Default channel** ([Channels.DEFAULT]): internal events, except `fault`, partitioned by `partitionKey`, so a
 *   consumer sees one entity's events in order.
 * - **Faults** go to the [FaultMonitor], and the **event lake** gets everything but faults; each of those stacks
 *   owns its own rule on this bus.
 */
class EventHub @JvmOverloads constructor(stack: KopipesStack, id: String, props: EventHubProps = EventHubProps()) : Construct(stack, id) {

    val bus: EventBus = EventBus.Builder.create(this, "Bus")
        .eventBusName(stack.hub.busName)
        .build()

    val defaultChannel: Stream = Stream.Builder.create(this, "DefaultChannel")
        .streamName(stack.hub.channelName(Channels.DEFAULT))
        .retentionPeriod(props.channelRetention)
        .shardCount(props.shardCount)
        .encryption(StreamEncryption.MANAGED)
        .build()

    init {
        Rule.Builder.create(this, "DefaultChannelRule")
            .ruleName("${stack.hub.busName}-${Channels.DEFAULT}-rule")
            .eventBus(bus)
            .eventPattern(
                EventPattern.builder()
                    .source(Match.anythingBut(EnvVars.EXTERNAL_SOURCE))
                    .detailType(Match.anythingBut(FAULT))
                    .build()
            )
            .targets(
                listOf(
                    KinesisStream.Builder.create(defaultChannel)
                        .partitionKeyPath("$.detail.partitionKey")
                        .message(RuleTargetInput.fromEventPath("$.detail"))
                        .build()
                )
            )
            .build()

        props.archiveRetention?.let { retention ->
            Archive.Builder.create(this, "Archive")
                .archiveName("${stack.hub.busName}-archive")
                .sourceEventBus(bus)
                .eventPattern(EventPattern.builder().detailType(Match.anythingBut(FAULT)).build())
                .retention(retention)
                .build()
        }

        if (props.logEvents) {
            logTo("Events", "/aws/events/${stack.hub.busName}-events", Match.anythingBut(FAULT), props.logRetention)
            logTo("Faults", "/aws/events/${stack.hub.busName}-faults", listOf(FAULT), props.logRetention)
        }
    }

    private fun logTo(id: String, logGroupName: String, detailType: List<String>, retention: RetentionDays) {
        val logGroup = LogGroup.Builder.create(this, "LogGroup$id")
            .logGroupName(logGroupName)
            .retention(retention)
            .build()
        Rule.Builder.create(this, "LogRule$id")
            .eventBus(bus)
            .eventPattern(EventPattern.builder().detailType(detailType).build())
            .targets(listOf(CloudWatchLogGroup(logGroup)))
            .build()
    }

    companion object {
        /** The detail type of the framework's fault events. */
        const val FAULT = "fault"
    }
}
