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
