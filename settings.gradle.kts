pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Lets Gradle auto-provision a JDK toolchain (e.g. the JDK 21 detekt
    // needs — see kotlin-common-convention.gradle.kts) if one isn't
    // already installed locally, instead of hard-failing.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    // No explicit versionCatalogs block: Gradle auto-creates the "libs"
    // catalog from gradle/libs.versions.toml at its default location.
}

rootProject.name = "moyi-backend"

include(
    "app",
    "common:core",
    "common:web",
    "common:security",
    "common:testing",
    "modules:identity",
    "modules:space",
    "modules:gratitude",
    "modules:media",
    "modules:notification",
    "modules:scheduling",
    "modules:admin",
    "modules:analytics",
    "contracts",
)
