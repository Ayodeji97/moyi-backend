# ADR-0003 — A generic `Bond` aggregate rather than a `Couple` entity

**Status:** Accepted · **Date:** 2026-08-30 · **Amended:** 2026-09-01 (aggregate renamed `Space` → `Bond`; decision unchanged)

## Context
The v1 product is exclusively for couples. Modelling it as `Couple` with `partnerA` and `partnerB` would be the simplest possible design and would produce the cleanest v1 code. But the stated intent (`01` §5) includes parent–child and friends later, and relationship-shaped schemas are notoriously expensive to generalise after the fact.

## Decision
Model a `Bond` containing `Member`s with roles, with `maxMembers` as a column on the Bond row (2 for `COUPLE`) and a `type` discriminator. All logic reads membership generically.

## Consequences
**Positive:** new Bond types are a configuration change, not a migration. Reveal, streak and notification logic generalise naturally. The word "couple" stays out of the codebase, so the domain language stays accurate.
**Negative:** every query carries a membership join that a two-column design would not need. "The partner" becomes "the other members", which is slightly more awkward to read. Some v1 logic is more general than it needs to be.
**Neutral:** the 2-member constraint is enforced at the application and index level, and is easy to relax.

## Alternatives considered
- **`Couple(partnerA, partnerB)`** — rejected: faster now, and a genuinely painful migration later touching every table and every query.
- **Fully general N-member groups in v1** — rejected: reveal semantics, streak definition and notification fan-out all become materially harder for a product that has exactly two members.

## Amendment — 2026-09-01: `Space` → `Bond`
The original decision named the aggregate `Space`. The shape is unchanged; only the word is. Three reasons:

1. **`Space` was defined by negation.** The glossary (`04` §1) could only say what it was *not* — "not Couple, not Room, not Pair". A word that can only be defined by exclusion is a placeholder.
2. **It was not carrying its own weight, and the excluded language leaked back in.** Because `Space` names an empty container rather than a relationship, the two places in the model that had to express what the relationship *is* reached for the banned vocabulary anyway: `Space.status = PENDING_PARTNER` and `Member.nicknameForPartner`. `Bond` absorbs both (`PENDING_MEMBER`, `nicknameForOther`).
3. **`space` is not a usable identifier in this codebase.** It already means whitespace, `namespace`, disk space and vector space. `grep -w bond` is signal; `grep -w space` is noise.

`Bond` also reads naturally across all three planned types — a couple's bond, a parent–child bond, a bond of friendship — which is precisely what the `type` discriminator was already implying.

Rejected alternatives for the word: `Hearth` (best metaphor — a fire two people tend, which maps onto both the streak and BR "kind to failure" — but `HearthDay` is clumsy and it is one letter from the banned "heart"); `Cairn` (exact mechanism-match, but obscure and memorial-tinged); `Journal` (cleanest compounds, but plants the domain language in the exact category `01` §1 differentiates from); `Pair`/`Dyad` (accurate but hard-lock the model to two, re-creating the `Couple` problem).

The cost of the rename at the time it was made: three lines of Kotlin/Gradle, one empty module directory, and a documentation sweep. It will never be cheaper.

## Revisit when
Never, hopefully. If bonds >2 are added, revisit the reveal rule specifically — "everyone must write before anyone reads" scales badly past three people, and that is a product decision, not a modelling one.
