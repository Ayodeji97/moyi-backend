# Module 3 — Multi-Module Setup

**Course:** PL Coding, *Building Industry-Level Kotlin Backends With Spring Boot*
**Built as:** Phase 0 of this repo (PRs #1–#9)
**Verdict:** Followed structurally, with the module layout and package
naming adapted, and two real defects found in the course's own approach.

---

## Gradle multi-module (the modular monolith)

| | |
|---|---|
| **What** | One deployable service, split into Gradle modules with enforced boundaries, rather than one source tree or many deployables. |
| **Why it exists** | Microservices' cost (distributed transactions, network failure modes, multiplied ops) buys scaling headroom most products don't need; a single undifferentiated source tree rots. This is the middle, and it keeps the option to extract a module later. |
| **In Spring Boot** | `settings.gradle.kts` with `include(...)`; one `@SpringBootApplication` in `app`, which depends on every other module. |
| **Moyi** | Same idea, different layout. The course puts feature modules at the top level (`user`, `chat`); ours are nested under `modules/` per doc 25 §6, with `common` split into `core`/`web`/`security`/`testing`. ADR-0001. |
| **2026 check** | ✅ Still current. Spring Modulith exists as an alternative for in-process module boundaries but adds a runtime dependency to solve what Gradle modules plus Kotlin `internal` already solve at compile time. |
| **Interview** | *When would you split it?* Around 500 RPS, or when a module's scaling profile genuinely diverges — `media` and `notification` first, which is why they're already separate. *Why not microservices now?* One developer, €25/month, <20 RPS: I'd be buying distributed-systems problems and no benefit. |

## Convention plugins (`build-logic`)

| | |
|---|---|
| **What** | Build logic extracted into an included build, applied to modules as plugins, instead of copy-pasted `build.gradle.kts` blocks. |
| **Why it exists** | Eight modules configuring their own Kotlin version, compiler args and test setup drift apart within weeks. A convention plugin makes the shared decision exist in one place. |
| **In Spring Boot** | `build-logic/` with `kotlin-dsl`, `pluginManagement { includeBuild("build-logic") }` in settings, and precompiled script plugins as `src/main/kotlin/<name>.gradle.kts`. |
| **Moyi** | Same three-plugin layering as the course (`kotlin-common` → `spring-boot-service` → `spring-boot-app`). We read plugin versions from the version catalog rather than hardcoding them in `build-logic` (doc 18 §8: versions live in the catalog only). |
| **2026 check** | ✅ Current, and the standard approach — Now in Android uses the same pattern. |
| **Interview** | *Why an included build rather than `buildSrc`?* `buildSrc` invalidates the whole build on any change to it; an included build is cached per-plugin and composes better. *What goes in a convention plugin vs a module's own build file?* Anything true of every module of that kind; anything specific to one module stays local. |

## Version catalogs

| | |
|---|---|
| **What** | `gradle/libs.versions.toml` as the single declaration of dependency and plugin versions, referenced as `libs.foo`. |
| **Why it exists** | Version strings scattered across modules silently diverge, and upgrading means finding all of them. |
| **In Spring Boot** | `[versions]`, `[libraries]`, `[plugins]`; `implementation(libs.some.thing)`. Gradle auto-creates the `libs` catalog from the default file location. |
| **Moyi** | Same. One gotcha: the generated `libs.*` typed accessor is **not** reliably available inside a precompiled script plugin — read the catalog via `extensions.getByType<VersionCatalogsExtension>().named("libs")` instead. The course hit this too and solved it identically (`VersionCatalogExt.kt`), which was reassuring to discover independently. |
| **2026 check** | ⚠️ Declaring the catalog explicitly in *both* the root and `build-logic` settings collides on Gradle 9.7 ("Multiple 'from' invocations") — Gradle already created it from the default path. |
| **Interview** | *Why not just a constants file?* The catalog is understood by Gradle itself, so it feeds dependency locking, version alignment and tooling; a constants object is invisible to all of that. |

## Module boundaries, enforced

| | |
|---|---|
| **What** | Rules that fail the build when a module reaches into another's internals or a layer depends outwards. |
| **Why it exists** | A boundary that is only a convention is a boundary that erodes under deadline. Doc 16 §2.1: "conventions that are not executable decay." |
| **In Spring Boot** | Kotlin's `internal` (scoped to the Gradle module) does the compile-time half; Konsist or ArchUnit tests do the rest — layer direction, annotation placement, injection style. |
| **Moyi** | This is our main addition over the course, which has no such tests. See `app/src/test/kotlin/com/moyi/app/ArchitectureTest.kt`. Our packages are `com.moyi.<module>.<layer>`; the course shares one package root across all modules, which is a split package and makes such rules unexpressible. |
| **2026 check** | ✅ Konsist 0.17.x is current and Kotlin-native (ArchUnit is Java-oriented and reads bytecode, so it can't see `internal` or expression bodies). |
| **Interview** | *How do you stop a modular monolith becoming a ball of mud?* Make the boundary a build failure, not a code-review opinion. *What does `internal` actually scope to?* The Gradle module / compilation unit — which is exactly why one module per boundary works. |

## JPA compiler plugins — `allopen` vs `noarg` ⚠️

| | |
|---|---|
| **What** | Two *separate* Kotlin compiler plugins that JPA entities need for different reasons. |
| **Why it exists** | Kotlin classes are `final` with no no-arg constructor. Hibernate needs to subclass entities (lazy proxies) **and** instantiate them reflectively. |
| **In Spring Boot** | `kotlin("plugin.jpa")` is **noarg** — it synthesises the no-arg constructor. `kotlin("plugin.allopen")` with the `jakarta.persistence` annotations listed is what makes classes non-final. `kotlin("plugin.spring")` opens Spring's own annotations but knows nothing about JPA. |
| **Moyi** | Both applied in `spring-boot-service-convention`, where our entities live. Verified in bytecode rather than assumed. |
| **2026 check** | ⚠️ **The course has a latent bug here.** Its `allOpen` block sits in the *app* convention, while its entities live in feature modules that only apply the *service* convention — so its entities are never opened. Invisible until the first `fetch = LAZY` association. |
| **Interview** | *Why do Kotlin JPA entities need compiler plugins?* Final-by-default breaks proxying; no default constructor breaks reflective instantiation — two problems, two plugins. *How would you catch it?* Compile an entity and read `javap`: `public final class` means allopen never applied. |

---

## What we deliberately did differently

| Course | Us | Why |
|---|---|---|
| Feature modules at top level | Nested under `modules/` | Doc 25 §6 |
| One shared package root across modules | `com.moyi.<module>.<layer>` | Avoids split packages; makes architecture rules expressible |
| `api/` = the HTTP layer | `api/` = the inter-module contract; HTTP is `web/` | Our modules genuinely call each other (outbox instead of RabbitMQ, D1), so the contract needs the name |
| `io.spring.dependency-management` plugin | Gradle's native `platform()` BOM | The plugin predates Gradle having this built in |
| Plugin versions hardcoded in `build-logic` | Read from the version catalog | Doc 18 §8 |
| No architecture tests | Konsist rules in CI | Doc 16 §2.1 |
| `kotlin("plugin.jpa")` only | `plugin.jpa` **and** `plugin.allopen` | The course's entities are never actually opened |

## What actually cost time

Not the multi-module concept — the ecosystem around it. JDK 25 shipped
ahead of its tooling: Kotlin 2.2's compiler has no `JVM_25` target, and
detekt 1.23.8 refuses to run on certain JDK 25 builds entirely. Neither
is in any course. The lesson that generalises: check a tool's release
date against the JDK's before assuming "current LTS" means supported.

Full detail in [`../docs/learning-log.md`](../docs/learning-log.md).
