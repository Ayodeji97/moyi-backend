# ADR-0070 — `semanticSearchActive`: the bond says whether it is searched by meaning now

**Status:** Proposed · **Date:** 2026-11-23 · **Deciders:** the owner ruled (2026-11-18) that the
app hides "Remember when" in release builds until a field says the meaning search is real
(moyi-app `docs/backend-contract-gaps.md` row 49); the lead decided the rest, **his to overrule**.
· **Amends:** ADR-0054 (a field on the bond, and the readiness search decides from), and the
contract's blanket `403` (ADR-0024's "every status an operation can answer") for two routes.

## Context

`semanticSearchEnabled` records the couple's consent and nothing else. Whether a search actually
asks by meaning also depends on three things the bond row does not hold: an embedding provider
configured (production names `none`, ADR-0054 Owed), a max-distance for that provider's model
(`moyi.gratitude.semantic-search.max-distance`; Voyage has none in production), and the
`AI_EMBEDDINGS` switch (ADR-0067 decision 7). With any of them missing, search and "Remember when"
answer from words alone, still `200`, by design (ADR-0054's degraded path), so the client cannot
tell. A "Remember when" that only does a keyword search is not the feature the owner wants shown.

Reading `QueryEmbedder.matchFor`, `GetDays.search` and `GetDays.remember` for this: nothing else
turns the meaning half off. Neither read checks `hasLeft`, `isOpen`, a countdown or a pause; an
ended bond is its archive and is searched by meaning as it was (`GetDays.search`'s KDoc, smoke
"remember: the ended bond is still asked"). The remaining drops are per request (the provider
fails, times out, or answers a vector of the wrong size) and per entry (one not yet embedded).

## Decision

1. **`BondResponse.semanticSearchActive: boolean`, required**, on every route that answers a bond:
   create, get, list, patch, the timezone, deletion and semantic-search proposals, and accepting an
   invite. All of them build it from `BondView`, which `BondViews.ofAll` fills, so there is one
   place it is computed. It is **true exactly when the bond's `semanticSearchEnabled` is true and
   the meaning half can run now**: `AI_EMBEDDINGS` on, a provider configured
   (`EmbeddingPort.space != null`) and a max-distance for its model. It is not narrowed by an ended
   bond, a countdown or a pause, because search is not. It does not promise that every entry has
   been embedded (the sweep runs every minute), nor that the provider will answer this request:
   those are the per-request degradations ADR-0054 already answers with keywords.

2. **One predicate, through a port `bond` owns and `gratitude` implements.** `bond.api` gains
   `SemanticSearchAvailability` (`fun isAvailable(): Boolean`). `gratitude`'s new
   `SemanticSearchReadiness` implements it, and `QueryEmbedder` now asks the same object's
   `ready()` for the space and threshold it embeds `q` with, after the bond's setting. So the field
   and the search decide from one call, not two copies (CLAUDE.md "a rule lives in one place").
   `gratitude` already depends on `bond`, and `ai` stays a leaf; no Gradle dependency is added.
   `QueryEmbedderTest` walks all eight combinations of flag, provider and threshold, with the
   setting on and off, and holds `matchFor` to `setting && isAvailable()`.

3. **The `ETag` digests it.** Configuration and the `ADMIN`'s switch move no bond row, so without
   it a client holding the old tag would be answered `304` and keep a stale answer, as `paused`
   (A3) would have. One consequence, accepted as it was for `paused`: a `PATCH` whose `If-Match`
   was read before the switch is `412`, and the client reads the bond again.

4. **No `403` on search or "Remember when".** The contract gave every signed-in route a `403`.
   On these two nothing can answer one: the access-denied handler answers only an authority a URL
   rule or `@PreAuthorize` demands, and these routes demand none beyond a valid token;
   `EMAIL_NOT_VERIFIED` is thrown only by creating and joining a bond; and a stranger gets the
   bond's `404` from the guard (doc 06 §2, T-02). This was **read from the code, not run**: no
   request was found that answers `403`, which is not proof that none exists. The other
   bond-scoped reads are in the same position and still document the blanket `403`; narrowing them
   all, in one pass, is owed.

5. **Additive, unlabelled.** oasdiff (v1.35.0-rc.1, run locally with `--flatten-allof`) reports the
   new required response property as `response-required-property-added` and the two removed `403`s
   as `response-non-success-status-removed`, all INFO, none breaking: a client generated from the
   previous document ignores an unknown field and never handled a `403` here that could come.

6. **The state map** gains `remember`'s `429` (`remember:user`, thirty an hour, `Retry-After` in
   whole seconds, at least 1), with `RememberRateLimitTest` as its evidence, now holding the
   `Retry-After` floor too. `CacheHeadersTest` holds "Remember when"'s `200`, `422` and stranger's
   `404` to never-stored, as it held search's.

## Consequences

**Positive:** the client can hide "Remember when" while it would only be a keyword search, and
learns when it becomes real without an app release. The field cannot drift from search, since
both ask one object. A switch is seen on the next read, `304`s included.

**Negative:** every bond read with a bond whose setting is on now reads the `AI_EMBEDDINGS` flag,
one query of `feature_flags` (flags are never cached, ADR-0067 decision 8). Every context that
scans `bond` needs a `SemanticSearchAvailability` bean: the three test contexts that boot `bond`
without `gratitude` got a fake. The field says the meaning half *runs*, not that it found
anything: a bond whose entries are not yet embedded, or a provider that is down, still answers
from words while the field says true.

**Neutral:** the threshold coupling ADR-0054 recorded (remember needs no max-distance, yet goes
keyword-only without one) is now visible in a field instead of only in a count.

## Alternatives considered

- **`bond` depends on `ai.api` and reads `EmbeddingPort.space` and the flag itself.** Rejected: it
  misses the max-distance, which is `gratitude`'s configuration, so it would say true for Voyage in
  production while search runs on words; and it is a second copy of the predicate.
- **A port in `common:core`, implemented by `ai`.** Rejected for the same reason: `ai` knows the
  provider and the flag, not the threshold. It would become right if the threshold moved into `ai`.
- **Not digesting it in the `ETag`, on the grounds that it rarely changes.** Rejected: a deploy that
  switches a provider on would leave every client that revalidates with `304` hiding the feature.

## Revisit when

A provider is switched on in production (the field turns true for every bond with the setting on;
the privacy-policy precondition of ADR-0054 Owed still holds first). The threshold moves out of
`gratitude`. A reason appears to narrow the field by bond state, which would have to narrow search
first.
