plugins {
    id("kotlin-common-convention")
    application
}

// Build-time tooling, deliberately outside the runtime graph: nothing in
// `app` or `modules/*` depends on this module, so the HTTP client that pulls
// ~100 GB from Have I Been Pwned cannot end up on the production classpath.
// It shares the filter's implementation with the service through
// common:security, which is the point — the code that writes the file and the
// code that reads it are the same code, so the format cannot drift between them.
dependencies {
    implementation(projects.common.security)
}

application {
    mainClass.set("com.moyi.tools.breachcorpus.MainKt")
}

// The corpus build is a long-running network job; the default 512 MB is
// generous for it (digests spill to a temp file rather than being held), but
// the filter itself is an 18 MB LongArray and the JVM should not be sizing
// its heap around that by trial and error.
tasks.named<JavaExec>("run") {
    jvmArgs("-Xmx1g")
}
