// payments-card-processor-egress-gateway's stack. `cdk deploy payments-card-processor-egress-gateway-local` runs `main` through `gradlew run` (see cdk.json).
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
    implementation(project(":examples:payments:common-infra"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("org.myorg.payments.AppKt")
}
