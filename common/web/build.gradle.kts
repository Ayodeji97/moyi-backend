plugins {
    id("spring-boot-service-convention")
}

dependencies {
    // Spring's own RFC 9457 type (org.springframework.http.ProblemDetail) and
    // the @RestControllerAdvice that produces it.
    implementation("org.springframework.boot:spring-boot-starter-web")
    // Bean Validation's exception types, so the handler can translate a failed
    // @Valid into the `errors` array doc 06 §2 specifies.
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // The injected Clock and IdGenerator (doc 18 §3) that
    // IdempotencyInterceptor stamps its reservation rows with.
    implementation(projects.common.core)
    // JdbcTemplate for the idempotency_keys table (V11) — compileOnly, and
    // this is load-bearing, not tidiness. `implementation` was tried first
    // and `./gradlew build` was the check that ruled it out: merely having
    // spring-boot-starter-jdbc on the classpath makes Spring Boot's
    // DataSourceAutoConfiguration build a `DataSource` bean *eagerly*, as
    // part of context refresh, whether or not anything ever autowires one —
    // which broke every test built on `SecurityTestApplication`
    // (common:security's context, deliberately "no database at all") the
    // moment this dependency became transitive through `common:web`.
    // `compileOnly` lets IdempotencyKeyStore compile against `JdbcTemplate`
    // here without forcing the starter onto any consumer's classpath;
    // `app` already brings the real thing transitively through its own
    // spring-boot-starter-data-jpa, and this module's own tests add it back
    // below, where a real Postgres is actually configured. See
    // IdempotencyKeyStore's KDoc for the JPA half of this same reasoning.
    compileOnly("org.springframework.boot:spring-boot-starter-jdbc")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // compileOnly above is not visible to the test source set by default;
    // this module's own tests need the real thing, against the real
    // Postgres testImplementation(projects.common.testing) starts below.
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    // Spring Boot 4 split MockMvc's auto-configuration out of
    // spring-boot-test-autoconfigure into a stack-specific module, needed by
    // IdempotencyInterceptorTest's @AutoConfigureMockMvc (the standalone
    // MockMvc the other tests in this module use doesn't need it).
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    // Jackson 3 (`tools.jackson`) is what Spring 7 wires into its message
    // converters, and its Kotlin module is what lets a data class be
    // constructed at all. The standalone MockMvc used in these tests builds
    // its own converter, so it has to be told the same thing Boot would.
    testImplementation("tools.jackson.module:jackson-module-kotlin")
    // A real Postgres for IdempotencyInterceptorTest (doc 12: prefer
    // real-Postgres integration tests over mocks) — the same shape bond's
    // own module test context uses.
    testImplementation(projects.common.testing)
    testRuntimeOnly("org.springframework.boot:spring-boot-starter-flyway")
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
}
