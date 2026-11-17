package io.kopipes.cdk

import software.amazon.awscdk.services.events.EventBus
import software.amazon.awscdk.services.events.EventPattern
import software.amazon.awscdk.services.events.Match
import software.amazon.awscdk.services.events.Rule
import software.amazon.awscdk.services.events.targets.EventBus as EventBusTarget
import software.constructs.Construct

/**
 * The egress rule of a subsystem's hub: forwards its **external** events whose type starts with [typePrefix]
 * (`PAYMENTS.`) to another subsystem's bus. Owned by the egress gateway that publishes them, one per downstream
 * subsystem. EventBridge retries delivery while the other bus is unreachable.
 *
 * Checking the prefix, not just `source = external`, keeps it right once events come *in* from other subsystems:
 * theirs are never forwarded on.
 */
class EventForwarding(stack: KopipesStack, id: String, typePrefix: String, targetBusName: String) : Construct(stack, id) {

    val rule: Rule = Rule.Builder.create(this, "Rule")
        .ruleName(stack.name("$id-rule"))
        .eventBus(stack.importBus())
        .eventPattern(
            EventPattern.builder()
                .source(listOf(EnvVars.EXTERNAL_SOURCE))
                .detailType(Match.prefix(typePrefix))
                .build()
        )
        .targets(listOf(EventBusTarget(EventBus.fromEventBusName(this, "Target", targetBusName))))
        .build()
}
