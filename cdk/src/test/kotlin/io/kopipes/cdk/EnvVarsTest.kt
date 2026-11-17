package io.kopipes.cdk

import io.kopipes.aws.AwsEnvironmentConfig
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The constructs set env vars by these names; the runtime must read the same ones. */
class EnvVarsTest {

    private class MapConfig(private val vars: Map<String, String>) : AwsEnvironmentConfig() {
        override fun env(name: String): String? = vars[name]
    }

    @Test
    fun `the runtime reads every env var the constructs set`() {
        // Arrange
        val config = MapConfig(
            mapOf(
                EnvVars.BUS_NAME to "bus",
                EnvVars.BUS_SRC to "src",
                EnvVars.EVENT_TABLE_NAME to "events",
                EnvVars.ENTITY_TABLE_NAME to "entities",
                EnvVars.BUCKET_NAME to "bucket",
                EnvVars.STAGE to "local",
                EnvVars.SERVICE to "payments-bff",
                EnvVars.STREAM_RETRY_ENABLED to "true",
                EnvVars.ITEM_LEVEL_RETRY_ENABLED to "true",
                EnvVars.METRICS to "*",
                EnvVars.TOPIC_ARN to "arn:topic",
            )
        )

        // Act and assert
        config.busName() shouldBe "bus"
        config.busSource() shouldBe "src"
        config.eventTableName() shouldBe "events"
        config.entityTableName() shouldBe "entities"
        config.bucketName() shouldBe "bucket"
        config.stage() shouldBe "local"
        config.service() shouldBe "payments-bff"
        config.streamRetryEnabled() shouldBe true
        config.itemLevelRetryEnabled() shouldBe true
        config.metrics() shouldBe "*"
        config.topicArn() shouldBe "arn:topic"
    }
}
