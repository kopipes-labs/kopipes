package org.myorg.payments

import io.kopipes.cdk.KopipesStack
import io.kopipes.cdk.Stage

/**
 * Where each ingress gateway's real input is. In simulation mode the egress gateway delivers the outside system's
 * answer there, so the two stacks agree on these names; the ingress stack owns the resources. Deploy it first.
 */
object Partners {
    /** Core checking's ledger, as change data capture would deliver it: a table with a stream. */
    fun ledgerTableName(stage: Stage) = "payments-core-checking-ingress-gateway-$stage-ledger"
}

/**
 * Simulation mode: on locally, off elsewhere, unless the `simulate` context says otherwise
 * (`cdk deploy -c simulate=true`). Never on in production.
 */
fun KopipesStack.simulate(): Boolean {
    val fromContext = node.tryGetContext("simulate")?.toString()?.toBooleanStrictOrNull()
    val simulate = fromContext ?: (stage == Stage.LOCAL)
    require(!(simulate && stage == Stage.PROD)) { "Simulation mode must never be on in production" }
    return simulate
}
