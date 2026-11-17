package org.myorg.payments

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.DeleteItemRequest
import aws.sdk.kotlin.services.dynamodb.model.QueryRequest
import aws.sdk.kotlin.services.eventbridge.EventBridgeClient
import aws.sdk.kotlin.services.eventbridge.model.PutEventsRequest
import aws.sdk.kotlin.services.eventbridge.model.PutEventsRequestEntry
import aws.sdk.kotlin.services.kinesis.KinesisClient
import aws.sdk.kotlin.services.kinesis.model.GetRecordsRequest
import aws.sdk.kotlin.services.kinesis.model.GetShardIteratorRequest
import aws.sdk.kotlin.services.kinesis.model.ShardIteratorType
import aws.sdk.kotlin.services.lambda.LambdaClient
import aws.sdk.kotlin.services.lambda.model.InvokeRequest
import aws.sdk.kotlin.services.sqs.SqsClient
import aws.sdk.kotlin.services.sqs.model.GetQueueUrlRequest
import aws.sdk.kotlin.services.sqs.model.ReceiveMessageRequest
import aws.smithy.kotlin.runtime.net.url.Url
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The payments subsystem as deployed to LocalStack, seen from outside: the customer's REST API, the bus (where the
 * tests play the gateways that aren't built yet), the default channel (where the tests watch what the services
 * publish), the fault monitor's notifications, and the control service's deadlines.
 */
object LocalPayments {
    private const val REGION = "us-east-1"
    private val endpoint = Url.parse("http://localhost:4566")
    private val credentials = StaticCredentialsProvider { accessKeyId = "test"; secretAccessKey = "test" }

    private val lambda by lazy { LambdaClient { region = REGION; endpointUrl = endpoint; credentialsProvider = credentials } }
    private val eventBridge by lazy { EventBridgeClient { region = REGION; endpointUrl = endpoint; credentialsProvider = credentials } }
    private val kinesis by lazy { KinesisClient { region = REGION; endpointUrl = endpoint; credentialsProvider = credentials } }
    private val dynamoDb by lazy { DynamoDbClient { region = REGION; endpointUrl = endpoint; credentialsProvider = credentials } }
    private val sqs by lazy { SqsClient { region = REGION; endpointUrl = endpoint; credentialsProvider = credentials } }

    private val json = Json { ignoreUnknownKeys = true }

    // ---- The customer: the BFF's REST API, invoked as API Gateway would ----

    @Serializable
    data class ApiRequest(
        val httpMethod: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String? = null,
    )

    @Serializable
    data class ApiResponse(val statusCode: Int, val body: String? = null)

    suspend fun api(method: String, path: String, customerId: String = "u-42", body: String? = null): ApiResponse {
        val request = ApiRequest(method, path, mapOf("X-Customer-Id" to customerId, "Content-Type" to "application/json"), body)
        val response = lambda.invoke(InvokeRequest {
            functionName = "payments-bff-local-restapi"
            payload = json.encodeToString(request).toByteArray()
        })
        return json.decodeFromString(response.payload!!.decodeToString())
    }

    fun paymentBody(from: String = "C-1", to: String = "K-2", amount: String = "100.00") =
        """{"from":{"kind":"checking","account":"$from"},"to":{"kind":"card","account":"$to"},"amount":"$amount","currency":"USD"}"""

    suspend fun submit(id: String, customerId: String = "u-42", body: String = paymentBody()): ApiResponse =
        api("PUT", "/payments/$id", customerId, body)

    suspend fun view(id: String, customerId: String = "u-42"): PaymentView? {
        val response = api("GET", "/payments/$id", customerId)
        return if (response.statusCode == 200) json.decodeFromString(response.body!!) else null
    }

    @Serializable
    data class PaymentView(val id: String, val status: String, val reason: String? = null)

    /** Waits until the BFF shows [status] for the payment. */
    suspend fun awaitStatus(id: String, status: String, timeout: Duration = 3.minutes): PaymentView =
        poll(timeout, "payment $id to show $status") { view(id)?.takeIf { it.status == status } }

    // ---- The gateways that aren't built yet: they publish to the bus ----

    /** Publishes [event] as the service [source] would. */
    suspend fun publish(event: PaymentEvent, source: String) {
        val response = eventBridge.putEvents(PutEventsRequest {
            entries = listOf(PutEventsRequestEntry {
                eventBusName = "payments-event-hub-local-bus"
                this.source = source
                detailType = event.eventType()
                detail = event.toString()
            })
        })
        check(response.failedEntryCount == 0) { "Failed to publish ${event.eventType()}: ${response.entries}" }
    }

    // ---- What the services publish: the default channel ----

    /** The events on the default channel for [paymentId], in order. */
    suspend fun channel(paymentId: String): List<PaymentEvent> {
        val iterator = kinesis.getShardIterator(GetShardIteratorRequest {
            streamName = "payments-event-hub-local-s1"
            shardId = "shardId-000000000000"
            shardIteratorType = ShardIteratorType.TrimHorizon
        }).shardIterator
        val events = mutableListOf<PaymentEvent>()
        var next = iterator
        while (next != null) {
            val response = kinesis.getRecords(GetRecordsRequest { shardIterator = next })
            response.records.forEach { record ->
                val text = record.data.decodeToString()
                val paymentIdInRecord = runCatching {
                    json.parseToJsonElement(text).jsonObject["payment"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                if (paymentIdInRecord == paymentId) events += PaymentsEventCodec.decode(text) as PaymentEvent
            }
            next = if (response.records.isEmpty()) null else response.nextShardIterator
        }
        return events
    }

    /** Waits until an event of [type] for the payment is on the default channel, and returns it. */
    suspend fun awaitEvent(paymentId: String, type: String, timeout: Duration = 3.minutes): PaymentEvent =
        poll(timeout, "$type for $paymentId") { channel(paymentId).firstOrNull { it.eventType() == type } }

    // ---- The fault monitor: its notifications, through the queue subscribed to its topic ----

    private val seenFaults = mutableListOf<String>()

    /** Waits for a fault notification that mentions [text]. Firehose buffers faults for about a minute, twice. */
    suspend fun awaitFault(text: String, timeout: Duration = 5.minutes): String {
        val queueUrl = sqs.getQueueUrl(GetQueueUrlRequest {
            queueName = "payments-event-fault-monitor-local-notification-verification.fifo"
        }).queueUrl
        return poll(timeout, "a fault about $text") {
            sqs.receiveMessage(ReceiveMessageRequest {
                this.queueUrl = queueUrl
                maxNumberOfMessages = 10
                waitTimeSeconds = 5
            }).messages.orEmpty().forEach { seenFaults += it.body.orEmpty() }
            seenFaults.firstOrNull { text in it }
        }
    }

    // ---- The control service's deadlines ----

    /**
     * Does what DynamoDB's TTL does once a step's deadline passes: deletes the payment's deadline item. The control
     * service reacts to the deletion as it would in AWS. (LocalStack's own TTL sweep is unreliable, and slow.)
     */
    suspend fun deadlinePasses(paymentId: String, step: String) {
        val table = "payments-control-service-local-events"
        val pk = "$paymentId.$step-deadline"
        val items = poll(1.minutes, "the $step deadline of $paymentId") {
            dynamoDb.query(QueryRequest {
                tableName = table
                keyConditionExpression = "pk = :pk"
                expressionAttributeValues = mapOf(":pk" to AttributeValue.S(pk))
            }).items?.takeIf { it.isNotEmpty() }
        }
        items.forEach { item ->
            dynamoDb.deleteItem(DeleteItemRequest {
                tableName = table
                key = mapOf("pk" to item.getValue("pk"), "sk" to item.getValue("sk"))
            })
        }
    }

    // ---- Waiting ----

    suspend fun <T : Any> poll(timeout: Duration, what: String, attempt: suspend () -> T?): T {
        val deadline = System.currentTimeMillis() + timeout.inWholeMilliseconds
        while (true) {
            attempt()?.let { return it }
            check(System.currentTimeMillis() < deadline) { "Timed out after $timeout waiting for $what" }
            delay(2.seconds)
        }
    }

    fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull
}
