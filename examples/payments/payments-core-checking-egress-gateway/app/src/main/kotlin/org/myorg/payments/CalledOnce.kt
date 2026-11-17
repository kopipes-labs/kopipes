package org.myorg.payments

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.ConditionalCheckFailedException
import aws.sdk.kotlin.services.dynamodb.model.DeleteItemRequest
import aws.sdk.kotlin.services.dynamodb.model.PutItemRequest
import aws.sdk.kotlin.services.dynamodb.model.UpdateItemRequest
import io.kopipes.core.resilience.CircuitBreaker
import mu.KotlinLogging

/** Core checking is down or closed: requests wait. Retryable, so the stream holds them, in order, until it's back. */
class CoreCheckingUnavailable(message: String) : RuntimeException(message)

/**
 * The call's outcome is unknown: did core checking post it? It is never called again for this request; the fault
 * tells the operator to check the ledger for the reference and, if nothing was posted, post it there by hand.
 */
class CallInDoubt(message: String, cause: Throwable) : RuntimeException(message, cause)

/** One claim per request: `<payment>#PMT` or `<payment>#RFND`. */
interface Claims {
    /** Claims [key]; `false` if it was already claimed. */
    suspend fun claim(key: String, now: Long): Boolean
    suspend fun markSent(key: String)
    suspend fun release(key: String)
}

/** What became of a request. */
enum class Outcome { SENT, ALREADY_CALLED }

/**
 * "Called once" for a system without idempotency keys. Exactly once can't be guaranteed against such a system, so the
 * gateway guarantees the next best thing: **it never calls twice blindly**.
 *
 * 1. While the [breaker] is open (core checking down, or closed for the night), nothing is sent: the request waits.
 * 2. Claim the request before calling. A request already claimed is never called again, whether it was sent or its
 *    outcome is unknown: a redelivered event changes nothing.
 * 3. A call that certainly never left ([CallNotSent]) releases the claim and waits, so the next delivery calls.
 * 4. A call whose outcome is unknown (a timeout) keeps the claim and becomes a fault ([CallInDoubt]) for the operator.
 * 5. A call core checking refused ([CallRefused]) keeps the claim and becomes a fault too.
 */
class CalledOnce(
    private val coreChecking: CoreChecking,
    private val claims: Claims,
    private val breaker: CircuitBreaker,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val logger = KotlinLogging.logger { }

    suspend fun debit(payment: Payment): Outcome = once(payment, LedgerEntry.PAYMENT) { coreChecking.debit(payment) }

    suspend fun credit(payment: Payment): Outcome = once(payment, LedgerEntry.REFUND) { coreChecking.credit(payment) }

    private suspend fun once(payment: Payment, txnType: String, call: suspend () -> Unit): Outcome {
        val key = "${payment.id}#$txnType"
        breaker.admit()?.let { throw CoreCheckingUnavailable("$key: core checking is unavailable until $it; waiting") }

        if (!claims.claim(key, clock())) {
            logger.info { "$key was already called: not calling core checking again" }
            return Outcome.ALREADY_CALLED
        }
        try {
            call()
        } catch (e: CallNotSent) {
            claims.release(key)
            breaker.failed()
            throw e
        } catch (e: CallRefused) {
            throw e
        } catch (e: Exception) {
            breaker.failed()
            throw CallInDoubt(
                "$key: the call failed and its outcome is unknown (${e.message}). Check core checking's ledger for " +
                    "reference ${payment.id}; if nothing was posted, post it there.",
                e,
            )
        }
        breaker.succeeded()
        claims.markSent(key)
        return Outcome.SENT
    }
}

/** The claims, in the egress gateway's own table: `pk = <payment>#<txn>`, `sk = CLAIM`. */
class DynamoDbClaims(private val client: DynamoDbClient, private val table: String) : Claims {

    private fun key(key: String) = mapOf("pk" to AttributeValue.S(key), "sk" to AttributeValue.S("CLAIM"))

    override suspend fun claim(key: String, now: Long): Boolean = try {
        client.putItem(PutItemRequest {
            tableName = table
            item = key(key) + mapOf("state" to AttributeValue.S("CALLING"), "claimedAt" to AttributeValue.N(now.toString()))
            conditionExpression = "attribute_not_exists(pk)"
        })
        true
    } catch (e: ConditionalCheckFailedException) {
        false
    }

    override suspend fun markSent(key: String) {
        client.updateItem(UpdateItemRequest {
            tableName = table
            this.key = key(key)
            updateExpression = "SET #s = :s"
            expressionAttributeNames = mapOf("#s" to "state")
            expressionAttributeValues = mapOf(":s" to AttributeValue.S("SENT"))
        })
    }

    override suspend fun release(key: String) {
        client.deleteItem(DeleteItemRequest { tableName = table; this.key = key(key) })
    }
}
