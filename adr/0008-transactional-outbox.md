# ADR-0008 — Transactional outbox from day one

**Status:** Accepted · **Date:** 2026-08-30

## Context
Submitting an entry must both persist the entry and (sometimes) trigger a reveal notification. Doing both directly is a dual write: if the process dies between them, either a notification is lost or one is sent for a state that was rolled back. In a product whose whole value is a moment being delivered, a lost reveal notification is a real failure.

## Decision
Every domain event is written to an `outbox_events` table inside the same transaction as the state change. A poller (2 s interval, `FOR UPDATE SKIP LOCKED`) dispatches events to handlers with retry and exponential backoff.

## Consequences
**Positive:** no lost or phantom notifications. Retry and backoff come free. A replayable event history. Crucially, it is the seam that makes the Phase 10 ML work additive (`04` §7) rather than a rewrite. It is also one of the highest-value patterns to be able to explain in an interview.
**Negative:** added latency (up to ~2 s) before a side effect fires — acceptable against the 60-second reveal-notification target. An extra table, an extra process, and a new failure mode to monitor (`outbox_pending` depth).
**Neutral:** introducing Kafka later becomes a swap of the dispatcher, not a re-architecture.

## Alternatives considered
- **Direct calls in the service method** — rejected: dual-write inconsistency, no retry, side effects inside a transaction.
- **Spring `@TransactionalEventListener(AFTER_COMMIT)`** — better than direct calls, but the event is lost if the process dies after commit and before the listener runs. Rejected for the reveal path; acceptable for non-critical events.
- **Kafka or RabbitMQ in v1** — rejected: significant operational cost for a problem Postgres solves adequately at this scale. Introducing it at Phase 10 as a deliberate learning exercise is the better sequence.

## Revisit when
Event volume exceeds what a 2-second Postgres poll comfortably handles (~thousands/minute), or fan-out to multiple independent consumers becomes necessary.
