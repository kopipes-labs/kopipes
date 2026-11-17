package org.myorg.payments

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import com.amazonaws.services.lambda.runtime.RequestStreamHandler
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent
import com.amazonaws.services.lambda.runtime.events.KinesisEvent
import io.kopipes.aws.from.ResubmittedRecords
import io.kopipes.core.JsonRaw
import io.kopipes.core.UnitOfWork
import io.kopipes.core.utils.loggedLazy
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import mu.KotlinLogging
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64

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
 *
 * **Resubmission:** the function is also invoked directly by the resubmit tool, with the bodies of the faults it
 * published: `{"Records": [{"value": "<body>"}]}`. Those bodies were verified when they first arrived, and only
 * someone allowed to invoke the function can send them, so they skip the signature (whose timestamp has long
 * expired anyway). Each goes down the same pipeline as before. If it can't be handled yet the invocation fails, and
 * the tool retries it or reports it. That's why this handler reads a stream: one function, two kinds of input.
 */
class Webhook(
    containerFactory: () -> WebhookContainer = { WebhookContainer.build() },
) : RequestStreamHandler {

    private val logger = KotlinLogging.logger { }
    private val container by loggedLazy(name = "WebhookContainer", logger = logger, initializer = containerFactory)

    override fun handleRequest(input: InputStream, output: OutputStream, context: Context) {
        val request = Json.parseToJsonElement(input.readBytes().decodeToString())
        val response = ResubmittedRecords.jsonRawRecordsOf(request)?.let { resubmit(it) } ?: handle(apiRequestOf(request))
        output.write(apiResponseJson(response).encodeToByteArray())
    }

    /** A request from API Gateway: the card processor calling. */
    fun handle(input: APIGatewayProxyRequestEvent): APIGatewayProxyResponseEvent = runBlocking {
        if (input.httpMethod != "POST" || input.path?.trimEnd('/') != "/webhooks/card-processor") {
            return@runBlocking respond(404, "no such resource")
        }
        val body = input.body.orEmpty()
        val signature = input.headers?.entries?.firstOrNull { it.key.equals(CardSignature.HEADER, ignoreCase = true) }?.value
        if (!CardSignature.verify(container.secret, signature, body, container.clock() / 1000)) {
            return@runBlocking respond(401, "invalid signature")
        }
        try {
            publish(listOf(WebhookContainer.recordOf(body)))
            respond(200, "ok")
        } catch (e: Exception) {
            logger.warn(e) { "webhook not handled yet; the processor will send it again" }
            respond(503, "not ready, send it again later")
        }
    }

    /** Bodies the resubmit tool sends back. Throws if one can't be handled yet, so the tool retries it. */
    fun resubmit(records: List<JsonRaw>): APIGatewayProxyResponseEvent = runBlocking {
        publish(records)
        respond(200, "resubmitted ${records.size}")
    }

    private suspend fun publish(records: List<JsonRaw>) {
        container.assembler
            .assemble(records.asFlow().map { UnitOfWork(record = it) })
            .collect { logger.info { "published ${it.event?.eventType()} ${it.event?.id}" } }
    }

    private fun respond(status: Int, message: String) = APIGatewayProxyResponseEvent()
        .withStatusCode(status)
        .withHeaders(mapOf("Content-Type" to "application/json"))
        .withBody("""{"message":"$message"}""")
}

/** The parts of API Gateway's proxy request the webhook reads. */
internal fun apiRequestOf(json: JsonElement): APIGatewayProxyRequestEvent {
    val request = json.jsonObject
    fun string(name: String) = (request[name] as? JsonPrimitive)?.contentOrNull
    val body = string("body")?.let {
        if ((request["isBase64Encoded"] as? JsonPrimitive)?.booleanOrNull == true) Base64.getDecoder().decode(it).decodeToString() else it
    }
    return APIGatewayProxyRequestEvent()
        .withHttpMethod(string("httpMethod"))
        .withPath(string("path"))
        .withHeaders((request["headers"] as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull.orEmpty() })
        .withBody(body)
}

/** API Gateway's proxy response, as it expects it back. */
internal fun apiResponseJson(response: APIGatewayProxyResponseEvent): String = buildJsonObject {
    put("statusCode", response.statusCode)
    put("headers", JsonObject(response.headers.orEmpty().mapValues { (_, v) -> JsonPrimitive(v) }))
    put("body", response.body)
}.toString()
