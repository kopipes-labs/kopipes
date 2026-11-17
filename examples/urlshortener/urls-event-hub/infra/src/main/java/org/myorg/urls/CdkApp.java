package org.myorg.urls;

import io.kopipes.cdk.ServiceProps;
import io.kopipes.cdk.Stage;

public class CdkApp {
    public static void main(String[] args) {
        software.amazon.awscdk.App app = new software.amazon.awscdk.App();

        ServiceProps serviceProps = new ServiceProps(
            "myorg",
            "urls",
            "urls-event-hub",
            Stage.DEV,
            "us-east-1"
        );
        new EventHubStack(app, serviceProps);
        new EventHubStack(app, new ServiceProps(serviceProps.getOrg(), serviceProps.getSubsys(), serviceProps.getService(),
                Stage.LOCAL, serviceProps.getRegion()));

        app.synth();
    }
}
