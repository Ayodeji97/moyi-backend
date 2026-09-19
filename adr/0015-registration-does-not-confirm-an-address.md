# ADR-0015 — Registration does not reveal whether an address is already registered

**Status:** Accepted · **Date:** 2026-09-19

## Context

Doc 06 §3.1 specifies `POST /auth/register` returning `201` and says nothing about what happens when the address already has an account. The obvious implementation — the one the course uses, and the one most tutorials use — looks up the address first and returns a `409` if it exists.

That answer makes the endpoint an oracle. Anyone can ask "does this person have an account on Moyi" one address at a time, with no credentials and no account of their own. For most products that is a mild privacy leak. For this one the product *is* the disclosure: Moyi is a private journal shared between two people, and doc 19 §6 makes privacy a stated reason to choose it. "Is my ex on this app" is exactly the question the answer must not be available for.

Doc 06 §2 already establishes the principle for the neighbouring endpoints — authentication failures return an identical response and take identical time whether the address exists or not, and password reset always returns `202`. Registration was simply not covered, and nothing in the threat model names it: T-18 addresses timing-based enumeration on *login*.

## Decision

`POST /api/v1/auth/register` returns **`201` with an empty body** whether or not the address is already registered, and the duplicate path **performs a password hash anyway** so that the two paths take comparable time.

Three consequences follow, and all three are required together:

- **No pre-flight existence check.** The insert is attempted and a `users_email_key` violation is absorbed. This is also the only correct way to check uniqueness under concurrency: two simultaneous registrations for one address both pass a pre-check, and the loser becomes a `500`.
- **No user-specific content in the response.** Not an id, not a status, not a timestamp — anything the duplicate path cannot produce is something an attacker can distinguish. The only body identical in both cases is no body.
- **The distinction is carried by email**, when the verification slice lands: a new account gets a verification link, an existing one gets "someone tried to register with your address, here is how to sign in". Until then the duplicate path is a silent no-op, which is the cost recorded below.

## Consequences

**Positive:** the endpoint answers no question it was not asked. The concurrency race disappears with the pre-check. And the implementation is *smaller* than the alternative — one fewer query, no conflict type, no error code.

**Negative, and real:** until the verification email exists, someone who has forgotten they already have an account gets a success response and nothing else happens. That is a genuinely poor experience for an honest user, and it is the argument for `409` stated fairly. It is accepted because the window is one or two slices and because the alternative — shipping the oracle now and removing it later — means the removal is a breaking change to a released contract, which is the change that never gets made.

Also negative: registration now costs ~150 ms of Argon2id even when it does nothing, which makes the endpoint marginally more attractive as a DoS target. FR-012's 3 registrations/hour/IP is the control for that, and NFR-005a's permit bound is what stops it becoming an outage.

**Neutral:** clients cannot tell a new registration from a duplicate. Nothing in the flow needs to: the next screen is "check your email" either way (`states.md`, Onboarding 3).

## Measured, not assumed

Timings against the packaged jar on an Apple M5, after warming — cold-start figures are useless here: the very first registration took 135 ms against a warmed 20 ms, which is JIT and first-query cost rather than the control working.

| Path | Median | Range | n |
|---|---|---|---|
| New account | 20.5 ms | 19.4-21.7 | 15 |
| Already registered | 18.5 ms | 17.7-22.0 | 15 |

The ranges overlap. The ~2 ms systematic difference is the five inserts a real registration performs and the duplicate does not, and it is **not** claimed to be zero — with enough samples it is measurable in principle. What it is not is the ~150 ms gap that skipping the hash produces, which is visible in a handful of requests from anywhere on the internet. FR-012's 3 registrations/hour/IP is what bounds sample collection, and the two controls are meant to be read together.

These numbers are also evidence for a different problem: 20 ms per registration means Argon2id is running at roughly an eighth of the cost NFR-046 intends. See `Argon2Properties`.

## Alternatives considered

- **`409 EMAIL_ALREADY_REGISTERED`.** Better UX, and standard. Rejected on the disclosure argument above. Would become right for a product where account existence is not sensitive.
- **`201` but skip the hash on the duplicate path.** Rejected: the response bodies would be identical and the *timings* would differ by ~150 ms, which is measurable across the internet. This is T-18's finding applied to a different endpoint — an identical body is half a control.
- **`202 Accepted` for both.** Honest about the asynchronous verification, and arguably better. Rejected only because doc 06 specifies `201`, and changing the documented status is a decision for the doc rather than for an implementation PR.
- **Rate-limit the endpoint and keep the 409.** Rate limiting raises the cost of bulk enumeration; it does not stop a targeted question about one address, which is the case that matters here.

## Revisit when

FR-010 lands Google/Apple sign-in, which introduces its own account-linking disclosure and may force a different shape; or the verification email exists and someone measures how many honest users are lost to the silent duplicate path.
