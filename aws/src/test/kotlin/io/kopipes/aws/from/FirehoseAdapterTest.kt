package io.kopipes.aws.from

import com.amazonaws.services.lambda.runtime.events.KinesisFirehoseEvent
import io.kopipes.aws.MyEventCodec
import io.kopipes.aws.queries.ClaimCheckRedeemer
import io.kopipes.core.EnvironmentConfig
import io.kopipes.core.EventCodec
import io.kopipes.core.UnitOfWork
import io.kopipes.core.faults.FaultManager
import io.kopipes.core.sinks.EventPublisherInMemory
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class FirehoseAdapterTest {

    private val envConfig = spyk(EnvironmentConfig()).apply {
    }

    private fun faultManager() = FaultManager(
        eventPublisher = EventPublisherInMemory(),
        skipErrorLogging = true,
    )

    private fun adapter(
        faultManager: FaultManager = faultManager(),
        eventCodec: EventCodec = MyEventCodec(),
        claimCheckRedeemer: ClaimCheckRedeemer? = null
    ) = FirehoseAdapter(faultManager, eventCodec, claimCheckRedeemer)

    private fun createFirehoseRecord(
        recordId: String,
        payload: String,
        timestamp: Long = 123456789L
    ): KinesisFirehoseEvent.Record {
        return KinesisFirehoseEvent.Record().apply {
            this.recordId = recordId
            this.data = ByteBuffer.wrap(payload.toByteArray())
            this.approximateArrivalTimestamp = timestamp
        }
    }

    @Test
    fun `fromFirehose should decode records and preserve record id`(): Unit = runBlocking {
        val adapter = adapter()
        val payload = """{"type":"MY_EVENT_A","foo":"bar"}"""
        val record = createFirehoseRecord(recordId = "rec-1", payload = payload)
        val event = KinesisFirehoseEvent().apply {
            records = listOf(record)
        }

        val results = adapter.fromFirehose(event).toList()

        results.shouldHaveSize(1)
        results[0].record shouldBe record
        results[0].event?.id shouldBe "rec-1"
        results[0].event?.eventType() shouldBe "MY_EVENT_A"
        results[0].timestamp shouldBe "123456789"
        results[0].getExtension<FirehoseExtension>()?.recordId shouldBe "rec-1"
    }

    @Test
    fun `fromFirehose should handle decode failures`(): Unit = runBlocking {
        val faultManager = faultManager()
        val adapter = adapter(faultManager = faultManager)
        val record = createFirehoseRecord(recordId = "rec-fail", payload = "invalid")
        val event = KinesisFirehoseEvent().apply {
            records = listOf(record)
        }

        val results = adapter.fromFirehose(event).toList()

        results.shouldHaveSize(0)
        faultManager.getFaults().shouldHaveSize(1)
    }

    @Test
    fun `fromFirehose should redeem claim checks`() = runBlocking {
        val claimCheckRedeemer = mockk<ClaimCheckRedeemer>()
        val adapter = adapter(claimCheckRedeemer = claimCheckRedeemer)
        val payload = """{"type":"MY_EVENT_A","foo":"bar"}"""
        val record = createFirehoseRecord(recordId = "rec-cc", payload = payload)
        val event = KinesisFirehoseEvent().apply {
            records = listOf(record)
        }

        every {
            with(claimCheckRedeemer) { any<Flow<UnitOfWork>>().redeemClaimCheck() }
        } answers {
            firstArg()
        }

        adapter.fromFirehose(event).toList()

        verify(exactly = 1) {
            with(claimCheckRedeemer) { any<Flow<UnitOfWork>>().redeemClaimCheck() }
        }
    }
}
