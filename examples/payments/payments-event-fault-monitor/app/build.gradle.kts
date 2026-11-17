// The fault monitor's function is the framework's FaultMonitorTransform: this module only packages it.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
}

group = "org.myorg"
version = "1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(libs.aws.sdk.bom))
    implementation(project(":aws"))
    implementation(libs.aws.sdk.sns)
    implementation(libs.lambda.json.logger)
}

tasks.shadowJar {
    archiveBaseName.set("payments-event-fault-monitor")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()
}

kotlin {
    jvmToolchain(21)
}
