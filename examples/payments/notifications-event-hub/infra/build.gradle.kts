// notifications-event-hub's stack. `cdk deploy notifications-event-hub-local` runs `main` through `gradlew run` (see cdk.json).
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
