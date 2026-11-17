@file:Suppress("UnstableApiUsage")

// The payments subsystem's internal events, shared by every service.
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
    api(project(":aws"))
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
