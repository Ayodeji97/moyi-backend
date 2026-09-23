plugins {
    id("spring-boot-service-convention")
}

dependencies {
    // The IdGenerator port (`jti`), the injected Clock, and the RFC 9457
    // builder that every 401 and 403 has to go through so the client sees one
    // error shape (doc 06 §2).
    implementation(projects.common.core)
    implementation(projects.common.web)

    // Spring Security's resource-server support: the `SecurityFilterChain`,
    // `BearerTokenAuthenticationFilter`, `NimbusJwtDecoder` and
    // `NimbusJwtEncoder`, with Nimbus JOSE underneath. Doc 25 D5 draws the
    // line here — we write the token *lifecycle*, the library does every byte
    // of crypto and protocol, and there is no hand-written filter.
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    // The entry point and denied handler write a problem body straight to the
    // servlet response, and the current-user resolver plugs into MVC.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(projects.common.testing)
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    // The chain test asserts the health endpoint stays public, which needs one to exist.
    testImplementation("org.springframework.boot:spring-boot-starter-actuator")
}
