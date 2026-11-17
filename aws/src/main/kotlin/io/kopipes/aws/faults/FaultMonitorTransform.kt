package io.kopipes.aws.faults

import aws.sdk.kotlin.runtime.auth.credentials.EnvironmentCredentialsProvider
import aws.sdk.kotlin.services.sns.SnsClient
import aws.sdk.kotlin.services.sns.model.PublishRequest
import aws.smithy.kotlin.runtime.net.url.Url
import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import com.amazonaws.services.lambda.runtime.events.KinesisFirehoseEvent
import io.kopipes.aws.AwsEnvironmentConfig
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mu.KotlinLogging
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.UUID
import java.util.zip.GZIPInputStream

/** A notification to the operator about a fault. Faults with the same [messageDeduplicationId] are sent once. */
data class FaultNotification(
    val subject: String,
    val messageDeduplicationId: String,
    val messageGroupId: String,
    val message: String,
)

/** Sends fault notifications. */
fun interface FaultNotifier {
    suspend fun send(notifications: Collection<FaultNotification>)
}

/** Publishes fault notifications to the fault monitor's FIFO topic (`TOPIC_ARN`). */
class SnsFaultNotifier(private val envConfig: AwsEnvironmentConfig = AwsEnvironmentConfig()) : FaultNotifier {

    private val logger = KotlinLogging.logger {}

    override suspend fun send(notifications: Collection<FaultNotification>) {
        if (notifications.isEmpty()) return
        val topicArn = envConfig.topicArn() ?: error("TOPIC_ARN is not set")
        SnsClient {
            region = envConfig.awsRegion()
            credentialsProvider = EnvironmentCredentialsProvider()
            envConfig.endPointUrl()?.let { endpointUrl = Url.parse(it) }
        }.use { sns ->
            notifications.forEach { notification ->
                try {
                    sns.publish(PublishRequest {
                        this.topicArn = topicArn
                        subject = notification.subject
                        message = notification.message
                        messageDeduplicationId = notification.messageDeduplicationId
                        messageGroupId = notification.messageGroupId
                    })
                } catch (err: Throwable) {
                    // A lost notification must not lose the fault: Firehose still stores it.
                    logger.error(err) { "Failed to publish notification ${notification.messageDeduplicationId}" }
                }
            }
            logger.info { "Sent ${notifications.size} fault notifications" }
        }
    }
}

@Serializable
data class FirehoseTransformResponse(val records: List<FirehoseTransformRecord>)

@Serializable
data class FirehoseTransformRecord(val recordId: String, val result: String, val data: String)

/**
 * The fault monitor's Firehose transform (see docs/FaultResubmission.md). For each `fault` event on its way to S3:
 * - it expands the fields the fault manager compressed, so the stored fault is readable and the resubmit tool can
 *   replay it, and ends it with a newline, one fault per line;
 * - it notifies the operator. Repeats of the same fault (same function, pipeline and message) within an hour share a
 *   deduplication id, so the FIFO topic sends them once and an outage doesn't flood the operator.
 *
 * A record it can't read is returned as `ProcessingFailed`, which Firehose stores under its error prefix.
 */
class FaultMonitorTransform(
    private val envConfig: AwsEnvironmentConfig = AwsEnvironmentConfig(),
    private val notifier: FaultNotifier = SnsFaultNotifier(envConfig),
) : RequestHandler<KinesisFirehoseEvent, FirehoseTransformResponse> {

    private val logger = KotlinLogging.logger {}

    override fun handleRequest(input: KinesisFirehoseEvent, context: Context): FirehoseTransformResponse = runBlocking {
        val notifications = linkedMapOf<String, FaultNotification>()
        val records = input.records.map { record ->
            val recordId = record.recordId ?: missingRecordId()
            val data = Charsets.UTF_8.decode(record.data).toString()
            try {
                val event = decompressDetail(json.parseToJsonElement(decodeIfBase64(data)))
                notificationOf(event)?.let { notifications[it.messageDeduplicationId] = it }
                FirehoseTransformRecord(recordId, OK, base64(json.encodeToString(JsonElement.serializer(), event) + "\n"))
            } catch (e: Exception) {
                logger.error(e) { "Failed to transform record $recordId" }
                FirehoseTransformRecord(recordId, PROCESSING_FAILED, base64(data))
            }
        }
        notifier.send(notifications.values)
        FirehoseTransformResponse(records)
    }

    /** LocalStack leaves out the record id that Firehose always sets. */
    private fun missingRecordId(): String =
        if (envConfig.isLocalStack()) "local-${UUID.randomUUID()}"
        else throw IllegalArgumentException("Malformed Firehose record: 'recordId' is missing")

    companion object {
        const val OK = "Ok"
        const val PROCESSING_FAILED = "ProcessingFailed"
        const val COMPRESSION_PREFIX = "COMPRESSED"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private val prettyJson = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

        /** SNS's limit on a message is 256 KB; a larger fault is sent as just its error. */
        private const val MAX_MESSAGE = 256 * 1024

        private fun base64(text: String): String = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

        /** The record's data, base64-decoded if it is base64 for a JSON document. */
        internal fun decodeIfBase64(value: String): String {
            val decoded = try {
                Base64.getDecoder().decode(value.trim()).toString(Charsets.UTF_8).trimStart()
            } catch (_: IllegalArgumentException) {
                return value
            }
            return if (decoded.startsWith("{") || decoded.startsWith("[")) decoded else value
        }

        /** Expands the compressed values under the event's `detail`; anything else is left as it is. */
        internal fun decompressDetail(event: JsonElement): JsonElement {
            val obj = event as? JsonObject ?: return event
            val detail = obj["detail"] ?: return event
            return JsonObject(obj + ("detail" to decompress(detail)))
        }

        internal fun decompress(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> buildJsonObject { element.forEach { (key, value) -> put(key, decompress(value)) } }
            is JsonArray -> buildJsonArray { element.forEach { add(decompress(it)) } }
            is JsonPrimitive ->
                if (element.isString && element.content.startsWith(COMPRESSION_PREFIX)) {
                    json.parseToJsonElement(unzip(element.content.removePrefix(COMPRESSION_PREFIX)))
                } else {
                    element
                }
        }

        internal fun unzip(compressed: String): String =
            GZIPInputStream(ByteArrayInputStream(Base64.getDecoder().decode(compressed))).bufferedReader().use { it.readText() }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun fifoSafe(value: String): String =
            value.replace(Regex("""[^A-Za-z0-9!"#$%&'()*+,\-./:;<=>?@\[\\\]^_`{|}~]"""), "-")

        /** The notification for a fault event, or `null` if it has no `detail` object. */
        internal fun notificationOf(event: JsonElement): FaultNotification? {
            val detail = (event as? JsonObject)?.get("detail") as? JsonObject ?: return null
            val tags = detail["tags"] as? JsonObject ?: JsonObject(emptyMap())
            val err = detail["err"] as? JsonObject ?: JsonObject(emptyMap())

            val timestamp = detail["timestamp"]?.jsonPrimitive?.longOrNull ?: 0L
            val d = Instant.fromEpochMilliseconds(timestamp).toLocalDateTime(TimeZone.UTC)
            val hour = "%04d%02d%02d%02d".format(d.year, d.monthNumber, d.dayOfMonth, d.hour)

            val account = tags.string("account") ?: "account"
            val region = tags.string("region") ?: "region"
            val functionName = tags.string("functionname") ?: "functionname"
            val pipeline = tags.string("pipeline") ?: "pipeline"
            val errorMessage = err.string("message") ?: ""

            val fault = prettyJson.encodeToString(JsonElement.serializer(), event)
            return FaultNotification(
                subject = "Fault: $account,$region,$functionName,$pipeline".take(100),
                messageDeduplicationId = fifoSafe("$functionName-$pipeline-$hour-$errorMessage").take(128),
                messageGroupId = fifoSafe("Fault:$account,$region,$functionName,$pipeline").take(100),
                message = if (fault.length < MAX_MESSAGE) fault else prettyJson.encodeToString(JsonElement.serializer(), err),
            )
        }
    }
}
