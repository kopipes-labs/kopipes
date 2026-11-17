package org.myorg.payments

import com.amazonaws.services.lambda.runtime.events.KinesisEvent
import com.amazonaws.services.lambda.runtime.events.SQSEvent
import io.kopipes.aws.AwsKopipesContext
import io.kopipes.aws.testsupport.TestContext
import io.kopipes.core.resilience.InMemoryRateStore
import io.kopipes.core.resilience.RateLimiter
import io.kopipes.core.sinks.EventPublisherInMemory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class CardEgressTest {

    private val p7 = Payment("p-7", "u-42", Party(Party.CHECKING, "C-1"), Party(Party.CARD, "K-2"), "100.00", "USD", 1L)
    private val noPacing = RateLimiter(InMemoryRateStore(), perSecond = 1000)

    private fun kinesis(vararg events: PaymentEvent) = KinesisEvent().apply {
        records = events.mapIndexed { i, event ->
            KinesisEvent.KinesisEventRecord().apply {
                eventID = "shard:$i"
                kinesis = KinesisEvent.Record().apply {
                    data = ByteBuffer.wrap(event.toString().toByteArray())
                    partitionKey = event.payment.id
                    sequenceNumber = "$i"
                }
            }
        }
    }

    @Test
    fun `a card deposit is credited, and nothing is published`() {
        // Arrange
        val bus = EventPublisherInMemory()
        val credited = mutableListOf<String>()
        val listener = Listener { EgressContainer(AwsKopipesContext(eventPublisher = bus), { credited += it.id; CreditResult.Accepted }) }

        // Act
        listener.handleRequest(kinesis(DepositRequested(p7), WithdrawalRequested(p7)), TestContext())

        // Assert
        credited shouldBe listOf("p-7")
        bus.getUows().isEmpty() shouldBe true
    }

    @Test
    fun `a request the processor refuses is DEPOSIT_FAILED, card-declined, so the refund runs`() {
        // Arrange
        val bus = EventPublisherInMemory()
        val refusal = CreditResult.Refused(400, """{"error":{"code":"invalid_card_number"}}""")
        val listener = Listener { EgressContainer(AwsKopipesContext(eventPublisher = bus), { refusal }, clock = { 5L }) }

        // Act
        listener.handleRequest(kinesis(DepositRequested(p7)), TestContext())

        // Assert
        val failed = bus.getUows().single().event
        failed.shouldBeInstanceOf<DepositFailed>()
        failed.reason shouldBe Reasons.CARD_DECLINED
        failed.id shouldBe "p-7.refused.DEPOSIT_FAILED"
        failed.raw.toString() shouldBe """JsonRaw(value={"error":{"code":"invalid_card_number"}})"""
    }

    @Test
    fun `a 429 is retried after the time the processor asks, a 5xx with backoff, always the same credit`(): Unit = runBlocking {
        // Arrange
        val failures = ArrayDeque(listOf<Exception>(RateLimited(2000), CardProcessorUnavailable("503")))
        val keys = mutableListOf<String>()
        val slept = mutableListOf<Long>()
        val processor = CardProcessor { payment -> keys += payment.id; failures.removeFirstOrNull()?.let { throw it }; CreditResult.Accepted }

        // Act
        val result = PacedCardProcessor(processor, noPacing, maxAttempts = 3, backoffMillis = 500, sleep = { slept += it }).credit(p7)

        // Assert
        result shouldBe CreditResult.Accepted
        keys shouldBe listOf("p-7", "p-7", "p-7")
        slept shouldBe listOf(2000L, 1000L)
    }

    @Test
    fun `after the last attempt the request goes back to the stream`(): Unit = runBlocking {
        // Arrange
        val processor = CardProcessor { throw CardProcessorUnavailable("down") }

        // Act and assert
        shouldThrow<CardProcessorUnavailable> { PacedCardProcessor(processor, noPacing, maxAttempts = 2, sleep = {}).credit(p7) }
    }

    @Test
    fun `the simulated processor refuses K-4, makes K-7 late, and schedules the others' webhooks`(): Unit = runBlocking {
        // Arrange
        val scheduled = mutableListOf<Pair<String, Int>>()
        val simulated = SimulatedCardProcessor({ body, delay -> scheduled += body to delay }, delaySeconds = 5, lateDelaySeconds = 150, clock = { 1000_000L })

        // Act
        val refused = simulated.credit(p7.copy(to = Party(Party.CARD, "K-4")))
        simulated.credit(p7)
        simulated.credit(p7.copy(id = "p-8", to = Party(Party.CARD, "K-7")))

        // Assert
        refused.shouldBeInstanceOf<CreditResult.Refused>().status shouldBe 400
        scheduled.map { it.second } shouldBe listOf(5, 150)
        cardJson.decodeFromString(CardWebhook.serializer(), scheduled[0].first).created shouldBe 1005
    }

    @Test
    fun `the simulator fails a message the webhook didn't take, so the queue delivers it again`() {
        // Arrange
        val message = SQSEvent.SQSMessage().apply { body = "{}" }

        // Act and assert
        Simulator { 200 }.handleRequest(SQSEvent().apply { records = listOf(message) }, TestContext())
        shouldThrowAny { Simulator { 503 }.handleRequest(SQSEvent().apply { records = listOf(message) }, TestContext()) }
    }
}
