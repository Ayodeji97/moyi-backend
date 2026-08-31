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

*Local development environment setup is in progress. This section will
cover:*
- *Prerequisites (JDK 25, Docker/Colima, Gradle wrapper)*
- *`docker compose up` for Postgres + Redis*
- *`./gradlew bootRun` to start the app*
- *`GET /health` to confirm it's alive*

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
in CI, not by discipline alone.

## How do I deploy it

Not yet — deployment (Hetzner + Kamal + kamal-proxy) is a follow-up once
the server infrastructure is provisioned. CI currently builds, tests, and
publishes a container image to GHCR on every merge to `main`; there's no
deploy stage wired up yet.
