plugins {
    id("spring-boot-service-convention")
}

// These are `api`, not `implementation`/`testImplementation`: this module's
// whole purpose is to be depended on from *other* modules' test source sets
// (`testImplementation(project(":common:testing"))`), so its own Testcontainers
// dependencies must be visible transitively to consumers.
dependencies {
    api("org.springframework.boot:spring-boot-testcontainers")
    // Testcontainers 2.x renamed these artifacts with a "testcontainers-"
    // prefix (junit-jupiter / postgresql -> testcontainers-junit-jupiter /
    // testcontainers-postgresql) — the pre-2.x names no longer exist.
    api("org.testcontainers:testcontainers-junit-jupiter")
    api("org.testcontainers:testcontainers-postgresql")
}
