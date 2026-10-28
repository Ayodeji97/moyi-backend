plugins {
    id("spring-boot-service-convention")
}

dependencies {
    // Spring's RestClient, the synchronous HTTP client that replaced
    // RestTemplate (Spring 6.1). The Resend adapter talks to a two-endpoint
    // JSON API, which is not enough surface to justify a vendor SDK — doc 25
    // D7 wants the vendor confined to one class, and an HTTP call confines it
    // further than an SDK type ever could. Boot 4 split the client into its
    // own starter; `starter-web` no longer brings it.
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Jackson 3 (`tools.jackson`), the tree Spring 7 actually wires — see
    // modules/identity/build.gradle.kts for the Jackson 2 trap.
    implementation("tools.jackson.module:jackson-module-kotlin")

    // Push (Phase 4 N1, ADR-0044). The ledger and the push tokens are plain
    // JDBC: every statement here is an upsert or a conditional update that
    // JPA would only wrap in a native query. The outbox carries a request to
    // the consumer that sends it (common:events), and ids and the clock come
    // from common:core.
    implementation(projects.common.core)
    implementation(projects.common.events)
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // Nimbus JOSE, to sign the service account's assertion for Google's
    // OAuth2 token endpoint (RS256). Doc 25 D5: no hand-written crypto.
    // Through Spring Security's JOSE module rather than by name: Nimbus is
    // not in the Spring Boot BOM, and this way its version is the one the
    // resource server (common:security) already runs, from one source.
    implementation("org.springframework.security:spring-security-oauth2-jose")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // MockRestServiceServer against the RestClient builder: the adapter is
    // tested by asserting the HTTP it produces, not by mocking the client.
    testImplementation("org.springframework.boot:spring-boot-starter-restclient-test")
    // The ledger and the consumer against a real Postgres, as every module's
    // persistence tests are.
    testImplementation(projects.common.testing)
    testRuntimeOnly("org.springframework.boot:spring-boot-starter-flyway")
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
}
