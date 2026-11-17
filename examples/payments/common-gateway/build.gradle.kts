@file:Suppress("UnstableApiUsage")

// What the gateways share: the outside systems' formats, the payments they asked about, and simulation mode.
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

dependencies {
    api(project(":examples:payments:common-app"))
    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.sdk.dynamodb)
    implementation(libs.aws.sdk.sqs)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.slf4j.simple)
}

kotlin {
    jvmToolchain(21)
}

testing {
    suites {
        getByName<JvmTestSuite>("test") {
            useJUnitJupiter()
        }
    }
}
