# ADR-0012 — Password policy: 8 characters and a real breach corpus

**Status:** Accepted · **Date:** 2026-09-01

## Context
FR-001 and `09` §3 required a minimum of **12 characters**. Neither cited a source. D-07 and ADR-0002 decide build-vs-buy for authentication and cover hashing only; the length, the "no composition rules" choice and the top-10k breach list entered the documents without a decision record behind them. This ADR does not supersede a decision — it supplies one that was never written.

Four controls protect an account here, and length is the weakest of them:

| Control | Where | What it stops |
|---|---|---|
| Argon2id, m=19456 KiB, t=2, p=1, ~150 ms | NFR-046, `09` §3 | Offline cracking after a hash-database leak |
| Per-account and per-IP rate limits, lockout, dummy-hash | FR-012, T-18 | Online guessing and enumeration |
| Breached-password check | FR-001 | Credential stuffing — **the dominant real-world attack** |
| Minimum length | FR-001 | Only the very shortest guesses |

Human-chosen passwords carry roughly 2–3 bits of entropy per character, so 8 characters (~20 bits) and 12 (~28 bits) are *both* trivially brute-forceable offline. What actually separates them is Argon2id, which we already have and have tuned. Against credential stuffing, length does almost nothing and the corpus does almost everything — and **a top-10k list is a token gesture**. It catches `password123` and essentially nothing else.

This matters because the shorter the minimum, the more user-chosen passwords fall into precisely the region a breach corpus covers. Lowering the floor without widening the corpus would be a genuine weakening. Doing both together is a net improvement over the status quo.

There is also a friction cost on the other side of the ledger. AS-01 — *"both partners will join"* — is the highest-scored assumption in the register (impact 5, uncertainty 5), persona P2 has "a low tolerance for anything that feels like an app trying to manipulate him", and `02` requires onboarding that can be handed to a less-motivated partner "in under three minutes". A 12-character floor with no strength meter (`14` currently has no password guidance at all) is a visible failure at the exact step where the funnel is most fragile.

Finally, the field had **no maximum at all**, anywhere in the document set. NIST requires accepting at least 64 characters, so a number has to be stated either way.

## Decision
- **8–128 characters.** No composition rules. All printing Unicode and spaces accepted; NFKC-normalised before hashing.
- Three independent limits, following FR-041's established pattern for bounded user input: 128 characters at request validation, an octet cap at the request layer, and no unbounded field reaching the encoder.
- **Checked against an offline breached-password corpus of the top ~10M hashes**, held as a Bloom filter (~18 MB at a 0.1% false-positive rate) built in CI from a pinned HIBP dump and baked into the container image. This moves the full-corpus check from "a Phase 5 improvement" (`09` §3) into **Phase 1**, and is the price of the shorter minimum. The two halves are one decision and must not be unbundled.
- Argon2id parameters (NFR-046) and rate limits (FR-012) are unchanged.

## Consequences
**Positive:** a net security increase against the attack that actually happens. Meets the floor in both NIST SP 800-63B rev 4 and OWASP ASVS 5.0, so the number is now defensible rather than arbitrary. Removes a visible failure at the most fragile step of the funnel. Bloom filters and k-anonymity are a better interview story than "I required twelve characters".

**Negative:** a false positive rejects a safe password roughly once in a thousand registrations, which needs copy that explains rather than blames. Phase 1 grows by the corpus work — call it half a session. The pinned dump needs an owner and a refresh cadence, or it silently ages.

**Neutral:** ~18 MB of heap against the 1.5 GB budget in `10` §2.1 — not worth a budget line. The 8-character floor will read as surprising to anyone who has absorbed the older ASVS 4.0.3 guidance, so the reasoning needs to stay attached to the number.

## Why offline rather than the k-anonymity API
`09` §3 named the HIBP k-anonymity range API as the Phase 5 route. Rejected in favour of the offline filter:

- **No US transfer to justify.** A 5-character SHA-1 prefix is arguably not personal data, but "arguably" costs a paragraph in the TIA and a line in the subprocessor list (NFR-050, NFR-053). The offline filter costs neither.
- **No third-party dependency in the signup path**, and therefore **no outage path** — which removes the fail-open/fail-closed question entirely. Fail-open silently weakens the control; fail-closed breaks registration when a service we do not run goes down. Not having to choose is worth more than the marginal corpus coverage.
- **No added latency** on a path that already spends ~150 ms in Argon2id.

## Alternatives considered
- **Keep 12 characters.** No friction benefit, and the number stays unsourced until someone asks. The genuine argument for it — defence in depth — is weak once the corpus is widened, because the corpus covers the same failure directly.
- **6 characters**, as first floated. Below the floor in every current standard. Rejected outright: not a number to write into a document set carrying this threat model.
- **10 as a middle.** Rejected as the worst of both: a standard can be cited for 8 and for 15, but not for 10, which makes it the hardest of the three to defend.
- **A strength meter instead of a minimum** (zxcvbn score ≥ 3). Attractive and closer to what the research supports, but it makes the acceptance criterion non-deterministic and hard to test. The meter is adopted as *guidance* in `14`, not as the gate.

## Revisit when
NIST or OWASP move their floor; the pinned corpus is more than a year stale; a credential-stuffing incident occurs; or the false-positive rate produces real support volume.
