package org.myorg.payments

import io.kopipes.core.resilience.RateLimiter
import kotlinx.coroutines.delay
import mu.KotlinLogging
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** What the card processor said to a credit request. */
sealed interface CreditResult {
    /** `202`: a promise, not a fact. The outcome comes later, by webhook. */
    data object Accepted : CreditResult

    /** A `4xx`, such as an invalid card number: it will never be credited, and no webhook will come. */
    data class Refused(val status: Int, val body: String) : CreditResult
}

/** The processor is down, timed out or answered `5xx`. Retryable: a retry carries the same idempotency key. */
class CardProcessorUnavailable(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** The processor answered `429 Too Many Requests`. Retryable, after [retryAfterMillis]. */
class RateLimited(val retryAfterMillis: Long) : RuntimeException("card processor rate limit reached")

/** The card processor's credit API. */
fun interface CardProcessor {
    /**
     * Credits the payment's amount to `to.account`, with the payment id as the processor's idempotency key: every call
     * and every retry for a payment is the same credit, so the card is credited at most once.
     */
    suspend fun credit(payment: Payment): CreditResult
}

/** The real API, `POST /v1/credits`. Not deployed in this example. */
class HttpCardProcessor(
    private val baseUrl: String,
    private val apiKey: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
) : CardProcessor {
    override suspend fun credit(payment: Payment): CreditResult {
        val body = """{"card":"${payment.to.account}","amount_cents":${amountInCents(payment.amount)},""" +
            """"currency":"${payment.currency.lowercase()}","metadata":{"reference":"${payment.id}"}}"""
        val response = try {
            http.send(
                HttpRequest.newBuilder(URI.create("$baseUrl/v1/credits"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer $apiKey")
                    .header("Idempotency-Key", payment.id)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        } catch (e: Exception) {
            throw CardProcessorUnavailable("card processor unreachable: ${e.message}", e)
        }
        return when (val status = response.statusCode()) {
            in 200..299 -> CreditResult.Accepted
            429 -> throw RateLimited(response.headers().firstValue("Retry-After").map { it.toLongOrNull() }.orElse(null)?.times(1000) ?: 1000)
            in 400..499 -> CreditResult.Refused(status, response.body())
            else -> throw CardProcessorUnavailable("card processor answered $status")
        }
    }
}

/**
 * Calls the processor at its allowed rate ([limiter]), and retries what can be retried: after a `429`, as long as
 * the processor says; after a `5xx` or a timeout, with exponential backoff. After [maxAttempts] it gives the request
 * back to the stream (the errors are retryable), so the backlog waits there.
 */
class PacedCardProcessor(
    private val processor: CardProcessor,
    private val limiter: RateLimiter,
    private val maxAttempts: Int = 3,
    private val backoffMillis: Long = 500,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : CardProcessor {
    private val logger = KotlinLogging.logger { }

    override suspend fun credit(payment: Payment): CreditResult {
        var attempt = 1
        while (true) {
            limiter.acquire()
            val wait = try {
                return processor.credit(payment)
            } catch (e: RateLimited) {
                e.retryAfterMillis
            } catch (e: CardProcessorUnavailable) {
                backoffMillis shl (attempt - 1)
            }
            if (attempt++ >= maxAttempts) throw CardProcessorUnavailable("${payment.id}: card processor still failing after $maxAttempts attempts")
            logger.warn { "${payment.id}: card processor failed (attempt ${attempt - 1}); retrying in $wait ms" }
            sleep(wait)
        }
    }
}

/**
 * Simulation mode: no call. `K-4` is refused outright, as an invalid card would be. Anything else is accepted, and the
 * webhook the processor would send is scheduled after [delaySeconds] (`K-7`: after [lateDelaySeconds], past the
 * deposit's deadline); the simulator signs it and posts it to the ingress gateway's real webhook.
 */
class SimulatedCardProcessor(
    private val delivery: DelayedDelivery,
    private val delaySeconds: Int,
    private val lateDelaySeconds: Int,
    private val clock: () -> Long = System::currentTimeMillis,
) : CardProcessor {
    override suspend fun credit(payment: Payment): CreditResult {
        if (payment.to.account == Simulation.REFUSED_CARD) {
            return CreditResult.Refused(400, """{"error":{"type":"invalid_request_error","code":"invalid_card_number"}}""")
        }
        val delay = if (payment.to.account == Simulation.LATE_CARD) lateDelaySeconds else delaySeconds
        val webhook = Simulation.cardWebhook(payment, createdSeconds = clock() / 1000 + delay)
        delivery.deliverLater(cardJson.encodeToString(CardWebhook.serializer(), webhook), delay)
        return CreditResult.Accepted
    }
}
