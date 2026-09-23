// In a Kotlin DSL build script `java` resolves to the JavaPluginExtension, not
// to the package root — so `java.security.MessageDigest` below is an
// unresolved reference without this. Imports have to precede `plugins {}`.
import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("spring-boot-service-convention")
}

dependencies {
    // The Bloom filter behind the breached-password check (ADR-0012). It
    // lives in common:security rather than here because the builder in
    // tools:breach-corpus writes the same format this reads — one definition,
    // so the file cannot drift between the job that makes it and the service
    // that loads it.
    implementation(projects.common.security)

    // The IdGenerator port. `implementation`, not `api`: nothing outside this
    // module should learn about common:core by depending on identity.
    implementation(projects.common.core)

    // The email port (ADR-0017). Only `com.moyi.notification.api` is reachable
    // from here — everything else in that module is `internal` — which is the
    // module boundary doing its job: identity knows an email can be sent and
    // nothing about how.
    implementation(projects.modules.notification)

    // JPA annotations and Spring Data repositories.
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // This module owns its own HTTP edge — controllers and request DTOs live
    // in `identity.web`, not in `app`, which only wires things together.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation(projects.common.web)
    // Kotlin-aware deserialisation. Note the `tools.jackson` group: Spring
    // Boot 4 / Spring 7 use **Jackson 3**, which is a different artifact tree
    // from Jackson 2's `com.fasterxml.jackson`. Both are on the classpath and
    // only the Jackson 3 one is wired into the message converters, so the
    // Jackson 2 Kotlin module this repo carried since Phase 0 was registering
    // with a mapper nobody uses. Without this, deserialising any Kotlin data
    // class fails with "Type definition error" — see docs/learning-log.md.
    implementation("tools.jackson.module:jackson-module-kotlin")

    // Argon2id (NFR-046). spring-security-crypto is the standalone half of
    // Spring Security — encoders only, no filter chain, no servlet coupling —
    // so it costs nothing to depend on before there is any authentication.
    // BouncyCastle is not optional: Argon2PasswordEncoder calls straight into
    // its Argon2BytesGenerator. Doc 25 D5: no hand-written crypto, ever.
    implementation("org.springframework.security:spring-security-crypto")
    runtimeOnly(libs.bouncycastle.provider)

    testImplementation(projects.common.testing)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Spring Boot 4 split MockMvc support out of spring-boot-test-autoconfigure
    // into a stack-specific module — needed explicitly, as in `app`.
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")

    // This module's persistence tests boot a small Spring context of their own
    // against a Testcontainers Postgres. They have to: the entities and
    // repositories are `internal`, so a test in `app` could not name them —
    // which is the architecture rule doing its job, not an obstacle to route
    // around. That means the module's own test runtime needs the pieces `app`
    // would otherwise supply: a driver, and Flyway to build the schema from
    // this module's own migration.
    testRuntimeOnly("org.springframework.boot:spring-boot-starter-flyway")
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
}

// --- The breached-password corpus -----------------------------------------
//
// ADR-0016: the corpus is a pinned, digest-verified build input, not a file in
// the repository and not a runtime fetch. `gradle/breach-corpus.properties`
// carries the release tag and the SHA-256; this task downloads it once per
// machine into the Gradle user home, verifies it, and puts it where
// `processResources` will package it.
//
// The download is unconditional rather than wired only to `bootJar`, because
// `BloomFilterBreachedPasswordCorpus` refuses to start without the file — so a
// context that boots in a test needs it exactly as much as the image does.
// After the first fetch it is a file copy: the cache is keyed by digest, so a
// corpus bump is a new download and a rebuild of the same version is not.

val corpusPin =
    Properties().apply {
        rootProject.file("gradle/breach-corpus.properties").inputStream().use(::load)
    }
val corpusVersion: String = corpusPin.getProperty("version")
val corpusSha256: String = corpusPin.getProperty("sha256")
val corpusUrl: String =
    providers
        .gradleProperty("moyi.breachCorpusUrl")
        .getOrElse("https://github.com/Ayodeji97/moyi-backend/releases/download/$corpusVersion/pwned-passwords.bloom")

val corpusOutputDir: Directory = layout.buildDirectory.dir("breach-corpus").get()
val corpusCache: File = File(gradle.gradleUserHomeDir, "moyi/breach-corpus/$corpusSha256.bloom")

val downloadBreachCorpus =
    tasks.register("downloadBreachCorpus") {
        description = "Downloads the pinned breached-password corpus and verifies its SHA-256 (ADR-0016)."
        group = "build"

        val url = corpusUrl
        val expected = corpusSha256
        val version = corpusVersion
        val cache = corpusCache
        val target = corpusOutputDir.file("security/pwned-passwords.bloom").asFile

        // Both, so that a re-pin re-runs the task and an unchanged pin does not.
        inputs.property("url", url)
        inputs.property("sha256", expected)
        outputs.file(target)

        doLast {
            check(expected != "PENDING" && version != "PENDING") {
                "gradle/breach-corpus.properties has no corpus pinned. Run the `Breach corpus` workflow and " +
                    "paste the two values it prints. ADR-0012 makes this control the price of the 8-character " +
                    "minimum, so there is deliberately no way to build without it."
            }

            if (!cache.isFile || cache.sha256() != expected) {
                cache.parentFile.mkdirs()
                // Downloaded beside the cache entry and renamed only after the
                // digest matches, so an interrupted fetch cannot leave a
                // truncated file that later runs treat as cached.
                val partial = File(cache.parentFile, "$expected.part")
                logger.lifecycle("Downloading the breached-password corpus $version")
                URI(url).toURL().openStream().use { source ->
                    partial.outputStream().use { source.copyTo(it) }
                }
                val actual = partial.sha256()
                check(actual == expected) {
                    partial.delete()
                    "The corpus at $url has SHA-256 $actual, but gradle/breach-corpus.properties pins $expected. " +
                        "Refusing to build: a corpus that is not the one we pinned is not a corpus we have reviewed."
                }
                partial.renameTo(cache)
            }

            target.parentFile.mkdirs()
            cache.copyTo(target, overwrite = true)
        }
    }

fun File.sha256(): String =
    inputStream().use { stream ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

sourceSets.main {
    resources.srcDir(corpusOutputDir)
}

tasks.named("processResources") {
    dependsOn(downloadBreachCorpus)
}
