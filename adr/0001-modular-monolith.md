# ADR-0001 — Modular monolith over microservices

**Status:** Accepted · **Date:** 2026-08-30

## Context
The system has clearly separable concerns (identity, spaces, entries, media, notifications). Microservices are the fashionable default and would look impressive on a CV. The developer is one person with ~12 h/week, a €25/month budget, and an expected peak load under 20 RPS. The primary goal is learning; the secondary goal is shipping.

## Decision
A single deployable Spring Boot application, internally decomposed into Gradle modules with dependency rules enforced by automated architecture tests. Cross-module communication goes through narrow `api` packages; no foreign keys cross module boundaries.

## Consequences
**Positive:** one thing to deploy, monitor and debug. Local transactions instead of sagas. Refactoring across boundaries stays cheap while the domain is still being understood. Infrastructure cost stays within budget. Ships far faster.
**Negative:** cannot scale modules independently (irrelevant at this load). No forced discipline from network boundaries, so module separation depends on the architecture tests actually being maintained. Does not produce the "I ran a distributed system" story — mitigated by being able to explain *why not*, which is a better story.
**Neutral:** the extraction path is designed in advance (`media` and `notifications` first).

## Alternatives considered
- **Microservices** — rejected: operational cost, distributed-transaction complexity and infrastructure spend all dwarf the benefit at this scale. Would likely prevent the project shipping at all.
- **A single-module monolith** — rejected: faster initially, but teaches nothing about boundaries and becomes unmaintainable at the size this project will reach.
- **Serverless functions** — rejected: cold starts, awkward fit for scheduled per-timezone work and long-lived DB connections, and vendor lock-in.

## Revisit when
Sustained load exceeds ~500 RPS, or one module's resource profile diverges sharply from the rest (media processing is the likely candidate), or the team exceeds one person.
