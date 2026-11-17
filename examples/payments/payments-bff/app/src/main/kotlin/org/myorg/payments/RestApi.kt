package org.myorg.payments

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent
import io.kopipes.aws.AwsKopipesContext
import io.kopipes.core.utils.loggedLazy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mu.KotlinLogging

/** The REST API's wiring: the views table and a clock. */
class RestApiContainer(
    val store: PaymentsStore,
    val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        /** The production wiring: the table named by `ENTITY_TABLE_NAME`. */
        fun build(): RestApiContainer {
            val context = AwsKopipesContext()
            val table = context.envConfig.entityTableName() ?: error("ENTITY_TABLE_NAME is not set")
            return RestApiContainer(DynamoDbPaymentsStore(context.dynamoDbClientFactory.getClient("restapi"), table))
        }
    }
}

/**
 * The customer's only way in.
 *
 * | Method and path        | Response                                  |
 * |------------------------|-------------------------------------------|
 * | `PUT /payments/{id}`   | `202` + the view (`status: SUBMITTED`)    |
 * | `GET /payments/{id}`   | `200` + the view; `404` if unknown        |
 * | `GET /payments`        | `200` + the customer's views, newest first |
 *
 * `PUT` only records the payment. `PAYMENT_SUBMITTED` is published from the table's stream (see [TriggerContainer]),
 * so once the API has answered `202`, the event will be published, even if the bus is down right now.
 *
 * The caller is the `X-Customer-Id` header: a stand-in for an access token so the example stays on events.
 * **It is not authentication and must not ship.**
 */
class RestApi(
    containerFactory: () -> RestApiContainer = { RestApiContainer.build() },
) : RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private val logger = KotlinLogging.logger { }
    private val container by loggedLazy(name = "RestApiContainer", logger = logger, initializer = containerFactory)

    override fun handleRequest(input: APIGatewayProxyRequestEvent, context: Context): APIGatewayProxyResponseEvent =
        runBlocking {
            logger.info { "${input.httpMethod} ${input.path}" }
            try {
                route(input)
            } catch (e: Exception) {
                logger.error(e) { "Unexpected error" }
                respond(500, ApiError("internal-error", "something went wrong"))
            }
        }

    private suspend fun route(input: APIGatewayProxyRequestEvent): APIGatewayProxyResponseEvent {
        val customerId = header(input, "X-Customer-Id")?.takeIf { it.isNotBlank() }
            ?: return respond(401, ApiError(ApiError.UNAUTHENTICATED, "X-Customer-Id is required"))

        val segments = input.path.orEmpty().trim('/').split('/').filter { it.isNotEmpty() }
        return when {
            segments == listOf("payments") && input.httpMethod == "GET" -> list(customerId)
            segments.size == 2 && segments[0] == "payments" && input.httpMethod == "PUT" ->
                put(customerId, segments[1], input.body)
            segments.size == 2 && segments[0] == "payments" && input.httpMethod == "GET" -> get(customerId, segments[1])
            else -> respond(404, ApiError(ApiError.NOT_FOUND, "no such resource"))
        }
    }

    private suspend fun put(customerId: String, id: String, body: String?): APIGatewayProxyResponseEvent {
        val request = when (val validation = validate(id, customerId, body)) {
            is Validation.Invalid -> return respond(400, validation.error)
            is Validation.Valid -> validation.request
        }
        return when (val outcome = container.store.submit(request, container.clock())) {
            is SubmitOutcome.Accepted -> respond(202, viewOf(outcome.record))
            is SubmitOutcome.AlreadyExists -> {
                val existing = outcome.record
                // The same request again is the same payment: answer with what's stored.
                if (existing != null && existing.requestHash == request.hash) respond(202, viewOf(existing))
                else respond(409, ApiError(ApiError.CONFLICT, "payment $id was already submitted with a different body"))
            }
        }
    }

    private suspend fun get(customerId: String, id: String): APIGatewayProxyResponseEvent {
        val record = container.store.find(id)
        // Another customer's payment is not found, not forbidden, so ids can't be probed.
        if (record == null || record.payment.customerId != customerId) {
            return respond(404, ApiError(ApiError.NOT_FOUND, "no such payment"))
        }
        return respond(200, viewOf(record))
    }

    private suspend fun list(customerId: String): APIGatewayProxyResponseEvent =
        respond(200, container.store.listByCustomer(customerId).map(::viewOf))

    private fun header(input: APIGatewayProxyRequestEvent, name: String): String? =
        input.headers?.entries?.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
            ?: input.multiValueHeaders?.entries?.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    companion object {
        private val responseJson = Json { explicitNulls = false; encodeDefaults = true }

        internal inline fun <reified T> respond(status: Int, body: T): APIGatewayProxyResponseEvent =
            APIGatewayProxyResponseEvent()
                .withStatusCode(status)
                .withHeaders(mapOf("Content-Type" to "application/json"))
                .withBody(responseJson.encodeToString(body))
    }
}
