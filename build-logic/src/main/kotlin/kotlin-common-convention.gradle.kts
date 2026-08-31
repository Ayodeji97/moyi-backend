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

// The generated `LibrariesForLibs` typed accessor is unreliable for a
// precompiled script plugin inside an included build-logic build, so the
// catalog is read through the plain (always-available) extension API instead.
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

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
        freeCompilerArgs.add("-Xjsr305=strict")
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
