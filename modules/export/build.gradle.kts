plugins {
    id("spring-boot-service-convention")
}

// The data export (FR-009, FR-094, ADR-0059): a person asks for a copy of everything they are
// entitled to, a timed job builds it into a zip, and a signed URL to it lasts 24 hours. A module of
// its own because the archive gathers from five others (identity, bond, gratitude, media, support)
// and none of them may depend on all the rest; each is reached through its `api` package only, and
// each decides what of its own the person may have.

dependencies {
    // Ids (UUID v7 for an export) and the injected Clock.
    implementation(projects.common.core)

    // CurrentUser, and the `account:export:user` bucket.
    implementation(projects.common.security)

    // The error contract: NotFoundException's one 404.
    implementation(projects.common.web)

    // The outbox: an account deletion is told to this module by `AccountDeleted`, and the
    // person's archives are deleted from it (ADR-0058, ADR-0059 decision 9).
    implementation(projects.common.events)

    // Each module's `api` only: the profile and consents and the email (identity), the bonds
    // (bond), the archive as the app shows it (gratitude), the media and the archive's storage
    // (media), and the support messages (support).
    implementation(projects.modules.identity)
    implementation(projects.modules.bond)
    implementation(projects.modules.gratitude)
    implementation(projects.modules.media)
    implementation(projects.modules.support)

    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation(projects.common.testing)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testRuntimeOnly("org.springframework.boot:spring-boot-starter-flyway")
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
}
