plugins {
    id("spring-boot-app-convention")
}

// group/version are inherited from the root build's `subprojects` block.

dependencies {
    implementation(projects.common.core)

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
    testImplementation(projects.common.testing)
    // Architecture tests (doc 25 §7 step 8) live here: `app` is the one
    // module with the whole project on its build path, and Konsist scans
    // by project directory, not Gradle dependency graph, so placement
    // doesn't need every module to depend on every other.
    testImplementation(libs.konsist)
}

springBoot {
    mainClass.set("com.moyi.app.MoyiApplicationKt")
}
