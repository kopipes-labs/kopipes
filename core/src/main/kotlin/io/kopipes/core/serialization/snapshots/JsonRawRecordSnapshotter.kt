package io.kopipes.core.serialization.snapshots

import io.kopipes.core.JsonRaw
import kotlinx.serialization.json.JsonObject

/**
 * Snapshots a [JsonRaw] record, such as a webhook body an ingress gateway received, so a fault keeps the input
 * whole: `kind = json`, `payload = {"value": <the record's value>}`.
 */
class JsonRawRecordSnapshotter : RecordSnapshotter {
    override fun supports(record: Any): Boolean = record is JsonRaw

    override fun snapshot(record: Any): RecordSnapshot =
        RecordSnapshot(kind = KIND, payload = JsonObject(mapOf("value" to (record as JsonRaw).value)))

    companion object {
        const val KIND = "json"
    }
}
