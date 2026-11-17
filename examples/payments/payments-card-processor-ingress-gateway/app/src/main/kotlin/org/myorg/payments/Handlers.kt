package org.myorg.payments

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent
import com.amazonaws.services.lambda.runtime.events.KinesisEvent
import io.kopipes.core.UnitOfWork
import io.kopipes.core.utils.loggedLazy
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging

/** Default channel → remember the card deposits we asked for. */
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

/**
 * `POST /webhooks/card-processor`, called by the card processor only.
 *
 * | Situation                                        | Answer | On our bus              |
 * |--------------------------------------------------|--------|-------------------------|
 * | A valid, signed callback (or the same again)     | `200`  | its event, same id      |
 * | Signed but unreadable                            | `200`  | a fault, with the body  |
 * | A bad or missing signature                       | `401`  | nothing                 |
 * | Its DEPOSIT_REQUESTED not heard yet; bus down    | `503`  | nothing yet             |
 *
 * **Answered means published:** `200` only once the event (or the fault) is on the bus. For a gateway, publishing is
 * the record, so a callback the processor thinks was delivered is never lost. Anything else makes it send again.
 */
class Webhook(
    containerFactory: () -> WebhookContainer = { WebhookContainer.build() },
) : RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private val logger = KotlinLogging.logger { }
    private val container by loggedLazy(name = "WebhookContainer", logger = logger, initializer = containerFactory)

    override fun handleRequest(input: APIGatewayProxyRequestEvent, context: Context): APIGatewayProxyResponseEvent =
        runBlocking {
            if (input.httpMethod != "POST" || input.path?.trimEnd('/') != "/webhooks/card-processor") {
                return@runBlocking respond(404, "no such resource")
            }
            val body = input.body.orEmpty()
            val signature = input.headers?.entries?.firstOrNull { it.key.equals(CardSignature.HEADER, ignoreCase = true) }?.value
            if (!CardSignature.verify(container.secret, signature, body, container.clock() / 1000)) {
                return@runBlocking respond(401, "invalid signature")
            }
            try {
                container.assembler
                    .assemble(flowOf(UnitOfWork(record = WebhookContainer.recordOf(body))))
                    .collect { logger.info { "published ${it.event?.eventType()} ${it.event?.id}" } }
                respond(200, "ok")
            } catch (e: Exception) {
                logger.warn(e) { "webhook not handled yet; the processor will send it again" }
                respond(503, "not ready, send it again later")
            }
        }

    private fun respond(status: Int, message: String) = APIGatewayProxyResponseEvent()
        .withStatusCode(status)
        .withHeaders(mapOf("Content-Type" to "application/json"))
        .withBody("""{"message":"$message"}""")
}
