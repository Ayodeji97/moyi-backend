plugins {
    id("spring-boot-service-convention")
}

dependencies {
    // Spring's own RFC 9457 type (org.springframework.http.ProblemDetail) and
    // the @RestControllerAdvice that produces it.
    implementation("org.springframework.boot:spring-boot-starter-web")
    // Bean Validation's exception types, so the handler can translate a failed
    // @Valid into the `errors` array doc 06 §2 specifies.
    implementation("org.springframework.boot:spring-boot-starter-validation")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Jackson 3 (`tools.jackson`) is what Spring 7 wires into its message
    // converters, and its Kotlin module is what lets a data class be
    // constructed at all. The standalone MockMvc used in these tests builds
    // its own converter, so it has to be told the same thing Boot would.
    testImplementation("tools.jackson.module:jackson-module-kotlin")
}
