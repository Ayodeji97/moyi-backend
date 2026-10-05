plugins {
    id("spring-boot-service-convention")
}

dependencies {
    // The Clock.
    implementation(projects.common.core)
    // IdempotencyKeyStore, for the reaper (ADR-0031, Owed).
    implementation(projects.common.web)
    // `gratitude.api.DayCloser` and nothing else of it: this module knows
    // when, never what (spec §2.2). The compiler holds the "nothing else":
    // everything in `gratitude` outside its `api` package is Kotlin
    // `internal`, and ArchitectureTest fails a declaration there that is not.
    implementation(projects.modules.gratitude)

    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("io.micrometer:micrometer-core")
    implementation(libs.shedlock.spring)
    implementation(libs.shedlock.jdbc)

    testImplementation(projects.common.testing)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.springframework.boot:spring-boot-starter-flyway")
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
}
