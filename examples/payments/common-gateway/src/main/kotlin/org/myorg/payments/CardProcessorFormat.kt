package org.myorg.payments

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The card processor's dialect: a third party's webhook, snake case, amounts in cents. */
@Serializable
data class CardWebhook(
    /** The processor's own id for this notification: a redelivered webhook has the same one. */
    @SerialName("event_id") val eventId: String,
    val type: String,
    /** Epoch seconds. */
    val created: Long,
    val data: CardCredit,
) {
    companion object {
        const val SUCCEEDED = "credit.succeeded"
        const val FAILED = "credit.failed"
    }
}

@Serializable
data class CardCredit(
    @SerialName("credit_id") val creditId: String,
    @SerialName("amount_cents") val amountCents: Long,
    val currency: String,
    /** What we sent with the credit; `reference` is our payment id. */
    val metadata: CardMetadata,
    @SerialName("failure_code") val failureCode: String? = null,
)

@Serializable
data class CardMetadata(val reference: String)

/** Liberal in what it accepts: the processor may add fields. */
val cardJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

fun amountInCents(amount: String): Long = BigDecimal(amount).movePointRight(2).longValueExact()

/**
 * Webhook signatures: header `Card-Signature: t=<epoch seconds>,v1=<hex HMAC-SHA256 of "<t>.<body>">`, with the secret
 * the processor and the ingress gateway share. The timestamp keeps a captured request from being replayed later.
 */
object CardSignature {
    const val HEADER = "Card-Signature"
    const val TOLERANCE_SECONDS = 300L

    fun sign(secret: String, timestampSeconds: Long, body: String): String =
        "t=$timestampSeconds,v1=${hmac(secret, "$timestampSeconds.$body")}"

    fun verify(secret: String, header: String?, body: String, nowSeconds: Long): Boolean {
        if (header == null) return false
        val parts = header.split(",").mapNotNull { part ->
            part.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() }
        }.toMap()
        val timestamp = parts["t"]?.toLongOrNull() ?: return false
        val signature = parts["v1"] ?: return false
        if (kotlin.math.abs(nowSeconds - timestamp) > TOLERANCE_SECONDS) return false
        return MessageDigest.isEqual(hmac(secret, "$timestamp.$body").toByteArray(), signature.toByteArray())
    }

    private fun hmac(secret: String, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
