plugins {
    id("spring-boot-app-convention")
}

group = "com.moyi"
version = "0.0.1-SNAPSHOT"

dependencies {
    implementation(project(":common:core"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Spring Boot 4 split MockMvc test support out of spring-boot-test-
    // autoconfigure into a stack-specific module — needed explicitly now.
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
}

springBoot {
    mainClass.set("com.moyi.app.MoyiApplicationKt")
}
