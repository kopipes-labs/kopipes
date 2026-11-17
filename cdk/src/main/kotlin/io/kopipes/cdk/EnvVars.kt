package io.kopipes.cdk

/**
 * The env var names the kopipes runtime reads (`AwsEnvironmentConfig`). The constructs set them, so a function's
 * configuration and its infrastructure can't disagree. `EnvVarsTest` checks each name against the runtime.
 */
object EnvVars {
    const val BUS_NAME = "BUS_NAME"

    /** The EventBridge `source` of everything the function publishes. `external` marks external events. */
    const val BUS_SRC = "BUS_SRC"
    const val EVENT_TABLE_NAME = "EVENT_TABLE_NAME"
    const val ENTITY_TABLE_NAME = "ENTITY_TABLE_NAME"
    const val BUCKET_NAME = "BUCKET_NAME"
    const val STAGE = "STAGE"

    /** Becomes `tags.source` on every event the function publishes. */
    const val SERVICE = "SERVICE"
    const val STREAM_RETRY_ENABLED = "STREAM_RETRY_ENABLED"
    const val ITEM_LEVEL_RETRY_ENABLED = "ITEM_LEVEL_RETRY_ENABLED"
    const val METRICS = "METRICS"

    /** The fault monitor's topic, for its transform function. */
    const val TOPIC_ARN = "TOPIC_ARN"

    /** The value of [BUS_SRC] for external events, which the default channel doesn't carry. */
    const val EXTERNAL_SOURCE = "external"
}
