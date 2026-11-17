package org.myorg.payments

import com.amazonaws.services.lambda.runtime.events.DynamodbEvent
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.StreamRecord
import io.kopipes.aws.AwsEnvironmentConfig
import io.kopipes.aws.AwsKopipesContext
import io.kopipes.aws.testsupport.TestContext
import io.kopipes.core.JsonRaw
import io.kopipes.core.sinks.EventPublisherInMemory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue as StreamAV

class LedgerTest {

    private val p7 = Payment("p-7", "u-42", Party(Party.CHECKING, "C-1"), Party(Party.CARD, "K-2"), "100.00", "USD", 1L)
    private val known = PaymentLookup { reference -> if (reference == "p-7") p7 else throw PaymentNotKnownYet(reference) }

    private fun row(txn: String = "PMT", stat: String = "POSTED", rejCd: String? = null, ref: String? = "p-7") =
        listOfNotNull(
            "ENTRY_ID" to "E100", "ACCT_NO" to "C-1", "AMT" to "-100.00", "TXN_TYP" to txn, "STAT" to stat,
            "POST_TS" to "1730900580000", "BRANCH" to "007",
            ref?.let { "REF_NO" to it }, rejCd?.let { "REJ_CD" to it },
        ).toMap()

    @Test
    fun `a posted debit is FUNDS_WITHDRAWN, with the remembered payment, the entry's id and time, and the row as raw`(): Unit = runBlocking {
        // Arrange and act
        val event = eventsOf(row(), known).single()

        // Assert
        event.shouldBeInstanceOf<FundsWithdrawn>()
        event.payment shouldBe p7
        event.id shouldBe "E100.FUNDS_WITHDRAWN"
        event.timestamp shouldBe 1730900580000
        event.partitionKey shouldBe "p-7"
        (event.raw as JsonRaw).value.toString() shouldBe
            """{"ENTRY_ID":"E100","ACCT_NO":"C-1","AMT":"-100.00","TXN_TYP":"PMT","STAT":"POSTED","POST_TS":"1730900580000","BRANCH":"007","REF_NO":"p-7"}"""
    }

    @Test
    fun `rejections carry our reason, and a code we do not know is rejected`(): Unit = runBlocking {
        // Arrange and act
        val withdrawal = eventsOf(row(stat = "REJECTED", rejCd = "NSF"), known).single()
        val refund = eventsOf(row(txn = "RFND", stat = "REJECTED", rejCd = "FROZEN"), known).single()

        // Assert
        withdrawal.shouldBeInstanceOf<WithdrawalFailed>().reason shouldBe Reasons.INSUFFICIENT_FUNDS
        refund.shouldBeInstanceOf<RefundFailed>().reason shouldBe Reasons.REJECTED
    }

    @Test
    fun `a posted refund credit is FUNDS_REFUNDED`(): Unit = runBlocking {
        eventsOf(row(txn = "RFND"), known).single().shouldBeInstanceOf<FundsRefunded>()
    }

    @Test
    fun `a row without our reference, or not final yet, means nothing`(): Unit = runBlocking {
        eventsOf(row(ref = null), known).shouldBeEmpty()
        eventsOf(row(stat = "PENDING"), known).shouldBeEmpty()
    }

    @Test
    fun `a row with our reference it can't read is an error, so a fault`(): Unit = runBlocking {
        shouldThrow<IllegalArgumentException> { eventsOf(row(txn = "FEE"), known) }
        shouldThrow<IllegalStateException> { eventsOf(row() - "AMT", known) }
    }

    @Test
    fun `a row heard before its request waits`(): Unit = runBlocking {
        shouldThrow<PaymentNotKnownYet> { eventsOf(row(ref = "p-8"), known) }
    }

    // ---- The ledger function, in process ----

    private class Config(private val streamRetry: Boolean) : AwsEnvironmentConfig() {
        override fun env(name: String): String? = if (name == "STREAM_RETRY_ENABLED") streamRetry.toString() else null
    }

    private fun ledgerFunction(bus: EventPublisherInMemory): Ledger {
        val context = AwsKopipesContext(
            envConfig = Config(streamRetry = true),
            eventPublisher = bus,
            isRetryable = { it is PaymentNotKnownYet },
        )
        return Ledger { LedgerContainer(context, known) }
    }

    private fun insert(row: Map<String, String>) = DynamodbEvent().apply {
        records = listOf(DynamodbEvent.DynamodbStreamRecord().apply {
            eventID = "1"
            eventName = "INSERT"
            awsRegion = "us-east-1"
            dynamodb = StreamRecord()
                .withKeys(mapOf("ENTRY_ID" to StreamAV().withS(row.getValue("ENTRY_ID"))))
                .withNewImage(row.mapValues { StreamAV().withS(it.value) })
        })
    }

    @Test
    fun `the ledger function publishes what a row means`() {
        // Arrange
        val bus = EventPublisherInMemory()

        // Act
        ledgerFunction(bus).handleRequest(insert(row()), TestContext())

        // Assert
        bus.getUows().single().event!!.eventType() shouldBe PaymentEvent.FUNDS_WITHDRAWN
    }

    @Test
    fun `with stream retry, a row heard before its request fails the batch, so the stream delivers it again`() {
        // Arrange
        val bus = EventPublisherInMemory()

        // Act and assert
        shouldThrowAny { ledgerFunction(bus).handleRequest(insert(row(ref = "p-8")), TestContext()) }
        bus.getUows().shouldBeEmpty()
    }
}
