package org.myorg.payments

import io.kopipes.cdk.EventHub
import io.kopipes.cdk.KopipesStack
import io.kopipes.cdk.ServiceProps
import software.constructs.Construct

/**
 * The only thing connecting the services: one bus, and the default channel every domain service reads.
 * Deploy it first; every other stack finds it by name. The fault monitor and the event lake own their rules on it.
 */
class EventHubStack(scope: Construct, props: ServiceProps) : KopipesStack(scope, props) {
    val eventHub = EventHub(this, "Hub")
}
