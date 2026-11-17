package org.myorg.payments

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.DeleteItemRequest
import aws.sdk.kotlin.services.dynamodb.model.QueryRequest
import aws.sdk.kotlin.services.dynamodb.model.ScanRequest
import aws.sdk.kotlin.services.eventbridge.EventBridgeClient
import aws.sdk.kotlin.services.eventbridge.model.PutEventsRequest
import aws.sdk.kotlin.services.eventbridge.model.PutEventsRequestEntry
import aws.sdk.kotlin.services.kinesis.KinesisClient
import aws.sdk.kotlin.services.kinesis.model.ExpiredIteratorException
import aws.sdk.kotlin.services.kinesis.model.GetRecordsRequest
import aws.sdk.kotlin.services.kinesis.model.GetShardIteratorRequest
import aws.sdk.kotlin.services.kinesis.model.ShardIteratorType
import aws.sdk.kotlin.services.lambda.LambdaClient
import aws.sdk.kotlin.services.lambda.model.InvokeRequest
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.sqs.SqsClient
import aws.sdk.kotlin.services.sqs.model.DeleteMessageRequest
import aws.sdk.kotlin.services.sqs.model.GetQueueUrlRequest
import aws.sdk.kotlin.services.sqs.model.ReceiveMessageRequest
import aws.smithy.kotlin.runtime.net.url.Url
import io.kopipes.aws.tools.ResubmitFaults
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 *
 * The tests run in parallel (see build.gradle.kts), so what's read once and shared between them (the channel, the
 * fault notifications, the notifications) is guarded by a mutex.
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
    private val s3 by lazy { S3Client { region = REGION; endpointUrl = endpoint; credentialsProvider = credentials; forcePathStyle = true } }

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

    /** Everything read from the default channel so far, and where to read on from; guarded by [channelLock]. */
    private val channelLock = Mutex()
    private val channelRecords = mutableListOf<String>()
    private var channelIterator: String? = null
    private var lastSequence: String? = null

    /**
     * Reads what's new on the default channel since the last call. Reading from the start every time would compete
     * with the services for the shard's few reads per second, and slow them all down.
     */
    private suspend fun readChannel() {
        while (true) {
            val iterator = channelIterator ?: kinesis.getShardIterator(GetShardIteratorRequest {
                streamName = "payments-event-hub-local-s1"
                shardId = "shardId-000000000000"
                if (lastSequence == null) {
                    shardIteratorType = ShardIteratorType.TrimHorizon
                } else {
                    shardIteratorType = ShardIteratorType.AfterSequenceNumber
                    startingSequenceNumber = lastSequence
                }
            }).shardIterator
            val response = try {
                kinesis.getRecords(GetRecordsRequest { shardIterator = iterator; limit = 1000 })
            } catch (e: ExpiredIteratorException) {
                channelIterator = null
                continue
            }
            response.records.forEach { record ->
                channelRecords += record.data.decodeToString()
                lastSequence = record.sequenceNumber
            }
            channelIterator = response.nextShardIterator
            if (response.records.isEmpty() || response.millisBehindLatest == 0L) return
            delay(250)
        }
    }

    /** The events on the default channel for [paymentId], in order. */
    suspend fun channel(paymentId: String): List<PaymentEvent> {
        val records = channelLock.withLock {
            readChannel()
            channelRecords.toList()
        }
        return records
            .filter { text ->
                runCatching {
                    json.parseToJsonElement(text).jsonObject["payment"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
                }.getOrNull() == paymentId
            }
            .map { PaymentsEventCodec.decode(it) as PaymentEvent }
    }

    /** Waits until an event of [type] for the payment is on the default channel, and returns it. */
    suspend fun awaitEvent(paymentId: String, type: String, timeout: Duration = 3.minutes): PaymentEvent =
        poll(timeout, "$type for $paymentId") { channel(paymentId).firstOrNull { it.eventType() == type } }

    // ---- The fault monitor: its notifications, through the queue subscribed to its topic ----

    private val faultsLock = Mutex()
    private val seenFaults = mutableListOf<String>()

    /** Waits for a fault notification that mentions [text]. Firehose buffers faults for about a minute, twice. */
    suspend fun awaitFault(text: String, timeout: Duration = 5.minutes): String {
        val queueUrl = sqs.getQueueUrl(GetQueueUrlRequest {
            queueName = "payments-event-fault-monitor-local-notification-verification.fifo"
        }).queueUrl
        return poll(timeout, "a fault about $text") {
            faultsLock.withLock {
                sqs.receiveMessage(ReceiveMessageRequest {
                    this.queueUrl = queueUrl
                    maxNumberOfMessages = 10
                    waitTimeSeconds = 5
                }).messages.orEmpty().forEach { seenFaults += it.body.orEmpty() }
                seenFaults.firstOrNull { text in it }
            }
        }
    }

    // ---- The operator: resubmitting faults with the resubmit tool ----

    private const val FAULT_BUCKET = "myorg-payments-event-fault-monitor-local-$REGION"

    /** The faults [function] published that mention [text], read from the fault monitor's bucket by the resubmit tool. */
    private suspend fun storedFaults(tool: ResubmitFaults, argv: ResubmitFaults.Args, text: String) =
        tool.filterAndPrepareRequests(argv, s3).filter { it.invokeRequest?.payload?.decodeToString()?.contains(text) == true }

    /** Every fault in the bucket (the tool puts [ResubmitFaults.Args.region] before the prefix, so it's left out). */
    private fun resubmitArgs(function: String) =
        ResubmitFaults.Args(bucket = FAULT_BUCKET, prefix = "$REGION/", functionname = function)

    /**
     * Resubmits the faults [function] published that mention [text], as the operator would: the resubmit tool reads
     * them from the fault monitor's bucket (once Firehose has delivered them) and invokes the function with their
     * records. Returns the tool's counters.
     */
    suspend fun resubmitFaults(function: String, text: String, timeout: Duration = 5.minutes): ResubmitFaults.Counters {
        val tool = ResubmitFaults()
        val argv = resubmitArgs(function)
        val faults = poll(timeout, "the fault about $text in the fault monitor's bucket") {
            storedFaults(tool, argv, text).takeIf { it.isNotEmpty() }
        }
        tool.invokeLambdas(argv, faults, lambda)
        return tool.counters
    }

    /** Waits until the fault monitor's bucket holds [count] faults from [function] that mention [text]. */
    suspend fun awaitStoredFaults(function: String, text: String, count: Int, timeout: Duration = 5.minutes): Int =
        poll(timeout, "$count faults about $text in the fault monitor's bucket") {
            storedFaults(ResubmitFaults(), resubmitArgs(function), text).size.takeIf { it >= count }
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

    // ---- Notifications (a stand-in): what payments told the rest of the bank ----

    private val notificationsLock = Mutex()
    private val notificationsSeen = linkedMapOf<String, JsonObject>()

    /** The external events that reached the Notifications hub for [paymentId], as EventBridge delivered them. */
    suspend fun notifications(paymentId: String): List<JsonObject> = notificationsLock.withLock {
        val queueUrl = sqs.getQueueUrl(GetQueueUrlRequest { queueName = "notifications-event-hub-local-inbox" }).queueUrl
        sqs.receiveMessage(ReceiveMessageRequest {
            this.queueUrl = queueUrl
            maxNumberOfMessages = 10
            waitTimeSeconds = 2
        }).messages.orEmpty().forEach { message ->
            notificationsSeen[message.messageId!!] = json.parseToJsonElement(message.body!!).jsonObject
            sqs.deleteMessage(DeleteMessageRequest { this.queueUrl = queueUrl; receiptHandle = message.receiptHandle })
        }
        notificationsSeen.values.filter {
            it["detail"]?.jsonObject?.get("payment")?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull == paymentId
        }
    }

    /** Waits until Notifications got [type] for the payment, and returns its `detail`: the external event. */
    suspend fun awaitNotification(paymentId: String, type: String, timeout: Duration = 3.minutes): JsonObject =
        poll(timeout, "$type for $paymentId at Notifications") {
            notifications(paymentId).firstOrNull { it["detail-type"]?.jsonPrimitive?.contentOrNull == type }
        }.getValue("detail").jsonObject

    // ---- The card processor: its webhook, signed as the processor would ----

    const val WEBHOOK_FUNCTION = "payments-card-processor-ingress-gateway-local-webhook"

    /** Posts a webhook body to the card processor ingress gateway, signed with the local secret unless [signed] is false. */
    suspend fun webhook(body: String, signed: Boolean = true): ApiResponse {
        val headers = buildMap {
            put("Content-Type", "application/json")
            if (signed) put(CardSignature.HEADER, CardSignature.sign("demo-signing-secret-local", System.currentTimeMillis() / 1000, body))
        }
        val response = lambda.invoke(InvokeRequest {
            functionName = WEBHOOK_FUNCTION
            payload = json.encodeToString(ApiRequest("POST", "/webhooks/card-processor", headers, body)).toByteArray()
        })
        return json.decodeFromString(response.payload!!.decodeToString())
    }

    /**
     * Posts the webhook the processor sends once it settled [payment]'s credit, as the simulator would. The simulator
     * still posts its own later; it has the same event id, so it's a redelivery.
     */
    suspend fun cardSettled(payment: Payment): ApiResponse =
        webhook(cardJson.encodeToString(CardWebhook.serializer(), Simulation.cardWebhook(payment, System.currentTimeMillis() / 1000)))

    // ---- Core checking (simulated): its ledger ----

    /** The ledger rows core checking posted for [paymentId]: one per call that reached it. */
    suspend fun ledgerRows(paymentId: String): List<Map<String, String>> =
        dynamoDb.scan(ScanRequest {
            tableName = "payments-core-checking-ingress-gateway-local-ledger"
            filterExpression = "REF_NO = :r"
            expressionAttributeValues = mapOf(":r" to AttributeValue.S(paymentId))
        }).items.orEmpty().map { item -> item.mapValues { (it.value as AttributeValue.S).value } }

    // ---- Waiting ----

    suspend fun <T : Any> poll(timeout: Duration, what: String, attempt: suspend () -> T?): T {
        val deadline = System.currentTimeMillis() + timeout.inWholeMilliseconds
        while (true) {
            attempt()?.let { return it }
            check(System.currentTimeMillis() < deadline) { "Timed out after $timeout waiting for $what" }
            delay(2.seconds)
        }
    }

}
