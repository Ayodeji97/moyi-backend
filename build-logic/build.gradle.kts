plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation(libs.plugins.kotlin.jvm.toPluginDependency())
    implementation(libs.plugins.kotlin.spring.toPluginDependency())
    implementation(libs.plugins.kotlin.jpa.toPluginDependency())
    implementation(libs.plugins.kotlin.allopen.toPluginDependency())
    implementation(libs.plugins.spring.boot.toPluginDependency())
    implementation(libs.plugins.detekt.toPluginDependency())
    implementation(libs.plugins.ktlint.toPluginDependency())
}

// Precompiled script plugins can't `alias(libs.plugins.x)` directly — this
// bridges a version-catalog plugin entry into a regular Gradle dependency
// coordinate ("<pluginId>:<pluginId>.gradle.plugin:<version>"), the marker
// artifact every Gradle plugin publishes, so build-logic's own tooling
// versions still come from the one shared libs.versions.toml.
fun Provider<org.gradle.plugin.use.PluginDependency>.toPluginDependency() =
    map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" }
