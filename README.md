# Moyi Backend

## What is this

The backend for **Moyi** — "Momoyi" in Yoruba, "I know your worth." A
private space for two people, often apart, to each write one thing
they're grateful for about the other, every day. Neither can read the
other's entry until both have written theirs — then both are revealed
together, and a streak tracks the days you both showed up.

One Kotlin/Spring Boot service. Built as a learning project alongside
Philipp Lackner's *Building Industry-Level Kotlin Backends With Spring
Boot* course, deliberately diverging from the course's chat-app shape
wherever this domain — scheduled, timezone-dependent, two-person-private —
actually differs from request/response chat. Every divergence is recorded
as an ADR in [`adr/`](./adr).

## Why it exists

Two reasons, both real: to actually ship a small, correctly-built product
for two people who are far apart, and to learn backend engineering deeply
enough to defend every decision in an interview — not just follow a
tutorial. See `adr/` for the record of what was decided and why, and
`docs/learning-log.md` for the record of what was actually learned
building it.

## How do I run it

**Prerequisites**
- JDK 25 (e.g. via [SDKMAN](https://sdkman.io): `sdk install java 25.0.4-tem`)
- Docker. Colima (`brew install colima docker docker-compose`, then
  `colima start`) works and is what this project is developed against;
  Docker Desktop works too.
  - **Colima only**: Testcontainers (used by the integration tests) needs
    to know where Colima's Docker socket is. Add this to
    `~/.testcontainers.properties` (create it if it doesn't exist),
    replacing the path with your own username:
    ```
    docker.host=unix:///Users/<you>/.colima/default/docker.sock
    ```
    (The other half of this — telling Testcontainers' Ryuk reaper the
    socket path *inside* the Docker host — is already handled in
    `build-logic`'s test configuration and needs no per-developer setup.)

**Running it**
```
docker compose up -d          # Postgres 18 + Redis (Valkey) for local dev
./gradlew bootRun             # starts the app on :8080, profile: default
curl localhost:8080/actuator/health
```
For the app to actually reach the Postgres/Redis started above, run it
with the `local` profile instead: `SPRING_PROFILES_ACTIVE=local ./gradlew bootRun`.

`./gradlew build` runs the full local verification loop: compile, ktlint,
detekt, and tests — including integration tests that spin up a real
Postgres via Testcontainers (needs Docker running).

## How is it structured

A Gradle multi-module build (modular monolith — one deployable service,
internally decomposed with enforced module boundaries):

```
moyi-backend/
├─ build-logic/     convention plugins shared across modules
├─ app/             @SpringBootApplication, wiring — depends on everything
├─ common/          core, web, security, testing — shared, no domain logic
├─ modules/         identity, space, gratitude, media, notification,
│                   scheduling, admin, analytics — the actual domain
├─ contracts/       OpenAPI generation for the client
├─ adr/             architecture decision records
├─ revision/        course-concept notes (what the course taught vs.
│                   what this project does, and why)
└─ docs/            learning log and other in-repo documentation
```

No module reaches into another module's internals — enforced by Konsist
architecture tests in CI (`app/src/test/kotlin/com/moyi/app/ArchitectureTest.kt`),
not left to discipline alone.

## How is it reviewed

Solo project, so review is automated rather than delegated (doc 18 §11):

- Every change goes through a PR — never a direct push to `main`, which
  is branch-protected and requires the `quality` and `gitleaks` checks.
- The PR template carries a ten-question hostile-reviewer checklist,
  filled in per PR rather than ticked through.
- `.github/workflows/claude-code-review.yml` posts an automated review on
  each PR when it's opened, reopened, or marked ready.

**One-time setup to activate the automated review** (it skips with a
notice until this is done, rather than failing):

```
claude setup-token
```

Then add the printed token as a repository secret named
`CLAUDE_CODE_OAUTH_TOKEN` (Settings → Secrets and variables → Actions →
New repository secret), or:

```
gh secret set CLAUDE_CODE_OAUTH_TOKEN --repo Ayodeji97/moyi-backend
```

This bills against the Claude subscription rather than separate API
credits. Note the review posts inline comments and a check run — GitHub
has no way to show it as a *requested reviewer*.

## How do I deploy it

Not yet. CI (`.github/workflows/ci.yml`) builds, tests, and — on every
merge to `main` — publishes a container image to GHCR via Spring Boot's
Cloud Native Buildpacks support (`./gradlew bootBuildImage`, no
hand-maintained Dockerfile). There's no deploy stage after that: nothing
pulls the image anywhere yet. Deployment to Hetzner + Kamal + kamal-proxy
is a follow-up plan, once that server infrastructure exists.
