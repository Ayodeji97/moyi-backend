plugins {
    id("spring-boot-service-convention")
}

// In-app support (FR-102, ADR-0057): a member writes to a person, and is told by
// email that it arrived. A small module of its own: a ticket is neither an account
// (identity), a bond's (bond, gratitude) nor a delivery (notification), and the
// admin console that will read it (Phase 5) reads it through this module's api.

dependencies {
    // Ids (UUID v7 for a ticket) and the injected Clock.
    implementation(projects.common.core)

    // CurrentUser, and the `support:create:user` bucket.
    implementation(projects.common.security)

    // The error contract, and the Idempotency-Key a resent message is not filed twice under.
    implementation(projects.common.web)

    // `com.moyi.identity.api` only: `AccountMail`, which writes to the account holder's
    // address without telling this module what the address is (ADR-0057 decision 6).
    implementation(projects.modules.identity)

    implementation("org.springframework.boot:spring-boot-starter-jdbc")
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
