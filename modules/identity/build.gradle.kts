plugins {
    id("spring-boot-service-convention")
}

dependencies {
    // The IdGenerator port. `implementation`, not `api`: nothing outside this
    // module should learn about common:core by depending on identity.
    implementation(projects.common.core)

    // JPA annotations and Spring Data repositories.
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // This module owns its own HTTP edge — controllers and request DTOs live
    // in `identity.web`, not in `app`, which only wires things together.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation(projects.common.web)
    // Kotlin-aware deserialisation. Note the `tools.jackson` group: Spring
    // Boot 4 / Spring 7 use **Jackson 3**, which is a different artifact tree
    // from Jackson 2's `com.fasterxml.jackson`. Both are on the classpath and
    // only the Jackson 3 one is wired into the message converters, so the
    // Jackson 2 Kotlin module this repo carried since Phase 0 was registering
    // with a mapper nobody uses. Without this, deserialising any Kotlin data
    // class fails with "Type definition error" — see docs/learning-log.md.
    implementation("tools.jackson.module:jackson-module-kotlin")

    // Argon2id (NFR-046). spring-security-crypto is the standalone half of
    // Spring Security — encoders only, no filter chain, no servlet coupling —
    // so it costs nothing to depend on before there is any authentication.
    // BouncyCastle is not optional: Argon2PasswordEncoder calls straight into
    // its Argon2BytesGenerator. Doc 25 D5: no hand-written crypto, ever.
    implementation("org.springframework.security:spring-security-crypto")
    runtimeOnly(libs.bouncycastle.provider)

    testImplementation(projects.common.testing)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Spring Boot 4 split MockMvc support out of spring-boot-test-autoconfigure
    // into a stack-specific module — needed explicitly, as in `app`.
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")

    // This module's persistence tests boot a small Spring context of their own
    // against a Testcontainers Postgres. They have to: the entities and
    // repositories are `internal`, so a test in `app` could not name them —
    // which is the architecture rule doing its job, not an obstacle to route
    // around. That means the module's own test runtime needs the pieces `app`
    // would otherwise supply: a driver, and Flyway to build the schema from
    // this module's own migration.
    testRuntimeOnly("org.springframework.boot:spring-boot-starter-flyway")
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
}
