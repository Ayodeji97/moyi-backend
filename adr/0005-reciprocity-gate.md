# ADR-0005 — Reciprocity gate: write to read

**Status:** Accepted · **Date:** 2026-08-30

## Context
The product's central risk (R-21) is asymmetry: one partner writes, the other consumes, the writer eventually stops. Paired and similar apps use a comparable mechanic for daily questions, which suggests it works. The alternative is trusting goodwill.

## Decision
A member cannot read the other's entry for day *D* until they have submitted their own for *D*. Enforced server-side at the domain layer and re-asserted at serialisation; a locked entry exposes only a status.

## Consequences
**Positive:** symmetry is structural rather than motivational. Creates genuine anticipation, which is the emotional core of the product. Gives both members a reason to open the app.
**Negative:** if one partner disengages, the other's entries pile up unread, which can feel worse than no app at all. Mitigated by auto-unlocking at day close (FR-063) so effort is never wasted. Also adds a real correctness burden — this is the system's highest-severity failure mode (T-01).
**Neutral:** it is the single most-tested rule in the codebase, which is proportionate.

## The 24-hour escape hatch, stated explicitly
FR-063 auto-unlocks a lone entry at the close of a SOLO day. The gate is therefore **not absolute — it expires at midnight.** A member who never writes will still see their partner's entry the next day. This is deliberate: the alternative is that a person's words are locked away forever because the other person had a bad week, which punishes the wrong party. The gate's job is to shape the *daily* incentive, not to hold anyone's words hostage. Both D-05 and this ADR previously stated the rule absolutely, which was inaccurate.

## Alternatives considered
- **Instant visibility** — rejected: loses anticipation and invites asymmetry.
- **Fixed reveal time only** — kept as an *additional* optional constraint (FR-062) rather than a replacement.
- **A soft nudge instead of a hard gate** — rejected: a rule that can be ignored is not a structure.

## Revisit when
Beta feedback shows the gate feels punitive rather than mutual, or the solo-day metric suggests it is deterring rather than encouraging.
