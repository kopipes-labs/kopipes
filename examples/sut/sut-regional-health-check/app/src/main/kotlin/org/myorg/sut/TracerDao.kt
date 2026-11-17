package org.myorg.sut

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.UpdateItemResponse
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.smithy.kotlin.runtime.content.ByteStream
import io.kopipes.aws.from.RecordPair
import io.kopipes.aws.sinks.DynamoDbUpdateValue
import io.kopipes.aws.sinks.updateExpression
import io.kopipes.core.*
import io.kopipes.core.sinks.EntityStore
import io.kopipes.core.utils.ttl
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.util.*
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds

const val DISCRIMINATOR = "trace"

private val tracerEventJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
data class Tracer (
    val awsRegion: String,  // pk
    val roundedTimestamp: Long,  // sk
    val timestamp: Long,
    val ttl: Long,
    val status: String,
)

@Serializable
data class TracerEvent(
    val tracer: Tracer,
    override val id: String? = null,
    override val timestamp: Long? = null,
    override val partitionKey: String? = null,
    override val tags: Map<String, String>? = null,
    override val raw: RawRecord? = null,
    override val eem: EnvelopeEncryptionMetadata? = null,
    override val triggers: List<EventReference>? = null,
    val type : String = "tracer"
) : Event {

    override fun eventType(): String = "tracer"

    override fun toString(): String = TracerEventCodec.encode(this)

    override fun copyEvent(
        id: String?,
        timestamp: Long?,
        partitionKey: String?,
        tags: Map<String, String>?,
        raw: RawRecord?,
        eem: EnvelopeEncryptionMetadata?,
        triggers: List<EventReference>?
    ): Event = copy(
        id = id,
        timestamp = timestamp,
        partitionKey = partitionKey,
        tags = tags,
        raw = raw,
        eem = eem,
        triggers = triggers
    )
}

object TracerEventCodec : EventCodec {
    override fun decode(eventAsString: String): Event {
        return tracerEventJson.decodeFromString<TracerEvent>(eventAsString)
    }

    override fun encode(event: Event): String {
        require(event is TracerEvent) {
            "TracerEventCodec can only encode TracerEvent instances, but received ${event::class.qualifiedName}"
        }

        return tracerEventJson.encodeToString(event)
    }
}

private const val STARTED = "STARTED"
private const val COMPLETED = "COMPLETED"

class TracerDao(
    private val connector: Connector,
    private val awsRegion: String,
) {

    private val logger = KotlinLogging.logger {  }

    suspend fun check( unhealthyFlag: Boolean? = null): HealthCheckResponse {
        val now = Clock.System.now().toEpochMilliseconds()
        val truncatedTimestamp = truncateToMinute(now)

        if (unhealthyFlag == true) {
            return HealthCheckResponse(
                statusCode = 503,
                timestamp = truncatedTimestamp,
                region = awsRegion,
            )
        }

        val currentTracers = getTracers()
        val toBeSaved = Tracer(
            awsRegion = awsRegion,
            roundedTimestamp = truncatedTimestamp,
            timestamp = now,
            ttl = ttl(now, 92.days),
            status = STARTED
        )
        val save = save(toBeSaved)
        logger.info { "tracers=$currentTracers save=$toBeSaved" }

        val mostRecent = currentTracers.firstOrNull()
        val incomplete = mostRecent?.status == STARTED

        val sk = mostRecent?.roundedTimestamp ?: 0L
        val elapsed = (truncatedTimestamp - sk).toDouble() / 1000.0 / 60.0

        // Is the most recent trace incomplete, or is it older than 1 minute?
        val unhealthyCheck = incomplete || elapsed > 1

        return HealthCheckResponse(
            statusCode = if (unhealthyCheck) 503 else 200,
            timestamp = truncatedTimestamp,
            region = awsRegion,
            incomplete = incomplete,
            elapsed = elapsed,
            tracers = currentTracers,
            saveResponse = save.toString(),
        )
    }

    suspend fun getTracers(): List<Tracer> {
        return connector.get(awsRegion).map {
            Tracer(
                awsRegion = awsRegion,
                roundedTimestamp = it["sk"]?.asStringOrNull()?.toLongOrNull() ?: 0L,
                timestamp = it["timestamp"]?.asStringOrNull()?.toLongOrNull() ?: 0L,
                ttl = it["ttl"]?.asStringOrNull()?.toLongOrNull() ?: 0L,
                status = it["status"]?.asStringOrNull() ?: "UNKNOWN",
            )
        }
    }


    suspend fun save(tracer: Tracer) : UpdateItemResponse {
        return connector.update(
            key = mapOf(
                "pk" to AttributeValue.S(tracer.awsRegion),
                "sk" to AttributeValue.S(tracer.roundedTimestamp.toString()),
            ),
            inputParams = mapOf(
                "timestamp" to DynamoDbUpdateValue.DbSet(AttributeValue.N(tracer.timestamp.toString())),
                "status" to DynamoDbUpdateValue.DbSet(AttributeValue.S(tracer.status)),
                "discriminator" to DynamoDbUpdateValue.DbSet(AttributeValue.S(DISCRIMINATOR)),
                "latched" to DynamoDbUpdateValue.DbRemove,
                "ttl" to DynamoDbUpdateValue.DbSet(AttributeValue.N(tracer.ttl.toString())),
                "awsregion" to DynamoDbUpdateValue.DbSet(AttributeValue.S(tracer.awsRegion)),
            ),
        )
    }
}

data class HealthCheckResponse(
    val statusCode: Int,
    val timestamp: Long,
    val region: String?,
    val incomplete: Boolean? = null,
    val elapsed: Double? = null,
    val tracers: List<Tracer>? = null,
    val saveResponse: String? = null,
)

private val logger = KotlinLogging.logger {  }

/** Marks the tracer's item completed, unless a newer timestamp is already there. */
fun toUpdate(uow: UnitOfWork): EntityStore.Update? {

    val timestamp = Clock.System.now().toEpochMilliseconds()

    val event = uow.event as? TracerEvent
    if (event == null) {
        logger.error { "Cannot build the tracer update: event is not a TracerEvent. event=${uow.event}, classType=${uow.event?.let { it::class.qualifiedName }}" }
        return null
    }

    val startTimestamp = event.tracer.timestamp
    val latency = (timestamp - startTimestamp).milliseconds.inWholeSeconds

    return EntityStore.Update(
        key = EntityStore.Key(pk = event.tracer.awsRegion, sk = event.tracer.roundedTimestamp.toString()),
        set = mapOf(
            "status" to COMPLETED,
            "discriminator" to DISCRIMINATOR,
            "timestamp" to timestamp,
            "latency" to latency,
            "latched" to true,
            "ttl" to ttl(timestamp, 92.days),
            "awsregion" to System.getenv("AWS_REGION"),
        ),
        onlyIfNewer = "timestamp",
    )
}


fun toS3PutRequest(uow: UnitOfWork): PutObjectRequest? {
    val event = uow.event as? TableChangeEvent
    if (event == null) {
        logger.error { "Cannot build S3 put request: event is not a TableChangeEvent. event=${uow.event}" }
        return null
    }

    val raw = uow.event?.raw as? RecordPair
    if (raw == null) {
        logger.error { "Cannot build S3 put request: event raw is not RecordPair. event=${uow.event}" }
        return null
    }

    val newRaw = raw.new
    if (newRaw == null) {
        logger.error { "Cannot build S3 put request: raw.new is null. event=${uow.event}" }
        return null
    }

    val pk = newRaw.getS("pk")
    val sk = newRaw.getS("sk")

    if (pk == null || sk == null) {
        logger.error {
            "Cannot build S3 put request: pk or sk missing. pk=$pk, sk=$sk, event=${uow.event}"
        }
        return null
    }

    val status = newRaw.getS("status")
    if (status != STARTED) {
        return null
    }

    val timestamp = newRaw.getLong("timestamp") ?: 0

    val s3Key = "${pk}/${sk}"
    logger.info { "Writing tracer event to S3. s3Key=$s3Key" }

    val tracer = Tracer(
        awsRegion = pk,
        roundedTimestamp = sk.toLongOrNull() ?: 0,
        timestamp = timestamp,
        ttl = 0,
        status = "STARTED"
    )

    val tracerEvent = TracerEvent(
        tracer = tracer,
        id = UUID.randomUUID().toString(),
        partitionKey = tracer.awsRegion
    )

    val eventAsString = TracerEventCodec.encode(tracerEvent)

    return PutObjectRequest {
        key = "${pk}/${sk}"
        body = ByteStream.fromString(eventAsString)
    }
}

fun truncateToMinute(timestamp: Long): Long {
    val minute = 60_000L
    return timestamp / minute * minute
}

private fun AttributeValue.asStringOrNull(): String? =
    when (this) {
        is AttributeValue.S -> value
        is AttributeValue.N -> value
        else -> null
    }
