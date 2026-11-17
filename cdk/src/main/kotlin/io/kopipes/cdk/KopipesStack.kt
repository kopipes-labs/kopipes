package io.kopipes.cdk

import software.amazon.awscdk.Aws
import software.amazon.awscdk.Stack
import software.amazon.awscdk.services.events.EventBus
import software.amazon.awscdk.services.events.IEventBus
import software.amazon.awscdk.services.kinesis.IStream
import software.amazon.awscdk.services.kinesis.Stream
import software.constructs.Construct

/**
 * The names of a subsystem's event hub, so every service can find the hub without passing references between stacks.
 * The hub's stack is the `<subsys>-event-hub` service.
 */
data class HubNames(val subsys: String, val stage: Stage) {
    val service: String get() = "$subsys-event-hub"
    val busName: String get() = "$service-$stage-bus"

    /** A Kinesis channel of the hub, by its short name, e.g. [Channels.DEFAULT]. */
    fun channelName(channel: String): String = "$service-$stage-$channel"
}

/** The hub's Kinesis channels. */
object Channels {
    /** Internal events (source not `external`), except faults. The channel every domain service listens to. */
    const val DEFAULT = "s1"
}

/**
 * Base class for a kopipes service's stack. Its id is `<service>-<stage>`, the name `cdk deploy` takes. It gives
 * resources consistent names and finds the subsystem's event hub by name.
 */
open class KopipesStack(scope: Construct, val serviceProps: ServiceProps) :
    Stack(scope, "${serviceProps.service}-${serviceProps.stage}") {

    val org: String get() = serviceProps.org
    val subsys: String get() = serviceProps.subsys
    val service: String get() = serviceProps.service
    val stage: Stage get() = serviceProps.stage
    val regionName: String get() = serviceProps.region

    val hub: HubNames = HubNames(serviceProps.subsys, serviceProps.stage)

    /** `<service>-<stage>-<suffix>`: the name of a resource that only needs to be unique in the account and region. */
    fun name(suffix: String): String = "$service-$stage-$suffix"

    /** `<org>-<service>-<stage>-<region>`: a globally unique name, for S3 buckets. */
    fun globalName(): String = "$org-$service-$stage-$regionName"

    /** The subsystem's bus. Imported once per stack. */
    fun importBus(): IEventBus =
        node.tryFindChild(IMPORTED_BUS_ID) as? IEventBus
            ?: EventBus.fromEventBusName(this, IMPORTED_BUS_ID, hub.busName)

    /** One of the hub's Kinesis channels. Imported once per stack. */
    fun importChannel(channel: String = Channels.DEFAULT): IStream {
        val id = "ImportedChannel-$channel"
        return node.tryFindChild(id) as? IStream
            ?: Stream.fromStreamArn(
                this,
                id,
                "arn:${Aws.PARTITION}:kinesis:${Aws.REGION}:${Aws.ACCOUNT_ID}:stream/${hub.channelName(channel)}",
            )
    }

    private companion object {
        const val IMPORTED_BUS_ID = "ImportedBus"
    }
}
