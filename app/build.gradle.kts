plugins {
    id("spring-boot-app-convention")
}

group = "com.moyi"
version = "0.0.1-SNAPSHOT"

dependencies {
    implementation(project(":common:core"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // Spring Boot 4 split Flyway's autoconfiguration out of the plain
    // flyway-core library into this dedicated starter (same pattern as
    // spring-boot-starter-webmvc-test) — without it, Flyway is on the
    // classpath but never actually runs. The starter alone doesn't know
    // about Postgres specifically though ("Unsupported Database:
    // PostgreSQL 18.6") — flyway-database-postgresql is still needed too.
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Spring Boot 4 split MockMvc test support out of spring-boot-test-
    // autoconfigure into a stack-specific module — needed explicitly now.
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation(project(":common:testing"))
    // Architecture tests (doc 25 §7 step 8) live here: `app` is the one
    // module with the whole project on its build path, and Konsist scans
    // by project directory, not Gradle dependency graph, so placement
    // doesn't need every module to depend on every other.
    testImplementation(libs.konsist)
}

springBoot {
    mainClass.set("com.moyi.app.MoyiApplicationKt")
}

// ArchitectureTest uses Konsist, which reads Kotlin source straight off
// disk across the whole repo rather than through the compile classpath.
// Gradle can't infer that, so without declaring it, adding a rule-violating
// file in another module leaves `:app:test` UP-TO-DATE and the
// architecture rules silently do not run — verified: a deliberate
// violation in modules/identity was reported as UP-TO-DATE and passed,
// and only failed once forced with --rerun. Declaring the sources as an
// input makes the rules re-evaluate whenever any module's code changes.
//
// `src/*/kotlin`, not `src/main/kotlin`: Konsist's scopeFromProject()
// scans test sources too — the field-injection rule depends on seeing
// HealthCheckTest — so tracking main only would leave the same staleness
// hole open for a violation introduced under another module's src/test.
tasks.named<Test>("test") {
    inputs
        .files(
            fileTree(rootDir) {
                include("**/src/*/kotlin/**/*.kt")
                exclude("**/build/**", "**/.gradle/**")
            },
        ).withPropertyName("konsistScannedSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
