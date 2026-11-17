package org.myorg.payments

import com.amazonaws.services.lambda.runtime.events.KinesisEvent
import com.amazonaws.services.lambda.runtime.events.SQSEvent
import io.kopipes.aws.AwsKopipesContext
import io.kopipes.aws.testsupport.TestContext
import io.kopipes.core.resilience.CircuitBreaker
import io.kopipes.core.resilience.InMemoryBreakerStore
import io.kopipes.core.sinks.EventPublisherInMemory
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.time.ZoneOffset
import java.time.ZonedDateTime

class EgressTest {

    private val p7 = Payment("p-7", "u-42", Party(Party.CHECKING, "C-2"), Party(Party.CARD, "K-2"), "100.00", "USD", 1L)

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
    fun `the listener withdraws on WITHDRAWAL_REQUESTED, refunds on REFUND_REQUESTED, and publishes nothing`() {
        // Arrange
        val bus = EventPublisherInMemory()
        val coreChecking = FakeCoreChecking()
        val calledOnce = CalledOnce(coreChecking, InMemoryClaims(), CircuitBreaker(InMemoryBreakerStore()))
        val listener = Listener { EgressContainer(AwsKopipesContext(eventPublisher = bus), calledOnce) }

        // Act
        listener.handleRequest(kinesis(WithdrawalRequested(p7), DepositRequested(p7), RefundRequested(p7)), TestContext())

        // Assert
        coreChecking.calls shouldBe listOf("debit p-7", "credit p-7")
        bus.getUows().isEmpty() shouldBe true
    }

    @Test
    fun `the simulated core checking schedules the row core checking would write, in its own format`(): Unit = runBlocking {
        // Arrange
        val scheduled = mutableListOf<Pair<String, Int>>()
        val simulated = SimulatedCoreChecking({ body, delay -> scheduled += body to delay }, delaySeconds = 2, clock = { 1000L }, newEntryId = { "E1" })

        // Act
        simulated.debit(p7)

        // Assert
        val (body, delay) = scheduled.single()
        delay shouldBe 2
        Json.decodeFromString(LedgerEntry.serializer(), body) shouldBe
            LedgerEntry("E1", "C-2", "-100.00", "PMT", "p-7", "REJECTED", "NSF", "3000")
    }

    @Test
    fun `the simulator writes each delayed row to the ledger`() {
        // Arrange
        val written = mutableListOf<LedgerEntry>()
        val entry = LedgerEntry("E1", "C-1", "-100.00", "PMT", "p-7", "POSTED", null, "3000")
        val message = SQSEvent.SQSMessage().apply { body = Json.encodeToString(LedgerEntry.serializer(), entry) }

        // Act
        Simulator { written += it }.handleRequest(SQSEvent().apply { records = listOf(message) }, TestContext())

        // Assert
        written shouldBe listOf(entry)
    }

    @Test
    fun `closed hours can span midnight`() {
        // Arrange
        val hours = ClosedHours.parse("22:00-06:00@UTC")!!
        fun at(hour: Int) = ZonedDateTime.of(2026, 11, 16, hour, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

        // Act and assert
        hours.isClosed(at(23)) shouldBe true
        hours.isClosed(at(3)) shouldBe true
        hours.isClosed(at(12)) shouldBe false
        ClosedHours.parse("") shouldBe null
    }
}
