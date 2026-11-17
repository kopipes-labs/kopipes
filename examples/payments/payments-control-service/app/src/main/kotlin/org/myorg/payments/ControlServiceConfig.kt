package org.myorg.payments

import io.kopipes.aws.AwsEnvironmentConfig
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * The control service's own settings, read like the framework's: from env vars, through [env].
 * Deadlines are ISO-8601 durations, such as `PT2M` in a demo or `P1D` in production.
 */
open class ControlServiceConfig : AwsEnvironmentConfig() {

    /** How long a step may take before `PAYMENT_EXPIRED`. Core checking is closed at night; the card is fast. */
    open fun deadline(step: String): Duration {
        val default = when (step) {
            Steps.WITHDRAWAL, Steps.REFUND -> 1.days
            Steps.DEPOSIT -> 1.hours
            else -> error("Unknown step: $step")
        }
        return env("${step.uppercase()}_DEADLINE")?.let(Duration::parseIsoString) ?: default
    }
}
