package org.myorg.payments

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.security.MessageDigest

/**
 * The `PUT /payments/{id}` body: only what the client decides. The id comes from the path, `customerId` from the
 * caller and `submittedAt` from the BFF's clock. Any other field is ignored.
 */
@Serializable
data class PaymentRequest(
    val from: Party? = null,
    val to: Party? = null,
    val amount: String? = null,
    val currency: String? = null,
)

/** The API's one error shape. `field` names the culprit of a `400`. */
@Serializable
data class ApiError(val error: String, val message: String, val field: String? = null) {
    companion object {
        const val INVALID_REQUEST = "invalid-request"
        const val UNAUTHENTICATED = "unauthenticated"
        const val NOT_FOUND = "not-found"
        const val CONFLICT = "conflict"
    }
}

/** A request that passed validation: the payment to be, without `submittedAt` yet. */
data class ValidRequest(
    val id: String,
    val customerId: String,
    val from: Party,
    val to: Party,
    val amount: String,
    val currency: String,
) {
    /** Identifies what was asked for: a repeated `PUT` with another body is a conflict, not a new payment. */
    val hash: String by lazy {
        val canonical = listOf(customerId, from.kind, from.account, to.kind, to.account, amount, currency).joinToString("|")
        MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun toPayment(submittedAt: Long) = Payment(id, customerId, from, to, amount, currency, submittedAt)
}

sealed interface Validation {
    data class Valid(val request: ValidRequest) : Validation
    data class Invalid(val error: ApiError) : Validation
}

private val requestJson = Json { ignoreUnknownKeys = true; isLenient = false }

private val ID = Regex("^[A-Za-z0-9_-]{1,64}$")
private val AMOUNT = Regex("^[0-9]{1,13}(\\.[0-9]{1,2})?$")
private val CURRENCY = Regex("^[A-Z]{3}$")
private val ACCOUNT = Regex("^[A-Za-z0-9_-]{1,64}$")

private fun invalid(message: String, field: String? = null) =
    Validation.Invalid(ApiError(ApiError.INVALID_REQUEST, message, field))

/**
 * Checks only the request's shape, with nothing but the request. Whether the account exists, is open and is the
 * customer's is core checking's call, at withdrawal; the BFF never asks another service.
 */
fun validate(id: String?, customerId: String, body: String?): Validation {
    if (id == null || !ID.matches(id)) return invalid("id must be 1-64 letters, digits, '-' or '_'", "id")
    if (body.isNullOrBlank()) return invalid("the body is required")

    val request = try {
        requestJson.decodeFromString<PaymentRequest>(body)
    } catch (e: Exception) {
        return invalid("the body is not a valid payment request")
    }

    val from = request.from ?: return invalid("from is required", "from")
    if (from.kind != Party.CHECKING || !ACCOUNT.matches(from.account)) return invalid("from must be a checking account", "from")

    val to = request.to ?: return invalid("to is required", "to")
    if (to.kind != Party.CARD || !ACCOUNT.matches(to.account)) return invalid("to must be a card", "to")

    val amountText = request.amount ?: return invalid("amount is required", "amount")
    if (!AMOUNT.matches(amountText)) return invalid("amount must be a decimal string such as \"100.00\"", "amount")
    val amount = BigDecimal(amountText)
    if (amount.signum() <= 0) return invalid("amount must be greater than 0", "amount")

    val currency = request.currency ?: return invalid("currency is required", "currency")
    if (!CURRENCY.matches(currency)) return invalid("currency must be an ISO 4217 code", "currency")

    return Validation.Valid(
        ValidRequest(id, customerId, from, to, amount = amount.setScale(2).toPlainString(), currency = currency)
    )
}
