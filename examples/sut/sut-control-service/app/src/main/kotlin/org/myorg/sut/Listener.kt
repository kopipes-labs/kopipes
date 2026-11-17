package org.myorg.sut

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import com.amazonaws.services.lambda.runtime.events.KinesisEvent
import io.kopipes.aws.metrics.EmfReporter
import io.kopipes.core.metrics.collectMetrics
import io.kopipes.core.metrics.updateMetrics
import io.kopipes.core.utils.loggedLazy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging

class Listener(
    containerFactory: () -> ListenerContainer = { ListenerContainer.build() },
) : RequestHandler<KinesisEvent, Void?> {

    private val logger = KotlinLogging.logger {  }

    private val container: ListenerContainer by loggedLazy(
        name = "ListenerContainer",
        logger = logger,
        initializer = containerFactory,
    )
    override fun handleRequest(kinesisEvent: KinesisEvent, context: Context): Void? = runBlocking {
        logger.info { "Listener invoked with ${kinesisEvent.records?.size ?: 0} Kinesis records" }

        val assembler = container.assembler
        val headFlow = container.kinesisAdapter
            .fromKinesis(kinesisEvent)

        assembler
            .assemble(headFlow)
            .map { uow ->
                uow.updateMetrics { it.gauge("custom.metric", 1.0) }
            }
            .collectMetrics(reporter = EmfReporter(container.context.envConfig))
            .collect { logger.info { "collected " + it.event?.id } }
        null
    }
}