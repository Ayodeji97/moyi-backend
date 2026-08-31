pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
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
