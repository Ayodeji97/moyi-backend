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
    // JdbcTemplate for the idempotency_keys table (V11): the plain Spring
    // Framework artifact, not the `spring-boot-starter-jdbc` starter — and
    // this choice is load-bearing, not tidiness. The starter was tried
    // first, as an ordinary `implementation`, and `./gradlew build` was the
    // check that ruled it out (review round 1): merely having it on the
    // classpath makes Spring Boot's `DataSourceAutoConfiguration` build a
    // `DataSource` bean *eagerly*, as part of context refresh, whether or
    // not anything ever autowires one — which broke every test built on
    // `SecurityTestApplication` (common:security's context, deliberately
    // "no database at all") the moment the starter became transitive
    // through `common:web`. Swapping the starter for the bare
    // `org.springframework:spring-jdbc` artifact (also review round 1, the
    // reviewer's own hypothesis) fixes that at the source rather than
    // working around it: the starter is what drags in HikariCP, and
    // `DataSourceAutoConfiguration`'s pooled-datasource branch — the one
    // that built a `DataSource` unconditionally — is itself conditional on
    // HikariCP being present. Without the starter, that branch never
    // activates, `SecurityTestApplication` passes, and `IdempotencyKeyStore`
    // still gets a real, transitive `JdbcTemplate` type to compile against —
    // confirmed by re-running the full `./gradlew build` with this in place.
    // See `IdempotencyKeyStore`'s KDoc for the JPA half of this same
    // reasoning (why no `@Entity`, not just why no starter).
    implementation("org.springframework:spring-jdbc")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // The plain artifact above has no autoconfiguration in it — this
    // module's own tests need `spring-boot-starter-jdbc` back to get
    // `JdbcTemplateAutoConfiguration`, which is what actually builds the
    // `JdbcTemplate` *bean* from the real Postgres
    // testImplementation(projects.common.testing) starts below. Safe here
    // in a way it was not as a main dependency: this module's own test
    // context always configures a real `DataSource`, so the eager creation
    // above is exactly what should happen.
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
