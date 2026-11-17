package org.myorg.payments

import io.kopipes.cdk.KopipesStack
import io.kopipes.cdk.ServiceProps
import software.amazon.awscdk.Duration
import software.amazon.awscdk.RemovalPolicy
import software.amazon.awscdk.services.events.EventBus
import software.amazon.awscdk.services.events.EventPattern
import software.amazon.awscdk.services.events.Rule
import software.amazon.awscdk.services.events.targets.CloudWatchLogGroup
import software.amazon.awscdk.services.events.targets.SqsQueue
import software.amazon.awscdk.services.logs.LogGroup
import software.amazon.awscdk.services.logs.RetentionDays
import software.amazon.awscdk.services.sqs.Queue
import software.constructs.Construct

/**
 * A stand-in for the Notifications subsystem's hub, which isn't designed here: a bus, and everything that reaches it
 * kept in a log and an inbox queue, so you (and the integration tests) can see what payments told it.
 */
class NotificationsEventHubStack(scope: Construct, props: ServiceProps) : KopipesStack(scope, props) {

    val bus: EventBus = EventBus.Builder.create(this, "Bus").eventBusName(hub.busName).build()

    val inbox: Queue = Queue.Builder.create(this, "Inbox")
        .queueName(name("inbox"))
        .retentionPeriod(Duration.days(1))
        .removalPolicy(RemovalPolicy.DESTROY)
        .build()

    init {
        val log = LogGroup.Builder.create(this, "Log")
            .logGroupName("/aws/events/${hub.busName}-events")
            .retention(RetentionDays.ONE_WEEK)
            .removalPolicy(RemovalPolicy.DESTROY)
            .build()
        Rule.Builder.create(this, "Everything")
            .eventBus(bus)
            .eventPattern(EventPattern.builder().account(listOf(account)).build())
            .targets(listOf(CloudWatchLogGroup(log), SqsQueue(inbox)))
            .build()
    }
}
