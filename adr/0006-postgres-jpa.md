# ADR-0006 — PostgreSQL with Spring Data JPA, plus a deliberate JDBC escape hatch

**Status:** Accepted · **Date:** 2026-08-30

## Context
The domain is strongly relational: users, spaces, members, days, entries. The choices were JPA/Hibernate, Spring Data JDBC, jOOQ, Exposed, or a document store. A relevant secondary factor: Kotlin/Spring job descriptions overwhelmingly ask for JPA, and one goal is employability.

## Decision
**PostgreSQL** (no major version pinned here) with Spring Data JPA and Flyway as the primary path. Spring's `JdbcClient` is used deliberately for read-heavy queries where JPA is the wrong tool (the archive feed, full-text search, admin reporting).

## Consequences
**Positive:** matches what employers ask for. Rich ecosystem. Postgres gives full-text search now and pgvector at Phase 10 without adding a second datastore. Hitting and fixing N+1 problems is genuine, valuable learning.
**Negative:** Hibernate's abstraction leaks, and understanding lazy loading, the persistence context and fetch plans is a real cost. It is easy to write accidentally slow code. Mitigated by integration tests that assert query counts on hot paths.
**Neutral:** the mixed approach means two mental models in one codebase — acceptable, and arguably realistic.

## Alternatives considered
- **jOOQ or Spring Data JDBC** — arguably better engineering: explicit SQL, no hidden behaviour. Rejected primarily on employability grounds, which is an honest reason rather than a technical one.
- **MongoDB** — rejected: the domain is relational, the uniqueness constraints matter, and it would weaken rather than strengthen the portfolio signal.
- **Exposed** — rejected: excellent Kotlin ergonomics, thin job-market signal.

## Why no version number
The first draft of this ADR said "PostgreSQL 17", which then disagreed with `05` and `10` at 18. The number was never the decision. An ADR records choices that are *expensive to reverse* (`18` §7); a Postgres major upgrade is an annual, routine, forward-fix operation. Pinning it here would make every yearly upgrade require a superseding ADR — ceremony that inverts the instrument's purpose — and the pinned number would be stale before the first commit, since Postgres releases each September. The version lives in exactly one operational place: the `05` §3 stack table, verified at Phase 0.

## Note
If Philipp's course uses a different persistence approach, implement Gratitude's this way anyway, then write a comparison in the learning log. Two approaches understood is worth more than one approach copied.
