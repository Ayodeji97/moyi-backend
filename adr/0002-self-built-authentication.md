# ADR-0002 — Build authentication rather than buy it

**Status:** Accepted · **Date:** 2026-08-30

## Context
Auth0, Supabase Auth, Firebase Auth and Keycloak all solve this faster and, in the average case, more safely. But authentication is the most commonly asked-about and most commonly misunderstood backend topic in interviews, and it is a stated primary learning objective (L-2). Rolling your own auth is also the classic way to introduce a serious vulnerability.

## Decision
Build it: Argon2id password hashing, RS256 JWT access tokens (15 min), opaque rotating refresh tokens (30 days) stored hashed, with reuse detection that revokes the entire token family.

## Consequences
**Positive:** deep, transferable understanding of the highest-value backend security topic. No vendor dependency, no per-MAU cost, full control over the data model. Every decision is explainable in an interview.
**Negative:** genuine security risk if done badly. More code to maintain. No free MFA, no free social login, no free breach detection. Social sign-in (FR-010) becomes real work rather than a checkbox.
**Neutral:** OAuth providers can be added later alongside the password path.

## Mitigations for the negative
Follow OWASP ASVS L1 explicitly; a dedicated auth security test suite (`12` §3.3); use battle-tested primitives (Spring Security, the Argon2 reference implementation) rather than writing crypto; ASVS self-assessment before launch; **mandatory** independent review of the auth code by a second competent engineer before public launch. **Fallback trigger:** if no reviewer is secured by the start of Phase 5, migrate authentication to a managed provider rather than launching unreviewed. The learning has already happened by then — it is in the code and the write-up — so the fallback costs the credential, not the education.

## Alternatives considered
- **Managed provider** — rejected as directly contrary to the primary learning goal, though it would be the correct choice for a commercial product on a deadline.
- **Keycloak self-hosted** — rejected: heavy for one VPS, and it teaches Keycloak administration rather than authentication.
- **Session cookies instead of JWT** — genuinely simpler and arguably better for a single first-party client; rejected because stateless token auth with rotation is the more transferable skill. Worth noting honestly that this is a learning-driven choice, not a purely technical one.

## Revisit when
Enterprise SSO is ever required, or maintaining the auth surface starts consuming more than ~10% of development time.
