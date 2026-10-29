plugins {
    id("spring-boot-service-convention")
}

dependencies {
    // `media` is a leaf (spec §2.1): it depends on `common` and on no domain
    // module, so that `gratitude` may depend on its `api` in D2 without a cycle.
    // Ids (v4, doc 06 §1) and the clock; the outbox (`MediaUploaded`); the
    // caller and the `media:intent` bucket; ApiException, ErrorCode and the
    // Idempotency-Key that completion requires (doc 06 §1).
    implementation(projects.common.core)
    implementation(projects.common.events)
    implementation(projects.common.security)
    implementation(projects.common.web)

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Plain JDBC, as notification's ledger is: every write here is a
    // conditional update under a row lock that JPA would only wrap.
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("tools.jackson.module:jackson-module-kotlin")
    // `gratitude.media.processing.duration` (doc 11:49), as the scheduling jobs' meters are.
    implementation("io.micrometer:micrometer-core")

    // The S3 API, for R2 (ADR-0047). The vendor appears in one internal class,
    // `R2ObjectStore` (doc 25:118). The JDK's URLConnection is the transport:
    // the SDK's default Apache and Netty clients are two more HTTP stacks to
    // audit for five calls, so both are excluded.
    implementation(platform(libs.awssdk.bom))
    implementation("software.amazon.awssdk:s3") {
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation("software.amazon.awssdk:url-connection-client")
    // Photo moderation (the owner's ruling Q1, ADR-0051): Rekognition's
    // DetectModerationLabels, in one internal class, `RekognitionImageModerator`,
    // over the same URLConnection transport.
    implementation("software.amazon.awssdk:rekognition") {
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }

    testImplementation(projects.common.testing)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    // MinIO stands in for R2 (spec §4 D1): the same SigV4 presigning, the same
    // PUT, HEAD, GET and DELETE, on a container the test owns.
    testImplementation("org.testcontainers:testcontainers-minio")
    testRuntimeOnly("org.springframework.boot:spring-boot-starter-flyway")
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
}
