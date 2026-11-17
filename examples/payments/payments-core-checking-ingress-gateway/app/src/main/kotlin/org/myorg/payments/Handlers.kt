package org.myorg.payments

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent
import com.amazonaws.services.lambda.runtime.events.KinesisEvent
import io.kopipes.core.utils.loggedLazy
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging

/** Default channel → remember the payments we asked core checking about. */
class Listener(
    containerFactory: () -> ListenerContainer = { ListenerContainer.build() },
) : RequestHandler<KinesisEvent, Void?> {

    private val logger = KotlinLogging.logger { }
    private val container by loggedLazy(name = "ListenerContainer", logger = logger, initializer = containerFactory)

    override fun handleRequest(input: KinesisEvent, context: Context): Void? = runBlocking {
        container.assembler
            .assemble(container.kinesisAdapter.fromKinesis(input))
            .collect { logger.info { "remembered ${it.event?.eventType()} ${it.event?.id}" } }
        null
    }
}

/** Core checking's ledger changes → our events on the bus. */
class Ledger(
    containerFactory: () -> LedgerContainer = { LedgerContainer.build() },
) : RequestHandler<DynamodbEvent, Void?> {

    private val logger = KotlinLogging.logger { }
    private val container by loggedLazy(name = "LedgerContainer", logger = logger, initializer = containerFactory)

    override fun handleRequest(input: DynamodbEvent, context: Context): Void? = runBlocking {
        container.assembler
            .assemble(container.dynamoDbAdapter.fromDynamoDB(input))
            .collect { logger.info { "published ${it.event?.eventType()} ${it.event?.id}" } }
        null
    }
}
