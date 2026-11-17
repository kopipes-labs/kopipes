package org.myorg.payments

import kotlinx.serialization.Serializable

/**
 * Core checking's dialect: a row of its ledger, as change data capture delivers it. Legacy conventions: upper-case
 * names, every value a string, a signed amount. Only the gateways speak it; inside the subsystem, nobody does.
 */
@Serializable
data class LedgerEntry(
    /** Core checking numbers every posting itself; a second call is a second posting. */
    val ENTRY_ID: String,
    val ACCT_NO: String,
    /** Signed: a debit is negative. */
    val AMT: String,
    /** `PMT` (a payment debit) or `RFND` (a refund credit). */
    val TXN_TYP: String,
    /** Our payment id, the reference we called with. A row without one isn't ours. */
    val REF_NO: String? = null,
    /** `POSTED` or `REJECTED`. */
    val STAT: String,
    /** Why a row was rejected: `NSF`, `ACCT_CLSD`, ... */
    val REJ_CD: String? = null,
    /** When it was posted, epoch ms. */
    val POST_TS: String,
) {
    fun toAttributes(): Map<String, String> = listOfNotNull(
        "ENTRY_ID" to ENTRY_ID, "ACCT_NO" to ACCT_NO, "AMT" to AMT, "TXN_TYP" to TXN_TYP,
        REF_NO?.let { "REF_NO" to it }, "STAT" to STAT, REJ_CD?.let { "REJ_CD" to it }, "POST_TS" to POST_TS,
    ).toMap()

    companion object {
        const val PAYMENT = "PMT"
        const val REFUND = "RFND"
        const val POSTED = "POSTED"
        const val REJECTED = "REJECTED"

        const val NSF = "NSF"
        const val ACCOUNT_CLOSED = "ACCT_CLSD"
        const val NO_ACCOUNT = "NO_ACCT"
        const val NOT_OWNER = "NOT_OWNER"

        /** Reads a row; unknown columns are ignored. Throws if a column it needs is missing. */
        fun fromAttributes(row: Map<String, String>): LedgerEntry = LedgerEntry(
            ENTRY_ID = row["ENTRY_ID"] ?: error("ledger row without ENTRY_ID"),
            ACCT_NO = row["ACCT_NO"] ?: error("ledger row without ACCT_NO"),
            AMT = row["AMT"] ?: error("ledger row without AMT"),
            TXN_TYP = row["TXN_TYP"] ?: error("ledger row without TXN_TYP"),
            REF_NO = row["REF_NO"],
            STAT = row["STAT"] ?: error("ledger row without STAT"),
            REJ_CD = row["REJ_CD"],
            POST_TS = row["POST_TS"] ?: error("ledger row without POST_TS"),
        )
    }
}

/**
 * Core checking's reject codes in our words. An account that isn't the customer's is `account-not-found`, so accounts
 * can't be probed. A code we don't know is `rejected`; the code stays in `raw`.
 */
fun coreCheckingReason(code: String?): String = when (code) {
    LedgerEntry.NSF -> Reasons.INSUFFICIENT_FUNDS
    LedgerEntry.ACCOUNT_CLOSED -> Reasons.ACCOUNT_CLOSED
    LedgerEntry.NO_ACCOUNT, LedgerEntry.NOT_OWNER -> Reasons.ACCOUNT_NOT_FOUND
    else -> Reasons.REJECTED
}
