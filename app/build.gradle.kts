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

    implementation("org.flywaydb:flyway-core")
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
