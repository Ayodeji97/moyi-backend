plugins {
    id("spring-boot-service-convention")
}

dependencies {
    // Ids (UUID v7 for bonds, members and invites) and the injected Clock.
    implementation(projects.common.core)

    // CurrentUser, and from slice B2 the rate-limit buckets.
    implementation(projects.common.security)

    // ApiException, ErrorCode, NotFoundException — the error contract every
    // module answers in (doc 06 §2).
    implementation(projects.common.web)

    // `com.moyi.identity.api` only — is this user verified, what is their
    // display name. Everything else in that module is `internal`, so the
    // module boundary is the compiler's to hold rather than a convention's
    // (ADR-0026). `implementation`, not `api`: nothing that depends on bond
    // learns about identity by doing so.
    implementation(projects.modules.identity)

    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // This module owns its own HTTP edge — controllers and DTOs live in
    // `bond.web`, not in `app`, which only wires things together.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Kotlin-aware deserialisation. Note `tools.jackson`: Spring Boot 4 uses
    // Jackson 3, a different artifact tree from Jackson 2's
    // `com.fasterxml.jackson`, and only the Jackson 3 module is wired into
    // the message converters.
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation(projects.common.testing)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Spring Boot 4 split MockMvc support out of spring-boot-test-autoconfigure
    // into a stack-specific module — needed explicitly, as in `app`.
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")

    // This module's tests boot a small Spring context of their own against a
    // Testcontainers Postgres, because everything here is `internal` and a
    // test in `app` could not name it. That means the test runtime needs what
    // `app` would otherwise supply: a driver, and Flyway to build the schema
    // from this module's own migration.
    testRuntimeOnly("org.springframework.boot:spring-boot-starter-flyway")
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
}
