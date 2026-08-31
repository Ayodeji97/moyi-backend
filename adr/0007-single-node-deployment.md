# ADR-0007 — Single-node deployment with recoverability instead of redundancy

**Status:** Accepted; **partially superseded by ADR-0011** (reverse proxy) · **Date:** 2026-08-30

## Context
Budget ceiling €25/month (D-10). Real availability requires redundancy at every layer, which at minimum means multiple app nodes, a managed or replicated database, and a load balancer — comfortably €80+/month. Expected load is under 20 RPS.

## Decision
One Hetzner CAX21 running the application, Postgres, Redis and ~~Caddy~~ **kamal-proxy** (superseded by ADR-0011) in containers, deployed blue/green with Kamal. Invest in *recoverability* — tested backups, documented rebuild, infrastructure as code — rather than redundancy.

## Consequences
**Positive:** within budget. Simple to reason about and operate. Blue/green still gives zero-downtime deploys. Forces genuine discipline about backups and recovery, which is more valuable to learn than clicking "enable multi-AZ".
**Negative:** the host is a single point of failure. Availability target is therefore 99.5%, not 99.9%. Recovery from host loss is a manual four-hour procedure. A resource-hungry query can affect the whole system.
**Neutral:** the target is stated honestly rather than aspirationally, which is itself the correct practice.

## Alternatives considered
- **Managed everything on AWS/GCP** — rejected on cost; also teaches console configuration more than systems.
- **Two nodes with a load balancer and managed Postgres** — the right answer at ~€60/month; revisit if the product grows.
- **Free-tier PaaS (Railway, Render, Fly free)** — rejected: cold starts, quota limits, and time spent fighting the platform rather than learning.

## Revisit when
Users depend on it commercially, availability drops below 99.5% for two consecutive months, or a restore drill fails twice.
