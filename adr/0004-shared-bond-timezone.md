# ADR-0004 — One anchor timezone per Bond

**Status:** Accepted · **Date:** 2026-08-30 · **Amended:** 2026-09-01 (`Space` → `Bond`, see ADR-0003)

## Context
The reference user is a couple split between, say, Lagos and Manchester. "A day" must mean something unambiguous for the streak to be fair and explainable. Options ranged from a shared anchor zone, to per-member local days with an overlap rule, to UTC, to a rolling window.

## Decision
Each Bond chooses one IANA anchor timezone at creation. All Bond-day boundaries, reveal timing and streak evaluation use it. Members' *notification* times remain in their own local timezones.

## Consequences
**Positive:** one shared day, one shared streak, one reveal — which is exactly what "our day together" means and can be explained in a sentence. Vastly simpler to implement correctly and to reason about in support. Only one clock to test against per Bond.
**Negative:** one partner is always writing at an inconvenient local hour — for a 12-hour separation, one of them is writing at breakfast. Some couples will pick the wrong zone and be mildly confused for a while. The anchor becomes a small point of negotiation between two people, which is a product wrinkle.
**Neutral:** the zone is changeable, rate-limited to once per 30 days, never retroactively.

## Alternatives considered
- **Per-member local days with an overlap rule** — genuinely kinder to users, and the most interesting engineering. Rejected for v1 because "complete day" becomes ambiguous, the streak becomes hard to render honestly, and the edge cases multiply. Recorded as the strongest candidate for a future revisit.
- **UTC for everyone** — rejected: arbitrary, and feels broken to anyone not in Europe.
- **Rolling 36-hour window** — rejected: no calendar days means no archive-by-date and no comprehensible streak.

## Mitigations
Onboarding explains the choice clearly and defaults to the creator's device zone. The compose screen shows which Bond-day an entry will be filed under whenever it differs from the writer's local date.

## Revisit when
Beta feedback shows the anchor is a genuine source of friction, or the ratio of solo days is materially higher for Bonds with a large timezone separation — which is measurable from `gratitude_days_solo_total` segmented by member offset.
