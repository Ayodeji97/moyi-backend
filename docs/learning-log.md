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

## 2026-08-31 · Phase 0 · Konsist architecture tests and a real coverage number
Expected: the three Phase 0 Konsist rules (doc 25 §7 step 8) would be a
         short, mechanical task — write a scope filter, assert it's empty.
Reality: `modules/*` being empty shells meant a naive rule scoped to that
         package would hit Konsist's own safety check (it refuses to
         assert against an empty declaration list), so the "no
         cross-module internal access" rule had to be written by hand
         against a filtered list with a plain JUnit assertion instead of
         Konsist's `assertTrue { }` sugar. Kotlin block comments turned
         out to nest (unlike Java/C) — writing the literal text
         "modules/*" inside a KDoc comment opened a second, unintended
         comment level that only closed 30 lines later inside an unrelated
         string, producing an "unclosed comment" error nowhere near the
         real cause. The @Autowired rule caught something genuinely
         useful on the first real run: `@param:Autowired` on
         `HealthCheckTest`'s constructor still showed up as an annotated
         *property* to Konsist (it doesn't distinguish Kotlin use-site
         targets), which led to discovering `isConstructorDefined` — the
         actual fix, and a better rule than what I'd first written. And
         `runApplication`'s `main` function alone was enough to fail an
         80% JaCoCo gate at 25% covered, days before there's any real
         business logic — needed the standard `*ApplicationKt` exclusion,
         which every real Spring Boot project has for exactly this reason.
Wrong about: assuming a coverage gate only becomes relevant once there's
         meaningful code to cover. It became relevant on line one, and
         tuning the exclusion now — before any pressure to hit a number —
         is a very different exercise than tuning it under pressure later.
         Also confirmed the rule mechanism itself works, not just that it
         compiles: added a real @Autowired field violation, watched the
         test fail, then reverted it (doc 25 §9's own instinct — test the
         test, not just the code).

## 2026-08-31 · Phase 0 · CI, simplified from the original plan
Expected: doc 25 §7 step 9's list — "lint → test → build → publish, plus
         architecture tests and a coverage gate" — to map to one GitHub
         Actions job per item, the way the plan I wrote described it.
Reality: splitting lint/test/architecture-tests/coverage into separate
         jobs would mean re-running Gradle setup (JDK, dependency
         resolution, build cache) four-plus times for a codebase that is
         currently a handful of files — the per-job overhead would
         dominate actual work. Collapsed them into one `quality` job
         running `./gradlew build`, which already chains all of it
         correctly via Gradle's own task graph; a failure still names the
         exact task in the log, which is what actually matters for
         diagnosing it. Kept `gitleaks` and `publish` (GHCR, on push to
         main only) as separate jobs since those genuinely don't share
         setup with the Gradle build. Used Spring Boot's built-in Cloud
         Native Buildpacks support (`bootBuildImage`) instead of writing a
         Dockerfile — tested it locally first (a real container, JDK 25,
         Spring Boot 4.1.1 all present and booting) before trusting it in
         CI, and it failed only for the expected reason (no datasource
         configured, same as running locally without the `local` profile).
Wrong about: treating my own plan document as a literal build spec rather
         than a statement of intent. The plan's actual words allowed this
         ("as their own job or a stage within test") — I just hadn't
         thought through the per-job cost until sizing the real workflow
         against the real (tiny) codebase.

## 2026-08-31 · Phase 0 · detekt fails on JDK 25 — but only on GitHub Actions
Expected: since `./gradlew clean build` was green locally (JDK 25
         throughout), the same command in CI on the same JDK major
         version would just work.
Reality: it didn't — `:app:detekt` failed on GitHub Actions with
         `GradleException: 25.0.4.1`, a bare, undocumented-looking
         message, while the identical task passed locally. `--stacktrace`
         traced it to detekt's own CLI invoker
         (`DetektInvoker.kt:102`) — detekt 1.23.8 (last released Feb 2025,
         before JDK 25 existed) apparently doesn't recognise the runtime
         it's executing on and throws its raw version string as the
         entire error. It happened on CI's Temurin 25.0.4+1 but not my
         local Temurin 25.0.4+7 — same feature version, only the build
         number differs, so this is about the *exact* runtime
         version string detekt receives, not "JDK 25 unsupported" as a
         blanket rule. First fix attempt was wrong: the Detekt task type
         does expose a `jdkHome` property, so I pointed it at a JDK 21
         resolved via Gradle's `JavaToolchainService` — compiled, ran
         locally, still failed identically on CI. Decompiling
         `DefaultCliInvoker` (detekt-gradle-plugin's actual class, not
         detekt-core) showed why: it loads `detekt.cli.Main` through a
         cached `URLClassLoader` and invokes it **in-process** via
         reflection — it never shells out to a `java` executable, so
         `jdkHome` has nothing to hand off to. The JVM detekt's CLI
         actually runs under is whatever JVM launched the Gradle daemon
         itself, full stop. Real fix: install JDK 21 *last* in CI's
         `actions/setup-java` step (making it the daemon's default
         `JAVA_HOME`) while `jvmToolchain(25)` still resolves JDK 25
         separately for compile/test/run, which — unlike detekt — do
         support genuine out-of-process toolchain selection.
Wrong about: two things, layered. First, "it's green locally" was
         insufficient evidence before trusting a CI change — the second
         time this session a CI-only failure surfaced something local
         didn't (Postgres 18's volume layout was the first, in the
         opposite direction). Second, and more specifically: assuming a
         Gradle task property that *exists* (`jdkHome`) does what its
         name implies. It compiled and ran without error on the first
         attempt, which felt like confirmation — but "doesn't error" and
         "does what I think" are different claims, and only actually
         reading the plugin's bytecode (not just its public API surface)
         settled which one was true.

## 2026-08-31 · Phase 0 · Flyway was configured, on the classpath, and never ran
Expected: adding `flyway-core` + `flyway-database-postgresql` plus
         `spring.flyway.locations` in `application-local.yml`, then
         having `HealthCheckTest` pass against a real Testcontainers
         Postgres, was solid evidence Flyway actually worked — that's
         literally why I wrote that test extending
         `PostgresIntegrationTest` instead of a plain context test.
Reality: it was configured and never ran. Running the app against the
         *Compose* Postgres (not Testcontainers) with `bootRun` — a check
         I did purely as final due diligence before calling Phase 0 done,
         not because I suspected anything — showed no Flyway log line at
         all, and `\dx` in psql confirmed citext/pgcrypto were never
         created. `HealthCheckTest` passing proved nothing about Flyway:
         with zero `@Entity` classes anywhere in the codebase, Hibernate's
         `ddl-auto: validate` has no schema to check, so the app boots
         identically whether migrations ran or not. Root cause: Spring
         Boot 4 moved Flyway's autoconfiguration out of the plain
         `flyway-core` library into a dedicated
         `spring-boot-starter-flyway` module (the same restructuring
         pattern as the MockMvc-test-support move from two entries ago) —
         a library can sit correctly on the classpath, fully configured,
         and simply never be wired up if the autoconfiguration module
         that activates it is missing. Fixed by adding the starter, which
         then surfaced a second, smaller gap: the starter alone doesn't
         know about Postgres specifically ("Unsupported Database:
         PostgreSQL 18.6") — `flyway-database-postgresql` is still needed
         alongside it, not instead of it.
Wrong about: trusting "the integration test passes" as proof of the
         specific thing I'd added, rather than proof the app boots. Wrote
         `FlywayMigrationTest` to actually query `flyway_schema_history`
         and `pg_extension` — then, following the same discipline as the
         Konsist rules, reverted the fix, watched the new test fail with
         a real Postgres error, and restored it. This is the second time
         this session a "passing test" turned out to be testing less than
         its name claimed (`HealthCheckTest`'s `@Autowired` fix earlier
         being the other) — worth treating as a pattern, not a
         coincidence: a green integration test proves the code path it
         exercises works, not the code path the test's *name* implies.

## 2026-08-31 · Phase 0 · Two ways an architecture rule can be fake
Expected: adding the layer rules (domain depends on nothing, entities
         confined to infra, controllers confined to web) would be
         mechanical — write the filters, watch six green tests.
Reality: the six tests went green immediately, and two of them were
         worthless. First, the `internal`-visibility rule written back in
         PR #3 filtered on `it.packagee?.name?.contains(".modules.")`.
         Our packages are `com.moyi.identity.…` — `modules/` is only the
         Gradle *directory*, it never appears in a package name. That
         filter could never match, so the rule had been passing for four
         PRs by being structurally incapable of failing. Second, and
         worse because it affected all six: Konsist reads source files
         off disk, which Gradle cannot see as a task input, so after
         dropping a deliberately violating file into `modules/identity`
         the build reported `:app:test UP-TO-DATE` and passed. The rules
         only failed when forced with `--rerun`. Locally, every
         architecture rule was decorative. Fixed by declaring the repo's
         Kotlin sources as an input to the test task, then re-checking
         that a violation now triggers a rerun on its own.
Wrong about: thinking "I verified the rule fails on a real violation"
         was a complete check. It was necessary and not sufficient —
         I had verified it *when the test ran*, having never asked
         whether it would run. The check that actually matters is
         narrower than it sounds: introduce the violation, then run the
         build **the ordinary way**, with no flags. CI would have masked
         this indefinitely, since a clean checkout has nothing to
         consider up to date. That is the uncomfortable part — the gap
         only existed on the machine where the code is actually written.

## 2026-08-31 · Phase 0 · The same two holes, one layer down
Expected: the fixes in the entry above closed the "fake rule" problem —
         the `.modules.` filter was corrected and the Konsist sources
         were declared as a task input, both verified by violation.
Reality: code review on that same PR found each fix incomplete in the
         same shape as the original. The task input tracked
         `**/src/main/kotlin/**` only, but `scopeFromProject()` scans
         test sources too (the field-injection rule exists to see
         `HealthCheckTest`). Editing an existing file under another
         module's `src/test/kotlin` into a violation left `:app:test`
         **UP-TO-DATE and green** — the exact hole, still open, on the
         half of the tree the fix did not name. And the visibility rule
         used `.classes()`, so a public *interface* in `service` or
         `infra` — the likeliest leak of all, since those are the layers
         that implement contracts and expose repositories — passed
         untouched. Both fixed and both proven by violation, this time
         including the edit-an-existing-file case rather than only the
         add-a-new-file one.
Wrong about: two things. First, that "adding a violating file" is the
         test. Adding one creates directories, and that alone can
         invalidate a Gradle task for reasons unrelated to the input you
         declared — the honest check edits a file that already exists.
         Second, and more general: both misses were the fix being
         narrower than the rule it repaired, and neither was visible in
         a green build. A rule keyed on a convention is only as good as
         the convention's own enforcement, which is why this PR now also
         asserts that every file under `modules/` declares a
         `com.moyi.<module>.<layer>` package. Without it, anything in an
         unrecognised package is not rejected by the other rules — it is
         invisible to all of them.
