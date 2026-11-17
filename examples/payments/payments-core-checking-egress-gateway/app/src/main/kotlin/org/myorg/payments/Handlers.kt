package org.myorg.payments

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.PutItemRequest
import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import com.amazonaws.services.lambda.runtime.events.KinesisEvent
import com.amazonaws.services.lambda.runtime.events.SQSEvent
import io.kopipes.aws.AwsKopipesContext
import io.kopipes.core.utils.loggedLazy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mu.KotlinLogging

/** Default channel → withdrawals and refunds → core checking. */
class Listener(
    containerFactory: () -> EgressContainer = { EgressContainer.build() },
) : RequestHandler<KinesisEvent, Void?> {

    private val logger = KotlinLogging.logger { }
    private val container by loggedLazy(name = "EgressContainer", logger = logger, initializer = containerFactory)

    override fun handleRequest(input: KinesisEvent, context: Context): Void? = runBlocking {
        container.assembler
            .assemble(container.kinesisAdapter.fromKinesis(input))
            .collect { logger.info { "${it.pipeline?.id}: ${it.event?.eventType()} ${it.event?.id}" } }
        null
    }
}

/**
 * Simulation only: plays core checking's part. Writes each delayed ledger row to the ledger the ingress gateway reads,
 * as core checking would. It's the far side of [SimulatedCoreChecking], not part of the gateway.
 */
class Simulator(
    private val ledger: suspend (LedgerEntry) -> Unit = defaultLedger(),
) : RequestHandler<SQSEvent, Void?> {

    private val logger = KotlinLogging.logger { }

    override fun handleRequest(input: SQSEvent, context: Context): Void? = runBlocking {
        input.records.forEach { message ->
            val entry = Json.decodeFromString(LedgerEntry.serializer(), message.body)
            ledger(entry)
            logger.info { "core checking (simulated) posted ${entry.ENTRY_ID} ${entry.TXN_TYP} ${entry.STAT} for ${entry.REF_NO}" }
        }
        null
    }

    companion object {
        fun defaultLedger(): suspend (LedgerEntry) -> Unit {
            val config = EgressConfig()
            val client: DynamoDbClient by lazy { AwsKopipesContext(envConfig = config).dynamoDbClientFactory.getClient("ledger") }
            return { entry ->
                client.putItem(PutItemRequest {
                    tableName = config.ledgerTableName()
                    item = entry.toAttributes().mapValues { AttributeValue.S(it.value) }
                })
            }
        }
    }
}
