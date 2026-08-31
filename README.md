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
  each non-draft PR when it is opened, reopened, or marked ready. It
  reviews the diff against the Kotlin/Spring conventions, architecture,
  testing strategy, and ADRs. It deliberately does not run on every push:
  the review plugin declines a PR it has already reviewed, so a per-push
  trigger would add no-op runs rather than fresher reviews. Re-run the
  workflow from the Actions tab to review again after pushing fixes.

**One-time setup to activate the automated review.** Both steps are
required — the token alone is not enough. Do only step 2 and every PR
fails with `401 Unauthorized - Claude Code is not installed on this
repository`.

**1. Install the Claude GitHub App** on this repository:
<https://github.com/apps/claude>. This is what grants the workflow
permission to act on the repo; without it the action fails with *"Claude
Code is not installed on this repository."*

**2. Create the auth token and store it as a repository secret:**

```
claude setup-token
gh secret set CLAUDE_CODE_OAUTH_TOKEN --repo Ayodeji97/moyi-backend
```

(Or paste it under Settings → Secrets and variables → Actions → New
repository secret, named `CLAUDE_CODE_OAUTH_TOKEN`.)

Using the OAuth token rather than an `ANTHROPIC_API_KEY` bills against
the Claude subscription instead of separate API credits.

Until the secret exists the job skips with a notice rather than failing.
Once it exists but the app is not installed, the job **fails** — that is
deliberate, because a review that cannot run should be visible rather
than quietly green.

**When the `review` check cannot be trusted.** There is one case that is
neither of the above: `claude-code-action` refuses to run whenever
`claude-code-review.yml` differs from the copy on `main`, and it reports
that refusal as *success*. Any PR that edits the review workflow
therefore gets a green `review` check having reviewed nothing. The job
emits a warning in its summary when it detects this, but the check still
passes — so a workflow change is reviewed by hand, and only proven by
merging it and watching the PR after it.

Note the review posts a single summary comment and a check run; GitHub
has no mechanism to show it as a *requested reviewer*.

## How do I deploy it

Not yet. CI (`.github/workflows/ci.yml`) builds, tests, and — on every
merge to `main` — publishes a container image to GHCR via Spring Boot's
Cloud Native Buildpacks support (`./gradlew bootBuildImage`, no
hand-maintained Dockerfile). There's no deploy stage after that: nothing
pulls the image anywhere yet. Deployment to Hetzner + Kamal + kamal-proxy
is a follow-up plan, once that server infrastructure exists.
