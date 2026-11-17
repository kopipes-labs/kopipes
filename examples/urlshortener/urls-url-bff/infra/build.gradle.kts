// `cdk deploy <stack>` runs `CdkApp` through `gradlew run` (see cdk.json).
plugins {
    `java-library`
    application
}

group = "org.myorg"
version = "1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":cdk"))
    implementation(libs.aws.cdk)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("org.myorg.urls.CdkApp")
}
