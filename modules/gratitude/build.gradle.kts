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

    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation(projects.common.testing)
    // F8 (whole-branch review): moved from `implementation` — nothing under
    // `src/main` imports `com.moyi.identity`. The `partner` display-name
    // field spec §3.4 names (ADR-0031 §Consequences: "GET /today ships
    // without the partner field") was deferred, so this module has no
    // production code that reaches identity yet. `test` is where it is
    // actually used: `GratitudeTestApplication`, `FakeUserDirectory` and
    // every HTTP test that needs a real `UserDirectory` bean to wire the
    // security chain around. `testImplementation` says so; the next slice
    // that builds `partner` moves this back to `implementation` rather than
    // discovering an unused production dependency nobody explained.
    testImplementation(projects.modules.identity)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testRuntimeOnly("org.springframework.boot:spring-boot-starter-flyway")
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
}
