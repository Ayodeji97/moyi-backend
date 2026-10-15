plugins {
    id("spring-boot-service-convention")
}

dependencies {
    // Ids (UUID v7 for Bond-days, v4 for entries — doc 06 §1) and the Clock.
    implementation(projects.common.core)
    implementation(projects.common.events)
    // CurrentUser and the dedicated per-user entry submission bucket.
    implementation(projects.common.security)
    // ApiException, ErrorCode, NotFoundException, and the Idempotency-Key
    // interceptor this slice adds there (doc 06 §1, §2).
    implementation(projects.common.web)
    // `com.moyi.bond.api` only. `implementation`, not `api`: nothing that
    // depends on gratitude learns about bonds by doing so (ADR-0026).
    implementation(projects.modules.bond)
    // `com.moyi.identity.api` only: a member's locale, for the prompt's text (ADR-0038).
    // `implementation`, not `api`: nothing that depends on gratitude learns about
    // identity by doing so. The tests use it too, for `FakeUserDirectory`.
    implementation(projects.modules.identity)

    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation(projects.common.testing)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testRuntimeOnly("org.springframework.boot:spring-boot-starter-flyway")
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
}
