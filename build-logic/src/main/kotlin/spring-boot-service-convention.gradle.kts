// Convention for a Spring-managed library module that is NOT itself
// bootable — everything under common/* and modules/* (doc 25 §6). No
// org.springframework.boot plugin here (that would add a bootJar task
// these modules don't need); just Kotlin-Spring interop and the shared
// dependency-version platform.

plugins {
    id("kotlin-common-convention")
    id("org.jetbrains.kotlin.plugin.spring")
    // JPA entities live in each module's own infra/database/entities
    // package, so both JPA compiler plugins belong here rather than in the
    // app convention. The course applies kotlin("plugin.jpa") separately
    // in every feature module's build file; putting it in the shared
    // convention is the same effect without repeating it across the
    // eight modules/* — which is what convention plugins are for.
    //
    // plugin.jpa is the *noarg* plugin: it synthesises the no-arg
    // constructor JPA requires. It does NOT make classes non-final —
    // verified by compiling an @Entity and reading the bytecode, which
    // still said `public final class`. Opening them is allopen's job,
    // configured below.
    id("org.jetbrains.kotlin.plugin.jpa")
    id("org.jetbrains.kotlin.plugin.allopen")
}

// Kotlin classes are final by default; Hibernate must subclass an entity
// to build a lazy-loading proxy. kotlin-spring already opens
// @Component/@Service/@Configuration and friends, but knows nothing about
// JPA annotations — so these three are listed explicitly. Without this,
// basic CRUD still works and the problem stays hidden until the first
// lazy association, which is a nasty way to find out.
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

val springBootVersion = libraries.findVersion("springBoot").get().requiredVersion
val springBootBom = "org.springframework.boot:spring-boot-dependencies:$springBootVersion"

dependencies {
    implementation(platform(springBootBom))
    testImplementation(platform(springBootBom))
}
