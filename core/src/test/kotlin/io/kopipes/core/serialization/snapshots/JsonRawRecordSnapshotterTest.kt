package io.kopipes.core.serialization.snapshots

import io.kopipes.core.JsonRaw
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

class JsonRawRecordSnapshotterTest {

    @Test
    fun `a json record is kept whole in the snapshot`() {
        // Arrange
        val record = JsonRaw(JsonPrimitive("""{"unreadable":true}"""))

        // Act
        val snapshot = JsonRawRecordSnapshotter().snapshot(record)

        // Assert
        JsonRawRecordSnapshotter().supports(record) shouldBe true
        JsonRawRecordSnapshotter().supports("text") shouldBe false
        snapshot shouldBe RecordSnapshot("json", JsonObject(mapOf("value" to JsonPrimitive("""{"unreadable":true}"""))))
    }
}
