package org.myorg.urls;

import io.kopipes.cdk.ServiceProps;
import io.kopipes.cdk.Stage;

public class CdkApp {
    public static void main(String[] args) {
        software.amazon.awscdk.App app = new software.amazon.awscdk.App();

        ServiceProps serviceProps = new ServiceProps(
            "myorg",
            "urls",
            "urls-url-bff",
            Stage.DEV,
            "us-east-1"
        );
        new UrlBffStack(app, serviceProps);
        new UrlBffStack(app, new ServiceProps(serviceProps.getOrg(), serviceProps.getSubsys(), serviceProps.getService(),
                Stage.LOCAL, serviceProps.getRegion()));

        app.synth();
    }
}
