package org.myorg.urls;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import io.kopipes.aws.java.Handlers;
import io.kopipes.core.PipelineRunner;

public class UrlTriggerHandler implements RequestHandler<DynamodbEvent, Void> {
    private final UrlTriggerContainer container;

    public UrlTriggerHandler() {
        this.container = UrlTriggerContainer.build();
    }

    public UrlTriggerHandler(UrlTriggerContainer container) {
        this.container = container;
    }

    @Override
    public Void handleRequest(DynamodbEvent input, Context context) {
        new PipelineRunner<DynamodbEvent>(container.assembler)
                .headFlow(container.dynamoDbAdapter::fromDynamoDB)
                .transformer(flow -> Handlers.collectMetrics(flow, container.context))
                .run(input);
        return null;
    }
}
