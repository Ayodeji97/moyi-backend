plugins {
    id("spring-boot-service-convention")
}

dependencies {
    // Spring AI 2.0.x (ADR-0054): the EmbeddingModel abstraction and its OpenAI
    // implementation, without the starter's autoconfiguration. AiConfiguration
    // builds the one bean itself, as PushSenderConfiguration does.
    implementation(platform(libs.spring.ai.bom))
    implementation(projects.common.core)
    implementation(libs.spring.ai.model)
    implementation(libs.spring.ai.openai)
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // The RestClient.Builder bean the Voyage model is built from, as notification uses.
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("io.micrometer:micrometer-core")

    testImplementation(projects.common.testing)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
