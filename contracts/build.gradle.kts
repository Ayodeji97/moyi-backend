plugins {
    id("spring-boot-service-convention")
}

// The OpenAPI document the KMP client is generated from (doc 05 §stack,
// doc 06 §5, ADR-0024). This module owns springdoc's configuration — the
// API's identity, the bearer scheme, and the rule that gives every operation
// its RFC 9457 error responses — and nothing else: the routes themselves
// are discovered from the modules at runtime, so nothing here names a
// controller.
dependencies {
    // `webmvc-api`, not `webmvc-ui`: the document, without a Swagger UI in
    // the application. Public documentation lives on Cloudflare Pages (doc 10).
    implementation(libs.springdoc.webmvc.api)
    implementation("org.springframework.boot:spring-boot-starter-web")
    // ErrorCode and ProblemDetails, so the error schema is derived from the
    // one registry rather than restated.
    implementation(projects.common.web)
    // The public-endpoint list the security override is derived from, and the
    // two argument-resolver types that must not appear as parameters.
    implementation(projects.common.security)
}
