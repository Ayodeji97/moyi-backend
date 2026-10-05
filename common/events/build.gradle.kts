plugins {
    id("spring-boot-service-convention")
}

dependencies {
    implementation(projects.common.core)
    implementation("org.springframework:spring-jdbc")
    implementation("org.springframework:spring-context")
    implementation("tools.jackson.module:jackson-module-kotlin")
    testImplementation(projects.common.testing)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    testRuntimeOnly("org.springframework.boot:spring-boot-starter-flyway")
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
}
