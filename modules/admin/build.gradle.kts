plugins {
    id("spring-boot-service-convention")
}

dependencies {
    implementation(projects.common.core)
    implementation(projects.common.security)
    implementation(projects.common.web)

    // The ports Task 1 added: the user lookup and the password hashing the
    // console borrows. Only their `api` packages are reachable from here.
    implementation(projects.modules.identity)
    implementation(projects.modules.bond)

    // A2's two moderation ports (spec §3): `gratitude.api.ReportModeration` and
    // `media.api.QuarantineModeration`. ArchitectureTest lets this module import nothing
    // else from either `api`.
    implementation(projects.modules.gratitude)
    implementation(projects.modules.media)

    // `export.api.AdminAccessLog`, which `AdminAccessDisclosure` implements (A2 spec §9.3,
    // ADR-0059 decision 8). export depends on no part of admin, so there is no cycle.
    implementation(projects.modules.export)

    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("tools.jackson.module:jackson-module-kotlin")
    // `moyi_admin_sign_in_total`, the sign-in counter (spec §10).
    implementation("io.micrometer:micrometer-core")

    // TOTP (RFC 6238) and the QR code for enrolment (spec §6).
    implementation(libs.java.otp)
    implementation(libs.zxing.core)

    testImplementation(projects.common.testing)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")

    // As identity's: the entities and repositories are `internal`, so this
    // module's persistence tests boot a small context of their own against a
    // Testcontainers Postgres, and need Flyway to build the schema from the
    // migrations on this module's classpath.
    testRuntimeOnly("org.springframework.boot:spring-boot-starter-flyway")
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
    // Flyway's own API, at compile time, for one test: V61MigrationTest stops the schema at
    // V60, writes A1's rows, and then migrates on, the only way to run V61 against rows that exist.
    testImplementation("org.flywaydb:flyway-core")
}
