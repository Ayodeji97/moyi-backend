import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification

plugins {
    id("spring-boot-service-convention")
}

// **The coverage floor does not apply to this module, and that is not a
// loophole.** Doc 12's 80% gate exists to keep the *application* tested; this
// module IS test infrastructure — Testcontainers base classes, a mutable
// clock, a counting id generator — and every line of it is executed by the
// suites of the modules that depend on it, where JaCoCo cannot attribute it.
// Measuring it in isolation would report a number that means nothing and would
// pressure someone into writing tests for a fixture rather than for behaviour.
//
// It became visible only when `MutableClockTest` arrived: until this module
// had a test source set at all, the verification task was skipped, so the gate
// was never applied here. Disabling it explicitly, with the reason, is more
// honest than deleting the test that exposed the question.
tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    enabled = false
}

// These are `api`, not `implementation`/`testImplementation`: this module's
// whole purpose is to be depended on from *other* modules' test source sets
// (`testImplementation(project(":common:testing"))`), so its own Testcontainers
// dependencies must be visible transitively to consumers.
dependencies {
    // DeterministicIdGenerator implements common:core's IdGenerator port, so
    // that type is part of this module's own surface — `api`, not `implementation`.
    api(projects.common.core)

    api("org.springframework.boot:spring-boot-testcontainers")
    // Testcontainers 2.x renamed these artifacts with a "testcontainers-"
    // prefix (junit-jupiter / postgresql -> testcontainers-junit-jupiter /
    // testcontainers-postgresql) — the pre-2.x names no longer exist.
    api("org.testcontainers:testcontainers-junit-jupiter")
    api("org.testcontainers:testcontainers-postgresql")
}
