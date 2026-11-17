package org.myorg.sut

import io.kopipes.cdk.EventHub
import io.kopipes.cdk.KopipesStack
import io.kopipes.cdk.ServiceProps
import software.constructs.Construct

/**
 * The sut subsystem's event hub: the bus every service publishes to, the default channel `s1` the services listen
 * to, the archive and the log groups. See the `cdk` module's `EventHub`.
 */
class EventHubStack(scope: Construct, serviceProps: ServiceProps) : KopipesStack(scope, serviceProps) {
    val eventHub = EventHub(this, "Hub")
}
