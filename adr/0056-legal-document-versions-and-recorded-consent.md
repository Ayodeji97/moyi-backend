# ADR-0056 — Legal document versions come from configuration, consent records the server's version, and re-consent is one idempotent POST

**Status:** Accepted · **Date:** 2026-11-08 · **Deciders:** made without the owner, under the
issue batch of 2026-11-08 (issue #128); decisions 2, 5 and 6 are his to revisit.

## Context

FR-103 (MUST): "Terms of Service, Privacy Policy and a data-processing summary MUST be reachable
in-app and from the marketing site, and MUST be versioned with recorded consent." FR-011: consent
is recorded "with version and timestamp". Doc 06 §3.7 lists `GET /meta/legal` ("Current
Terms/Privacy versions + URLs") and `GET /meta/config` ("min supported version, feature flags,
legal doc versions"); neither existed. Issue #128 (from the client) asks for the first, cacheable,
and optionally for `GET /me` to say which versions a person accepted so the client can ask again
when they lag.

What registration did until now: V3's `consent_records` has a row per document (`TERMS`,
`PRIVACY`, `AGE_18`) with `version` and `accepted_at`, and `RegisterUser` wrote all three with the
**client's** `acceptedTermsVersion`, one string for all three. So the version and the timestamp
were recorded, but the version was whatever the request said: a claim about what the person was
shown, made by the party that might get it wrong, in the row that is the evidence of consent.
Nothing could read the rows back, and nothing let a person accept a newer version.

The site (`site/`, Cloudflare Pages at `getmoyi.app`) has no terms, privacy or data-processing
page yet.

## Decision

**1. The current documents are configuration: `moyi.legal.{terms,privacy,summary}.{version,url}`,
required, with no default** in `application.yml`, as the link bases are (ADR-0018's amendment).
Every consent row records one of these versions, so a shipped default would be a version recorded
as accepted by every deployment that forgot its own. `LegalProperties` refuses to start without
all six; the `local` profile and the test contexts carry their own, production's are in
`config/deploy.yml` (`MOYI_LEGAL_*`). `summary`, not `data-processing-summary`, because relaxed
binding would have made the variable `MOYI_LEGAL_DATAPROCESSINGSUMMARY_URL`. A version is a label
of 1–40 of `A-Z a-z 0-9 . _ -` (what `consent_records_version_length_check` can hold, and nothing
that needs escaping anywhere), compared and never parsed; a URL is absolute `http(s)`. The rule is
`identity.domain.LegalDocument`'s; the properties report it with the property's name.

**2. Registration records the configured versions and ignores the client's.** `TERMS` gets the
Terms version, `PRIVACY` the Privacy version, and the 18+ declaration (`AGE_18`) the Terms version,
because it is a clause of the sign-up form accepted with the Terms. `acceptedTermsVersion` stays in
`RegisterRequest`, **optional and ignored** (still at most 40 characters), so an older client that
sends it is not refused: making a required request field optional is not breaking for a generated
client. Refusing a request whose version differs from the current one was considered and not done:
every client in the field sends a build-time constant, and a refusal would break sign-up for all of
them at the first version change, to protect against a window (a deploy between showing the form and
submitting it) the re-consent prompt closes anyway.

**3. Only the Terms and the Privacy Policy are accepted.** The data-processing summary is a
plain-language reading of the Privacy Policy: it is served, versioned and linked, never separately
consented to, so it has no `ConsentDocument` and no row. The 18+ declaration is made once, at
sign-up; a new Terms version does not unmake it, so re-consent does not record it again.

**4. `POST /api/v1/me/legal-consent`, no body, `204`.** It records, for Terms and Privacy, a row
with the current version, now (truncated to microseconds), and the caller's hashed address and user
agent (ADR-0023), **unless the person has already accepted that version**:
`INSERT … ON CONFLICT ON CONSTRAINT consent_records_user_document_version_key DO NOTHING`. So it is
idempotent, two at once cannot collide on the key (by construction; no test races it), and a version keeps the moment it was first accepted (rows are
insert-only, as V3 intended). No body is read, so the client cannot choose what is recorded. A token
whose user has gone is the `401` `GET /me` gives, not a foreign-key `500`. No bucket of its own: the
global per-user bucket (120 a minute) bounds it, and a repeat writes nothing.

**5. `GET /me` carries `legalAccepted: {terms, privacy}`, each `{version, acceptedAt}` or `null`.**
For each document: the row for the current version if the person has one, else the newest. Current
first, because otherwise a configuration rolled back to a version already accepted would report a
later one, the client would ask, re-consent would write nothing, and it would ask for ever. `/me`'s
body is a new schema, `ProfileResponse` (`UserResponse`'s fields plus `legalAccepted`), rather than
a field on `UserResponse`, which sign-in and refresh also send and which would then cost both a read
they do not need. (`MeResponse` was taken: it is the bond module's member view.)

**Existing users are not backfilled; `null` means no acceptance is recorded.** Every account
registered before this change has three rows already, with the version its client sent (in practice
a build constant such as `2026-09-01`), and those rows are kept as they are: they are the record of
what happened, and a migration that rewrote them, or added rows for an "initial" version, would
manufacture consent nobody gave. Their versions will differ from the configured ones, so `GET /me`
reports the client-sent version and the client asks them to accept the current documents, which is
the right outcome: nobody can show what those accounts were actually shown. A person with no row for
a document (only possible by hand) is told `null`, never a version. So no migration was needed; V44
was reserved for this and is not used. Every row written before this change carries a client-sent
version, and this ADR is where that is recorded (a merged migration's comment cannot be amended).

**6. `GET /api/v1/meta/legal`: public, `ETag` with `Cache-Control: public, no-cache`, no rate-limit
bucket.** Public, because sign-up shows the documents before there is an account (named in
`SecurityConfiguration.PUBLIC_READS`, `GET` only, like the public auth `POST`s; a bearer token that
is sent is still checked, as on every public route). The body is the same for everyone and names
nobody, so `public` lets a shared cache keep it; `no-cache` makes every cache ask first, because a
copy kept past a version change would show a person a document other than the one their consent
is recorded against, and asking is a cheap `304`. The tag is `common.web.Representations`' keyed
digest of the bytes, as on the archive's reads, through a new `Representations.shared`.

The issue suggested "`429` under the per-IP bucket". There is no general per-IP bucket: each per-IP
bucket in doc 06 §4 bounds something an endpoint *does* (hash a password, send an email, try a code),
and the unauthenticated routes that do nothing costly (`refresh`, `verify-email`, `/healthz`) have
none. This one reads two configuration values. A bucket would make every request write to Redis to
protect a response that costs less than the write, which is D6's own objection ("every
unauthenticated request writes"). So it has none; the contract documents `429` on every operation
generically, and this one cannot currently give it. A flood is Cloudflare's to absorb.

**7. Not folded into `GET /meta/config`.** Doc 06 lets either carry the versions; the client needs
one. `/meta/config` (minimum supported version, feature flags) is a separate route with its own
owner questions and is still owed; when it is built it may repeat the versions, read from the same
`LegalProperties`.

## Consequences

**Positive.** FR-011's "version and timestamp" now records what the server served, not what the
client said. A policy change is a configuration change and a deploy, not an app release, and every
person is asked again by comparing two responses. Re-consent cannot be replayed into extra rows or
moved in time. The legal read costs nothing and caches.

**Negative.** Six more required environment values before anything but `local` boots (and the restore
drill's env file carries placeholders). A version change asks **everyone** to accept again,
including for a typo fix; whether a change is material is the owner's to decide when he changes
the value. Until the documents are published, production's versions are the sentinel `unpublished`
and the three `getmoyi.app` addresses are 404. A sign-up in that gap is recorded as accepting
`unpublished`, never a date that could later be mistaken for a real document's version, so setting
the real versions at publication asks every such person to accept again (review of #148). Every consent row before this change carries a client-sent
version, which will make every existing account (test accounts only, as of today) be asked again.
`ProfileResponse` is a new schema name for `GET /me`'s body: a generated client's type for that call
changes name, though every field it had is still there.

## Alternatives considered

- **Trust the client's version and check it against the current one.** Rejected in decision 2.
- **Backfill existing users to an "initial" version.** Rejected in decision 5: it invents consent.
- **A `consent_records.source` column marking client-sent versions.** Not needed: the cut-over is a
  date, recorded here, and every row before it is client-sent.
- **`Cache-Control: public, max-age=…`.** Saves a round trip and lets a client show a superseded
  document for that long; `no-cache` with a `304` costs one small request.
- **A per-IP bucket on `/meta/legal`.** Rejected in decision 6.
- **`legalAccepted` on `UserResponse`, for sign-in and refresh too.** Rejected in decision 5.

## Open questions (the owner's)

1. The production versions and addresses: publish the three documents on `getmoyi.app` (the site
   has none) and set `MOYI_LEGAL_*` to their real versions before launch.
2. Should re-consent carry the version the client showed (an `If-Match` on `/meta/legal`'s tag, `412`
   when it moved) to close the window where a version changes between showing and accepting? Not
   built; the window is a deploy wide.
3. Is a separate acceptance of the data-processing summary ever required (some readings of GDPR
   Art. 13 transparency do not need one; this ADR assumes not)?

## Owed

- `GET /meta/config` (doc 06 §3.7, doc 13's `minSupportedVersion`).
- The terms, privacy and data-processing pages on `getmoyi.app`.
- The client: fetch `/meta/legal` at sign-up and in the account section, compare with `/me`'s
  `legalAccepted`, prompt and `POST /me/legal-consent` (moyi-app gap row 12).

## Revisit when

The documents are published (set real versions). A client needs to know *what* changed, not only
that something did (add a `changedAt` or a summary). A second kind of consent appears (marketing,
semantic search's provider consent): it is a `ConsentDocument` and a row, by the same rules.
