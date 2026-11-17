package io.kopipes.aws.from

import io.kopipes.aws.tools.ResubmitFaults
import io.kopipes.core.JsonRaw
import io.kopipes.core.serialization.snapshots.JsonRawRecordSnapshotter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

class ResubmittedRecordsTest {

    private val body = """{"not":"a webhook"}"""

    /** A fault as the fault monitor stores it, whose record is a [JsonRaw] snapshot. */
    private fun faultOf(record: JsonRaw): JsonObject {
        val snapshot = JsonRawRecordSnapshotter().snapshot(record)
        return buildJsonObject {
            put("type", "fault")
            put("tags", buildJsonObject { put("functionname", "webhook") })
            put("uow", buildJsonObject {
                put("record", buildJsonObject {
                    put("kind", snapshot.kind)
                    put("payload", snapshot.payload)
                })
            })
        }
    }

    @Test
    fun `what the resubmit tool sends for a JsonRaw fault is the record the function first built`() {
        // Arrange
        val record = JsonRaw(JsonPrimitive(body))
        val prepared = ResubmitFaults().withInvokeRequest(
            ResubmitFaults.UnitOfWork(event = faultOf(record)),
            ResubmitFaults.Args(prefix = "prefix"),
        )
        val sent = Json.parseToJsonElement(prepared.invokeRequest!!.payload!!.decodeToString())

        // Act
        val records = ResubmittedRecords.jsonRawRecordsOf(sent)

        // Assert
        records shouldBe listOf(record)
    }

    @Test
    fun `an API Gateway request is not a resubmission`() {
        // Arrange
        val request = Json.parseToJsonElement(
            """{"httpMethod":"POST","path":"/webhooks/card-processor","body":"{\"Records\":[]}"}"""
        )

        // Act
        val records = ResubmittedRecords.jsonRawRecordsOf(request)

        // Assert
        records.shouldBeNull()
    }

    @Test
    fun `a payload that is not a JsonRaw snapshot is refused`() {
        // Arrange
        val kinesis = Json.parseToJsonElement("""{"Records":[{"eventID":"1","kinesis":{}}]}""")

        // Act & Assert
        shouldThrow<IllegalArgumentException> { ResubmittedRecords.jsonRawRecordsOf(kinesis) }
    }
}
