package org.myorg.payments

import io.kopipes.aws.AwsEnvironmentConfig

/** The egress gateway's settings, read like the framework's: from env vars, through [env]. */
open class EgressConfig : AwsEnvironmentConfig() {
    /** Simulation mode: never on in production. */
    open fun simulate(): Boolean = env("SIMULATE") == "true"
    open fun simulatedDelaySeconds(): Int = env("SIMULATED_DELAY_SECONDS")?.toIntOrNull() ?: 2
    open fun simulationQueueUrl(): String = env("SIMULATION_QUEUE_URL") ?: error("SIMULATION_QUEUE_URL is not set")

    /** Where the simulator writes core checking's ledger: the ingress gateway's input. */
    open fun ledgerTableName(): String = env("LEDGER_TABLE_NAME") ?: error("LEDGER_TABLE_NAME is not set")

    open fun coreCheckingUrl(): String = env("CORE_CHECKING_URL") ?: error("CORE_CHECKING_URL is not set")
    open fun closedHours(): ClosedHours? = ClosedHours.parse(env("CORE_CHECKING_CLOSED_HOURS"))
    open fun breakerThreshold(): Int = env("BREAKER_THRESHOLD")?.toIntOrNull() ?: 3
    open fun breakerOpenSeconds(): Long = env("BREAKER_OPEN_SECONDS")?.toLongOrNull() ?: 30
}
