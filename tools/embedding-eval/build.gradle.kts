plugins {
    id("spring-boot-service-convention")
    application
}

// Build-time tooling, outside the runtime graph as `tools:breach-corpus` is:
// nothing in `app` or `modules/*` depends on it. It is run by hand, with the
// owner's provider keys, to embed the golden set (Task 9, ADR-0054), and it
// reaches the providers only through `modules:ai`'s own adapters, so the
// vectors it writes are the vectors production would make.
dependencies {
    implementation(projects.modules.ai)
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-web")
    implementation("io.micrometer:micrometer-core")
    implementation("tools.jackson.core:jackson-databind")
}

application {
    mainClass.set("com.moyi.tools.embeddingeval.MainKt")
}

tasks.named<JavaExec>("run") {
    // As for breach-corpus: a relative path means what it means at the repo
    // root, where the golden set's default path is written from.
    workingDir = rootDir
}
