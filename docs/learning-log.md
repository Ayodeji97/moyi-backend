# Learning Log

One entry per session, three lines (format: `documents/16-learning-plan-and-course-mapping.md` §5).

## 2026-08-31 · Phase 0 · Gradle 9 skeleton on Spring Boot 4.1 / Kotlin 2.2 / JDK 25
Expected: `jvmToolchain(25)` plus the standard convention-plugin setup from the
         course would just work — doc 25 §5 flagged Boot 4.x build friction,
         but I expected it to be about plugin IDs and dependency-management
         syntax, not the JDK itself.
Reality: JDK 25 shipped ahead of its own ecosystem. Kotlin 2.2.21's compiler
         doesn't have a `JVM_25` target yet (silently falls back to 24,
         which then fights javac's default of 25 in the same module);
         detekt 1.23.8 bundles an older frontend capped at JVM 22 entirely,
         independent of the project's own target; and a version-catalog
         declared in both the root and an included `build-logic` build
         (the standard convention-plugin pattern) collides on Gradle 9.7 —
         it turns out Gradle auto-creates a `libs` catalog from
         `gradle/libs.versions.toml` at its default location, so my
         explicit declaration was a second, colliding `from()` call.
         Separately — not a JDK-25 issue — Spring Boot 4 quietly moved
         `@AutoConfigureMockMvc` out of `spring-boot-test-autoconfigure`
         into a new stack-specific `spring-boot-webmvc-test` module under
         package `org.springframework.boot.webmvc.test.autoconfigure`,
         which no tutorial written before this release would show.
Wrong about: assuming a JDK LTS release and a framework's stated baseline
         support ("Kotlin 2.2 baseline") mean the whole toolchain
         (compiler, static analysis, test infra) is already caught up on
         day one. It wasn't wrong to start on 25/4.1 — doc 25 §5's own
         reasoning about not starting a greenfield project on an
         unsupported branch still holds — but the individual tool versions
         needed checking against real Maven Central metadata one at a
         time rather than assumed from the "current LTS" framing. Stayed
         within the one-session escape hatch; no need to drop to
         Boot 3.5.x.

## 2026-08-31 · Phase 0 · Compose, Flyway, and Testcontainers on Colima
Expected: `docker compose up` with a Postgres image and a volume mount
         would just work the way it always has; Testcontainers would find
         Docker the same way the `docker` CLI does.
Reality: Postgres 18's official image restructured its data directory to
         a `pg_ctlcluster`-style layout — mounting a volume at
         `.../data` (the old convention) now makes the container refuse
         to start; it wants the *parent* directory mounted instead.
         Testcontainers 2.x (pulled in transitively by
         `spring-boot-testcontainers` on Boot 4.1) turned out to be a real
         major-version jump: `org.testcontainers:postgresql` and
         `:junit-jupiter` don't exist as artifact IDs any more (renamed
         `testcontainers-postgresql` / `testcontainers-junit-jupiter`),
         and `PostgreSQLContainer` moved to a new, no-longer-generic
         `org.testcontainers.postgresql` package. Separately, Colima's
         Docker socket lives outside Docker's own default-detection path,
         so Testcontainers needed `docker.host` pointed at it explicitly
         (personal `~/.testcontainers.properties`) — and even then, the
         Ryuk reaper container failed until told the socket path *as seen
         from inside the Docker host*, which is always `/var/run/docker.sock`
         regardless of where Colima actually keeps the file on macOS.
Wrong about: assuming "Testcontainers" as a name in a locked stack table
         (doc 25 §2) meant one fixed, checkable API surface. A library
         crossing a major version between when a course/doc was written
         and when the code actually gets typed is a real, recurring risk
         class — not a one-off. Also wrong to assume a Docker-socket
         workaround is inherently machine-specific: the reaper's
         *destination* path turned out to be universal, so it belongs in
         `build-logic` (helps every future developer, Colima or not), while
         only the *source* path genuinely stays per-developer config.
