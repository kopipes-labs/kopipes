package org.myorg.urls;

import io.kopipes.aws.AwsKopipesContext;
import io.kopipes.aws.from.KinesisAdapter;
import io.kopipes.aws.sinks.DynamoDbEventsMicrostore;
import io.kopipes.core.PipelineAssembler;
import io.kopipes.core.flavors.CollectPipeline;
import io.kopipes.core.sinks.EventsMicrostore;

public class ControlListenerContainer {
    public final AwsKopipesContext context;
    public final EventsMicrostore eventsMicrostore;
    public final PipelineAssembler assembler;
    public final KinesisAdapter kinesisAdapter;

    public ControlListenerContainer(AwsKopipesContext context, EventsMicrostore eventsMicrostore) {
        this.context = context;
        this.eventsMicrostore = eventsMicrostore;
        CollectPipeline collectPipeline = CollectPipeline
                .builder()
                .id("coll1")
                .eventsMicrostore(eventsMicrostore)
                .build();
        this.assembler = PipelineAssembler
                .builder()
                .context(context)
                .addPipeline(collectPipeline)
                .build();
        this.kinesisAdapter = new KinesisAdapter(context, JacksonEventCodec.INSTANCE);
    }

    public static ControlListenerContainer build() {
        AwsKopipesContext context = new AwsKopipesContext();
        return new ControlListenerContainer(context, new DynamoDbEventsMicrostore(context));
    }
}
