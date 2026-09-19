# Module 2 — System Design

13 lessons · 91 min · checked against 2026 · **2 items materially out of date, both in our favour**

---

## Verdict first

| | |
|---|---|
| **Adopt as taught** | System design method · modular monolith · Postgres · Redis · JWT · self-host + hybrid cloud · reverse proxy |
| **Deliberately diverge** | RabbitMQ → **transactional outbox** · WebSockets → **push + refresh** · Supabase → **self-hosted PG + Cloudflare R2** · Nginx → **kamal-proxy** · Mailgun → **Resend** |
| **Course has aged** | ⚠️ **"Use Java-style APIs, Kotlin coroutines are awkward"** — Spring Boot 4 changed this · ⚠️ **"NoSQL has no guaranteed consistency"** — dated since 2018 |
| **Changes to our plan** | None structural. One addition to `05` §3 noted at the bottom. |

---

## 1. What is System Design

**What** — Deciding the shape of a backend *before* writing it: which components exist, how they talk, where they fail.
**Why it exists** — Retrofitting a database choice or a service boundary costs weeks. Deciding badly on day one is the most expensive mistake available.
**In Spring Boot** — Not a framework thing. It's what determines your module layout, your starters, and your `application.yml`.
**Moyi** — Same method. His four steps (requirements → high-level components → low-level design → scale only when it hurts) map onto our docs `03` → `05` → `06`/`07` → `08`.
**2026 check** ✅ Timeless.

> **"Walk me through how you'd design X."**
> Requirements first — who uses it, what's the read/write ratio, what's the scale. Then components and their boundaries. Then technology and schema. **Then stop.** Don't optimise until you have a number that hurts. Most candidates jump straight to "I'd add Redis and a CDN" and lose the room.

> **"What's the most expensive decision to reverse?"**
> The data model and the service boundaries. Everything else is a rewrite of one layer; those two are a rewrite of everything.

---

## 2. Monolith vs Microservices vs Modular Monolith

**What** — Monolith: one deployable. Microservices: many deployables, own databases, network between them. Modular monolith: one deployable, enforced internal boundaries.
**Why it exists** — Microservices buy independent scaling and fault isolation, and pay for it in distributed transactions, network latency, multiplied ops cost and cross-service debugging.
**In Spring Boot** — Gradle modules with a `common` module. Boundaries enforced by **ArchUnit or Konsist tests in CI**, not by discipline.
**Moyi** — Same choice (ADR-0001). We add the enforcement tests, which the course doesn't. Split point is pre-decided: `media` and `notifications` extract first.
**2026 check** ✅ The industry moved *toward* modular monoliths since ~2023. This is now the defensible default, not the timid one.

> **"Why didn't you use microservices?"**
> Peak load under 20 RPS and one developer. Microservices would have bought scaling headroom I don't need in exchange for distributed transactions and 4× the infrastructure cost. I built a modular monolith with enforced boundaries and wrote down the exact conditions under which I'd split — sustained load past ~500 RPS, or one module's resource profile diverging.

> **"How do you stop a modular monolith becoming a ball of mud?"**
> Executable rules. Each module exposes a narrow `api` package; cross-module calls go through it; no foreign keys cross module boundaries; a Konsist test fails the build if any of that is violated. Documented conventions decay, tested ones don't.

---

## 3. Authentication vs Authorization

**What** — Authentication: *who are you*. Authorization: *what may you do*.
**Why it exists** — Conflating them is how you get an endpoint that checks you're logged in but not that the resource is yours (IDOR — the most common real-world API vulnerability).
**In Spring Boot** — `spring-boot-starter-security`. `SecurityFilterChain` bean for the auth layer; `@PreAuthorize` and service-level guards for authorization.
**Moyi** — We go considerably further than the course: Argon2id, RS256, **rotating refresh tokens with reuse detection**, `tokensInvalidBefore` for instant revocation, and a generated cross-tenant test suite. See `09` §3.
**2026 check** ✅ JWT + refresh is still standard. ⚠️ The course stops at "store tokens client-side" — that's the *beginning* of the topic, and the rotation half is what interviews actually probe.

> **"How do you handle a stolen refresh token?"**
> Rotation with reuse detection. Every refresh issues a new token and marks the old one rotated. If a rotated token is ever presented again, that means two parties hold it — so I revoke the entire token family and email the user. This is the mechanism that makes stateless auth safe, and most implementations skip it.

> **"Your access token is a stateless JWT. How do you log someone out immediately?"**
> You can't revoke a JWT by itself, so you need one piece of state. I keep `tokensInvalidBefore` on the user row and reject any token issued earlier. Cached in Redis for speed, Postgres as the source of truth — putting revocation *only* in Redis means a cache flush silently un-revokes everything.

> **"Why hash the refresh token but not the access token?"**
> The refresh token is stored server-side, so a database leak would hand over live credentials — hash it. The access token isn't stored at all; it's verified by signature and expires in 15 minutes.

---

## 4. SQL vs NoSQL

**What** — SQL: fixed schema, tables, joins, ACID. NoSQL: flexible schema, horizontal scale, weaker guarantees by default.
**Why it exists** — Relational data with real invariants (one entry per member per day) wants constraints. High-volume, schema-fluid data wants flexibility.
**In Spring Boot** — `spring-boot-starter-data-jpa` + a JDBC driver. Flyway for migrations.
**Moyi** — PostgreSQL 18, same as the course. Our domain is deeply relational and our uniqueness constraints are load-bearing (ADR-0006). Postgres also gives us `tsvector` search now and `pgvector` at Phase 10 without a second datastore.
**2026 check** ⚠️ **Two claims in the lesson are dated.** "NoSQL lacks guaranteed consistency" — MongoDB has had multi-document ACID transactions since 2018. "SQL only scales vertically" — read replicas, Citus, CockroachDB and Vitess all exist. The honest 2026 framing is: *pick by data shape and query pattern, not by scaling folklore.*

> **"Why Postgres and not Mongo?"**
> The domain is relational — users, bonds, members, days, entries — and the correctness rules are uniqueness constraints the database can enforce for me. In Mongo I'd be enforcing "one entry per member per day" in application code, which is where race conditions live.

> **"When would you actually reach for NoSQL?"**
> When the access pattern is a known key and the shape varies — event logs, session data, product catalogues with heterogeneous attributes. Not because "it scales", which is the reason people usually give and rarely the real one.

---

## 5. Redis / Caching

**What** — In-memory key-value store. Fast, RAM-bound, shared across instances.
**Why it exists** — Database connections are finite and disk is slow. And in a distributed system, in-process state doesn't work — two instances need one place to agree on a rate-limit count.
**In Spring Boot** — `spring-boot-starter-data-redis`. `RedisTemplate` / `@Cacheable`. Bucket4j for rate limits, ShedLock for scheduler locking.
**Moyi** — Same tool, **different job**, and a narrower one than first claimed. An external reviewer pushed back that Postgres could do our coordination, and was substantially right (`25` §3 D6): idempotency, revocation and ShedLock are all Postgres-backed already. **Redis is load-bearing only for rate limiting** — the one case with a real argument, because every unauthenticated request writes and that should not touch the primary database. It also stays because learning it is a stated objective, which is a legitimate reason on a learning project *provided you say so* rather than inventing an engineering one.
**2026 check** ⚠️ **Licence change worth knowing**: Redis moved off open source in 2024 (RSALv2/SSPL), which spawned the **Valkey** fork under the Linux Foundation. Valkey is drop-in and increasingly the default in cloud offerings. Not a plan change, but "I know why Valkey exists" is a good signal.

> **"What breaks if Redis dies?"**
> It should be performance only — but I found two places in my own design where it wasn't. The token denylist would have un-revoked every revoked token on a flush, and idempotency would have failed *open*, letting duplicate submissions through into a unique constraint. Both moved to Postgres with Redis as the fast path. That's the question to ask about any cache: does losing it degrade, or does it break a guarantee?

> **"Cache invalidation strategy?"**
> Short TTLs and cache only what's expensive and stable. And be careful what you key on — I caught a bug where a per-member payload was keyed by bond id, which would have served one partner's private entry to the other.

---

## 6. Message Queues / RabbitMQ

**What** — A broker that accepts a message and reliably delivers it to interested consumers, with retries.
**Why it exists** — A direct HTTP call between services is fire-and-forget: if the receiver is down, the event is gone. A queue makes delivery durable.
**In Spring Boot** — `spring-boot-starter-amqp`, `@RabbitListener`, `RabbitTemplate`.
**Moyi** — **We diverge, deliberately.** We use a **transactional outbox** in Postgres (ADR-0008) instead.
**2026 check** ✅ RabbitMQ itself is fine.

> ⚠️ **Note the tension in the course's own material.** The lesson says *"RabbitMQ should not be used for communication within a single service — it's designed for distributed services"*, but the architecture is a **modular monolith** and the diagram puts RabbitMQ between the User, Chat and Notification units. In one deployable, that's the operational cost of distribution without the benefit of it. This is a legitimate thing to disagree with, and it's a strong interview story.

> **"Why an outbox instead of a message queue?"**
> Because my problem wasn't inter-service delivery, it was the dual write. When an entry is saved I also need to fire a notification — two writes, and if the process dies between them I've either lost a notification or sent one for a rollback. The outbox writes the event to a table *in the same transaction* as the state change, and a poller dispatches it. Retries and replay come free, and it's the clean seam to swap in Kafka later without touching any write path.

> **"When would you add a real broker?"**
> When event volume outgrows a two-second Postgres poll — thousands a minute — or when multiple independent consumers need the same stream. At my volume, adding RabbitMQ would be a component to operate, monitor and debug in exchange for nothing.

---

## 7. Real-Time: WebSockets vs SSE

**What** — SSE: one-way server→client over plain HTTP. WebSocket: full-duplex after an HTTP upgrade handshake.
**Why it exists** — REST makes the client ask. Some products need the server to tell.
**In Spring Boot** — WebSocket: `spring-boot-starter-websocket`, `WebSocketHandler`. SSE: just return `SseEmitter` or `Flux<ServerSentEvent>` — no extra dependency.
**Moyi** — **We use neither.** Reveal arrives by **FCM push**, and the client refreshes on foreground.
**2026 check** ✅ Both accurate. Worth adding: WebSockets need sticky sessions or a shared backplane once you have more than one instance — a real operational cost the lesson doesn't mention.

> **"Why no WebSockets?"**
> My product has roughly two meaningful events per user per day, and the app is usually backgrounded when they happen — so a persistent socket would be open and idle almost all the time, and would still need push for the backgrounded case. Push plus a foreground refresh does the whole job. If I did need server-initiated updates while the app is open, I'd reach for SSE first: it's one-directional, which is all I'd need, it survives proxies, and it reconnects on its own.

> **"What's the operational cost of WebSockets people forget?"**
> Connection state. Once you have two instances you need sticky routing or a Redis backplane, plus ping/pong keepalives, reconnection with backoff, and a plan for what the client missed while disconnected.

---

## 8. Notifications & Mailing

**What** — Transactional email (verification, reset) and push (FCM → Android and, via APNs, iOS).
**Why it exists** — Sending mail from your own SMTP box lands in spam. Push is the only way to reach a backgrounded app.
**In Spring Boot** — `spring-boot-starter-mail` + `JavaMailSender`; `firebase-admin` SDK for FCM.
**Moyi** — Same shape. **Resend** instead of Mailgun (better free tier, cleaner API — either is fine). FCM as taught. We add: dispatch through the outbox with retry, a per-Bond daily budget with a reserved slot for reveals, quiet hours, and **no entry text in any payload** (lock screens).
**2026 check** ⚠️ **FCM legacy HTTP API was shut off in June 2024** — you must use HTTP v1 with OAuth2 service-account credentials. Old tutorials still show the legacy server key. ✅ Firebase Admin SDK covering both platforms is still correct.

> **"How do you guarantee a notification is sent exactly once?"**
> You mostly can't guarantee *exactly* once end-to-end, so I aim for at-least-once delivery plus idempotent dispatch. The outbox gives durability and retry; a unique `dedupe_key` on the notification log means a retried event can't produce a second push.

> **"iOS and Android push — one integration or two?"**
> FCM fronts APNs, so one integration covers both. You still need an Apple Developer account and an APNs auth key for the iOS half, which has a multi-week lead time — worth starting before you need it.

---

## 9. Load Balancing & Reverse Proxy

**What** — Reverse proxy: terminates TLS and routes by hostname to a backend. Load balancer: spreads traffic across instances.
**Why it exists** — You want one public port 443, several apps behind it, and certificates handled in one place.
**In Spring Boot** — Nothing app-side, but set `server.forward-headers-strategy=framework` so the app sees the real client IP through the proxy. **Rate limiting by IP is silently broken without this** — every request looks like it came from the proxy.
**Moyi** — Diverge: **kamal-proxy** rather than Nginx (ADR-0011), because it also owns the blue/green cutover, so TLS and traffic-switching have one owner during a rollback.
**2026 check** ✅ Sound. Caddy and Traefik are the other common modern answers. **Learn Nginx config anyway** — it appears in far more job descriptions than kamal-proxy ever will.

> **"Reverse proxy vs load balancer?"**
> Same box, different jobs. Reverse proxy is about routing and TLS termination — one entry point, many backends. Load balancing is about distributing across identical instances. Nginx does both; I only need the first.

---

## 10. Self-Hosting vs Cloud

**What** — Run Postgres/Redis/RabbitMQ yourself on a VPS, or buy them managed.
**Why it exists** — Managed removes ops burden at a large cost multiple. Self-hosting is cheap and educational and makes you the DBA at 2 a.m.
**Moyi** — Same hybrid instinct, different split. **Self-host** Postgres and Redis on one Hetzner ARM box; **buy** the things that aren't worth learning (R2, FCM, Resend). Budget ceiling €26/month.
**2026 check** ✅ The reasoning holds. The course uses **Supabase** for managed Postgres and file storage; we self-host and use Cloudflare R2 (zero egress fees, which is decisive at our budget). Supabase remains our documented escape hatch if restore drills fail twice (`10` §3).

> **"You self-host Postgres. What happens when the box dies?"**
> That's why the load-bearing practice is a *timed monthly restore drill*, not the backup itself. A backup that has never been restored isn't a backup. I also found that my drill would have certified a system where every entry was undecryptable, because the key store wasn't in the backup set — worth checking what your restore actually proves.

---

## 11. How Much Kotlin in Spring Boot ⚠️ **THE BIG UPDATE**

**What** — Whether to write Spring Boot in idiomatic Kotlin (coroutines, DSLs, null-safety) or Java-style.
**Course position** — Use Java-style APIs; coroutines need workarounds, especially with transactional event listeners.

> ⚠️ **This lesson has aged, and it's the most important correction in the module.** **Spring Boot 4 / Spring Framework 7** (Nov–Dec 2025) made Kotlin a first-class citizen:
> - **JSpecify null-safety across the whole portfolio** — no more unsafe platform types from Spring, Reactor or Micrometer. You get real Kotlin nullable types.
> - **Kotlin 2.2 baseline.**
> - **Automatic coroutine context propagation** (`spring.reactor.context-propagation=auto`) so tracing and observability finally work inside suspending functions — the exact rough edge the lesson complains about.
> - `spring-boot-starter-kotlinx-serialization-json`, and `BeanRegistrarDsl` for programmatic bean registration.

**Moyi** — Idiomatic Kotlin, Spring MVC, **no coroutines in the web layer**. Not because coroutines are awkward any more, but because **virtual threads (JDK 21+, and JEP 491 in JDK 24 removed the `synchronized` pinning problem) give you the scalability without the colouring**. Blocking JDBC on a virtual thread scales fine.
**2026 check** ⚠️ Course position outdated; ours is current and, usefully, *more* current than most job descriptions.

> **"Kotlin coroutines or virtual threads in Spring Boot?"**
> For a web layer over a blocking database, virtual threads. You keep straightforward blocking code, the debugger and stack traces still make sense, and you get the concurrency. Coroutines earn their place for structured concurrency in the client layer — which is where I use them, in the KMP app. And since JEP 491 removed pinning on `synchronized`, the main historical objection to virtual threads with JDBC is gone.

> **"So is WebFlux dead?"**
> Not dead, but the case narrowed a lot. WebFlux still wins for very high-concurrency streaming and genuinely reactive end-to-end stacks. For a CRUD-plus-scheduler backend on JDBC, virtual threads get most of the benefit for a fraction of the debugging cost.

Sources: [Next level Kotlin support in Spring Boot 4](https://spring.io/blog/2025/12/18/next-level-kotlin-support-in-spring-boot-4/) · [Null-safe applications with Spring Boot 4](https://spring.io/blog/2025/11/12/null-safe-applications-with-spring-boot-4/) · [Spring Boot 4 & Framework 7 overview](https://www.baeldung.com/spring-boot-4-spring-framework-7)

---

## 12. Which Persistence API

**What** — How your code talks to the database.
**The map:**

| Stack | Web layer | Driver | Persistence |
|---|---|---|---|
| **Blocking (ours)** | `spring-boot-starter-web` (MVC) | JDBC | JPA/Hibernate, Spring Data JDBC, jOOQ, `JdbcClient` |
| **Reactive** | `spring-boot-starter-webflux` | R2DBC | Spring Data R2DBC, jOOQ reactive |

**Why it exists** — JPA gives you free CRUD and hides SQL. jOOQ gives you type-safe explicit SQL and no magic. The trade is convenience against control.
**Moyi** — **JPA primary, `JdbcClient` as the escape hatch** for the archive feed, search and reporting (ADR-0006). JPA because it dominates Kotlin/Spring job descriptions and because hitting and fixing N+1 is worth learning properly.
**2026 check** ✅ Accurate. One nuance: the lesson frames jOOQ's advantage partly as reactive support — with virtual threads that's much less compelling, so choose jOOQ for **query control**, not for reactivity.

> **"JPA or jOOQ?"**
> JPA for the standard CRUD, because it's what the ecosystem and most teams use, and I want the persistence context and fetch-plan knowledge. But I drop to `JdbcClient` deliberately for read-heavy queries where JPA is the wrong tool. Being able to say *which* queries and *why* is the real answer — "we use JPA for everything" usually means someone hasn't looked at their SQL.

> **"What's an N+1 and how do you find it?"**
> Loading a collection then lazily fetching each element's association — one query becomes N+1. You find it by turning on SQL logging and looking, or better, by an integration test that asserts the query count on hot paths so a regression fails CI rather than production.

> **"Why not WebFlux + R2DBC?"**
> Because I'd pay in debuggability and library support for concurrency I can get from virtual threads on a blocking stack. And R2DBC still has gaps against mature JDBC drivers.

---

## Does this module change our plan?

**No structural change.** The module validates the choices already made: modular monolith, Postgres, Redis, JWT, self-hosted-plus-hybrid, and no real-time layer.

**One addition** worth making to `05` §3 — the Spring Boot 4 Kotlin improvements are now a *reason* for our stack, not just a footnote: JSpecify null-safety and coroutine context propagation mean idiomatic Kotlin no longer costs anything, which strengthens the case for MVC + virtual threads + idiomatic Kotlin over both Java-style and WebFlux.

**Four divergences to be able to defend on demand** — outbox over RabbitMQ, no WebSockets, self-hosted over Supabase, kamal-proxy over Nginx. Each has an ADR. Those four are the most interesting things you can say about this project in an interview, because each one is a case of knowing the standard answer and choosing differently for a stated reason.
