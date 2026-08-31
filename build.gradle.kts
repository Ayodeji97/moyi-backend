// Root project — no plugins applied here. Every module gets its build
// behaviour from a convention plugin in build-logic/ (doc 25 §2/§6).
// Group and version are set once and inherited, so modules don't each
// restate them.

group = "com.moyi"
version = "0.0.1-SNAPSHOT"

subprojects {
    group = rootProject.group
    version = rootProject.version
}
