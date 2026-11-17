// payments-event-fault-monitor's stack. `cdk deploy payments-event-fault-monitor-local` runs `main` through `gradlew run` (see cdk.json).
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

group = "org.myorg"
version = "1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":cdk"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("org.myorg.payments.AppKt")
}
