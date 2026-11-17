@file:Suppress("UnstableApiUsage")

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.serialization)
    alias(libs.plugins.shadow)
    `jvm-test-suite`
}

group = "org.myorg"
version = "1.0"

repositories {
    mavenCentral()
}

// Tests log with slf4j-simple, not the Lambda JSON logger.
configurations.matching { it.name.startsWith("test") }.configureEach {
    val loggerModule = libs.lambda.json.logger.get().module
    exclude(group = loggerModule.group, module = loggerModule.name)
}

dependencies {
    implementation(platform(libs.aws.sdk.bom))

    implementation(project(":aws"))
    implementation(project(":examples:payments:common-gateway"))

    implementation(libs.aws.java.core)
    implementation(libs.aws.java.events)
    implementation(libs.aws.sdk.dynamodb)
    implementation(libs.aws.sdk.eventbridge)
    implementation(libs.aws.sdk.sqs)
    implementation(libs.kotlin.logging)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.lambda.json.logger)
    implementation(libs.slf4j.api)

    testImplementation(kotlin("test"))
    testImplementation(libs.aws.lambda.java.tests)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.slf4j.simple)
}

tasks.shadowJar {
    archiveBaseName.set("payments-card-processor-ingress-gateway")
    archiveClassifier.set("")
    archiveVersion.set("")
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

tasks.withType<Test> {
    jvmArgs("-XX:+EnableDynamicAgentLoading")
}
