package org.myorg.payments

import com.amazonaws.services.lambda.runtime.events.KinesisEvent
import io.kopipes.aws.AwsKopipesContext
import io.kopipes.aws.testsupport.EventsTableSimulator
import io.kopipes.aws.testsupport.TestContext
import io.kopipes.core.faults.FaultEvent
import io.kopipes.core.faults.FaultManager
import io.kopipes.core.sinks.EventPublisherInMemory
import java.nio.ByteBuffer
import java.util.Date

/** The running example: payment `p-7`, 100 USD from checking account `C-1` to card `K-2`. */
val P7 = Payment(
    id = "p-7",
    customerId = "u-42",
    from = Party(Party.CHECKING, "C-1"),
    to = Party(Party.CARD, "K-2"),
    amount = "100.00",
    currency = "USD",
    submittedAt = 1_730_900_520_000L,
)

/** An event as another service would publish it: an id and a timestamp of its own. */
fun <T : PaymentEvent> T.published(id: String, at: Long = P7.submittedAt + 60_000): T =
    copyEvent(id = id, timestamp = at, partitionKey = payment.id) as T

/** Deadlines as in a demo: two minutes per step. */
class TestConfig : ControlServiceConfig() {
    override fun env(name: String): String? = if (name.endsWith("_DEADLINE")) "PT2M" else null
}

/**
 * The control service in process: its listener and trigger, an in-memory events table with a stream, and an
 * in-memory bus. [hear] delivers events as the default channel would, then runs the table's stream through the
 * trigger until nothing is left, so a test sees everything the service published in response.
 */
class ControlService {
    val bus = EventPublisherInMemory()
    val table = EventsTableSimulator()
    private val config = TestConfig()
    private val context = AwsKopipesContext(
        envConfig = config,
        eventPublisher = bus,
        faultManager = FaultManager(bus, skipErrorLogging = true),
    )
    private val listener = Listener { ListenerContainer(context, eventsMicrostore = table) }
    private val trigger = Trigger { TriggerContainer(context, eventsMicrostore = table, eventPublisher = bus, config = config) }
    private var sequence = 0

    /** Delivers [events] on the default channel and lets the service react. */
    fun hear(vararg events: PaymentEvent): ControlService = apply {
        val records = events.map { event ->
            KinesisEvent.KinesisEventRecord().apply {
                eventID = "shardId-000:${++sequence}"
                kinesis = KinesisEvent.Record().apply {
                    data = ByteBuffer.wrap(event.toString().toByteArray())
                    partitionKey = event.payment.id
                    sequenceNumber = sequence.toString()
                    approximateArrivalTimestamp = Date()
                }
            }
        }
        listener.handleRequest(KinesisEvent().apply { this.records = records }, TestContext())
        settle()
    }

    /** What DynamoDB's TTL does once a step's deadline has passed: deletes the deadline item. */
    fun deadlinePasses(step: String): ControlService = apply {
        table.expire { it.suffix == TriggerContainer.deadline(step) }
        settle()
    }

    private fun settle() {
        while (true) {
            val batch = table.drainStream() ?: break
            trigger.handleRequest(batch, TestContext())
        }
    }

    /** The events published so far. */
    fun published(): List<PaymentEvent> = bus.getUows().mapNotNull { it.event as? PaymentEvent }

    fun publishedTypes(): List<String> = published().map { it.eventType() }

    fun faults(): List<FaultEvent> = bus.faults()

    fun clear() = bus.reset()
}
