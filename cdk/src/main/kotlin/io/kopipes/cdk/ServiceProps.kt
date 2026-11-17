package io.kopipes.cdk

/** Where a stack is deployed. `LOCAL` is LocalStack. */
enum class Stage(val id: String) {
    LOCAL("local"),
    DEV("dev"),
    QA("qa"),
    PROD("prod");

    override fun toString(): String = id

    companion object {
        fun parse(value: String): Stage =
            entries.firstOrNull { it.id.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown stage: $value")
    }
}

/**
 * Identifies one service's stack.
 *
 * @param org prefix for globally unique names, such as S3 buckets.
 * @param subsys the subsystem (an autonomous group of services sharing one event hub), e.g. `payments`.
 * @param service the service, by convention prefixed with the subsystem, e.g. `payments-bff`.
 */
data class ServiceProps(
    val org: String,
    val subsys: String,
    val service: String,
    val stage: Stage,
    val region: String,
)
