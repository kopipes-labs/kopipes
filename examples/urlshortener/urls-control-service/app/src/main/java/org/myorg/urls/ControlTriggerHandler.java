package org.myorg.urls;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import io.kopipes.aws.java.Handlers;
import io.kopipes.core.PipelineRunner;

public class ControlTriggerHandler implements RequestHandler<DynamodbEvent, Void> {
    private final ControlTriggerContainer container;

    public ControlTriggerHandler() {
        this.container = ControlTriggerContainer.build();
    }

    public ControlTriggerHandler(ControlTriggerContainer container) {
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
