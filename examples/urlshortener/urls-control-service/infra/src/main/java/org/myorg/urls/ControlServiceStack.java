package org.myorg.urls;

import io.kopipes.cdk.EnvVars;
import io.kopipes.cdk.EventsTable;
import io.kopipes.cdk.KopipesFunction;
import io.kopipes.cdk.KopipesFunctionProps;
import io.kopipes.cdk.KopipesStack;
import io.kopipes.cdk.ServiceProps;
import software.amazon.awscdk.services.dynamodb.ITableV2;
import software.amazon.awscdk.services.dynamodb.TableV2;
import software.amazon.awscdk.services.lambda.Code;
import software.constructs.Construct;

/**
 * Collects the url events and keeps the access counts.
 *
 * <pre>
 *     default channel ─► listener ─► events table ─► (stream) ─► trigger ─► urls table (access counts)
 * </pre>
 */
public class ControlServiceStack extends KopipesStack {

    public final EventsTable events;
    public final KopipesFunction listener;
    public final KopipesFunction trigger;

    public ControlServiceStack(Construct scope, ServiceProps serviceProps) {
        super(scope, serviceProps);

        Code jar = Code.fromAsset("../app/build/libs/serverless.jar");

        this.events = new EventsTable(this, "Events");

        this.listener = new KopipesFunction(this, "listener",
                new KopipesFunctionProps(jar, "org.myorg.urls.ControlListenerHandler::handleRequest"))
                .listenTo()
                .uses(events);

        // The BFF's table, where the trigger counts accesses. Found by name: the BFF's stack owns it.
        ITableV2 urls = TableV2.fromTableName(this, "Urls", getSubsys() + "-url-bff-" + getStage() + "-table");

        this.trigger = new KopipesFunction(this, "trigger",
                new KopipesFunctionProps(jar, "org.myorg.urls.ControlTriggerHandler::handleRequest"))
                .triggeredBy(events)
                .uses(events)
                .uses(urls, EnvVars.ENTITY_TABLE_NAME);
    }
}
