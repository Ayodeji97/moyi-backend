# ADR-0003 — A generic `Space` aggregate rather than a `Couple` entity

**Status:** Accepted · **Date:** 2026-08-30

## Context
The v1 product is exclusively for couples. Modelling it as `Couple` with `partnerA` and `partnerB` would be the simplest possible design and would produce the cleanest v1 code. But the stated intent (`01` §5) includes parent–child and friends later, and relationship-shaped schemas are notoriously expensive to generalise after the fact.

## Decision
Model a `Space` containing `Member`s with roles, with `maxMembers` as a column on the Space row (2 for `COUPLE`) and a `type` discriminator. All logic reads membership generically.

## Consequences
**Positive:** new Space types are a configuration change, not a migration. Reveal, streak and notification logic generalise naturally. The word "couple" stays out of the codebase, so the domain language stays accurate.
**Negative:** every query carries a membership join that a two-column design would not need. "The partner" becomes "the other members", which is slightly more awkward to read. Some v1 logic is more general than it needs to be.
**Neutral:** the 2-member constraint is enforced at the application and index level, and is easy to relax.

## Alternatives considered
- **`Couple(partnerA, partnerB)`** — rejected: faster now, and a genuinely painful migration later touching every table and every query.
- **Fully general N-member groups in v1** — rejected: reveal semantics, streak definition and notification fan-out all become materially harder for a product that has exactly two members.

## Revisit when
Never, hopefully. If groups >2 are added, revisit the reveal rule specifically — "everyone must write before anyone reads" scales badly past three people, and that is a product decision, not a modelling one.
