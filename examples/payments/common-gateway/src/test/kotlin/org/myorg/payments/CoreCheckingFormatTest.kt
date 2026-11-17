package org.myorg.payments

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CoreCheckingFormatTest {

    private val p7 = Payment("p-7", "u-42", Party(Party.CHECKING, "C-1"), Party(Party.CARD, "K-2"), "100.00", "USD", 1L)

    @Test
    fun `reject codes become our reasons, and an unknown one is rejected`() {
        coreCheckingReason("NSF") shouldBe Reasons.INSUFFICIENT_FUNDS
        coreCheckingReason("ACCT_CLSD") shouldBe Reasons.ACCOUNT_CLOSED
        coreCheckingReason("NOT_OWNER") shouldBe Reasons.ACCOUNT_NOT_FOUND
        coreCheckingReason("FROZEN") shouldBe Reasons.REJECTED
    }

    @Test
    fun `a row is read with its unknown columns ignored, and needs the columns it uses`() {
        // Arrange
        val row = mapOf("ENTRY_ID" to "E1", "ACCT_NO" to "C-1", "AMT" to "-100.00", "TXN_TYP" to "PMT",
            "REF_NO" to "p-7", "STAT" to "POSTED", "POST_TS" to "5", "BRANCH" to "007")

        // Act
        val entry = LedgerEntry.fromAttributes(row)

        // Assert
        entry.REF_NO shouldBe "p-7"
        shouldThrow<IllegalStateException> { LedgerEntry.fromAttributes(row - "STAT") }
    }

    @Test
    fun `the simulation answers by account`() {
        // Arrange
        fun entry(from: String, txn: String) =
            Simulation.ledgerEntry(p7.copy(from = Party(Party.CHECKING, from)), txn, postedAt = 9L, entryId = "E1")

        // Act and assert
        entry("C-1", LedgerEntry.PAYMENT).STAT shouldBe LedgerEntry.POSTED
        entry("C-1", LedgerEntry.PAYMENT).AMT shouldBe "-100.00"
        entry("C-2", LedgerEntry.PAYMENT).REJ_CD shouldBe LedgerEntry.NSF
        entry("C-9", LedgerEntry.PAYMENT).REJ_CD shouldBe LedgerEntry.NOT_OWNER
        entry("C-5", LedgerEntry.PAYMENT).STAT shouldBe LedgerEntry.POSTED
        entry("C-5", LedgerEntry.REFUND).REJ_CD shouldBe LedgerEntry.ACCOUNT_CLOSED
        entry("C-1", LedgerEntry.REFUND).AMT shouldBe "100.00"
    }
}
