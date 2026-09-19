import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Base convention for every Kotlin module in the build: JVM toolchain,
// compiler options, and the quality gates (ktlint, detekt) from doc 25 §2 /
// doc 18. Applied directly by common/* and modules/* (kotlin-common-convention
// alone) and transitively by spring-boot-service-convention / spring-boot-app-convention.

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("io.gitlab.arturbosch.detekt")
    id("org.jlleitschuh.gradle.ktlint")
    jacoco
}

// `libraries` is defined in VersionCatalogExt.kt — see there for why the
// generated typed accessor can't be used from a convention plugin.
val libs = libraries

kotlin {
    // Compiled and run WITH a JDK 25 toolchain, but the emitted bytecode
    // targets JVM_24: Kotlin 2.2.21's compiler doesn't yet recognise JDK 25
    // as a target (falls back silently to 24, which then conflicts with
    // javac's default target of 25 in the same module). Class files built
    // for 24 run fine on a 25 runtime — virtual threads etc. are JDK
    // APIs/runtime behaviour, not bytecode-version-gated. Revisit once
    // Kotlin adds JVM_25 to its JvmTarget enum.
    jvmToolchain(25)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_24)
        freeCompilerArgs.addAll(
            "-Xjsr305=strict",
            // Opts in early to Kotlin's future default: an annotation on a
            // constructor `val` applies to both the parameter and the
            // property, instead of the parameter only. Without it, Kotlin
            // warns on every such annotation and asks for an explicit
            // `@param:`/`@property:` prefix. The course sets this too.
            "-Xannotation-default-target=param-property",
        )
        // Doc 18 §3: nullability must be meaningful — treat platform-type
        // leniency from Java interop as an error, not a warning.
        allWarningsAsErrors.set(false)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(24)
}

tasks.withType<Test>().configureEach {
    // Testcontainers' Ryuk reaper bind-mounts the Docker socket into a
    // container it spawns, using the path as seen INSIDE the Docker host —
    // always /var/run/docker.sock, regardless of where the client (this
    // JVM) reaches that socket from. Harmless default for Docker Desktop;
    // required for Colima, where docker.host (set per-developer in
    // ~/.testcontainers.properties — see README) is a different path.
    environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock")
}

// No custom detekt.yml yet — running on detekt's built-in default ruleset
// until real code surfaces false positives worth tuning (Phase 1+).
detekt {
    buildUponDefaultConfig = true
}

// detekt 1.23.8 bundles its own (older) compiler frontend, which only
// accepts jvm-target up to 22 — independent of the project's own
// Kotlin/Java target set above. Not a functional constraint: detekt only
// parses source for static analysis, it doesn't emit bytecode.
//
// NOTE: detekt's Gradle plugin runs its CLI in-process (reflection into a
// cached classloader — confirmed by decompiling DefaultCliInvoker, it
// never shells out to `java`), so `jdkHome` on the Detekt task does NOT
// select which JVM executes it, despite existing as a property — that
// stays whatever JVM launched the Gradle daemon. So the daemon's own JVM
// is the only lever, and it is now pulled in `gradle/gradle-daemon-jvm.properties`
// (Gradle 9 daemon JVM criteria, `./gradlew updateDaemonJvm --jvm-version=21`):
// the daemon runs on 21 everywhere, while jvmToolchain(25) above still
// resolves 25 separately for anything that supports out-of-process
// toolchain selection (compile, test, run) — verified by the emitted
// bytecode being major version 68 (Java 24), which a 21 compiler cannot
// produce.
//
// This was CI-only until 2026-09-19, and that asymmetry meant `./gradlew
// build` FAILED on the machine the code is written on while CI was green
// — the third instance in this repo of a gate that only worked where
// nobody was looking (see docs/learning-log.md).
//
// DELETE `gradle/gradle-daemon-jvm.properties` (and this note, and the
// `jvmTarget` override below) the moment detekt can run on the project's
// own JDK. detekt is the ONLY reason the daemon is held back; nothing this
// project ships is built on 21. As of 2026-09-19 there is no newer detekt
// — 1.23.8 is the latest release and no 2.x exists on Maven Central under
// either coordinate — so this is a workaround with no better option, not a
// preference.
//
// Known latent risk in that file: `updateDaemonJvm` writes `toolchainUrl.*`
// entries pinning JDK 21 by opaque foojay build ID. They make provisioning
// reproducible, but they are only ever exercised on a machine with no local
// JDK 21 — which is neither the dev machine nor CI (both install it
// directly). If foojay retires those IDs, the failure appears first for a
// fresh clone. The file is left exactly as generated rather than
// hand-trimmed; if that path ever breaks, delete the `toolchainUrl.*`
// lines and keep `toolchainVersion=21`, which requires a locally
// installed 21 and fails with a clear message instead of a broken
// redirect.
tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "21"
}

ktlint {
    version.set("1.8.0")
}

testing {
    suites {
        getByName<JvmTestSuite>("test") {
            useJUnitJupiter()
            dependencies {
                implementation(platform("org.junit:junit-bom:${libs.findVersion("junit").get().requiredVersion}"))
                implementation(libs.findLibrary("kotest-assertions-core").get())
                implementation(libs.findLibrary("mockk").get())
            }
        }
    }
}

// Doc 12: 80% line coverage floor (90% on packages the project later
// designates critical — none yet at Phase 0). `check` fails the build
// below the floor rather than just reporting it.
//
// `*ApplicationKt` (the @SpringBootApplication file's compiled name) is
// excluded: a `main` that only calls `runApplication` has nothing
// meaningful to unit-test, and every real Spring Boot project excludes
// it the same way — leaving it in would pressure someone into writing a
// test that exists purely to move a coverage number, not to catch a bug.
val jacocoExclusions = listOf("**/*ApplicationKt.class")

tasks.named<Test>("test") {
    finalizedBy(tasks.named("jacocoTestReport"))
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    classDirectories.setFrom(
        classDirectories.files.map { fileTree(it) { exclude(jacocoExclusions) } },
    )
}

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.named("jacocoTestReport"))
    classDirectories.setFrom(
        classDirectories.files.map { fileTree(it) { exclude(jacocoExclusions) } },
    )
    violationRules {
        rule {
            limit {
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.named("jacocoTestCoverageVerification"))
}
