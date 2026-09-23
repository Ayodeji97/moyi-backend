plugins {
    id("spring-boot-service-convention")
}

dependencies {
    // Spring's RestClient, the synchronous HTTP client that replaced
    // RestTemplate (Spring 6.1). The Resend adapter talks to a two-endpoint
    // JSON API, which is not enough surface to justify a vendor SDK — doc 25
    // D7 wants the vendor confined to one class, and an HTTP call confines it
    // further than an SDK type ever could. Boot 4 split the client into its
    // own starter; `starter-web` no longer brings it.
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Jackson 3 (`tools.jackson`), the tree Spring 7 actually wires — see
    // modules/identity/build.gradle.kts for the Jackson 2 trap.
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // MockRestServiceServer against the RestClient builder: the adapter is
    // tested by asserting the HTTP it produces, not by mocking the client.
    testImplementation("org.springframework.boot:spring-boot-starter-restclient-test")
}
