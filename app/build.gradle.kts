plugins {
    id("spring-boot-app-convention")
}

// group/version are inherited from the root build's `subprojects` block.

dependencies {
    implementation(projects.common.core)
    // `app` is the composition root: it depends on every module so that
    // their beans, entities and migrations are on the runtime classpath.
    implementation(projects.modules.identity)
    // Phase 2: bonds, members, invites and blocks (ADR-0026). Here for the
    // same reason identity is — beans, entities and V9 on the classpath.
    implementation(projects.modules.bond)
    // The email port and its Resend adapter. Wired here so the composition
    // root sees the EmailSender bean; identity depends on the module for the
    // `api` package only.
    implementation(projects.modules.notification)
    implementation(projects.common.web)
    // The filter chain, the JWT encoder/decoder and the current-user resolver.
    // Reachable transitively through identity already; declared so the
    // composition root names everything it composes.
    implementation(projects.common.security)
    // springdoc and the OpenAPI document's shape (ADR-0024).
    implementation(projects.contracts)

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    // Jackson 3 (`tools.jackson`), which is what Spring Boot 4 wires into
    // its message converters — not Jackson 2's `com.fasterxml.jackson`
    // module, which was here since Phase 0 and was never consulted.
    implementation("tools.jackson.module:jackson-module-kotlin")

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
    testImplementation(projects.common.testing)
    // Architecture tests (doc 25 §7 step 8) live here: `app` is the one
    // module with the whole project on its build path, and Konsist scans
    // by project directory, not Gradle dependency graph, so placement
    // doesn't need every module to depend on every other.
    testImplementation(libs.konsist)
    // Validates the generated OpenAPI document structurally (doc 12 §3.4).
    // swagger-parser depends on the javax flavour of swagger-core, which
    // carries the same class names as the jakarta flavour springdoc uses and
    // shadows it with classes that need javax.xml.bind — every request for
    // the document became a 500 until the javax pair was excluded.
    testImplementation(libs.swagger.parser) {
        exclude(group = "io.swagger.core.v3", module = "swagger-core")
        exclude(group = "io.swagger.core.v3", module = "swagger-models")
    }
    // The jakarta models the parser then resolves against, and the
    // OpenAPI type the contract test reads: `contracts` holds springdoc as
    // `implementation`, which does not reach this module's test compile classpath.
    testImplementation(libs.springdoc.webmvc.api)
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
