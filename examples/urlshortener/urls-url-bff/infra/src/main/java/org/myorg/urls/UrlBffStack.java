package org.myorg.urls;

import io.kopipes.cdk.KopipesFunction;
import io.kopipes.cdk.KopipesFunctionProps;
import io.kopipes.cdk.KopipesStack;
import io.kopipes.cdk.ServiceProps;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.services.dynamodb.Attribute;
import software.amazon.awscdk.services.dynamodb.AttributeType;
import software.amazon.awscdk.services.dynamodb.Billing;
import software.amazon.awscdk.services.dynamodb.StreamViewType;
import software.amazon.awscdk.services.dynamodb.TableV2;
import software.amazon.awscdk.services.lambda.Code;
import software.constructs.Construct;

/**
 * The urls' BFF: create, change and delete urls, and redirect.
 *
 * <pre>
 *     caller ─► restapi ─► urls table ─► (stream) ─► trigger ─► bus
 * </pre>
 *
 * The urls table is keyed by {@code pk} alone, the short url, so it isn't an {@code EventsTable}: any table with a
 * stream works with {@code triggeredBy} and {@code uses}.
 */
public class UrlBffStack extends KopipesStack {

    /** The env var the BFF's {@code UrlBffContainer} reads its table name from. */
    static final String TABLE_NAME = "TABLE_NAME";

    public final TableV2 urls;
    public final KopipesFunction restApi;
    public final KopipesFunction trigger;

    public UrlBffStack(Construct scope, ServiceProps serviceProps) {
        super(scope, serviceProps);

        Code jar = Code.fromAsset("../app/build/libs/serverless.jar");

        this.urls = TableV2.Builder.create(this, "UrlTable")
                .tableName(name("table"))
                .partitionKey(Attribute.builder().name("pk").type(AttributeType.STRING).build())
                .billing(Billing.onDemand())
                .removalPolicy(RemovalPolicy.DESTROY) // For an example project
                .dynamoStream(StreamViewType.NEW_AND_OLD_IMAGES)
                .build();

        this.restApi = new KopipesFunction(this, "restapi",
                new KopipesFunctionProps(jar, "org.myorg.urls.UrlBffHandler::handleRequest"))
                .uses(urls, TABLE_NAME);

        this.trigger = new KopipesFunction(this, "trigger",
                new KopipesFunctionProps(jar, "org.myorg.urls.UrlTriggerHandler::handleRequest"))
                .triggeredBy(urls)
                .uses(urls, TABLE_NAME);
    }
}
