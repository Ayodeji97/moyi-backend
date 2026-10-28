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
    // `com.moyi.notification.api` only: `Notifications.request`, for the daily
    // reminder (spec §4 N2) and, from N3, the pushes a day earns. `notification`
    // is a leaf, so this makes no cycle (spec §2.1, ADR-0044).
    implementation(projects.modules.notification)
    // `com.moyi.media.api` only: FR-044a's claim of a photo for an entry, and how a
    // photo on an entry is shown to its reader (spec §4 D2, ADR-0048). `media` is
    // a leaf, so this makes no cycle (spec §2.1, ADR-0047).
    implementation(projects.modules.media)

    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation(projects.common.testing)
    // A registry for `media`'s processing meter, which this module's test context scans.
    testImplementation("io.micrometer:micrometer-core")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testRuntimeOnly("org.springframework.boot:spring-boot-starter-flyway")
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
}

// SearchTermsTest holds the committed contract's search limits to SearchTerms (ADR-0039): the
// document is an input, so a change to it alone reruns the test rather than reusing a result.
tasks.test {
    inputs
        .file(rootProject.layout.projectDirectory.file("contracts/openapi.json"))
        .withPropertyName("contract")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
