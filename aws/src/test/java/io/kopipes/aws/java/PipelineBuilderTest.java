package io.kopipes.aws.java;

import io.kopipes.aws.AwsKopipesContext;
import io.kopipes.aws.flavors.CdcPipeline;
import io.kopipes.core.UnitOfWork;
import io.kopipes.aws.sinks.DynamoDbEntityStore;
import io.kopipes.core.flavors.CollectPipeline;
import io.kopipes.core.flavors.UpdatePipeline;
import io.kopipes.core.sinks.EntityStore;
import io.kopipes.core.sinks.EventPublisher;
import io.kopipes.core.sinks.EventsMicrostore;
import kotlinx.coroutines.flow.Flow;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PipelineBuilderTest {

    @Test
    public void testCdcPipelineBuilder() {
        EventPublisher publisher = new EventPublisher() {
            @Override
            public @NotNull Flow<UnitOfWork> publish(@NotNull Flow<UnitOfWork> uows) {
                return uows;
            }
        };

        CdcPipeline pipeline = CdcPipeline.builder()
                .id("test-cdc")
                .eventPublisher(publisher)
                .toEventJava(uow -> null)
                .build();

        assertEquals("test-cdc", pipeline.getId());
    }

    @Test
    public void testUpdatePipelineBuilder() {
        UpdatePipeline pipeline = UpdatePipeline.builder()
                .id("test-update")
                .entityStore(new DynamoDbEntityStore(new AwsKopipesContext()))
                .toQueryJava(uow -> new EntityStore.Query("p1"))
                .toUpdate(uow -> new EntityStore.Update(
                        new EntityStore.Key("p1", "s1"),
                        java.util.Map.of("status", "DONE")))
                .build();

        assertEquals("test-update", pipeline.getId());
    }

    @Test
    public void testCollectPipelineBuilder() {
        EventsMicrostore microstore = new EventsMicrostore() {
            @Override
            public Flow<UnitOfWork> save(Flow<UnitOfWork> uows) { return uows; }
            @Override
            public Flow<UnitOfWork> queryByPk(Flow<UnitOfWork> uows) { return uows; }
        };

        CollectPipeline pipeline = CollectPipeline.builder()
                .id("test-collect")
                .eventsMicrostore(microstore)
                .build();

        assertEquals("test-collect", pipeline.getId());
    }
}
