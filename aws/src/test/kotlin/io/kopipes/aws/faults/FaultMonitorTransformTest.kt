package io.kopipes.aws.faults

import com.amazonaws.services.lambda.runtime.events.KinesisFirehoseEvent
import io.kopipes.aws.testsupport.TestContext
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.GZIPOutputStream

class FaultMonitorTransformTest {

    private val json = Json

    private fun compressed(text: String): String {
        val bytes = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(text.toByteArray()) } }
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    private fun fault(timestamp: Long = 1_700_000_000_000L, message: String = "boom") = """
        {"id":"e-1","detail-type":"fault","detail":{"type":"fault","timestamp":$timestamp,
         "tags":{"account":"acct-1","region":"us-east-1","functionname":"fn-a","pipeline":"rule-a"},
         "err":{"message":"$message"}}}
    """.trimIndent()

    @Test
    fun `decodeIfBase64 decodes base64 json and leaves anything else alone`() {
        // Arrange
        val doc = """{"a":1}"""

        // Act
        val decoded = FaultMonitorTransform.decodeIfBase64(Base64.getEncoder().encodeToString(doc.toByteArray()))
        val plain = FaultMonitorTransform.decodeIfBase64(doc)

        // Assert
        decoded shouldBe doc
        plain shouldBe doc
    }

    @Test
    fun `decompressDetail expands compressed values under detail only`() {
        // Arrange
        val outside = "COMPRESSED${compressed("""{"stay":true}""")}"
        val event = buildJsonObject {
            put("outside", outside)
            put("detail", buildJsonObject {
                put("object", "COMPRESSED${compressed("""{"expanded":true}""")}")
                put("list", buildJsonArray { add(JsonPrimitive("COMPRESSED${compressed("""["a"]""")}")); add(JsonNull) })
            })
        }

        // Act
        val result = FaultMonitorTransform.decompressDetail(event)

        // Assert
        result shouldBe json.parseToJsonElement(
            """{"outside":"$outside","detail":{"object":{"expanded":true},"list":[["a"],null]}}"""
        )
    }

    @Test
    fun `the notification deduplicates by function, pipeline, hour and message`() {
        // Arrange
        val event = json.parseToJsonElement(fault())

        // Act
        val notification = FaultMonitorTransform.notificationOf(event)!!

        // Assert
        notification.subject shouldBe "Fault: acct-1,us-east-1,fn-a,rule-a"
        notification.messageDeduplicationId shouldBe "fn-a-rule-a-2023111422-boom"
        notification.messageGroupId shouldBe "Fault:acct-1,us-east-1,fn-a,rule-a"
        json.parseToJsonElement(notification.message) shouldBe event
    }

    @Test
    fun `an event without a detail object has no notification`() {
        // Arrange
        val event = json.parseToJsonElement("""{"detail":"text"}""")

        // Act
        val notification = FaultMonitorTransform.notificationOf(event)

        // Assert
        notification.shouldBeNull()
    }

    @Test
    fun `handleRequest stores each fault as a line and notifies once per distinct fault`() {
        // Arrange
        val sent = mutableListOf<FaultNotification>()
        val transform = FaultMonitorTransform(notifier = { sent += it })
        val input = KinesisFirehoseEvent().apply {
            records = listOf(fault(), fault(), "not json").mapIndexed { i, data ->
                KinesisFirehoseEvent.Record().apply {
                    recordId = "r-$i"
                    this.data = ByteBuffer.wrap(data.toByteArray())
                }
            }
        }

        // Act
        val response = transform.handleRequest(input, TestContext())

        // Assert
        response.records.map { it.result } shouldBe listOf("Ok", "Ok", "ProcessingFailed")
        String(Base64.getDecoder().decode(response.records[0].data)) shouldEndWith "}\n"
        sent shouldHaveSize 1
    }
}
