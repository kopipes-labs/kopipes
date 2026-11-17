package org.myorg.payments

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import com.amazonaws.services.lambda.runtime.events.KinesisEvent
import com.amazonaws.services.lambda.runtime.events.SQSEvent
import io.kopipes.core.utils.loggedLazy
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** Default channel → card credits. */
class Listener(
    containerFactory: () -> EgressContainer = { EgressContainer.build() },
) : RequestHandler<KinesisEvent, Void?> {

    private val logger = KotlinLogging.logger { }
    private val container by loggedLazy(name = "EgressContainer", logger = logger, initializer = containerFactory)

    override fun handleRequest(input: KinesisEvent, context: Context): Void? = runBlocking {
        container.assembler
            .assemble(container.kinesisAdapter.fromKinesis(input))
            .collect { logger.info { "${it.pipeline?.id}: ${it.event?.eventType()} ${it.event?.id}" } }
        null
    }
}

/**
 * Simulation only: plays the card processor's part. Signs each delayed webhook with the ingress gateway's secret and
 * posts it to the gateway's real URL. A non-`2xx` answer fails the message, so the queue delivers it again later, as
 * the processor would resend it.
 */
class Simulator(
    private val post: (body: String) -> Int = defaultPost(),
) : RequestHandler<SQSEvent, Void?> {

    private val logger = KotlinLogging.logger { }

    override fun handleRequest(input: SQSEvent, context: Context): Void? {
        input.records.forEach { message ->
            val status = post(message.body)
            logger.info { "card processor (simulated) webhook answered $status" }
            check(status in 200..299) { "webhook answered $status; the queue will deliver it again" }
        }
        return null
    }

    companion object {
        fun defaultPost(config: EgressConfig = EgressConfig(), http: HttpClient = HttpClient.newHttpClient()): (String) -> Int = { body ->
            http.send(
                HttpRequest.newBuilder(URI.create(config.webhookUrl()))
                    .header("Content-Type", "application/json")
                    .header(CardSignature.HEADER, CardSignature.sign(config.webhookSecret(), System.currentTimeMillis() / 1000, body))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            ).statusCode()
        }
    }
}
