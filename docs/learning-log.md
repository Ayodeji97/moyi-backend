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

## 2026-08-31 · Phase 0 · Reading the course's repo instead of guessing at it
Expected: our Gradle setup diverged from the course in lots of small
         ways, and reconciling them would be a long list of cosmetic
         renames.
Reality: the opposite. Comparing file-by-file against
         `philipplackner/chirp-api`, we already match on everything
         structural — `build-logic` as an included build, the same three
         convention plugins in the same layering, version catalog,
         `-Xjsr305=strict`. His `VersionCatalogExt.kt` uses the *identical*
         `extensions.getByType<VersionCatalogsExtension>().named("libs")`
         workaround I'd arrived at independently three PRs ago when the
         generated typed accessor wouldn't resolve inside a precompiled
         script plugin. Nice confirmation that it's the known answer and
         not a hack. Adopted his extraction of it into a shared file
         (we had the line duplicated across two conventions), plus
         `TYPESAFE_PROJECT_ACCESSORS` and `-Xannotation-default-target=
         param-property` — that last one retroactively explains the
         `@param:Autowired` warnings from earlier: he'd already hit the
         same thing and set the flag rather than prefixing each site.
         The one genuine gap was JPA class opening, and chasing it
         produced the actual lesson below.
Wrong about: assuming `kotlin("plugin.jpa")` was the "open my entities"
         plugin. It is not — it's the **noarg** plugin
         (`org.jetbrains.kotlin.noarg.gradle.KotlinJpaSubplugin`, which
         the applied-plugin probe spelled out), and it only synthesises
         the no-arg constructor JPA requires. Making `@Entity` classes
         non-final so Hibernate can build lazy proxies is **allopen's**
         separate job, and `kotlin-spring` doesn't cover it because it
         only knows Spring's own annotations. I only caught this because
         I compiled a throwaway `@Entity` and ran `javap` on it instead
         of trusting that adding the plugin had worked — the bytecode
         still said `public final class`. Both plugins are now applied
         and both halves verified in the bytecode (`public class` plus a
         synthesised `TmpEntity()`). Worth noting the course has the same
         latent gap: its `allOpen` block sits in the *app* convention,
         while its entities live in feature modules that never apply it —
         which stays invisible until the first `fetch = LAZY`
         association. Third time this session that "the config is
         present" turned out not to mean "the config is doing anything."

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

## 2026-09-19 · Phase 1 · The build was red on the only machine that matters
Expected: to open Phase 1 by writing code. First step was the habit this
         repo has earned twice already — run `./gradlew build` and look at
         it, rather than assume a repo that passed CI six times passes
         locally.
Reality: `BUILD FAILED`. `:app:detekt` died with a bare `25.0.4` — detekt
         1.23.8 bundles an older Kotlin frontend that will not run on a
         JDK 25 daemon, and detekt's Gradle plugin invokes its CLI
         **in-process**, so `jdkHome` on the task is a decoy: the only JVM
         that matters is whichever one launched the daemon. CI had been
         green the whole time because `setup-java` was given `25` then
         `21`, and the *last* entry wins JAVA_HOME — so CI's daemon was on
         21 by a side effect of list order. Locally JAVA_HOME is sdkman's
         25, so every local `build` had been failing. Fixed properly with
         Gradle 9's daemon JVM criteria — `./gradlew updateDaemonJvm
         --jvm-version=21` writes `gradle/gradle-daemon-jvm.properties`,
         which states the daemon's JVM outright, is committed, and applies
         identically on both machines. Verified the fix did not quietly
         downgrade compilation: the emitted bytecode is still major
         version 68 (Java 24), which a JDK 21 compiler cannot produce, so
         `jvmToolchain(25)` is still doing the real work out-of-process.
Wrong about: two things, one of them written down by me earlier in this
         repo. The comment in `kotlin-common-convention.gradle.kts` stated
         that GitHub's Temurin 25 build throws this "that a
         locally-installed Temurin 25 build doesn't." That is false — the
         local build throws too, with `25.0.4` instead of `25.0.4.1`. A
         confident parenthetical in a comment became the reason nobody
         looked, which is worse than having no comment, because a comment
         that asserts a negative is read as evidence.
         The general shape is now the **third** instance of the same class
         in this project: the `.modules.` filter that could never match,
         the Konsist rule that never re-ran, and now a quality gate that
         only ever executed where its failure was invisible. Each time the
         mechanism differed and the lesson was identical — *green* is a
         claim about where you ran it, not about the code. The rule I
         should have been applying: a gate is not verified until it has
         been run on the developer machine, with no flags, by the ordinary
         command.

         Addendum, same session: reviewing my own fix by hand — the
         automated PR reviewer was down, its OAuth token having expired —
         found that the fix **reintroduced the same class of bug it was
         fixing.** Declaring the daemon JVM in
         `gradle/gradle-daemon-jvm.properties` applies to *every* Gradle
         invocation in the repo, and the `publish` job installed only
         JDK 25. So publish would have started downloading a JDK on every
         merge, or failed outright. It runs only on push-to-main, was
         SKIPPED on the PR, and therefore no green check could ever have
         contradicted it. Fourth instance, and the first one I authored
         while writing the entry about the previous three. The thing that
         caught it was reading the diff and asking "which jobs does this
         touch that did not run" — a question a green check cannot answer,
         and the reason the reviewer being down mattered.

## 2026-09-19 · Phase 1 · A wrong answer that raised no error
Expected: the interesting failure in the first identity slice to be the one
         I had written down in advance — `JpaRepository.save()` issuing a
         `SELECT` before every `INSERT`, because an application-assigned id
         is never null and `isNew()` has nothing else to look at. That part
         went exactly as predicted: with `Persistable` the statement counter
         says 1, and with `isNew()` forced to `false` it says 2. Predicting a
         bug and then measuring it is a good feeling and taught me nothing I
         did not already know.
Reality: the expensive one was `citext`. Doc 07 specifies `email citext`
         so that uniqueness and lookup are case-insensitive in the database
         rather than in application code. Hibernate maps a Kotlin `String` to
         `varchar`, so the driver tells PostgreSQL that the parameter in
         `WHERE email = ?` *is* a varchar — and PostgreSQL, having no
         `citext = varchar` operator, resolves the comparison through the
         implicit `citext -> text` cast and compares two ordinary strings.
         Case-sensitively. **Nothing failed.** The column was still `citext`,
         the unique index was still case-insensitive (so the "two accounts
         cannot share a mailbox" test passed the whole time), `ddl-auto:
         validate` was satisfied, inserts worked. Only the lookup was wrong,
         and it was wrong by returning *no row* — which is indistinguishable
         from "no such user" at every layer above it. In a login path that is
         a support ticket reading "my password stopped working when I typed
         my email with a capital letter", and the query it comes from looks
         correct in the diff, in review, and in the logs.
         Fixed with a `UserType` that binds the parameter untyped
         (`setObject(…, Types.OTHER)`), so PostgreSQL infers `citext` from
         the column it is being compared to. `@Column(columnDefinition)` is a
         separate half of the same problem and only satisfies the schema
         validator; the two are easy to confuse because either one alone
         leaves a build that is green.
Wrong about: three things, and the middle one is the one worth keeping.
         First, that Kotlin can satisfy a Java interface's getter with a
         property. `@Id override val id: UUID` does not compile against
         `Persistable<UUID>` — `'id' overrides nothing` — and a *public*
         `val id` additionally clashes with the `getId()` you then have to
         write. A `private val id` generates no accessor at all, which leaves
         the name free. Small, and I would have guessed wrong indefinitely
         without compiling it.
         Second, and this is the real lesson: I expected the `citext` problem
         to announce itself. I had even predicted the mechanism — "the
         parameter will be a varchar and there is no such operator" — and
         assumed the consequence would be `operator does not exist`, a loud,
         immediate, obviously-my-fault error. The consequence was `null`.
         I had reasoned correctly about the cause and then quietly assumed
         the failure mode I was most comfortable with. **Predicting a bug is
         not the same as predicting how it presents, and the presentation is
         what decides whether you ever find it.**
         Third, my first fix was `@JdbcTypeCode(SqlTypes.OTHER)`, which looks
         like the targeted, column-local version of the right idea. It made
         Hibernate route the String through its `Object` type and Java-
         *serialize* it into a `bytea` — so the round-trip test then failed
         inside the domain with "email is not a valid address", a message
         pointing at validation rather than at binding. A wrong fix that
         moves the error somewhere else costs more than no fix, and the only
         reason it cost minutes rather than an afternoon is that the tests
         that broke were ones I had written for other reasons.
         Worth recording separately: the architecture decided where this code
         could be tested, and I did not see it coming. The plan said the
         integration tests would live in `app`. They cannot — `UserEntity`
         and `UserRepository` are `internal`, so `app` cannot name them.
         The choice was to widen the visibility (deleting the boundary to
         suit the test) or to give the module its own migrations and its own
         small Spring context (ADR-0014). The rule pushed back on the plan,
         which is what an executable rule is for, but it pushed back at
         implementation time rather than at design time — I should have read
         the Konsist rules *as constraints on the plan* before writing it.

## 2026-09-19 · Phase 1 · Three guards that were not guarding, one of them mine
Expected: registration to be mostly plumbing on top of slice A — a
         controller, a service, Argon2id, done. The interesting decision was
         supposed to be the one I had already reasoned about: attempt the
         insert and absorb the unique-constraint violation rather than
         check-then-insert, because the check is a race and the conflict
         response is an enumeration oracle.
Reality: that part went as planned, and the mutation test for it is the most
         satisfying thing in the PR — rewriting the service to the course's
         `findByEmail` → throw → save shape fails *the timing test
         specifically*, because that version skips the ~150 ms hash on the
         duplicate path. The identical response body is only half a control;
         the other half is that both paths cost the same, and it took an
         assertion on hash *call count* to pin it, because any assertion on
         elapsed time that is not flaky is an assertion that is not measuring
         anything.
         What actually cost the session was three separate guards that were
         not guarding. **Jackson**: `app` has carried
         `com.fasterxml.jackson.module:jackson-module-kotlin` since Phase 0,
         and Spring Boot 4 uses **Jackson 3** — a different artifact tree
         under `tools.jackson`. Both were on the classpath, only Jackson 3
         was wired into the message converters, and the Kotlin module was
         registering with a mapper nobody used. Invisible for four PRs
         because no endpoint had ever taken a request body. The first one
         failed with "Type definition error", which is Jackson 3 saying it
         cannot construct a Kotlin data class, and is not a sentence that
         mentions Kotlin.
         **The controller architecture rule** filtered on the substring
         `".web."`. Controllers live in `com.moyi.identity.web`, which has no
         dot after `web`, so the filter excluded nothing and the rule reported
         every controller as a violation. It had never run against a
         controller because until this slice there were none — the third
         filter in this file to have been structurally unable to do its job,
         after the `.modules.` one and the Konsist staleness hole.
Wrong about: the `@Order` I added to stop `common:web`'s catch-all advice
         beating a module's own handler. I wrote it, wrote a paragraph
         explaining the race it prevented, then deleted it as a mutation
         test — and **every test stayed green**. An advice with no `@Order`
         already sorts at `Ordered.LOWEST_PRECEDENCE`, so annotating the
         catch-all with `LOWEST_PRECEDENCE` gives it exactly the order it
         already had. There was no race to lose and no precedence gained; the
         module advice was winning by bean-discovery luck the whole time. The
         fix is the opposite annotation on the opposite class — the module's
         advice has to declare a *higher* precedence — and it now has a test
         that reads the annotation rather than a response, because a
         behavioural test genuinely cannot tell the two apart.
         The general shape is one I keep meeting from a new angle: **a guard
         whose removal changes nothing observable is not yet a guard.** The
         previous four instances were all rules that could not fail. This one
         was a rule that could not fail *and that I had just written, with a
         confident comment attached* — the same failure mode as the
         `kotlin-common-convention` comment that asserted a negative and
         stopped anyone looking. The only reason I found it is that I have
         started deleting my own guards to watch them break, and that habit
         is now the most valuable thing in this repo's process.
         Separately, and worth its own line: Argon2id at NFR-046's parameters
         hashes in **17.7 ms** on this M5, against the ~150 ms `09` §3
         intends. The parameters are documented as a *minimum*, and the
         minimum turns out to be about an eighth of the intended cost on
         modern ARM — which is an eighth of the work an attacker does per
         guess. I did not change it: the number that matters is measured on
         the deployment target, that box does not exist yet, and tuning
         against a laptop would bake in the wrong answer while looking like
         diligence. It is written into the properties file and owed before M1.

## 2026-09-19 · Phase 1 · Validating twice is validating differently
Expected: the pre-merge review of the two identity PRs to be a formality. The
         code had 87 passing tests, a green build, a self-review against
         doc 18 §6, and six mutation checks behind it. I had also just run a
         security pass over the same diff, which found nothing that survived
         verification.
Reality: four defects, three of them the same bug wearing different clothes,
         and all four found by **asking the running application** rather than
         by reading the code again. `RegisterRequest` carried `@Email`,
         `@Size(min = 12, max = 128)` and a hand-rolled byte check, and its
         own KDoc defended that duplication: the domain's `require` produces a
         500, the annotation produces a renderable 422, so state the rule in
         both places. The argument was right about the consequence and wrong
         about the fix. **Every point where the two statements disagreed was a
         500 on a well-formed request**, and three were reachable:
         `a@b` satisfies `@Email`, which deliberately does not require a dot,
         and fails `Email`'s own shape check. A twelve-character password
         containing a combining accent composes to eleven under NFKC — and
         `@Size` measured the string *before* normalisation, which the domain
         does after. 128 `ﬁ` ligatures expand to 256 characters, breaking the
         maximum from the other side. The fourth was quieter: `toCommand()`
         trimmed the email with a comment explaining that a surrounding space
         is a typing accident, but the trim ran *after* validation, so
         `@Email` had already rejected the request. A comment describing a
         behaviour the code did not have — the same failure as the
         `kotlin-common-convention` note that asserted a negative and stopped
         anyone looking.
         The fix was to delete the second statement rather than to correct it:
         `@ValidEmail` and `@ValidPassword` call the domain factories and
         report the domain's own message as the field error. Three bugs gone,
         one definition left, and a rule added to the domain later becomes a
         422 without anyone remembering to mirror it.
Wrong about: what a test suite is evidence of. Those three 500s were
         reachable from the first request of a real client, and 87 tests, a
         security review and my own hostile-reviewer pass all missed them —
         because every one of those was reading the code, and the code reads
         correctly. Each statement of the rule is defensible in isolation;
         only running it shows they disagree. **Two statements of one rule do
         not need a bug to diverge, only time, and no amount of reading either
         one finds the gap between them.** The thing that found all four was
         twenty minutes of curl against the packaged jar.
         A fifth came from the same twenty minutes and is worth its own note:
         404, 405 and 415 came back correctly shaped and with **no `code`
         field**, because they are produced by `ResponseEntityExceptionHandler`
         and never pass through our own `respond()`. Doc 06 §2 calls `code`
         the stable contract a client switches on, "enumerated and exhaustive,
         generated into the client as a sealed class" — so a third of the
         responses were quietly outside the design. Nothing failed. The
         handler looked complete, and was, for the exceptions it had been
         written to think about.

         **Addendum, same session — CI caught one I had argued myself into.**
         The fix above went green locally and turned `quality` red on the PR:
         the Argon2id saturation test reported zero refusals on GitHub's
         two-core runner. The test used virtual threads, and **a virtual
         thread unmounts from its carrier only when it blocks.** Argon2id
         blocks on nothing — it is pure CPU and memory — so on a two-carrier
         machine at most two hashes are ever in flight, the third and fourth
         permits are never taken, and nothing is refused. Reproduced locally
         by running the same test under `-XX:ActiveProcessorCount=2`: fails
         with virtual threads, passes with platform threads, which are
         scheduled preemptively and therefore all reach `tryAcquire` whatever
         the core count.
         The uncomfortable part is not the test. It is that the same mistake
         was written into `Argon2Properties`' KDoc as the *justification* for
         the semaphore — "virtual threads impose no limit of their own, so a
         burst of ordinary sign-ups exhausts a 2 GB container". That argument
         is wrong for exactly the reason the test was: the carrier pool
         already caps concurrent CPU-bound work at the processor count, so on
         the two-core box this project actually deploys to, peak is ~38 MiB
         and the semaphore never binds. The control is still right — NFR-005a
         requires it, it survives someone raising the scheduler's parallelism
         or moving to a bigger instance, and it turns a kill into a 503 — but
         the reason I gave for it was a story I had not checked.
         Two things to keep. A confident paragraph explaining *why* a control
         is needed deserves the same "prove it" treatment as the control
         itself; I have now twice written a justification that was more wrong
         than the code it justified. And a test that only ever runs on one
         machine shape is a test whose result is partly about that machine —
         which is the same lesson as "green is a claim about where you ran
         it", arriving from the side where the *developer* machine is the
         permissive one and CI is the honest one.

## 2026-09-20 · Phase 1 · Five runs, five different failures
Expected: adding CodeQL to be configuration. A workflow file, a language
         identifier, a build command; the interesting question was whether
         the extractor supports Kotlin 2.2.21, and the whole point of the
         verification step was to answer that rather than assume it.
Reality: it took five runs, and each one failed differently and further
         along than the last. The build **hung** for 83 minutes at
         `:build-logic:compileKotlin` — CodeQL traces a build by
         `LD_PRELOAD`ing a library and following the processes it spawns,
         and Gradle compiles Kotlin in a separate long-lived daemon; tracing
         that handoff deadlocks. Compiling in-process fixed that and then
         ran **out of memory**, because in-process puts the compiler and
         CodeQL's extractor plugin inside a Gradle daemon whose heap is sized
         for orchestrating a build. With the heap raised, the build and the
         analysis both succeeded — and my own verification step failed,
         because it read `db-location` from the `init` action, an output that
         **does not exist** (`init` publishes `codeql-path` and
         `codeql-version`; `db-locations` belongs to `analyze`). Fixed, it
         finally reported the number this whole exercise existed to get:
         657 Kotlin files extracted, and the extractor reads 2.2.21 fine.
         Then that number showed the check's second half was useless. 657
         against 32 files of ours: the surplus is Kotlin pulled from
         dependencies, so the "did it read enough" comparison could never
         fire — a build silently dropping one module out of eight would still
         have shown a comfortable surplus. It now checks for each of our
         files **by name**, which cannot be inflated and says which one is
         missing instead of reporting a number.
Wrong about: what "verify the guard" means. I wrote the verification step
         precisely because a CodeQL run that extracts nothing reports zero
         alerts in green, and I was pleased with it. But I shipped it reading
         an output that does not exist, and I shipped its second half
         comparing two numbers that are not comparable. **A guard is code,
         and I had held it to a lower standard than the code it guards** —
         no test, no run, no check of the API it depended on, because it felt
         like configuration rather than logic.
         The thing that saved it was writing it to *fail* rather than warn.
         With an empty path it looked for `/src.zip`, found nothing, and went
         red on a job whose every other step was green. A version that logged
         a warning would have printed a line nobody reads, and the job would
         have reported success for an analysis nobody had confirmed read any
         code — the exact outcome the step exists to prevent, arriving
         through the step itself. That is the rule worth keeping: **a check
         that cannot fail loudly is not a check, and that applies most to the
         checks you are proudest of.**
         Smaller, and also mine: the timeout. The first run would have burned
         the six-hour job limit, and on the weekly schedule it would have
         done so with nobody watching. A scheduled job with no timeout is the
         same class of invisible as a green check that checked nothing.

## 2026-09-22 · Phase 1 · The document said "download the top 10 million"
Expected: slice C to be plumbing. ADR-0012 had already decided everything —
         a ~10M-hash Bloom filter, built in CI from a pinned HIBP dump, baked
         into the image, half a session — so the work looked like reading a
         file, filling a data structure and wiring one check into validation.
Reality: the first sentence of the plan was not executable. There is no "top
         ~10M hashes" to download: HIBP serves 1,048,576 prefix ranges sorted
         only *within* themselves, so a global top-N means pulling and sorting
         all ~2.1 billion entries. And there is no "pinned dump" either — the
         downloadable corpus was retired, and the official downloader now just
         walks every prefix. Two of the three load-bearing phrases in the
         decision described something that does not exist.
         What replaced the top-N was a prevalence threshold, and the number
         came from running the thing rather than reasoning about it. Five
         ranges sampled with `curl` put ~10M near "appears at least 700
         times"; the finished builder over 300 ranges said 700 gives 9.0M and
         **600** gives 10.5M. The quick estimate was off by a sixth, which is
         the size of error you get for free by measuring with the real tool
         instead of a shell pipeline.
Wrong about: what a decided decision decides. I have been treating the corpus
         as authoritative in the strong sense — doc 25 says decisions are not
         renegotiated without an ADR, and that is right — and I read that as
         "the plan is executable". ADR-0012 is an excellent document about
         *why* the floor moves to 8 and it is completely right about that. It
         is not a document about where an 18 MB file lives between the job
         that builds it and the build that packages it, because nobody had
         looked yet. The reading to keep: **an authoritative document settles
         the argument, not the mechanism**, and the gap between them is not a
         licence to relitigate — ADR-0016 changes nothing ADR-0012 decided —
         but it is work, and pretending it is not is how "half a session"
         becomes four.
         Two smaller ones, both mine. I nearly wrote the false-positive rate
         as an assertion from the formula; it is now measured over 200k
         non-members, because a formula restated in a test only proves I can
         restate it. And the first version of the CI job interpolated
         `${{ inputs.ranges }}` straight into a shell script — the same shape
         as string-concatenating SQL, in the repository that has CodeQL
         running specifically to find that class of thing. Caught by reading
         it back, not by any gate I had put in place.

## 2026-09-22 · Phase 1 · A test that would have passed by stopping testing
Expected: the second half of slice C to be wiring. The corpus exists, the
         filter reads, so: a port, an adapter, one line in the validator, and
         `MIN_LENGTH` from 12 to 8.
Reality: most of it was that. The thing worth writing down is what moving the
         floor did to a test I was not looking at.
         `RegistrationEndpointTest` has a case called *a password whose length
         changes under NFKC is 422 in both directions*, built around a
         twelve-character password that composes to eleven — twelve passes the
         raw check, eleven fails the normalised one, and the 422 is the proof
         that normalisation happens first. With the floor at 8, eleven is
         **fine**. The test would have gone green by *accepting* the password:
         same name, same assertion shape, asserting nothing. It is now six
         characters composing to seven, which straddles the new floor.
         Nothing would have caught that. The name still described the
         behaviour, the file still contained the case, and the suite was
         greener than before.
Wrong about: which numbers in a test are data and which are structure. I have
         been treating literals in tests as fixtures — details of the example,
         free to be anything valid. But a boundary test's literals *are* the
         test: they exist to sit either side of a line, and the moment the line
         moves they are just numbers. The rule to keep: **when a constant
         changes, grep for the tests that were interesting because of its old
         value, not only for the ones that fail.** A failing test tells you it
         noticed. This one would not have.
         The other thing done right, and only because the habit is written
         down: I verified fail-closed by actually taking the corpus away —
         pointing the real application at a resource that does not exist and
         watching the context refuse to start with a `BeanCreationException`.
         The first attempt at that proved nothing, because I deleted the
         packaged file and Gradle simply put it back on the next build. A
         guard is not verified by removing something the build regenerates.

**Same session, found by the Figma alignment check rather than by the code.**
`states.md` §1c already gives the breached-password case its own designed copy
on the sign-up screen — "FR-001's breach message is the password case". That is
a client requirement, and it sent me back to look at what the API actually
hands the client. A `FieldViolation` carries the *constraint's* name as its
`code`, so with the breach question folded into `@ValidPassword`, "too short"
and "already breached" both arrived as `VALID_PASSWORD`, distinguishable only
by the English sentence. `ErrorCode`'s own KDoc says exactly why that is wrong
— "a client that pattern-matches on English is a client that breaks when
someone improves a sentence" — and I had written a paragraph of KDoc arguing
that one annotation asking two questions was the *better* design. The argument
was coherent and it never looked at the wire. Two annotations now, two codes.
Worth keeping: **the standing rule to check every change against the Figma file
is not a formality about screens.** It is the only step in the loop that makes
me read the API from the client's side, and it caught a contract defect that no
backend test would have — every assertion I had written passed.

## 2026-09-22 · Phase 1 · The verification step that could not fail
Expected: #21 to be reviewed by the automated reviewer and, failing that, by
         my own pass, which had already caught a shell-injection hole and a
         slash in a release tag. I said the PR was green and ready.
Reality: Daniel asked whether it was actually *reviewed*. It was not. The
         Claude reviewer failed in 32 seconds — the expired
         `CLAUDE_CODE_OAUTH_TOKEN`, the same failure since PR #13, and I had
         reported "CI green" without checking that the *review* job was part of
         what went green. A Codex reviewer had run and left two inline comments
         I had not looked at. Both were real:
         **P1** — a smoke run (`ranges=300`) still had `publish` defaulting to
         true, so the documented smoke-test path publishes a 300-of-1,048,576
         filter as the corpus, with the release body saying nothing about the
         limit. 0.03% of the space, pinnable, and indistinguishable from a
         working control. **P2** — a date-only release tag collides on a
         same-day retry, and the release action resolves that by *replacing*
         the asset, which invalidates a SHA-256 somebody has pinned and
         contradicts the release body's own "never replace an asset in place".
         Going back through it properly then found two of my own, and the first
         is the bad one: the step named **"Verify the published file reads
         back" compared the file's SHA-256 against the digest the tool had
         printed for that same file seconds earlier.** A file compared with
         itself. It could not fail. Its comment claimed it proved "nothing
         between the writer and the artefact store mangles it" — and it ran
         *before* the upload.
Wrong about: two things, and they are the same thing twice.
         **"CI is green" is not "this was reviewed".** I read a list of passing
         checks and reported a conclusion the list did not support, without
         noticing that the job whose entire purpose is review was absent from
         it because it had failed on an earlier commit. The check I should have
         run is the one Daniel ran: *did a review actually happen*.
         **And I wrote another guard that cannot fail.** On 2026-09-20 I wrote,
         in this file, that "a check that cannot fail loudly is not a check, and
         that applies most to the checks you are proudest of". Two days later I
         shipped a verification step whose comparison is a tautology, and I was
         pleased enough with it to name it in the PR description as evidence.
         Knowing the rule is not the same as applying it. The thing that would
         have caught it is mechanical and I did not do it: **for every check,
         ask what input makes it fail, and if there isn't one, it is
         decoration.** The replacement asks the corpus for two passwords that
         appear 210 million and 52 million times — and I verified those counts
         against the live endpoint rather than assuming them, because a
         sentinel that is not really in the corpus is the same bug one level up.

**Same session, third correction, and the one I would have shipped.** The
review pass ended with a number I had repeated four times — "~70 GB over about
an hour" — in the ADR, the workflow, and two PR descriptions. Both halves were
invented. The duration came from extrapolating a 300-range sample at ~290
ranges/s; a real 130,000-range run sustained **~98/s**, which makes the full
job about **three hours**, not one. A small sample of a CDN comes back warm and
overstates the rate, and I had no business treating it as a rate at all. The
byte figure was worse: I had reasoned that disabling response padding removed
"about a third" of the transfer — but the 98,561-byte measurement I started
from was *already* unpadded, so I subtracted a saving twice. The real figure
was ~103 GB.
Chasing that down found something genuinely worth having. HIBP serves gzip,
and Java's `HttpClient` neither requests it nor decodes it: 98,561 bytes per
range becomes **55,362**. Two lines and a `GZIPInputStream` take a full run
from ~103 GB to ~58 GB — a bigger saving than halving the cadence, which is the
change Daniel had actually asked for. Verified by re-running the same 300
ranges and getting the same 3,005 digests.
The lesson is not "measure before you write a number", which I already knew and
had already written in this file. It is narrower and more useful: **a number
you have repeated is not thereby confirmed.** I said "~70 GB" once from a bad
inference and then quoted myself three times, and each repetition made it
feel more settled. The check is to go back to where a figure entered the
documents and ask what measurement it came from — and if the answer is "an
earlier sentence of mine", it has never been checked.

## 2026-09-22 · Phase 1 · Three green steps that did nothing
Expected: the first real corpus run to either work or fail. It did both. The
         corpus built perfectly — 10,546,783 digests from 2,068,408,781 entries
         scanned, zero malformed lines, sentinels present, in **19m34s** — and
         the release it published contained no file at all.
Reality: `JavaExec`'s working directory defaults to the **subproject**
         directory, not where Gradle was invoked. So `--output
         build/pwned-passwords.bloom` landed in
         `tools/breach-corpus/build/`, and every later step looked for it at
         the repository root. Invisible locally because I had always passed
         absolute paths — the one habit that guaranteed I would never meet this.
         What happened next is the part worth keeping. **Three consecutive
         steps reported success while doing nothing.** `stat` failed inside a
         command substitution, and `set -e` does not abort on that, so the
         published size was empty and the step went green (verified afterwards
         in a shell, not assumed). `upload-artifact` logged "No files were
         found" as a **warning**. `action-gh-release` logged "does not include
         a valid file" and published an empty release, also green. A pipeline
         with `set -euo pipefail` at the top of it produced a public release
         object containing nothing, and reported four successes on the way.
Wrong about: where to put a check. I had been placing verification at the
         *end* — a step that fetches the published asset and proves it is what
         we pinned. That is a good check and it is the one that caught this.
         But it caught it three steps late, after a public release had already
         been created and had to be deleted. The missing check was one line,
         `test -f "$CORPUS"`, at the exact point where the assumption is first
         made: the builder said it succeeded, therefore the file is here.
         The general form: **an assumption should be checked where it is made,
         not where it eventually hurts.** End-to-end verification tells you
         something is broken; a check at the assumption tells you *what*, and
         stops the damage before it is public.
         Second, smaller, and mine again: I have now been wrong about this
         job's duration three times — an hour, then three hours, then 19
         minutes. Every wrong figure came from extrapolating a measurement
         taken on a machine that was not the one running the job. The one
         estimate that held was the corpus size, and it held because it came
         from running the real builder: predicted 10.49M, actual 10,546,783,
         inside 0.5%. **Extrapolation is only as good as the thing you
         extrapolated from being the thing you are describing.**

## 2026-09-23 · Phase 1 · Two wrong diagnoses before the evidence
Expected: the automated reviewer's failure to be the expired
         `CLAUDE_CODE_OAUTH_TOKEN` my notes already named.
Reality: it was. I talked myself out of it twice on the way there, and both
         detours are worth keeping because the reasoning looked sound each time.
         **First wrong turn.** Two runs that morning showed `success`, so I
         concluded the token worked and the problem was elsewhere. They were
         Dependabot PRs, and their `claude-code-action` step was **skipped** —
         Dependabot runs cannot read repository secrets, so the guard saw an
         empty token and no-opped into a green job. I had read a *job*
         conclusion and drawn a conclusion about a *step*.
         **Second wrong turn.** With the token apparently exonerated I bisected
         to the action version and found 20 releases of
         `anthropics/claude-code-action@v1` between the last success and the
         first failure — a floating tag, a real supply-chain weakness, and a
         tidy story. It was not the cause: `v1` has resolved to the same commit
         since 2026-09-19T03:12Z, which spans both the failures and the run
         that fixed them. The correlation was real and the causation was not.
         What settled it was one command, `gh secret list`, showing the secret
         last updated 2026-08-31 and never rotated. Last success 2026-09-01.
Wrong about: what counts as evidence. Both detours came from reasoning over
         *summaries* — a job's conclusion, a release timeline — when the
         primary fact was one API call away and I had not made it. **A green
         job is not a green step, and a correlation across a window is not a
         cause.** The habit to keep: when a diagnosis rests on "X was working
         at time T", go and check X at time T directly, not something that
         would usually imply it.
         And the sting in the tail: with the token fixed, the review ran
         properly — 24 turns, three models, four and a half minutes, about a
         dollar of subscription usage — and **posted nothing at all**. Five
         permission denials, contents hidden. The check went green. So the
         thing I was fixing was never the only thing broken, and the second
         fault was invisible behind the first precisely because the first one
         failed loudly enough to explain the symptom. Fixing the loud failure
         is how you find out what the quiet one was.

**Same day, and the most useful thing I learned all week.** After pushing a fix
to the review workflow's PR, no checks ran. Not the review, not CI, not CodeQL
— zero workflow runs for the commit. I pushed again, closed and reopened the
PR, toggled it draft and back, and finally pushed an empty commit for a fresh
SHA. Still zero. Meanwhile a `workflow_dispatch` run on the same repository
started normally, all six workflows showed `active`, and Actions was enabled.
The cause was one field: `mergeable: CONFLICTING`. **A `pull_request` workflow
runs against the PR's *merge* commit, and when the branch conflicts with the
base GitHub cannot compute one — so it schedules nothing at all.** No error, no
queued run, no annotation. The branch had conflicted the moment a PR merged
ahead of it, because both had appended to this file. Rebasing made every check
fire within seconds.
What made this cost forty minutes was looking in the wrong register. I
reasoned about Actions health, workflow states, billing, the action version,
concurrency limits — all repository-level explanations for what turned out to
be a property of one pull request, visible in a field I had already queried
twice that morning for another purpose. **When something does not run, ask what
it would have run *on* before asking whether the runner is healthy.**
The honourable mention: the guard I had just written counted every new comment,
so CodeQL posting an alert would have made a silent no-op review look like a
successful one. Caught by reading real numbers off a real PR rather than by
imagining the happy path — `all=3, github-actions[bot]=0` on a PR where the
review had genuinely posted nothing.

## 2026-09-23 · Phase 1 · Four red tests and two green ones, all for the same wrong reason
Expected: `MockRestServiceServer.bindTo(builder)` to intercept every request
         the `ResendEmailSender` made through the `RestClient` built from
         that builder — the documented, standard way to test a client.
Reality: four tests failed with `ConnectException`, because the client had
         gone to the real network looking for `api.resend.test`. The sender's
         factory method installed its own request factory on the builder —
         a JDK client with the configured timeouts — *after* the test had
         bound the mock server, and `requestFactory(...)` is last-writer-wins.
         The mock was replaced, silently, by a real transport. The fix moved
         the timeout configuration to the composition root, which is where it
         belonged anyway: the transport is wiring, the sender is behaviour.
Wrong about: what a passing test proved. The "5xx is transient" and
         "unreachable is transient" tests were **green on that same run**,
         because a real `ConnectException` is also classified as
         `Unavailable`. Two tests passed for a reason that had nothing to do
         with the code they were written to exercise. Had the four others not
         failed, nothing would have said so. The habit: a test on a failure
         path asserts *which* failure — the `503` in the reason, not just the
         type — because the failure branch is exactly where two different
         causes converge on one answer. This is the third entry in this log
         about a green test that was not testing anything, and the pattern is
         always the same: the assertion was true, and it was true for a
         reason the test did not control.
**Also today:** the first `api` package, the first cross-module port, and one
architecture rule loosened by one edge (`infra → api`) — proven still to bite
by planting an `infra → service` import and watching `layers only depend
inwards` fail before removing it. And the first provider posture decision
written down (ADR-0017): the default is the real provider, and the real
provider refuses to boot without its key. Confirmed by running the packaged
jar both ways — `local` boots and logs the provider, the default exits 1 with
a message that names the missing property.

## 2026-09-23 · Phase 1 · The guard fired, and it was right
Expected: the review job on PR #26 to run the `/code-review` plugin and post.
Reality: it ran for twenty seconds, six turns, posted nothing, and the
         "review must have said something" step from #24 turned the job red.
         The transcript — visible only because `show_full_output` is on — had
         one `permission_denied`: tool `Skill`, "Execute skill:
         code-review:code-review". The prompt *is* a skill invocation, and the
         allowlist named every tool the review uses and not the one that
         starts it. Denied that, the model spawned three `Task` agents itself,
         said "I'll continue once results arrive", and the headless session
         ended — `terminal_reason: completed`, `is_error: false`.
Wrong about: what a complete allowlist is. I had audited it against what the
         plugin's *commands* do, not against what the *prompt* does first.
         The two lessons that were already in this log both applied at once:
         a check that cannot fail is not a check (the guard is what surfaced
         this), and read the primary transcript before theorising (the denial
         was one grep away). The fix is one word. Whether it is the *whole*
         fix cannot be known from this PR, because editing the workflow makes
         the action skip itself — the next ordinary PR is the test.

## 2026-09-23 · Phase 1 · A comment that asserted a negative, and the mutation that made it true
Expected: to write "published after the boundary, there is no transaction to
         bind to and the listener silently drops the event" as a code comment
         in `RegisterUser`, and move on.
Reality: this log already says a comment asserting a negative is the claim to
         distrust most, because nothing tests prose. So the claim was tested:
         the `publishEvent` call was moved three lines down, outside
         `transactions.executeWithoutResult { }`, and the identity suite
         re-run. Seven tests failed — every one that expected an email — and
         the eighth, the duplicate-registration test, failed *too*, because
         the moved publish now fired on the duplicate path where it had been
         skipped by the exception before. The comment was true. It is now a
         comment I have watched be true, which is a different thing.
         The second mutation deleted `AND t.consumedAt IS NULL` from the
         conditional `UPDATE`. Two tests died: the persistence test that calls
         `consume` twice, and the endpoint test that presents one token from
         two threads behind a latch. Both `UPDATE`s succeeded, both requests
         got 200, and the "single-use" in FR-002 was a word in a document.
Wrong about: nothing this time, and that is the point of recording it. The
         first run of the new suite was green — 28 tasks, 111 tests — and a
         green first run on ninety lines of new tests is the moment to be most
         suspicious, not least. Ten minutes of breaking the code on purpose is
         what turns "the tests pass" into "the tests would notice". The habit
         from slice C's log holds: *green is a claim about where you ran it*,
         and it is also a claim about what you broke to check.
**Also today.** The email now leaves the system through an `AFTER_COMMIT`
`@Async` listener rather than the outbox, and ADR-0018 spends a paragraph on
why that is allowed here and would not be for a reveal notification: the
difference is whether the person has a button that recovers the loss. Worth
being able to say out loud, because "why not just use the outbox for
everything" is the obvious question and "because this event has a designed
recovery path and that one does not" is the whole answer. And one thing the
course does that we now deliberately do not: Chirp's verification link is a
`GET` on the API. Mail scanners fetch links. A `GET` must never spend a token.

## 2026-09-23 · Phase 1 · Three things the compiler and the framework disagreed about
Expected: slice E1 to be mostly configuration — Spring Security's resource
         server, a decoder, a chain — with the risk in the security semantics.
Reality: the semantics held on the first run (every 401 case, the 403, the
         revocation window). The first three failures were all *language
         meets framework*, and each is worth one sentence because each will
         recur.
         **A `value class` cannot be a controller parameter.** `CurrentUser`
         was a `@JvmInline value class` over a UUID; Kotlin compiles that
         parameter to a bare `UUID` with a mangled method name, so Spring MVC
         asked the argument resolvers for a `UUID`, none matched, and the
         request was a 500 — "Parameter specified as non-null is null". A
         `data class` with one field costs an allocation and works. The
         wrapping still buys what it was for.
         **Kotlin block comments nest.** A KDoc line that quoted a URL pattern
         ending in `/**` opened a second comment inside the first, and the
         file failed to parse with "Unclosed comment" forty lines later. The
         fix was a word; the lesson is that a code comment is code.
         **The architecture rule caught the test, not the code.** The chain
         test built forged tokens with Nimbus, whose claims builder takes a
         `java.util.Date` — and `no file imports java-util-Date` scans test
         sources on purpose. Rewriting the forgeries through Spring's own
         `JwtClaimsSet` and `NimbusJwtEncoder` over a stranger's key was
         shorter and reads better. A rule that only inconveniences is a rule
         doing its job.
Wrong about: where the risk was. I had budgeted attention for the token
         design and spent it well; the failures came from the seams between
         Kotlin, Spring and our own rules, none of which any document
         describes, all of which the first `./gradlew build` found in under a
         minute. Run the build early, before the code is finished, because
         the build is cheaper than the theory.

## 2026-09-23 · Phase 1 · Reviewing a slice I did not write, and the four things green tests could not see
Expected: to review PR #32 — login, refresh rotation, logout and password
         reset, written by Codex while my session was rate-limited — as a
         finished slice with all checks green, and to add the ADRs it lacked.
Reality: all checks *were* green, and four requirements were not met. None of
         the four was a bug in the sense of a wrong line; each was a decision
         the corpus had already made and the code had made differently, with a
         test enshrining the difference:
         **FR-002.** "Unverified accounts may sign in." Login required
         `ACTIVE`, refresh revoked any non-`ACTIVE` family, and a test named
         "an unverified account cannot log in" passed. Screen 3's "Check
         again" — sign in, read `/me` — would have been impossible.
         **T-03.** "Account lockout with exponential backoff." A fixed
         fifteen minutes, with the counter reset to 1 when a lock expired, so
         every lock was the same length. A lockout, not a backoff.
         **Doc 06 / doc 13.** `TOKEN_REUSE_DETECTED` is named in the API spec
         and is the code the client wipes its credentials on. Every refresh
         failure was `UNAUTHENTICATED`, so a stolen session and an expired one
         were the same event to the app.
         **Doc 09 §3 / T-17.** "The user is emailed" on reuse; "notification
         email to the old address" on reset. Neither existed. And the reset
         link pointed at the *verify* landing page — a reset token `POST`ed to
         `/verify-email` is "not recognised" — which nothing caught because the
         reset test inserted its token by SQL instead of reading the email.
Wrong about: what a green PR from a capable author tells you. It tells you the
         code does what its tests say. It does not tell you the tests say what
         the *documents* say, and every one of the four gaps was in that
         second distance. The review that found them was not a reading of the
         diff; it was a reading of the diff *against* FR-002, T-03, doc 06
         §3.1, doc 09 §3 and doc 13, one requirement at a time, asking "where
         is this". That is a checklist, and it is slower than reading, and it
         is the only thing that worked.
         The fixes themselves were the usual shape: a `canAuthenticate` on the
         domain model used by login, refresh and reset alike; the lockout rule
         stated once in Kotlin and once in one native `UPDATE`, with two tests
         pinning them to the same numbers; three 401 codes where there was
         one; a `SecurityNotice` event and an `AFTER_COMMIT` listener for the
         two emails; a second, required, landing URL. Four mutations, all
         killed by the tests written for them — including the one that
         restores the fixed-15-minute behaviour, which fails three tests now
         and would have failed none before.
         Also kept from the review: what Codex did *better* than my design.
         The compare-and-set on `rotated_at` with `replaced_by` making the
         family readable as a chain; `logout` idempotent and unauthenticated
         so it cannot be used to test tokens; the malformed-address path
         running the dummy verify instead of returning a 422. Good decisions
         are worth writing down when they are somebody else's.
## 2026-09-23 · Phase 1 · The smoke test is now a script, and its first run found a bug in itself
Expected: to hand Daniel a list of curl commands.
Reality: a list is read once; a script is run every time. `scripts/smoke.sh`
         boots the jar, probes 22 things, checks the rows, exits with the
         failure count. Its first run reported one failure — "wrong content
         type is 415" came back 400 — and the API was right: the helper added
         its own `Content-Type: application/json` to every request, so the
         probe sent two headers and the server honoured the first. The test
         harness was the bug. Same lesson as the mock server that went to the
         network: a test's plumbing is code, and the first run of a new test
         is the moment to distrust a failure *and* a pass.

## 2026-09-24 · Phase 1 · Rate limiting, and the number the test told me I had wrong
Expected: to wire Bucket4j to Redis, annotate four controllers, and write the
         429. The design had been written the day before and re-read this
         morning; the interesting part was expected to be the trusted-proxy
         resolver.
Reality: the resolver was the easy part — eight unit tests, one loop. Four
         things were learned by running, none by reading:
         **`X-RateLimit-Reset` is not "now plus the period".** I wrote the
         test expecting the reset an hour after one registration; Bucket4j
         answered twenty minutes. Reset is when the bucket is *full again*,
         and with greedy refill one missing token is one refill interval
         away. The test was wrong, the library was right, and the header now
         says something true that I would have documented falsely.
         **FR-012 and T-03 share a threshold.** Five sign-in attempts per
         fifteen minutes per email, and a lock after five failures. Counting
         the smoke script's logins showed the sixth attempt is always a 429,
         so the lockout can never be observed over HTTP inside the window.
         Not a conflict — the bucket is the cheap front door, the lockout is
         what remains when Redis is gone — but nobody had written down which
         fires first, and the test contexts had to be arranged around it:
         limiter off for the shared context, on for one dedicated class.
         **A duplicate top-level key in a test `application.yml`** fails the
         context with "while constructing a mapping", buried under twenty-
         seven `ParameterResolutionException`s. The YAML was valid to the eye.
         **The identity module has no Redis client**, which is correct — it
         consumes a port — and the test that needed to empty the buckets
         between cases found that out at compile time. It flushes through the
         container (`valkey-cli FLUSHALL`) instead, which is a module boundary
         doing its job in a test.
         Also confirmed by running rather than assuming: Bucket4j's Lettuce
         adapter, built against Lettuce 6, works over the Lettuce 7 that Boot
         4.1 ships — three calls, `EVAL`/`GET`/`DEL`, none of them changed —
         and the clock binding refills a fifteen-minute bucket in a test that
         takes milliseconds. My notes from yesterday pinned Bucket4j 8.14;
         Maven Central said 8.20, which is a reminder that a version in a
         design note is a guess until the day it is added to the catalog.
Wrong about: where the difficulty would be. The design named the resolver;
         the day was spent on semantics — what "reset" means, which control
         fires first, what a 422 costs — each of which is a sentence in the
         ADR now and none of which was in the design.

## 2026-09-24 · Phase 1 · The contract, and the two things the first document said that were not true
Expected: to add springdoc, commit the document it produced, and write the
         breaking-change job. The interesting part was expected to be CI.
Reality: the first document springdoc produced was wrong in ways that a
         generated client would have faithfully reproduced. `CurrentUser`
         and `ClientContext` — the two types our argument resolvers fill from
         the token and the socket — appeared as *query parameters* on `/me`,
         `/logout-all` and `/register`, with their own schemas; a client built
         from that would have sent them. `logout` was documented as 200 (it
         returns `ResponseEntity.noContent()`, and springdoc reads the
         annotation, not the builder). Every success body was `*/*`. No
         operation had a single error response, and the public auth endpoints
         inherited the bearer requirement. None of that was a springdoc bug;
         it was a document that described the code as written rather than
         the API as specified, and the gap between those two is exactly what
         doc 12 §3.4's contract test exists to close. The test was written to
         the specification first, and it failed on six of seven assertions.
         Two more things learned by running: swagger-parser's javax
         `swagger-core` shares every class name with springdoc's jakarta
         flavour and shadowed it — every request for the document was a 500
         until the javax pair was excluded from the test classpath; and
         schemas placed on the `OpenAPI` bean are dropped when springdoc
         rebuilds `components`, so the problem schema has to be added in a
         customizer, after that pass. Also: a parallel shell call inherited
         the other call's `cd` and ran a Gradle command in the wrong
         worktree twice — every command now starts with an absolute path.
Wrong about: what "generate the spec" means. Generation is the cheap part; the
         document is a claim about the API, and like every other claim in
         this project it needed a test that could fail before it was worth
         committing.

## 2026-09-24 · Phase 1 · Sessions, and the three places the tests were wrong instead of the code
Expected: a new table, two endpoints, and the first breaking change to the
         API — a medium slice, with the interesting part being the `sid`
         claim and the 404-not-403 rule.
Reality: the code went in almost as designed; the day's corrections were to
         tests and to my own earlier assumptions, which is a different kind
         of finding. **A "not found" body legitimately contains the id**:
         my test asserted the 404 body must not contain the session id, and
         it failed because `instance` is the path the caller typed. The rule
         is that the *sentence* must not repeat it; the path is theirs.
         **The contract test forbade every parameter**, which was true for
         ten operations and false the moment the API had a path variable;
         the assertion now excludes `in: path`. **The migration-sequence
         test pins the list of versions** — good, it caught V8 as intended,
         and the fix was to add 8, not to loosen it.
         Two detekt thresholds fired and both were design signals again:
         the identity exception handler had grown one method per slice and
         hit eleven, and the sessions 404 turned out to be the first
         "not yours is not found" in the system — the shape every bond-
         scoped endpoint will need (T-02) — so it became `NotFoundException`
         in `common:web`, handled by the catch-all, and the module's handler
         got shorter. And rotation's constructor hit seven parameters when
         it learned to touch the device, which is how `deviceSeen` ended up
         behind the session facade instead of a fourth store in rotation.
         Decided while building, not before: a session *is* a refresh-token
         family — no new table — and `lastSeenAt` is the live token's
         `issuedAt`, so there is nothing to keep in step. Two sign-ins from
         one phone are two devices until push tokens can fold them, and the
         ADR says so rather than inventing a fingerprint.
Wrong about: where the risk was. I expected the claim and the authorisation
         to be the delicate parts; both were one line. The delicate parts
         were three assertions I had written with a picture of the API that
         the API had just outgrown.
         **Added after CI and the Codex review of #35.** Two more corrections,
         one to code. The revoke `UPDATE` matched every unrevoked row of a
         family, expired ones included, so a stale id from a client's cache
         earned a 204 for a session the list had stopped showing; an `EXISTS`
         on a live token in the family is the fix, and a test that advances
         the clock thirty-one days is the proof. And two timestamp assertions
         that were green on this Mac were red on CI: a Linux clock carries
         nanoseconds, Postgres keeps microseconds, and the round trip through
         `timestamptz` drops the difference — the tests now compare at the
         database's resolution. Also worth knowing: oasdiff does *not* call a
         removed optional request property breaking (a client still sending
         it is ignored), so the `breaking-api-change` label on #35 was a
         reviewer's judgement, not the gate's; ADR-0025 §6 says so.

## 2026-09-24 · Phase 1 · The smoke test of everything, and the two things the script could not see
Expected: a green run of the 98-probe script over `main @ 2e8f7e7`, then a
         list of things to fix. Daniel asked for the whole of Phase 1 wired
         together and exercised end to end, not for a slice.
Reality: the script could not boot the jar, and the code was not the reason.
         The jar is compiled for JDK 25; `java` on a non-interactive PATH is
         21, because SDKMAN's `current` is only on PATH in a login shell. The
         JVM died at once with `UnsupportedClassVersionError` and the script
         waited its full ninety seconds to say "timed out", because it only
         watched for Spring's "APPLICATION FAILED" banner and never asked
         whether the process was still alive. It now finds a JDK 25 for
         itself (`MOYI_JAVA`, `JAVA_HOME`, SDKMAN, `java_home`, then PATH),
         refuses with a sentence when there is none, and notices a dead
         process on every tick. On JDK 25: 98 of 98.
         Then some eighty probes by hand for what the script does not cover.
         Confirmed as designed: the unverified sign-in (FR-002); NFKC on the
         password (a ligature `ﬁ` at registration signs in as `fi`); citext
         on the address; four concurrent refreshes of one token (one 200,
         three `TOKEN_REUSE_DETECTED`, the winner's successor dead); the
         access token dying two seconds after `logout-all` and surviving a
         rotation or a `DELETE /sessions/{id}` of its own session (stateless
         until `exp`, ADR-0025); the fail-open with Valkey stopped (201 in
         292 ms on the first request, 36 ms on the next, health still UP);
         the live `/v3/api-docs` identical to the committed document;
         `alg=none`, a forged `sub` and a bent signature all 401; nothing
         under `/actuator` but health, even with a bearer; every per-email
         and per-IP bucket at doc 06 §4's numbers. The one-second window on
         `logout-all` behaved exactly as ADR-0019 §4 documents.
         **What the script could not see.** A trailing slash — `/api/v1/me/`
         — is a 404 whose body has no `type` (Spring 7 emits `null`; the
         contract lists it as required), the title "Not Found" instead of
         our prose, and the detail "No static resource api/v1/me." on an API
         that serves none. Every error Spring raises for itself — 404, 405,
         406, 415 — had the same three defects; slice B's handler stamped
         `code` onto them and looked complete. They are now rebuilt through
         `ProblemDetails` like every other error, keeping Spring's headers
         and, except for the 404, its detail; two tests and two smoke probes
         hold it. Recorded, not fixed: `HEAD /actuator/health` is 401 (only
         GET is permitted, and kamal-proxy uses GET); a missing required
         property is a 400 `MALFORMED_REQUEST` that does not name the field
         — deliberate, doc 06 §2 makes 400 the client's fault, but a client
         author will wish it did. And a third script fault: an absent header
         made `header_is` exit the whole run under `pipefail` with no FAIL
         line, which is how the new 405 probe was found to be in the wrong
         place.
Wrong about: where the first failure would be. I took the API for the thing
         under test and the script for the instrument; the instrument failed
         first, for the reason ("which `java`?") every works-on-my-machine
         story has in it. And "every error carries a `type`" was something
         I believed because our builder sets it — the errors that never
         touch the builder were exactly the ones I had never asked the
         running application for.

## 2026-09-24 · Phase 2 · The bond module, and the authorisation check that cannot be forgotten
Expected: a second module much like the first — a migration, an aggregate, a
         store, three endpoints — with the interesting part being the guard,
         which I expected to be a service the controllers remember to call.
Reality: the guard turned out to be a *type*. Doc 12 §3.6 asks for a Konsist
         rule that "every controller method taking a bondId passes through
         BondAccessGuard", and that rule cannot be written: whether the guard
         ran is a fact about the call graph, which Konsist does not see. What
         is checkable is the signature one layer down. So `Membership` became
         a value only the guard can construct, every bond-scoped service takes
         one, and two rules hold the halves the compiler cannot — nothing else
         constructs it, and no service function names a bond without it. The
         authorisation check stopped being a step someone can forget and
         became an argument they must be holding.
         **Three things the verification found that reading would not.**
         (1) Breaking `BondStore.findByMember`'s membership predicate did
         *not* fail the cross-tenant suite, because the guard re-checks
         membership on the loaded aggregate. That is defence in depth working,
         and it means the two layers need two tests: the store's is
         BondPersistenceTest, which did fail. Breaking the guard itself failed
         the suite with "a stranger: expected 404, got 200".
         (2) My first deliberate violation of the BondId rule slipped through,
         because I wrote the parameter type fully qualified and the rule
         matches the simple name. The gap is now in the rule's comment. A rule
         that has never failed is not known to work, and a rule verified once
         is not known to work *generally*.
         (3) Adding one test class to identity broke the whole build with
         "FATAL: sorry, too many clients already". Spring caches a context per
         distinct configuration, every one holds a ten-connection Hikari pool
         for the entire run, and the count finally crossed Postgres's default.
         Nothing was wrong with the code under test. The pools are capped at
         four now.
         Two smaller ones: detekt's six-parameter limit split `Member.join`
         into `owner()` and `member()`, and it was right — the role was the
         only difference between the two call sites, and a name reads better
         than an enum argument. And springdoc documented `POST /bonds` as 200,
         because the status lived in the ResponseEntity; the annotation is
         what it reads, so the method now carries both and two tests hold them
         together.
Wrong about: where the design work was. I thought it was in the aggregate —
         ADR-0003 had already decided the shape, so modelling it took an hour.
         The design work was in making the authorisation unforgettable, and
         the answer came from a constraint I first read as an obstacle: that
         Konsist cannot see a call graph.
         **Added after CI.** I wrote "additive, oasdiff should report no
         breaking change" in the ADR and the PR body, and oasdiff reported 76
         errors. Adding a value to `ErrorCode` is `response-property-enum-value-
         added`, which oasdiff calls breaking — and it is right by doc 06 §2's
         own design, which wants the generated client's sealed class to be
         exhaustive so an unhandled code is a compile error there. So an error
         code is source-breaking for the client while being entirely
         wire-compatible. Labelled rather than suppressed, and ADR-0024 amended
         to say so once rather than every slice re-deciding it. The lesson is
         narrower than "check before claiming": I reasoned about the wire and
         the document's shape, and forgot that the contract's consumer is a
         *generated sealed class* whose exhaustiveness is the feature.
         **Added after CI, second time.** `SessionsEndpointTest` went red on
         the Linux runner with two timestamp assertions — the *same* symptom
         slice H met and "fixed" by truncating the assertion side to
         microseconds. That fix was half right. **Postgres rounds fractional
         seconds to microseconds; it does not truncate** — confirmed at a psql
         prompt: `.123456789` comes back `.123457`. So a nanosecond-precision
         Linux clock and a truncating assertion disagree whenever the
         remainder rounds up, which is about half the time. Green on a Mac,
         green on some CI runs, red on others. `MutableClock` now truncates at
         the source, so there is nothing left for the database to round, and a
         test in `common:testing` pins that invariant. The lesson: when a
         value survives a round trip unchanged on one machine and not another,
         find out what the *store* does to it rather than adjusting what the
         test expects.

## 2026-09-25 · Phase 2 · Invites, and the two defects the tests found before the reviewers could
Expected: a mechanical slice. Four endpoints on top of B1's aggregate and
         guard, a compare-and-set I already knew I needed, and the only
         interesting thing being FR-024's one-answer rule, which I had
         designed in the spec a day earlier.
Reality: the one-answer rule was not a formatting exercise, it was a *test*,
         and it failed. `InviteOneAnswerTest` builds all six unusable states
         genuinely and asserts the six responses are the same bytes. It came
         back red on the blocked case — because `resolve` did not check
         blocks. I had reasoned that a preview should preview and `accept`
         should refuse, which sounds like separation of concerns and is
         actually a **block oracle**: the blocked person sees the bond's name
         and the inviter's, then gets refused, and now knows the code is real
         and that something is wrong with *them*. Doc 26 §2.1 says that person
         must learn nothing, and I had written an endpoint that tells them
         something. The endpoint test I had written first asserted the defect —
         `resolve(...).status shouldBe 200` — which is the clearest reminder
         I have had that a test written from the same wrong idea as the code
         confirms the idea, not the behaviour. The exhaustive test is what
         broke the loop.
         **Then `InviteRaceTest` found the second one.** Two concurrent creates
         each revoke the live invites *their own snapshot* can see and each
         insert a new one, so a bond ends with two working codes — which
         `states.md` §2 promises a member cannot happen. The CAS on accept does
         not help: both codes are genuinely live. It is the same shape as the
         refresh-token family race Codex found on PR #32, and I had already
         written the lock into `accept` and not into `create`, which is what
         "I fixed that class of bug once" buys you: nothing, unless the test
         exists.
         Smaller: detekt put `BondStore` over its method limit, which turned
         out to be my own Phase 2 spec arriving late — it said invites get
         their own store and B1 had put them in the bond's. And detekt flagged
         `resolve`'s `CurrentUser` as unused, so I removed it; an hour later
         the block check needed it back. The rule was right about the code as
         written and wrong about the code as it should have been, which is
         not something a linter can know.
Wrong about: what "already designed" protects you from. The spec had the one
         answer, the CAS and the block rule all written down, and I still
         built two defects — one by applying a rule in fewer places than it
         needed, one by fixing a race in one of the two places it occurs.
         A design document tells you what to build. Only a test that tries
         every case tells you whether you did.

## 2026-09-27 · Phase 2 · Ending a bond, and the channel I nearly shipped in the ETag
Expected: the smallest slice of Phase 2. Two `204` endpoints, one archive flag,
         one `blocks` insert, and a test that the two responses look the same —
         the design had it all written down and B2's `InviteOneAnswerTest` was
         the pattern to copy.
Reality: the interesting part was not the bodies. Doc 26 §2.1 says a blocked
         person must learn nothing from the other side, and the bodies of a
         left bond and a blocked bond are trivially identical because both are
         just an archived bond. The channel that is *not* trivial is the
         **`ETag`** — a bond's `ETag` is its row version, so if the block path
         wrote to the bond row once more than the leave path did, the other
         member would be holding a number that counts how many times something
         happened to them. Nothing in the JSON would differ. I only noticed
         because I was writing the assertion list and asked what else the
         response carries, which is a thin reason to catch something that
         thin. It is now asserted in `DiscreetExitTest` and again in the smoke
         script.
         **The first mutation of that assertion did not kill it, and the reason
         was the more useful finding.** I added two writes to the block path
         that changed a field and changed it back, expecting the version to
         tick twice — it did not move at all, because both writes live in one
         transaction and Hibernate coalesces them into a single flush and a
         single version bump. Forcing a flush between them (a bulk JPQL update
         does that) made the version jump to 4 against the leave's 2 and the
         test failed properly: `expected:<"2"> but was:<"4">`. So the version
         is only exposed when a write crosses a flush boundary — worth knowing
         before B4 makes `If-Match` load-bearing, and a reminder that a
         mutation that fails to kill a test has two explanations and the
         flattering one is usually wrong.
         The lock mutation was blunter: deleting `lockBond` from `EndBond` made
         both race tests fail three runs out of three — with a **500**, not
         corruption, because `@Version` catches the conflicting write. The lock
         is not what prevents the bad state; the version is. The lock is what
         stops the user seeing a server error for asking two reasonable things
         at once. Same for the archived check: removing it gives 500, not a
         wrong 204, because `Bond.leave`'s `check(isOpen)` still refuses. Two
         layers hold every guard here, and only the outer one knows the right
         status — which is an argument for keeping both, not for trusting one.
         Smaller: `DELETE /bonds/{id}/invites/{id}` on an archived bond used to
         answer 404, which is true (ending a bond revokes its code) and is not
         what the design's §6.3 promises — "every other bond-scoped write is
         409 BOND_ARCHIVED". Nearly-true rules are the ones that get built on,
         so it is 409 now, and the contract gained one response. And the
         cross-tenant suite failed on both new routes before I added their
         fixtures, naming them in the message, exactly as B1 designed it to.
         **CI found the one real mistake in the slice, and it was in a test.**
         `EndBondRaceTest` asserted that after a concurrent leave and block
         nobody is left active — true in the ordering my machine produced, and
         false in CI's. `left_at` is only ever the *caller's own*: if the block
         wins, the bond is archived by the time the leave arrives, so the leave
         is a 409 and that member keeps `left_at IS NULL`. Which is the ordinary
         state of whoever did not end the bond, and here reached by losing a
         race. The assertion is now written per branch and says why, because the
         state is worth documenting rather than discovering: no slot is held
         (FR-025 counts open bonds only), and block still stamps them if they
         want out. Third time CI's Linux runner has caught an assumption that
         held on this laptop.

         **Then the Codex bot found a P1 my own tests could not have caught,
         and it was the same class of mistake as the `ETag`.** Blocking an
         already-archived bond stamped the blocker's `left_at`. The person who
         left keeps read access, `MemberResponse` shows every member's `leftAt`,
         and block is the only mutation an archived bond accepts — so from her
         side a field *changed*, and the only thing that change could mean is
         "she blocked me". My `DiscreetExitTest` compares two bonds ended by the
         same member, which is the symmetric case; the oracle lives in the
         asymmetric one (she leaves, then he blocks), and I never wrote that
         test. `Bond.end` now returns the aggregate unchanged on a bond that has
         already ended, and the new test asserts her GET is byte-identical
         before and after, `ETag` included. Reverting the fix makes it fail, so
         it holds. That is twice in one slice that the leak was a *change over
         time* rather than a difference between two responses — comparing two
         snapshots is not the same as comparing before and after, and I had only
         built the first kind.

Wrong about: which part of "indistinguishable" is hard. I assumed it was the
         copy and the status codes — the things a person reads. Those were
         free. The hard part was the metadata the *client* is told to keep,
         and I would not have found it by reading FR-029 or by comparing
         response bodies more carefully. It came from enumerating everything
         that crosses the wire, headers included, which is now the question I
         want to ask on every slice where two paths must look alike.

## 2026-09-28 · Phase 2 · The conditional update, and the mechanism I was sure was doing the work
Expected: a mechanical slice. `@Version` has been on the bond row since B1 for
         exactly this, the `ETag` has been going out since then, so `PATCH`
         was surely just "read the header, compare it, write, and let
         Hibernate catch anybody who slipped through".
Reality: **the mechanism I planned the slice around cannot fire on the path I
         planned it for, and the test said so before I had written the
         endpoint.** `BondStore.update` re-reads the row inside its own
         transaction and the mapper deliberately leaves `version` to Hibernate,
         so the UPDATE always carries the row's *current* version — hand it a
         stale aggregate and it overwrites newer values in silence. My plan had
         a whole step about flushing so the optimistic-lock exception could be
         caught; there was no exception to catch.
         So the protection had to move up a layer: `UpdateBond` takes the
         bond's row lock, then compares `If-Match`, so the read, the check and
         the write are one serialised decision. Removing that lock fails
         `BondSettingsRaceTest` three runs out of three — and it fails as
         `[200, 500]`, which corrected me a second time: `@Version` **does**
         fire under genuine concurrency, because each transaction loads its own
         copy before the other commits. It just arrives as a 500. So the three
         things are doing three different jobs — the column is the `ETag`,
         Hibernate's check is a backstop against corruption, the lock is what
         turns a race into the `412` the contract promises — and I had
         collapsed all three into one sentence in the plan.
         The flush turned out to be needed anyway, for a reason I had not
         thought of: `@Version` increments at flush and `EntityManager.find`
         answers from the persistence context without one, so the re-read that
         builds the response saw version 0 and the endpoint returned
         `ETag: "0"` after a successful write — a value the client's next
         `If-Match` would be refused with. Right instinct, wrong reason,
         and only the test knew.
         Two smaller ones, both found by running rather than reading.
         `Optional<@Pattern String>` **compiles and does not run**: `"9am"`
         sailed past validation and `LocalTime.parse` threw a 500. And the
         smoke script found that a patch whose values already match the row
         writes nothing, so the version does not move — I had written a probe
         expecting a bump. That behaviour is right (the other member's `ETag`
         should not be invalidated for nothing) and is now asserted rather than
         incidental.
         Also: detekt pushed `BondStore` over its method limit for the second
         time in three slices, and for the second time the fix was a real
         boundary rather than a bigger threshold — `MemberStore` holds what
         belongs to one member and nobody else. While moving things I noticed
         `memberUserIdsEverOf` was a second query for data both callers already
         had loaded; it is `Bond.everyMemberUserId()` now, one fewer round trip
         on every resolve and accept.
Wrong about: how much a mechanism being *present* tells you about it being
         *load-bearing*. `@Version` was in the schema, mapped, and visibly
         doing something — it had been bumping the number in the `ETag` since
         B1. That made it easy to believe it was also enforcing the thing it is
         famous for enforcing, and it was not, on the one path that mattered.
         The general form: a component can be in the right place, with the
         right name, working correctly, and still not be the thing standing
         between you and the bug. Only breaking it tells you which.

## 2026-09-28 · Phase 2 · Consent, and the two oracles the spec would have shipped
Expected: the largest slice of Phase 2 but the least surprising one — a table,
         five endpoints, a seven-day window and a thirty-day one. The design
         had all of it written down, including the `bond_proposals` shape.
Reality: **the design also had two defects, and both were visible before I
         wrote a line** — which is new. B2's and B3's defects were things I
         built and then found; these were things I found by reading the spec
         against the rest of the corpus, and I put them to Daniel as decisions
         rather than discovering them in a test later.
         The first is the one that matters. §6.4 said a deletion request is
         refused on a bond ended by a **block** and allowed on one ended by a
         **leave**. That is an oracle, and the same oracle B3's review found in
         the `left_at` stamp: the blocked member tries it once and learns which
         happened. Doc 26 §2.1 forbids exactly that, and the spec sentence
         forbidding it and the sentence creating it are four paragraphs apart
         in the same document. Refusing it on *any* archived bond costs a
         member who left the ability to start a mutual deletion — real, and
         cheaper than the leak.
         The second is smaller and more technical: V10's partial unique index
         is `WHERE confirmed_at IS NULL AND cancelled_at IS NULL`, and §6.4
         says lapsed proposals are "never reaped". Both cannot hold — a lapsed
         row still occupies the slot, because **an index cannot ask what time
         it is**. The fix is to close a lapsed row when a new proposal needs
         it, which is what `CreateInvite` already does for the outstanding
         code; the alternative was dropping the index and holding the invariant
         only in the application. `ProposalPersistenceTest` pins the tension
         down rather than leaving it in a comment.
         **Three mutations, all killed.** Dropping the lazy close turns a
         legitimate 200 into a 500 from the unique index. Dropping the proposer
         check lets one person confirm their own request — consent by clicking
         twice. Dropping the compare-and-set from `confirm` failed all three
         runs, which is the one I expected least to be reliable.
         And one self-inflicted wound worth writing down: I ran `git checkout
         -- modules/bond` to revert a mutation and destroyed my *uncommitted*
         EndBond work along with it. The mutation was in one file and I reverted
         a directory. Copying the file to `/tmp` first, as I had done for every
         other mutation in this project, is the habit; skipping it once cost
         twenty minutes of re-typing.
Wrong about: where a design review pays. I have been treating the corpus as
         the thing that tells me what to build, and the tests as the thing that
         tells me whether I built it. But the corpus contradicts itself in
         places, and a contradiction between two documents is not something a
         test can find — the test only knows what I told it. Reading §6.4
         against doc 26 §2.1 before writing the plan found in ten minutes what
         a review found in B3 only after the code existed. The practice worth
         keeping: for each rule the spec states, ask which *other* document
         constrains it, and check the two agree before planning the work.

## 2026-09-28 · Phase 2 · The review of #40, and the space that is not a space
Expected: a triage pass. Five findings on the settings PR, one of them a P1
         about serialising a settings write with leave and block; take the
         lock, answer the two contract nits, push.
Reality: the P1 was real and the fix was one line, but proving it took a test
         that had to catch the PUT *while* it waited. Holding an ending
         transaction open and asserting on the result is not enough — without
         the lock the PUT blocks on the member `UPDATE` instead and then
         commits its stale `leftAt` afterwards, which passes a naive test.
         `BondSettingsRaceTest` now polls `pg_blocking_pids` until the request
         is genuinely contending, and only then commits the ending. Remove the
         `lockBond` line and it fails; that is the only reason to believe it.
         The two "contract nits" were both **edge defects wearing a document's
         clothes**. `If-Match` was `required: false` in `openapi.json` because
         the handler declares it optional — deliberately, so an absent header
         is our `428` rather than Spring's `400` — and springdoc copied the
         declaration without knowing why it was made. And four `@AssertTrue`
         cross-field checks were being published as writable request fields,
         because springdoc reads every public getter as one. Neither could be
         fixed in the file: it is generated, and the next regeneration would
         have eaten the edit.
         Then the third finding, about a nickname of one space, turned out to
         name a defect class rather than a field. **`@NotBlank` and Kotlin's
         `isBlank` do not agree.** Bean Validation trims with Java's
         `String.trim`, which removes only characters at or below `U+0020`;
         Kotlin's `trim` also removes every `isSpaceChar` — `U+00A0`, `U+2007`,
         the whole set. So a name of one non-breaking space passed the edge,
         arrived at the domain as `""`, and `require(name.isNotBlank())` turned
         a well-formed request into a **500**. On `POST /bonds` too, merged in
         B1 and live since. A `create` with `name = " "` returns 500 today
         on `main`; the test that says so went in before the fix did.
Wrong about: what "the same check, in two places" means. `BondConstraints`
         opens by saying the edge should *ask the domain* instead of restating
         its rules, and I read that as being about the interesting rules —
         zones, types, the things with a factory to call. Blankness looked too
         small to be a rule at all, so it got `@NotBlank` at the edge and
         `isNotBlank()` in the aggregate, and those are two different
         predicates that agree on every input anybody types by hand. The
         general form: a restated rule is dangerous in proportion to how
         *obvious* it looks, because nobody checks the obvious ones for
         disagreement. The zones were delegated on the first try. The word
         "blank" was not.

## 2026-09-28 · Phase 2 · The second review of #41, and the flag nobody read
Expected: a formality. B5 had already had a `max`-effort review that found six
         defects and I had fixed all six; the branch was green, the smoke suite
         was green, and the only thing that had changed since was a merge.
         Another pass would confirm it.
Reality: two more, and **the serious one was created by the first review's own
         fix.** That review made `leave` legal during a deletion cooling-off —
         correctly, because refusing `leave` while permitting `block` would have
         made the two distinguishable, which doc 26 §2.1 forbids. But legalising
         it opened a sequence that had never been reachable: both members ask
         for the deletion, one leaves, and then *that* member cancels. The bond
         lands in `ARCHIVED` with the countdown cleared, and the member still in
         it can never delete it, because every re-request is `409` on an archived
         bond. One person consented to destruction and the other revoked the
         agreement on their way out.
         `RequestDeletion.cancel` drops the `isOpen` check on purpose —
         `PENDING_DELETION` is not open, so consulting it would make a
         cooling-off uncancellable. What it should have read instead is
         `Membership.left`. **And `Membership.left` has no readers anywhere in
         the module.** The guard's own KDoc says "it is the write paths that
         refuse them", and they do — by checking `isOpen`, because leaving
         archives the bond. The flag has been decorative since B1, and `cancel`
         is the first path that drops `isOpen`, so it is the first place the
         guarantee was ever load-bearing.
         The second defect was in the same three lines: `cancelDeletion` is the
         only path to `ARCHIVED` that does not go through `Bond.end`, and it set
         the status without `archived_at` — minting the one archived bond in the
         system with a null timestamp, in a column Phase 5's deletion job and the
         export both read as "when did this end".
Wrong about: what "already reviewed" covers. I treated the first review as a
         property of the slice — B5 has been reviewed — when it is a property of
         a *diff*. Three commits had landed since, one of them a behaviour change
         to the exact method the new defect lives in, and I still described the
         PR as reviewed. The rule that falls out is narrower and more useful than
         "review everything twice": **a fix that makes a previously unreachable
         state reachable needs its own pass, and it cannot be the pass that
         produced it.** The first review could not have found this one; the
         defect did not exist until its recommendation was taken.
         Second, smaller: `Membership.left` is the third mechanism this project
         has found that was present, correctly named, visibly doing something,
         and not standing between anyone and a bug — after `@Version` in B4 and
         the `blocks` check in B2. The tell is the same every time. Nothing reads
         it. `grep` for the readers of a flag before believing the sentence that
         says it is enforced.

## 2026-09-29 · Phase 3 · Closing C1 — a deferral that was half wrong, and a false failure the volume had to be empty to find
Expected: the closing task to be paperwork against work already done — regenerate the
         contract, write the smoke section, write the ADR. The one piece of code left,
         `TodayResponse.partnerEntry`'s empty OpenAPI schema, had its own fix already
         named in Task 8's KDoc: add an `OpenApiCustomizer`, the same shape as the
         `ETag` and `Idempotency-Key` ones, so springdoc can express the `oneOf`.
Reality: **springdoc had already fixed half of it, and the KDoc's fear was stale by
         the time I ran the generator.** `PartnerEntryResponse` is a Kotlin `sealed
         interface`, which compiles to a JVM sealed type with `permittedSubclasses`,
         and this swagger-core version reads that on its own — `partnerEntry` came
         out of the generator as `oneOf: [EntryResponse, LockedEntryResponse]`
         already, no customizer involved. What actually stayed missing was narrower
         and easy to miss precisely because the `oneOf` looked complete: no
         `discriminator`, so a generated client would still have to try both shapes
         structurally to learn which one it got. The customizer I wrote adds one,
         keyed on `status`, mapped explicitly for every value either branch's enum
         can hold — `SUBMITTED`/`REVEALED`/`DELETED` to `EntryResponse`, `LOCKED` to
         `LockedEntryResponse` — because an unmapped discriminator value falls back
         to naming a schema directly, and none of those four names one.
         Then the smoke run found a false failure that had nothing to do with the
         slice: "the log never says who blocked whom" failed on a database I had
         just reset with `docker compose down -v` to clear an unrelated stale-migration
         error, and passed again on the very next run against the same, by-then-
         migrated volume. Flyway logs its own migration description on a genuinely
         fresh boot, and V9's filename is `bond_bonds_members_invites_blocks` — the
         word the check was grepping the *entire* log for is also half of a
         migration's own name. Nobody had hit it before because nobody had run this
         script against a truly empty Postgres since that migration was written;
         every ordinary run reuses a volume already past that log line. Scoped the
         check to lines written after its own section starts, the same idiom the
         email-polling checks already use, rather than the whole log from boot.
         Separately, re-ran the mutation the fix reports for Tasks 7/8 claimed:
         flipping `Entry.canBeReadBy` to `= true` fails exactly two `RevealGateTest`
         cases, both through the same mechanism (one member submits, the other reads
         `partnerEntry`) — confirming an earlier fix report's claim that the second
         failure came from `myEntry`'s own routing was wrong, and both tests exercise
         `partnerEntry`, not `myEntry`, either way.
Wrong about: trusting a KDoc's stated risk as still current just because it was
         reasoned carefully when it was written. It was right about the *symptom*
         (a generated client gets neither branch's shape) and wrong about the
         *cause* by the time this task ran the actual generator — the shape was
         already there, the discriminator was not. The general form, twice in one
         task: a deferral note and a stale-log grep both describe a failure mode
         that was true once, under conditions that had since changed underneath
         them, and the only way to find out which parts still held was to run the
         generator and the script rather than read what somebody expected them to
         say.

## 2026-10-03 · Phase 3 · Rebuilding C1 — the lock that retired a test, and the day I opened in the wrong order
Expected: a rework with a known shape. The spec had been revised under a draft PR and I
         had five deltas written down: a timeline instead of a copied zone string, the
         bond lock taken inside the write, idempotency in one transaction, BR-1 on the
         entry's own timestamp, BR-3a rechecked under the day lock. Ten tasks, each with a
         brief, each reviewed. I expected the corrections to come from the reviewers and
         to be about the code.
Reality: **most of what was wrong was in what I told the implementers, and in tests that
         were green.** In the order they surfaced:
         The concurrency test. `SubmitEntryConcurrencyTest` proved that two first entries
         racing produce one `bond_days` row. Task 4 put the bond's row lock in front of
         the submit path, and from that commit both members queue on the bond before
         either reaches the day. The test stayed green with the day lock deleted. It had
         not been broken; it had been retired, by a lock added somewhere else, and nothing
         about a green run says so. The race that still exists is a submission against a
         writer that takes no bond lock, which is what C3's close job will be, and Task 5
         rewrote the test to stage that one.
         Then the rewrite repeated the mistake at a smaller scale. Its two-member case was
         named and commented as proving "the bond lock, not the index". The implementer's
         own mutations said otherwise: remove the bond lock and it passes, remove the index
         and it fails. A test named for a mechanism it survives the removal of. The
         mutation was in the report; the name was in the code; only one of them was true.
         The re-read. `lockMembershipOf` runs the guard, takes `FOR UPDATE` on the bond,
         and reads the membership again under the lock. The second read is the whole
         point, and Hibernate answered it from the persistence context: the guard had
         already loaded the bond and its members, and a query for rows already in the
         identity map hands back the same instances with their old state. An entry
         committed onto a bond that had been archived while the submission waited.
         `BondDayStore.lockAndFind` had the same defect in the first build and ADR-0031
         decision 9 was already about it. I had written that decision and did not carry it
         to the next lock.
         The hash. Task 6 removed `idempotency_keys.response_body` because it was a
         plaintext copy of an entry for 24 hours, and left `request_hash` as a plain
         SHA-256 of the request body. For `POST /entries` the body is the entry. A short
         one is recoverable from that column by hashing guesses. The task closed the
         hazard through one column and left it open through the one beside it, and the
         reviewer raised it as an out-of-scope minor. It is an HMAC now.
         My amendment to Task 3 said: lock before any read, as `ChangeTimezone`, `EndBond`
         and `RequestDeletion` do. They do not. They guard first and then lock, and the
         reviewer found it by opening `EndBond.kt`. I had described the existing code from
         what I believed its discipline to be. Locking first would also have let any
         caller take a row lock on a bond that is not theirs.
         Spec §2.1 lists `BondMembership`'s fields and omits `hasLeft`. Followed as
         written, the rework would have deleted the check the second review of PR #41 had
         added two days before the plan was written, the one from the entry two above
         this, about a flag nobody read. The spec revision simply predates that review
         landing. The plan caught it (R2) by reading `SubmitEntry` against the list.
         And the defect that mattered most came last. Task 9's timezone matrix was the
         first test to open a day and *then* change the zone. No unit or slice test
         before it, across eight tasks, had done those two things in that order.
         `openOrGet` is `ON CONFLICT DO NOTHING`, so the row kept the `ends_at` it was
         opened with while the timeline ran the day on for another 25 hours: an entry
         filed on a row whose span did not contain it, and a hole in the stored calendar.
         The timeline was right, the row was right when written, and the two were never
         compared in that order. In the same task, my amendment's eastward
         example was Lagos to Kiritimati, which skips no label at all: the handoff is 13:00
         on the next day. The implementer computed it, said so, and used Pago Pago.
Wrong about: where the risk in a rework sits. I treated the briefs as the fixed part and
         the code as the part under review. Two of the findings above are errors in an
         amendment of mine (the lock order, the eastward example), and a third is not
         above because it was caught on day one: Task 1's brief gave a westward test
         expected values that contradicted each other. Each was caught because an
         implementer or reviewer recomputed instead of trusting me. The instruction that
         paid for itself was "recompute every number yourself".
         And, again, what a green test is evidence of. A test proves the mechanism it would
         fail without, and that changes when other code changes. Adding a lock can retire
         a test three files away. The only check that found it, both times, was deleting
         the mechanism and running the suite. This log already counts three mechanisms
         that were present and not load-bearing; the difference this time is that the
         deletion was planned as a step (Task 5's mutation) rather than stumbled on.
         Smaller: order is an input. "Open, then change" and "change, then open" are
         different tests, and I had written one of them eight times.

## 2026-10-03 · Phase 3 · Reading C1 whole — three right answers that made a 500
Expected: a formality. Every rework task had been reviewed on its own, most of them twice,
         with mutations. Three reviewers reading the whole branch at the end were there
         to confirm it.
Reality: **no critical defect, and several real ones, none of which was inside a task.**
         The first is three tasks, each correct. Task 1's timeline assumes no label past
         today's is in use. The first build's day assignment lets a claim up to five
         minutes ahead be the candidate, so a fast phone is not refused. Task 9's
         extension assumes a row starts where the timeline says its label starts. Put
         them in order: at 23:57 a phone reading 00:01 opens tomorrow's row; a westward
         zone change confirmed in the next three minutes moves tomorrow's start; and from
         then on every write to that day finds a row whose start disagrees with the
         timeline and fails a `require`. A `500` for both people, for a whole day. Each
         task's tests passed because each task's tests held the other two still. The
         reviewer of the extension had asked the right question, "can a row exist ahead of
         now?", and accepted "rows open only for now or the past". That was false, and it
         was false in a file that task never touched.
         The second is not an interaction at all. `EntryText.of` stored
         `NFKC(raw).trim()`. The spec says normalise to count and store raw; the KDoc said
         normalising "changes nothing a person wrote to mean". It turns an ellipsis into
         three full stops and a trade mark sign into `TM`. No test exercised it: delete
         the normaliser and the suite stayed green. It was written in the first build, it
         was not on any rework task's file list, and so ten reviews of the rework read
         around it. The plan even said "do not touch EntryText", meaning its limits.
         The rest were the same kind of thing at smaller size. A chunked `POST` was a
         `500`, reasoned from reading by a reviewer and then reproduced, all four ways,
         before any fix: the bound I had added to stop an unbounded read refused every
         body whose length was not declared, and the interceptor called that a wiring
         bug. V13's backfill had never run against a row, because every test database is
         empty when it runs. That one turned out correct. It was still untested.
         Then it was run. The development database had the old table shapes and had
         never applied V10; the repair dropped three tables of smoke data and two
         history rows, Flyway applied V10 to V13, the backfill ran over 26 real bonds
         with nothing to correct, and `scripts/smoke.sh` on the jar built from
         `b08b385` came back 350 passed, 0 failed, the handoff section included. The
         repair I had first handed over, a checksum reset, would have left the
         application starting and then failing on columns that were not there.
Wrong about: what a per-task review can see. I had been treating ten clean reviews as
         ten independent confirmations of the branch. They were ten confirmations of ten
         diffs. A defect that needs two tasks' assumptions to collide is in nobody's
         diff, and code nobody changed is in nobody's diff either. The whole-branch read
         is not a second look at the same thing; it is the only look at those two.
         Also: a reviewer's accepted answer is a claim, and it goes stale like a KDoc
         does. "Rows open only for now or the past" was written down as a finding and I
         carried it forward as a fact.
         And the one I should have known by now: "stored raw" was a sentence in the spec
         with no test under it. A sentence the suite survives the negation of is not
         implemented, it is believed.

## 2026-10-04 · Phase 3 · The C1 follow-up — two things reasoned from reading, and what running them said
Expected: a tidy-up. The reviews of #46 had left a short list of small items that were
         recorded rather than fixed: a lone surrogate in entry text, two log lines, a
         client that disconnects mid-body, four missing tests, some smoke probes. Two of
         them had a predicted outcome written beside them, both from reading the code:
         the surrogate "may store `?` or may be a 500", the disconnect "is probably an
         ERROR-logged 500". The instruction was to run each before touching it.
Reality: **neither was a plain `500`, and each had a part nobody had predicted.** The
         surrogate did store `?`, as the list said it might; what was not predicted was
         a `201` echoing the surrogate back while the row held `?`. The disconnect did
         log an ERROR; the status on a real server was not the predicted one.
         The surrogate. `{"text":"thank you\ud800"}` was a `201`. The response echoed the
         surrogate back, rendered from memory. The row held `?`. So ADR-0031 decision 23,
         "stored exactly as sent", was false for one class of input, and the test that
         pins it (`an entry's words survive POST, the row and GET today byte for byte`)
         could not have caught it: every character it sends has a UTF-8 form. The `201`
         was the lie, not the row. Refused now, beside the NUL check, as a `422`.
         The disconnect. Under MockMvc, with a stream that throws after eight bytes: a
         `500`, as predicted. Against a real Tomcat, with a socket that declares 100
         bytes and sends 12: the log line was the predicted one, `Unhandled exception`
         and a stack trace, and the status on the wire was `400`. Tomcat had already
         marked the response when the read failed, and answered through its own error
         page. Two harnesses, the same code, two different statuses. The fix is the same
         either way (catch the `IOException` where the body is read, answer `400`, log a
         WARN), but only the real server showed that the body of that `400` is Boot's
         default error document and not our problem details, before the fix and after
         it. That was left as it is and recorded as owed in ADR-0031. It has not been
         ruled on. A client that aborted is not there to read it; one that only stalled is.
         The log line. `redirectOnce` wrote "an offline entry is redirected to {date}"
         and then checked whether that date was settled. When it was, the request was a
         `409` and the log had recorded a redirect. A log line written before the
         decision it describes.
         The four tests all passed the first time they ran. The behaviour had been right
         since the rework; what was missing was the evidence. The chunked one records
         what the server was actually sent (`Content-Length` absent, `Transfer-Encoding:
         chunked`) rather than trusting that the client framed it that way.
         The smoke script went from 350 probes to 361, and `ALREADY_MEMBER`, which the
         list said to skip unless a sequential probe could reach it, turned out to be one
         request: the creator accepting their own code.
Wrong about: what "stored exactly as sent" was a claim about. ADR-0031 decision 23 and
         the tests under it are about normalisation, because normalisation was the bug
         that produced the decision. The brief said the surrogate was either a `?` or a
         `500`, and nobody had run it. The sentence is in fact
         a claim about every string a JSON parser will hand over, and a JSON string is
         UTF-16 code units, not characters. The driver's substitution was silent, and
         the response was built before the driver ran.
         Also: a reproduction is a claim about the harness it ran in. The MockMvc test
         said `500`, which is also what the brief expected, and on that evidence alone
         the ADR would have recorded a `500`. It took the
         socket to learn that the wire said `400` and that the defect was in the log.
         And one made in this same piece of work. The fallback log line was noisy for a
         client whose clock runs fast, and the fix was to make the line DEBUG. That one
         line covered four reasons a claim is not used; three of them (too old, before
         the bond began, a settled day) put the entry on a day its author did not name,
         which is exactly what the line exists to explain. The noise was fixed by
         hiding the three cases that needed the record. A reviewer on the PR caught it.
         `DayAssignment.resolve` now says which reason it was, and only the
         ahead-of-clock one is DEBUG.
         Smaller: the brief said to remove both items from the ADR's "Owed". Neither was
         there. They had lived in review comments on the PR, which is to say nowhere a
         later reader of the repository would find them.

## 2026-10-04 · Phase 3 · Auditing the other lock callers — a right conclusion filed under a wrong reason
Expected: the recorded note predicted a defect. C1 had found a "re-read under the lock"
         that read nothing, and eleven other call sites take the same lock and then read.
         ADR-0031's Owed list and `lockBond`'s KDoc said of them: they all write the bond
         afterwards, so a stale read meets `@Version`; any that decides without writing
         the bond row would not be caught. On that note the audit would find such a caller
         and fix it with `refreshReads = true`.
Reality: **none of the eleven was wrong, and the sentence was.** Eight of them do not
         write the bond row on at least one path: a new invite, a revoke, a proposal, a
         cancel, a first deletion request, a member's settings. `@Version` was never
         going to see those. And a leave during a cooling-off moves `left_at` and not the
         bond's version, so cancelling a deletion, which does write the bond row when it
         is counting down, was not covered by `@Version` either.
         What keeps them right is smaller and was already written down, in ADR-0028 and in
         a comment in `application.yml`: the controller's guard reads in its own
         transaction, that transaction is over before the service's begins, and the lock
         is the first statement of an empty one. A reviewer had said so from reading. It
         had not been run. Fourteen tests now run it, each a request sent through its
         controller, seen queued in `pg_blocking_pids` behind a transaction that holds
         the bond's row and then commits a leave or an accept.
         The mutations were the informative part. With the bond loaded one line above the
         lock, the callers that write the bond answered `500`: `@Version` did catch them,
         as a server error. The ones that do not answered `200`, `201`, `202` and `204`
         for a bond that had ended. With `open-in-view` switched on in the test
         configuration and no source line changed, eleven of the fourteen went red.
         Two tests written during the audit did not survive their own mutation, and were
         deleted in it. A new invite behind an accept, and a deletion request behind an
         accept, both stayed green with the bond stale, because both count member rows
         and an inserted row is new to the persistence context, so the query returns it.
         Only updated rows hide. And one test needed two mutations at once to fail:
         cancelling a zone proposal reads it after the lock and then cancels by
         compare-and-set, and each of those is enough alone.
Wrong about: what the earlier note was. "They all write the bond afterwards" read like a
         finding. It was a claim recorded about eleven call sites that had not been read.
         It went into an ADR and a KDoc, and a review and a brief both repeated it. The
         conclusion it supported was true, which is why nothing contradicted it.
         Also where a property like this lives. No line in any of the eight services says
         "my transaction starts empty". It is true because of an annotation that is absent
         from six controllers and one line of YAML, and it would have stopped being true
         with a single `@Transactional` added somewhere reasonable-looking. There is an
         architecture rule for that now. It was run against one real violation,
         `@Transactional` on `BondInvitesController`, and failed naming it; one
         controller, not six.
         What is still not covered: a service that calls the guard itself and then locks,
         in one transaction. That is exactly what `lockMembershipOf` is, and why it
         refreshes. Nothing stops the next one being written without it. Nor is a
         transaction opened by a filter or interceptor outside the domain modules, a
         transactional base class from another layer, or a hand-registered
         `OpenEntityManagerInViewFilter`. ADR-0028 §6b lists them, with the runtime check
         that would close them and was not built.

## 2026-10-05 · Phase 3 · Finishing C2 from somebody else's desk — green, reviewed, and two defects a request could reach
Expected: Codex had built the reveal over two days and its session ended mid-test. Its
         own note listed what was left: a rate bucket, some tests, the ADR. The note was
         two hours behind the code, so the first job was finding out what the code was.
         Committed as it stood, the build was green: 873 tests. Three reviewers then read
         the whole branch, one each for locking, for privacy and the contract, and for the
         spec. None found a leak, an authorisation hole or a deadlock. On that the rest
         looked like paperwork.
Reality: two of the findings were wrong behaviour a request could reach, and both were
         about the same day: the one a couple pairs on.
         The first: a day C1 had left `SUSPENDED` with both entries on it is reconciled
         by the first request that meets it, and the reconcile ran inside that request's
         transaction. Reconciling reveals the day. A revealed entry cannot be edited. So
         a `PATCH` as the first request reveals, is refused `409` because of the reveal
         it has just made, and rolls it back. Every retry does the same. Each step is
         right. Only a `GET /today` got the couple out, because a read has nothing to be
         refused for.
         The second: whether a day from before the pairing stays private depended on
         whether anybody had written on it at the time. With a row it was `SUSPENDED`.
         Without one, a back-fill after pairing opened it `OPEN`, and two back-fills
         revealed it. The opening status asked "is the bond still waiting?" where the
         spec's rule is about the day.
         Both were written as tests first and both failed for the reason given: the day
         stayed `SUSPENDED`; `expected SUSPENDED but was REVEALED`.
         A third finding was called latent and not reachable, and it was still worth a
         test: the reveal read a day's entries from Hibernate's identity map and wrote
         each back whole, so an entry loaded before the lock was written back as it
         stood then. The test changed the row from another connection in between and
         got the original text back.
         Then the tests themselves. Fourteen new ones pinned behaviour that was built
         and untested, and all fourteen passed on the first run, which says nothing. Ten
         mechanisms were then removed one at a time. Nine runs went red. The tenth,
         `GET /today` choosing a live entry over a withdrawn one, stayed green with its
         ordering gone: one tombstone and one live row, and an unordered read is right
         half the time. It took five rows to make a test that fails.
         The smoke run was the last unknown and found nothing: 389 probes, 0 failed. It
         ran against a database of its own, because V14 is unmerged and the compose
         Postgres is shared by every checkout.
Wrong about: what a green build and a clean review add up to. The reviewers were asked
         for defects and found them by tracing; nobody ran anything. The two that
         mattered were each one request away, and neither was in any test, because every
         test of the joining day began with a `GET`.
         Also the word "latent". The stale-copy write was unreachable because every
         writer of an entry also reconciled the joining day in the same commit. That was
         true, and it is the same kind of sentence as "they all write the bond
         afterwards" from the day before: a property of eleven call sites, stated once,
         somewhere else. The slice after this one adds a writer that would have broken it.
         And a smaller one, mine. I wrote in the plan that Task 5's tests would "pin what
         is built". A test that has never failed pins nothing; the plan should have said
         the mutation run was part of the task, not the task after it.
         Not settled, and the owner's: an author cannot delete an entry once the bond has
         ended; a delete before the reveal shows the partner that something was removed;
         a replay spends a rate-limit token. Each is built one way with one test on it.
         ADR-0032 has them. And a day in `PENDING_REVEAL` has no way out until C3.

## 2026-10-05 · Phase 3 · The close job — the lock I was told not to take, and what not taking it costs
Expected: C3 came with twelve obligations written down by the two slices before it, and
         one ruling: the closer takes no bond lock. I planned it as nine tasks around one
         idea, that a day is settled alone, in its own transaction, under its own row.
         Everything a submission could do to a day was already tested against a close made
         by hand; replacing the hand with the real closer looked like the safe part.
Reality: the eight build tasks went as planned and the tests found three of my own
         mistakes before a reviewer did.
         The budget was wrong twice. It charged for days the job looked at and left, so
         enough couples waiting on an evening reveal would have used it up every run and
         nothing behind them would close; and my fix for that did not stop mid-page,
         because `takeWhile` on a list is decided before anything in the loop has run. I
         found both by breaking the code on purpose to see whether the tests noticed. The
         tests I had written first passed either way.
         ShedLock's table was wrong. I gave it `timestamptz` like every other table. Four
         tests failed and the failures said only that the job had not run. The cause was
         an hour: ShedLock's database clock writes UTC with no zone, and in a zoned column
         that reads as local time, so on this machine a lock that had lapsed was an hour
         from lapsing. The library's documented schema says `timestamp`. I had not read it.
         And the test class's first version hung, because I counted waiters by asking who
         the lock holder was blocking; a second waiter on a row queues behind the first
         waiter, not behind the holder.
         Then two reviewers read the slice. The spec reviewer found that a bond counting
         down to deletion was being given an `EMPTY` day for each day of the countdown,
         which the spec forbids in so many words. I had read that sentence and built for
         "archived".
         The other finding was the ruling itself. Without the bond's lock the closer sees
         what has committed. A pairing stamped at 23:59:59 can commit at 00:00:01, and the
         job fires at midnight because that is when days end. Read in between, the bond is
         one person, the day is closed as nobody's, and when the pairing lands it was the
         couple's first day together, shut, with the one thing that would have reopened it
         removed by me two tasks earlier for a good reason. I wrote the test: hold the
         bond's row, start the accept, run the job five seconds past midnight. It closed
         the day.
         The fix is a minute. A day is not settled until it has been over for one, and the
         job runs at a minute past the quarter.
Wrong about: where the risk was. I tested the closer against everything that touches a
         *day*, in both orders, because the day is what it locks. The race was against
         something that touches the *bond*, which the closer only reads. "Takes no lock on
         X" was written as a property of the closer's writes. It is also a statement about
         every read of X the closer makes, and I made two.
         And what a margin is. It is not a proof. It covers a commit and a clock a few
         seconds off; it does not cover a transaction that hangs for two minutes. The exact
         fix is a lock that never waits, and that is a bond lock, which is not mine to add.
         ADR-0033 says so, as a question.
         Also the 38-minute build. One run took 38 minutes and I went looking for which of
         my tests was slow. A validation test in another module showed sixteen minutes.
         The machine had slept. The next run took two.
         Not settled, and the owner's: the job reveals a lone entry on a bond that ended
         that afternoon, because the spec says an ended bond must not strand a day, and the
         author can no longer delete it. Nothing can read a past day yet. C5 will.

## 2026-10-05 · Phase 3 · The streak — a number shown and then taken back, and two tests that could not fail

Expected: the easy slice. The rules are a fold over a list of days, the spec states them,
         and for once the hard part (when does a day end) was somebody else's and done. I
         planned the rules as a pure function first so the property tests would need no
         database, and expected the reviews to find wording.
Reality: the function was fine. What I got wrong was everything around it that decides
         what the function is *given*.
         A day both people wrote on, on the day one of them left, was recorded as "after
         the end" and dropped. I had written the rule as "a day that ends after the bond
         stopped taking writes moves nothing", to stop the day somebody left from breaking
         the streak. It also stopped it from counting. The couple were shown 31 that
         morning, by my own read path, and 30 for ever after. The rule is about a *missed*
         day and I wrote it about a day.
         Strict mode was read when the job ran. The job runs after the day is over, and
         one member can change the setting alone. So: miss a day in Strict mode, switch it
         off before quarter past, have a freeze spent on it. The spec's sentence is "not
         in Strict mode at that moment" and I never asked which moment. The bond had no
         way to answer for a past instant, so that was a column in another module.
         The calendar returned each day's status. The design system has a paragraph
         saying exactly why it must not: you know which days you wrote, so a calendar of
         SOLO days is a calendar of the other person's misses. I had read `states.md` §7
         for the layout and not for that.
         And two of my four property tests could not fail. "Replay is a fixed point"
         replayed each day with the same Strict-mode value it was decided with, so a
         replay that ignored the record and decided again got the same answer. "Toggling
         Strict mode alters no past day" toggled the tail of a list and compared the head
         of a fold. Two thousand timelines each, green, proving nothing. I had run
         mutations on the table tests and not on these, because a property test feels
         like it is already the stronger thing.
Wrong about: what "pure" bought. I made the rules pure so they could be tested without a
         database, and then took the tests of the rules for tests of the streak. Every
         defect was in an argument: which outcome, which Strict mode, which days, in what
         order. None was in the function.
         And what an invariant is worth. An invariant that holds for every implementation
         is a fact about the test. The question to ask of a property is the one I ask of
         any test here: what do I break to make this fail? For two of them the answer was
         "nothing in this repository".
         Also which document was the specification. The API contract said `status`, so I
         returned the status. The rule that forbade it was in the design system, under a
         screen.
         Then I fixed the Strict-mode one with a single "last changed at" column and
         wrote in the ADR that it was exact unless two changes fell either side of a
         day's end. A third reader showed that case is the one it gets right. The one it
         gets wrong is two changes *after* the day ended: off, on, and the last change
         says the day was not strict. The fix I had just shipped for "rescue a missed day
         with one request" could be beaten with two. It is a table of changes now. I had
         reasoned about my own shortcut and reasoned wrong, in the document meant to say
         what it cost.
         Not settled, and the owner's: a deletion that is called off leaves a month of
         empty days, and those now end the streak. Nothing records that the countdown
         happened. ADR-0034, question 1.

## 2026-10-06 · Phase 3 · Three rulings, and a reviewer who found what three of mine had not

Expected: the streak was reviewed three times and green; the pull request was the end of it.
Reality: Codex read it and found two things in an hour. Days whose bond row is gone were
         reported as a successful evaluation of nothing, for ever, with no count and no
         log: I had changed a `checkNotNull` into `return 0` to avoid making an orphan
         row, and made an invisible retry loop instead. And my Strict-mode history kept
         two changes at one instant as one row with the later value, which the history
         then read backwards. I had written that upsert with the comment "one change to
         whichever came last", which is true of the bond and false of the history.
         Then the owner ruled. Called-off deletion days are suspended; that needed `bond`
         to remember the countdown, which it had been forgetting since B5. Solo days are drawn on the calendar after all. FR-073 beats
         the drawn Strict-mode frame.
Wrong about: the solo ruling, in a useful way. I had treated `states.md` as a rule to obey
         and built an API that *could not* draw a solo day. The rule was a design
         position, the owner's to change, and he changed it in a sentence. Holding a
         product decision in the type system is right when the decision is settled and
         a tax when it is not; I did not ask which this was.
         And reviewers are not interchangeable. Three readers I briefed found what I
         pointed them at. The fourth I had not briefed, and found what I had not thought
         to point at.
