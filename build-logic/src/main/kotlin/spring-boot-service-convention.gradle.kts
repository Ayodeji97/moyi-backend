// Convention for a Spring-managed library module that is NOT itself
// bootable — everything under common/* and modules/* (doc 25 §6). No
// org.springframework.boot plugin here (that would add a bootJar task
// these modules don't need); just Kotlin-Spring interop and the shared
// dependency-version platform.

plugins {
    id("kotlin-common-convention")
    id("org.jetbrains.kotlin.plugin.spring")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val springBootVersion = libs.findVersion("springBoot").get().requiredVersion
val springBootBom = "org.springframework.boot:spring-boot-dependencies:$springBootVersion"

dependencies {
    implementation(platform(springBootBom))
    testImplementation(platform(springBootBom))
}
