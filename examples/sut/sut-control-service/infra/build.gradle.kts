
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.serialization)
    alias(libs.plugins.shadow)
    application
}

group = "org.myorg"
version = "1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.aws.cdk)
    testImplementation(kotlin("test"))
    implementation(project(":cdk"))
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("org.myorg.sut.AppKt")
}


