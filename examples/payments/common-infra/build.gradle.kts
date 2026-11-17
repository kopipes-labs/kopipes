// What the payments stacks share: the gateways' meeting points and simulation mode.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "org.myorg"
version = "1.0"

repositories {
    mavenCentral()
}

dependencies {
    api(project(":cdk"))
}

kotlin {
    jvmToolchain(21)
}
