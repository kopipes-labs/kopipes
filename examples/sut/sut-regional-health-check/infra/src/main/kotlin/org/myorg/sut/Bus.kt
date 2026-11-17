package org.myorg.sut

import software.amazon.awscdk.services.events.CfnRule
import software.amazon.awscdk.services.events.EventBus
import software.amazon.awscdk.services.iam.*
import software.amazon.awscdk.services.logs.LogGroup
import software.amazon.awscdk.services.logs.RetentionDays

fun RegionalHealthCheckStack.newBus(): EventBus =
    EventBus.Builder
        .create(this, "Bus")
        .eventBusName("${service}-${stage}-bus")
        .build()

fun RegionalHealthCheckStack.grantAccessToBus(
    grantee: IGrantable,
    bus: EventBus,
) {
    bus.grantPutEventsTo(grantee)
}

fun RegionalHealthCheckStack.logToCloudWatch(bus: EventBus) {
    val logGroupEvents = LogGroup.Builder.create(this, "LogGroupEvents")
        .retention(RetentionDays.ONE_MONTH)
        .logGroupName("/aws/events/${service}-${stage}-events")
        .build()

    val logGroupFaults = LogGroup.Builder.create(this, "LogGroupFaults")
        .retention(RetentionDays.ONE_MONTH)
        .logGroupName("/aws/events/${service}-${stage}-faults")
        .build()

    val logRole = Role.Builder.create(this, "LogRole")
        .roleName("${service}-${stage}-${regionName}-log-role")
        .assumedBy(ServicePrincipal("events.amazonaws.com"))
        .inlinePolicies(
            mapOf(
                "${service}-${stage}-log" to PolicyDocument.Builder.create()
                    .statements(
                        listOf(
                            PolicyStatement.Builder.create()
                                .effect(Effect.ALLOW)
                                .actions(listOf("logs:CreateLogStream"))
                                .resources(
                                    listOf(
                                        "${logGroupEvents.logGroupArn}:*",
                                        "${logGroupFaults.logGroupArn}:*",
                                    )
                                )
                                .build(),
                            PolicyStatement.Builder.create()
                                .effect(Effect.ALLOW)
                                .actions(listOf("logs:PutLogEvents"))
                                .resources(
                                    listOf(
                                        "${logGroupEvents.logGroupArn}:log-stream:*",
                                        "${logGroupFaults.logGroupArn}:log-stream:*",
                                    )
                                )
                                .build(),
                        )
                    )
                    .build()
            )
        )
        .build()

    CfnRule.Builder.create(this, "LogRuleEvents")
        .eventBusName(bus.eventBusName)
        .eventPattern(
            mapOf(
                "detail" to mapOf(
                    "type" to listOf(
                        mapOf("anything-but" to "fault")
                    )
                )
            )
        )
        .state("ENABLED")
        .roleArn(logRole.roleArn)
        .targets(
            listOf(
                CfnRule.TargetProperty.builder()
                    .id("LogEvents")
                    .arn(logGroupEvents.logGroupArn)
                    .build()
            )
        )
        .build()

    CfnRule.Builder.create(this, "LogRuleFaults")
        .eventBusName(bus.eventBusName)
        .eventPattern(
            mapOf(
                "detail" to mapOf(
                    "type" to listOf("fault")
                )
            )
        )
        .state("ENABLED")
        .roleArn(logRole.roleArn)
        .targets(
            listOf(
                CfnRule.TargetProperty.builder()
                    .id("LogFaults")
                    .arn(logGroupFaults.logGroupArn)
                    .build()
            )
        )
        .build()
}