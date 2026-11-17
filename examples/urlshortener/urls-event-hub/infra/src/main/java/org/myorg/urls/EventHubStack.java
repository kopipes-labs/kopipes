package org.myorg.urls;

import io.kopipes.cdk.EventHub;
import io.kopipes.cdk.KopipesStack;
import io.kopipes.cdk.ServiceProps;
import software.constructs.Construct;

/**
 * The urls subsystem's event hub: the bus every service publishes to, the default channel {@code s1} the services
 * listen to, the archive and the log groups. See the {@code cdk} module's {@code EventHub}.
 */
public class EventHubStack extends KopipesStack {

    public final EventHub eventHub;

    public EventHubStack(Construct scope, ServiceProps serviceProps) {
        super(scope, serviceProps);
        this.eventHub = new EventHub(this, "Hub");
    }
}
