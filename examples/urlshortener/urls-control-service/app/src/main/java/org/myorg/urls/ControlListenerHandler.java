package org.myorg.urls;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.KinesisEvent;
import io.kopipes.aws.java.Handlers;
import io.kopipes.core.PipelineRunner;

public class ControlListenerHandler implements RequestHandler<KinesisEvent, Void> {
    private final ControlListenerContainer container;

    public ControlListenerHandler() {
        this.container = ControlListenerContainer.build();
    }

    public ControlListenerHandler(ControlListenerContainer container) {
        this.container = container;
    }

    @Override
    public Void handleRequest(KinesisEvent input, Context context) {
        new PipelineRunner<KinesisEvent>(container.assembler)
                .headFlow(container.kinesisAdapter::fromKinesis)
                .transformer(flow -> Handlers.collectMetrics(flow, container.context))
                .run(input);
        return null;
    }
}
