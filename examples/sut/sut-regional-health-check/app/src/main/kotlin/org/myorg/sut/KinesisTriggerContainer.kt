package org.myorg.sut

import io.kopipes.aws.AwsKopipesContext
import io.kopipes.aws.from.KinesisAdapter
import io.kopipes.aws.sinks.DynamoDbEntityStore
import io.kopipes.core.PipelineAssembler
import io.kopipes.core.flavors.Pipeline
import io.kopipes.core.flavors.UpdatePipeline

class KinesisTriggerContainer(val context: AwsKopipesContext) {

    companion object {

        fun build() : KinesisTriggerContainer {
            return KinesisTriggerContainer(AwsKopipesContext())
        }
    }

    private  val updatePipeline: Pipeline by lazy {
        UpdatePipeline.builder()
            .id("update")
            .entityStore(DynamoDbEntityStore(context))
            .toUpdate(::toUpdate)
            .build()
    }

    val assembler: PipelineAssembler by lazy {
        PipelineAssembler
            .builder()
            .context(context)
            .addPipeline(updatePipeline)
            .build()
    }

    val kinesisAdapter: KinesisAdapter by lazy {
        KinesisAdapter(context, TracerEventCodec)
    }

}
