# ADR-0014 — Each module owns its Flyway migrations, in one global version sequence

**Status:** Accepted · **Date:** 2026-09-19

## Context

Phase 0 put the only migration, `V1__extensions.sql`, in `app/src/main/resources/db/migration`. That was the obvious place while there was one file and no module had a table. Phase 1's first slice adds `users` and `credentials`, and the question became load-bearing rather than cosmetic.

The forcing constraint was not aesthetics. ADR-0001's module boundaries are enforced by Kotlin `internal`: a module's entities, repositories and mappers are invisible outside it. `UserEntity` and `UserRepository` therefore **cannot be named from `app`**, so the integration tests that prove the mapping matches the schema have to live inside `modules/identity`. A test inside the module needs a schema, which means the module needs its migration on its own test classpath. Keeping migrations in `app` would have forced one of three bad answers: widen the visibility and delete the boundary; skip the boundary tests; or duplicate the schema in a test fixture that can drift from the real one.

Two sub-questions come with the decision: how versions are numbered across modules, and who owns database-wide objects that belong to no module.

## Decision

**A module owns the migrations for the tables it owns.** They live in `modules/<name>/src/main/resources/db/migration`. Flyway merges every `classpath:db/migration` on the classpath, so `app` picks them up through the dependency it already has on each module.

**`app` keeps only what belongs to no module** — currently `V1__extensions.sql` (`citext`, `pgcrypto`), which is database-wide infrastructure.

**Versions are one global sequence**, not per-module ranges. There is one schema, so there is one timeline. A migration takes the next free number across the whole repository.

**A module's migration states the extensions it needs**, with `IF NOT EXISTS`, so its own test suite can build the schema on an empty database without `app`.

## Consequences

**Positive:** a module is testable on its own — `./gradlew :modules:identity:test` stands up a real Postgres, runs the module's own migrations and validates its entities against them, with no application context involved. Table ownership (doc 07 §1) stops being prose and becomes a directory. And the `internal` boundary survives contact with integration testing, which is the first thing that usually breaks it.

**Negative:** two branches can claim the same version number. Flyway refuses to start on a duplicate, so the failure is loud and immediate rather than silent — but it is still a merge conflict that has to be renumbered by hand, and the renaming is manual. It is also now possible to have a migration on a module's *test* classpath that never reaches production, if a module is not wired into `app`; the assertion in `FlywayMigrationTest` that the full expected version list applied is the guard against that.

**Neutral:** the extension declaration is repeated between V1 and the first migration of each module that needs it. `IF NOT EXISTS` makes the repetition a no-op, and the alternative — a shared migration module on every classpath — reintroduces duplicate-version errors in `app`, which would see the file twice.

## Alternatives considered

- **All migrations in `app`, integration tests in `app`.** The status quo. Rejected because it cannot work with `internal` boundaries: `app` cannot name the types under test. Would become right if the modules' persistence types were public, which is the trade ADR-0001 explicitly refuses.
- **Per-module version ranges** (identity 2xx, bond 3xx, …). Removes the collision risk entirely at no runtime cost. Rejected as premature for a single developer on short-lived branches, where a collision is both rare and instantly diagnosed. Becomes right the moment more than one person writes migrations, or branches start living longer than a day.
- **Flyway callbacks or a shared `common:migrations` module** carrying V1 for everyone. Rejected: anything on both `app`'s and a module's classpath is seen twice by `app`, and Flyway treats a duplicate version as an error.
- **A test fixture that creates the schema separately from the migration.** Rejected outright: the entire value of these tests is that they run against the schema production will have.

## Revisit when

A second person starts writing migrations, a branch lives long enough for two migrations to be authored in parallel, or the first module is extracted into its own deployable (doc 25 names `media` and `notification` as the split candidates) — at which point "one schema, one sequence" stops being true and this decision is void rather than merely inconvenient.
