# ADR-0015 — Registration does not reveal whether an address is already registered

**Status:** Accepted · **Date:** 2026-09-19 · **Amended:** 2026-10-28 (a display name holds no control or format character; the decision above unchanged)

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

## Amendment — 2026-10-28: a display name holds no control or format character

Registration took any display name that was not blank and was at most 80 UTF-16 units (V2's CHECK, mirrored in `User`). A display name is shown to the other member: in the bond, on every archive day since #97 (ADR-0036 question 5), and in emails (`SecurityNotice.displayName`). Two kinds of character had no business in one:

- **Controls.** A name could be made of C0 and C1 control characters, which JSON writes as six octets each. That is what spent the archive page's margin (learning log, 2026-10-27).
- **Bidi controls.** A name could carry U+202E and its kin, which reorder the text around them on the partner's screen and in an email. That is a spoofing tool.

The decision on the address, above, is unchanged. This amendment adds a rule to the one other field registration stores for the partner to read.

**1. What is refused.** Judged per code point with `Character.getType`, so a surrogate pair is one character and the tag block, outside the BMP, is seen:

- **Every `Cc` (control) code point**, tab and newline included. A name is one line.
- **Every `Cf` (format) code point, except U+200C ZWNJ and U+200D ZWJ.** `Cf` holds the bidi controls (U+200E and U+200F, U+202A to U+202E, U+2066 to U+2069), the zero-width space, the word joiner and the BOM, the soft hyphen, and the tag characters.
- **ZWNJ and ZWJ are kept** because they are how Persian, Urdu and several Indic scripts are correctly written (ZWNJ), and how emoji sequences are built (ZWJ). Refusing them would refuse real names.
- The message names the rule and never the name: "display name must not contain control or formatting characters".

**2. The write path only.** `User.init` runs on every row read (`IdentityMappers.toDomain`), so the rule there would make an account already holding such a name unreadable: every sign-in, every `/me` and every bond view of it a `500`. So the rule lives in one place, `identity.domain.DisplayNames.check`, and is called where a name is written. Today that is `RegisterUser` alone, before the hash is paid for. There is no rename route; when one exists it goes through the same check. `User.init` keeps exactly the rules it had, blank and at most 80, which mirror V2.

**3. The edge asks the domain.** `RegisterRequest.displayName` carries `@ValidDisplayName`, whose validator runs `DisplayNames.check`, the shape `@ValidEmail` already has. `@NotBlank` and `@Size(max = User.MAX_DISPLAY_NAME_LENGTH)` stay: `@Size` is what publishes `maxLength`, which `OpenApiContractTest` holds to `ArchivePageBudget`. The check runs on the **trimmed** value, because the trimmed value is what is stored (`RegisterRequest.toCommand`). Kotlin's `trim()` removes whitespace, and tab, newline and U+001C to U+001F are both whitespace and `Cc`: so a name with a trailing newline is trimmed and accepted, and a newline inside a name is refused. The answer is `422 VALIDATION_FAILED`, with the field `displayName` and its code `VALID_DISPLAY_NAME`, in the same problem shape as every other field error, and nothing is written or sent. More registrations are refused than before; the contract's schema is otherwise unchanged (the field gains a description), so this is not marked a breaking API change.

**4. Existing rows are untouched.** No migration rewrites them, and none is needed for them to keep working (decision 2). Production holds only test accounts.

**5. The archive page budget.** `DaysFeedTest`'s largest page builds its worst name from U+001F through a fake user directory. That name is now worse than identity accepts, so the measurement still bounds the page: it over-counts. The worst name identity now accepts escapes to **at most 3 octets a UTF-16 unit** in JSON: a BMP character's UTF-8 (a supplementary character is 4 octets over 2 units), or 2 for the printable characters JSON escapes with a backslash, `"` and `\`, against 6 for a control before. `DaysFeedTest` and `ArchivePageBudget` are not changed here, because #105 rewrites that test and the two would conflict. **Re-measuring the page with the new worst name is owed once #105 has merged** (ADR-0036 decision 6 carries the same note).

**Tests.** `DisplayNamesTest` refuses U+0000, U+0009, U+000A, U+001F, U+007F, U+0085, U+200B, U+200E, U+202E, U+2066, U+FEFF, U+00AD and U+E0041, each inside a name, and accepts a Persian name with ZWNJ, an emoji family joined by ZWJ, a flag, accented letters precomposed and combining, and a CJK name. `RegistrationEndpointTest` holds the `422` on `displayName` with no user, consent or token row, no hash and no email, and no log line holding the name; the newline inside refused and the trailing one trimmed; each accepted name stored exactly as sent; and `RegisterUser` refusing a command that did not come through the edge. `MeEndpointTest` and `UserTest` hold the read path: a row given U+202E by SQL still reads back through `/me` and `UserDirectory`. `scripts/smoke.sh` sends U+202E and expects the `422`.
