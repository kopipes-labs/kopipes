package org.myorg.urls;

import io.kopipes.cdk.ServiceProps;
import io.kopipes.cdk.Stage;

public class CdkApp {
    public static void main(String[] args) {
        software.amazon.awscdk.App app = new software.amazon.awscdk.App();

        ServiceProps serviceProps = new ServiceProps(
            "myorg",
            "urls",
            "urls-control-service",
            Stage.DEV,
            "us-east-1"
        );
        new ControlServiceStack(app, serviceProps);
        new ControlServiceStack(app, new ServiceProps(serviceProps.getOrg(), serviceProps.getSubsys(), serviceProps.getService(),
                Stage.LOCAL, serviceProps.getRegion()));

        app.synth();
    }
}
