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

    // **JavaExec's working directory defaults to the *subproject* directory**,
    // not the directory Gradle was invoked from. So `--output build/x.bloom`
    // written by someone standing at the repo root lands in
    // `tools/breach-corpus/build/x.bloom`, and every later step that looks for
    // it at the root finds nothing.
    //
    // That is not hypothetical: it is what happened on the first real corpus
    // run. The 10.5M-digest corpus built correctly in 19 minutes and was
    // written somewhere nobody was looking, after which upload-artifact logged
    // a warning, action-gh-release published an empty release, and both
    // reported success.
    //
    // Anchoring to the root makes a relative path mean what a person typing it
    // at the root would expect. It was invisible in local testing because
    // absolute paths were used there — which is its own lesson.
    workingDir = rootDir
}
