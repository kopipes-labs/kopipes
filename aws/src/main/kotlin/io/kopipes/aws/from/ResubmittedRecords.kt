package io.kopipes.aws.from

import io.kopipes.core.JsonRaw
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Reads what the resubmit tool (`ResubmitFaults`) sends a function: `{"Records": [payload, ...]}`, each payload a
 * fault's record snapshot.
 *
 * A stream function needs nothing from this: its snapshots are its event source's own records, so the tool's payload
 * is an ordinary Kinesis, SQS or DynamoDB event. A function whose records are [JsonRaw] does need it. An HTTP ingress,
 * such as a webhook, gets a request from API Gateway, and its fault keeps only the body, snapshotted as
 * `{"value": <body>}` (`JsonRawRecordSnapshotter`). [jsonRawRecordsOf] turns those payloads back into the records
 * the function first built.
 *
 * Only someone allowed to invoke the function can send this. API Gateway's requests never look like it: their
 * top-level keys are fixed, and the caller's input is only in `body`.
 */
object ResubmittedRecords {
    /**
     * The [JsonRaw] records in a resubmission, or `null` if [input] isn't one (an API Gateway request, say).
     * Fails if a payload isn't a `JsonRaw` snapshot: resubmitting it here would be a mistake, not a fault.
     */
    fun jsonRawRecordsOf(input: JsonElement): List<JsonRaw>? {
        val records = (input as? JsonObject)?.get("Records") as? JsonArray ?: return null
        return records.map { payload ->
            val value = (payload as? JsonObject)?.get("value")
                ?: throw IllegalArgumentException("not a resubmitted JsonRaw record: $payload")
            JsonRaw(value)
        }
    }
}
