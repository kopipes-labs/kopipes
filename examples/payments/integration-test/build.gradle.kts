@file:Suppress("UnstableApiUsage")

// End-to-end tests against the stacks deployed to LocalStack (see ../README.md): `../run_itests.sh`.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.serialization)
    `jvm-test-suite`
}

group = "org.myorg"
version = "1.0"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

testing {
    suites {
        val test = getByName<JvmTestSuite>("test") {
            useJUnitJupiter()
        }

        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter()
            dependencies {
                implementation(platform(libs.aws.sdk.bom))

                implementation(libs.aws.java.events)
                implementation(libs.aws.sdk.dynamodb)
                implementation(libs.aws.sdk.eventbridge)
                implementation(libs.aws.sdk.kinesis)
                implementation(libs.aws.sdk.lambda)
                implementation(libs.aws.sdk.sqs)
                implementation(libs.kotest.assertions)
                implementation(libs.kotlinx.coroutines)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.slf4j.simple)

                implementation(project(":examples:payments:common-app"))
            }
            targets {
                all {
                    testTask.configure {
                        shouldRunAfter(test)
                        // A payment crosses several functions per step; give LocalStack time.
                        systemProperty("junit.jupiter.execution.timeout.default", "10m")
                    }
                }
            }
        }
    }
}
