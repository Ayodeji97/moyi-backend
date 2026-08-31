// Convention for the one bootable module: app/ (doc 25 §6 — "depends on
// everything"). Adds the Spring Boot plugin (bootJar/bootRun) on top of
// spring-boot-service-convention.

plugins {
    id("spring-boot-service-convention")
    id("org.springframework.boot")
}
