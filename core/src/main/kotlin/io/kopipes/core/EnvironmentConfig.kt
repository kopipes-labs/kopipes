package io.kopipes.core

/** Configuration backed by environment variables for stream operations. */
open class EnvironmentConfig {

    /**
     * Reads one environment variable. Every other method goes through here, so a subclass can supply values from
     * somewhere else (a map in a test, for example) by overriding just this.
     */
    protected open fun env(name: String): String? = System.getenv(name)

    open fun ttl(): Int? {
        return env("TTL")?.toIntOrNull()
    }

    open fun streamRetryEnabled(): Boolean {
        val enabled = env("STREAM_RETRY_ENABLED")
        return enabled != null && enabled.isNotEmpty() && enabled == "true"
    }

    open fun itemLevelRetryEnabled(): Boolean {
        val enabled = env("ITEM_LEVEL_RETRY_ENABLED")
        return enabled != null && enabled.isNotEmpty() && enabled == "true"
    }

    open fun maxPublishRequestSize(): Int? {
        return env("PUBLISH_MAX_REQ_SIZE")?.toIntOrNull()
    }

    open fun maxRequestSize(): Int? {
        return env("MAX_REQ_SIZE")?.toIntOrNull()
    }

    open fun publishBatchSize(): Int? {
        return env("PUBLISH_BATCH_SIZE")?.toIntOrNull()
    }

    open fun batchSize(): Int? {
        return env("BATCH_SIZE")?.toIntOrNull()
    }

    open fun publishParallel(): Int? {
        return env("PUBLISH_PARALLEL")?.toIntOrNull()
    }

    open fun parallel(): Int? {
        return env("PARALLEL")?.toIntOrNull()
    }

    open fun timeout(): Long? {
        return env("TIMEOUT")?.toLongOrNull()
    }

    open fun skip(): Boolean {
        return env("SKIP")?.toBoolean() ?: false
    }

    open fun unhealthy(): Boolean {
        return env("UNHEALTHY")?.toBoolean() ?: false
    }

    open fun awsLambdaFunctionName(): String? {
        return env("AWS_LAMBDA_FUNCTION_NAME")
    }

    open fun accountName(): String? {
        return env("ACCOUNT_NAME")
    }

    open fun region(): String? {
        return env("AWS_REGION") ?: env("AWS_DEFAULT_REGION")
    }

    open fun awsRegion(): String {
        return region() ?: "us-east-1"
    }

    open fun stage(): String? {
        return env("STAGE")
    }

    open fun serverlessStage(): String? {
        return env("SERVERLESS_STAGE")
    }

    open fun service(): String? {
        return env("SERVICE")
    }

    open fun project(): String? {
        return env("PROJECT")
    }

    open fun serverlessProject(): String? {
        return env("SERVERLESS_PROJECT")
    }

    open fun metrics(): String? {
        return env("METRICS")
    }

    open fun isMetricEnabled(key: String): Boolean {
        val config = metrics() ?: return false
        return config.contains(key) || config.contains("*")
    }
}
