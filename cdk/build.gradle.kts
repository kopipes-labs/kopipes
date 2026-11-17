@file:Suppress("UnstableApiUsage")

// CDK constructs for kopipes applications: the event hub, event lake, fault monitor, events tables and the Lambda
// functions that read and write them. It depends only on the CDK, never on `core` or `aws`, so a stack's build
// doesn't pull in the runtime. The env var names the runtime reads are checked against `aws` in the tests.
plugins {
    alias(libs.plugins.kotlin.jvm)
    `jvm-test-suite`
}

group = "io.kopipes.cdk"
version = "1.0"

repositories {
    mavenCentral()
}

dependencies {
    api(libs.aws.cdk)

    testImplementation(project(":aws"))
    testImplementation(kotlin("test"))
    testImplementation(libs.kotest.assertions)
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
