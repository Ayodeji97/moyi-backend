plugins {
    id("spring-boot-service-convention")
}

dependencies {
    // The IdGenerator port. `implementation`, not `api`: nothing outside this
    // module should learn about common:core by depending on identity.
    implementation(projects.common.core)

    // JPA annotations and Spring Data repositories.
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    testImplementation(projects.common.testing)
    testImplementation("org.springframework.boot:spring-boot-starter-test")

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
