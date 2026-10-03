# CLAUDE.md

Guidance for Claude Code (and any other agent) working in this repository. It is kept
short on purpose: it states the rules that are expensive to get wrong and points at the
documents that hold everything else. **If this file and one of those documents disagree,
the document wins and this file is the bug.**

## What this is

The backend for **Moyi**, a private gratitude journal for exactly two people. Each writes
an entry a day; neither can read the other's until both have written. Kotlin, Spring Boot,
Postgres, Redis (Valkey). One deployable service, split into Gradle modules whose
boundaries are enforced by tests. The repository is **public**.

## Where things are

| For | Read |
|---|---|
| How to run, test and deploy; the module diagram | `README.md` |
| Why a thing is the way it is | `adr/` — one numbered record per decision |
| What is being built now, and the rules it must satisfy | `docs/superpowers/specs/` |
| What went wrong before, honestly | `docs/learning-log.md` |
| The Definition of Done and the self-review questions | `.github/PULL_REQUEST_TEMPLATE.md` |
| The API contract | `contracts/openapi.json` (generated) |
| What the build enforces | `app/src/test/kotlin/com/moyi/app/ArchitectureTest.kt`, `build-logic/` |

Read the ADR before changing something it decided. A decision is changed by a new or
amended ADR, not by a commit that quietly does otherwise.

## Commands

```
colima start && docker compose up -d      # Docker, then Postgres + Valkey for local dev
./gradlew build                           # everything: tests, ktlint, detekt, architecture rules
./gradlew :modules:bond:test --tests "com.moyi.bond.SomeTest"   # one class
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
scripts/smoke.sh                          # boots the jar and drives every endpoint with curl
```

The jar needs JDK 25. Tests use Testcontainers, so Docker must be running.

## Structure

`app` wires; `common/{core,web,security,testing}` is shared and holds no domain logic;
`modules/*` is the domain. Inside a module the layers are `api`, `domain`, `service`,
`infra`, `web`, and dependencies point inwards — `domain` depends on nothing.

**A module reaches another module only through that module's `api` package.** Everything
else is Kotlin `internal`. No foreign key crosses a module boundary.

## Rules

Each rule carries its reason, so that a case the rule did not foresee can still be judged
the way the rule would have judged it.

**Process**

- **Every change goes through a pull request; never commit or push to `main`.** This is a
  solo project, so review and CI are the only second pair of eyes there is.
- **Do not merge unless the owner says so.** The owner merges; an instruction to merge
  covers the pull requests it names, not the ones after them.
- **Commits follow Conventional Commits, subject at most 88 characters.** The hook at
  `.githooks/commit-msg` checks it, but only once it is switched on
  (`git config core.hooksPath .githooks`), and CI does not check it at all. So check the
  subject yourself; on a fresh clone nothing will stop a bad one.
- **A breaking API change carries the `breaking-api-change` label.** The client is
  generated from the contract, so the contract check fails without it, deliberately.

**Code**

- **A rule lives in one place.** Validation at the edge runs the domain's own check and
  reports its answer; it never restates the rule. Every time a rule has been written
  twice here, the two copies have drifted and the second one was the bug.
- **Migrations are forward-only once merged.** A merged migration has already run on a
  real database, so a correction is a new migration. On an unmerged branch it may still
  be edited in place. Versions are one sequence across all modules.
- **`contracts/openapi.json` is generated, never hand-edited.** `OpenApiContractTest`
  fails when it is stale and prints the command that regenerates it. Read the diff it
  produces: that diff is the API changing.
- **Entry text, passwords, tokens and invite codes never reach a log**, an exception
  message, or a second column; types that hold them redact their `toString`. What two
  people write to each other is the one thing this product must not leak.
- **Authorisation comes before any read, and a non-member gets `404`, never `403`.** A
  `403` would confirm the bond exists. A new bond-scoped route needs a cross-tenant test,
  and the suite fails if it has none.
- **Timestamps are truncated to microseconds before they are stored.** That is what
  Postgres keeps; a finer value compares unequal to itself after a round trip.
- **JPA entities are not `data class`es, and ids are assigned in application code.**
  Generated `equals`/`hashCode`/`copy` break Hibernate's identity, and an id known before
  the insert spares a round trip.

**Tests**

- **A test must fail when the thing it tests is removed.** Break the mechanism, watch the
  test go red, restore it. Tests here have passed for the wrong reason before, and a
  guard that has never failed is not yet known to work.
- **Concurrency tests synchronise on what happened, never on a sleep**: a lock held, a
  statement blocked. A sleep makes a test that is slow when it passes and flaky when it
  matters.
- **Green tests are a claim about the machine they ran on.** Before calling something
  done, run it: `./gradlew build`, then `scripts/smoke.sh` for anything that touches an
  endpoint. Defects have been found by the smoke run that no test caught.

**Reporting**

- **Say what was run and what was only read.** "Verified" means it was executed and seen;
  the owner decides what to merge on the strength of that word.
- **When the spec and the code disagree, say so and stop.** Do not bend a test or an
  expectation to fit. The disagreement is the finding.

## Keeping this file true

This file is loaded into every session, so a stale line here is followed as faithfully as
a correct one. Change it in the same PR that changes the rule it describes.

`AGENTS.md` repeats four of these rules, word for word, for tools that read only that
file. That is a second copy, which this file warns against, so it is kept deliberately
small: when one of those four changes here, change it there in the same commit.
