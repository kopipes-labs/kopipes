package org.myorg.payments

import kotlinx.serialization.json.Json
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/**
 * Core checking's withdraw and refund API. It never says "it worked": the result, posted or rejected, comes from its
 * ledger, through the ingress gateway (act, then hear back). It has no idempotency keys: a second call is a second
 * posting.
 */
interface CoreChecking {
    /** Withdraw the payment's amount from `from.account`, for `customerId`, reference the payment id. */
    suspend fun debit(payment: Payment)

    /** Credit the amount back to `from.account`, reference the payment id. A new ledger entry, not a rollback. */
    suspend fun credit(payment: Payment)
}

/** The call certainly never reached core checking: the connection was refused. Safe to try again later. */
class CallNotSent(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Core checking refused the call itself: malformed, not a business rejection. Not retried. */
class CallRefused(message: String) : RuntimeException(message)

/**
 * The real API, `POST /CHK/DEBIT` and `POST /CHK/CREDIT`. Not deployed in this example (core checking lives elsewhere).
 * A connection that fails is [CallNotSent]; a `4xx` is [CallRefused]; a timeout or a `5xx` leaves the outcome unknown.
 */
class HttpCoreChecking(
    private val baseUrl: String,
    private val timeout: Duration = Duration.ofSeconds(10),
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
) : CoreChecking {
    override suspend fun debit(payment: Payment) = post("/CHK/DEBIT", payment)
    override suspend fun credit(payment: Payment) = post("/CHK/CREDIT", payment)

    private fun post(path: String, payment: Payment) {
        val body = """{"REF_NO":"${payment.id}","ACCT_NO":"${payment.from.account}","CUST_ID":"${payment.customerId}","AMT":"${payment.amount}"}"""
        val request = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: HttpConnectTimeoutException) {
            throw CallNotSent("core checking didn't accept the connection", e)
        } catch (e: ConnectException) {
            throw CallNotSent("core checking refused the connection", e)
        }
        when (response.statusCode()) {
            in 200..299 -> Unit
            in 400..499 -> throw CallRefused("core checking refused $path for ${payment.id}: ${response.statusCode()} ${response.body()}")
            else -> error("core checking answered $path for ${payment.id} with ${response.statusCode()}")
        }
    }
}

/**
 * Simulation mode: no call. Schedules the ledger row core checking would have written, which the simulator writes to
 * the ingress gateway's ledger after [delaySeconds]. Like core checking, it numbers every posting itself.
 */
class SimulatedCoreChecking(
    private val delivery: DelayedDelivery,
    private val delaySeconds: Int,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newEntryId: () -> String = { "E" + UUID.randomUUID().toString().replace("-", "").take(16).uppercase() },
) : CoreChecking {
    override suspend fun debit(payment: Payment) = schedule(payment, LedgerEntry.PAYMENT)
    override suspend fun credit(payment: Payment) = schedule(payment, LedgerEntry.REFUND)

    private suspend fun schedule(payment: Payment, txnType: String) {
        val entry = Simulation.ledgerEntry(payment, txnType, postedAt = clock() + delaySeconds * 1000L, entryId = newEntryId())
        delivery.deliverLater(Json.encodeToString(LedgerEntry.serializer(), entry), delaySeconds)
    }
}
