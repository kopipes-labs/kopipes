package org.myorg.payments

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent
import com.amazonaws.services.lambda.runtime.events.KinesisEvent
import io.kopipes.core.utils.loggedLazy
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging

/*
 * The Lambda handlers only build their container, once per Lambda container, and run a batch through it.
 * Tests pass a container built with in-memory fakes.
 */

/** Default channel → collect into the events table. */
class Listener(
    containerFactory: () -> ListenerContainer = { ListenerContainer.build() },
) : RequestHandler<KinesisEvent, Void?> {

    private val logger = KotlinLogging.logger { }
    private val container by loggedLazy(name = "ListenerContainer", logger = logger, initializer = containerFactory)

    override fun handleRequest(input: KinesisEvent, context: Context): Void? = runBlocking {
        container.assembler
            .assemble(container.kinesisAdapter.fromKinesis(input))
            .collect { logger.info { "collected ${it.event?.eventType()} ${it.event?.id}" } }
        null
    }
}

/** The events table's stream → correlate, deadlines, rules → the bus. */
class Trigger(
    containerFactory: () -> TriggerContainer = { TriggerContainer.build() },
) : RequestHandler<DynamodbEvent, Void?> {

    private val logger = KotlinLogging.logger { }
    private val container by loggedLazy(name = "TriggerContainer", logger = logger, initializer = containerFactory)

    override fun handleRequest(input: DynamodbEvent, context: Context): Void? = runBlocking {
        container.assembler
            .assemble(container.dynamoDbAdapter.fromDynamoDB(input))
            .collect { logger.info { "${it.pipeline?.id}: ${it.event?.eventType()} ${it.event?.id}" } }
        null
    }
}
