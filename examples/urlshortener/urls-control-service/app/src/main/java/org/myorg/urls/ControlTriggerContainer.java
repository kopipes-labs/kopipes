package org.myorg.urls;

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient;
import io.kopipes.aws.AwsKopipesContext;
import io.kopipes.aws.from.DynamodbAdapter;
import io.kopipes.aws.sinks.DynamoDbEventsMicrostore;
import io.kopipes.core.Event;
import io.kopipes.core.PipelineAssembler;
import io.kopipes.core.UnitOfWork;
import io.kopipes.core.flavors.CorrelatePipeline;
import io.kopipes.core.flavors.EvaluatePipeline;
import io.kopipes.core.sinks.EventsMicrostore;

import java.util.List;
import java.util.Objects;

public class ControlTriggerContainer {
    public final AwsKopipesContext context;
    public final PipelineAssembler assembler;
    public final DynamodbAdapter dynamoDbAdapter;
    public final UrlDao urlDao;

    public ControlTriggerContainer(AwsKopipesContext context, EventsMicrostore eventsMicrostore, UrlDao urlDao) {
        this.context = context;
        this.urlDao = urlDao;
        CorrelatePipeline correlate = CorrelatePipeline
                .builder()
                .id("correlate")
                .eventsMicrostore(eventsMicrostore)
                .eventCodec(JacksonEventCodec.INSTANCE)
                .isCollectedEvent(DynamodbAdapter::forCollectedEvents)
                .normalizer(DynamodbAdapter.normalize(JacksonEventCodec.INSTANCE))
                .correlationKeySupplier(this::correlationKey)
                .build();

        EvaluatePipeline evaluate = EvaluatePipeline
                .builder()
                .id("evaluate")
                .eventsMicrostore(eventsMicrostore)
                .eventCodec(JacksonEventCodec.INSTANCE)
                .expression(this::shouldProcess)
                .emit(this::processEvent)
                .build();

        this.assembler = PipelineAssembler.builder()
                .context(context)
                .addPipeline(correlate)
                .addPipeline(evaluate)
                .build();
        this.dynamoDbAdapter = new DynamodbAdapter(context);
    }

    private String correlationKey(UnitOfWork uow) {
        Event event = Objects.requireNonNull(uow.getEvent(), "event is required");
        return Objects.requireNonNull(event.getPartitionKey(), "event.partitionKey is required");
    }

    private Boolean shouldProcess(UnitOfWork uow) {
        return uow.getEvent() instanceof UrlAccessedEvent;
    }

    private List<Event> processEvent(UnitOfWork uow) {
        UrlAccessedEvent event = (UrlAccessedEvent) uow.getEvent();
        urlDao.incrementAccessCount(event.entity().shortUrl());
        return List.of();
    }

    public static ControlTriggerContainer build() {
        String urlsTableName = System.getenv("ENTITY_TABLE_NAME");
        if (urlsTableName == null) urlsTableName = "urls-dev-entities";
        AwsKopipesContext context = new AwsKopipesContext();
        DynamoDbClient client = context.getDynamoDbClientFactory().getClient("urls-control-service");

        EventsMicrostore eventsMicrostore = new DynamoDbEventsMicrostore(context);
        UrlDao urlDao = new UrlDao(client, urlsTableName);

        return new ControlTriggerContainer(context, eventsMicrostore, urlDao);
    }
}
