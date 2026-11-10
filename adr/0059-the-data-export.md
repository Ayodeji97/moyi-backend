# ADR-0059 — The data export: asked for, built by a job from five modules' own answers, downloadable for 24 hours

**Status:** Accepted · **Date:** 2026-11-09 · **Deciders:** Daniel, for FR-009, FR-094, FR-085, T-10,
T-20 and doc 06 §3.2; decisions 4, 5, 7 (the archive's shape), 8, 10, 11 and 12 were made without him, as
defaults issue #126 left to the backend, and are **his to overrule** (Open questions, below).

## Context

FR-009 (MUST): "A user MUST be able to export all their data (JSON + media) on demand." FR-094 (MUST):
"Data export MUST produce a downloadable archive (JSON + original media) via a signed URL valid 24h."
FR-085 lists an email for "data export ready". Doc 06 §3.2 has `POST /me/export` ("`202` → async;
email + push when ready") and `GET /me/export/{id}` ("Signed download URL, 24h TTL"). NFR-051: within
30 days, target minutes, automated.

What a person is entitled to is stated in four places. T-20: "each member's export contains only what
they are entitled to". `states.md` §9: each member keeps read access to their own archive and can
export it, "except entries they withdrew when blocking" (FR-029a, BR-10a: one copy of the words, and
the withdrawal erased it for both). BR-9: on an archived bond every write is `409` "except export and
deletion". T-10 and FR-112: administrator access is "disclosed to the affected user in their data
export". ADR-0036 and ADR-0037 owe the export a member's favourites and reactions; ADR-0056 recorded
consents "for the export"; doc 04 says a creator's entries written while waiting for a partner are in
it. ADR-0035 question 2 and the owner's ruling of 2026-10-21 made a working export a condition of M5,
because until it exists the block confirm's "export first" (doc 26 §3.3) offers nothing.

The shape of the code decides one thing first. The archive gathers from `identity`, `bond`,
`gratitude`, `media` and `support`; `identity` is depended on by three of them, `media` is a leaf, and
`gratitude` cannot see `support`. No existing module can depend on all five.

## Decision

**1. A module of its own, `export`, that asks and zips, and decides nothing about visibility.** It
depends on the five through their `api` packages only; each of them answers for its own data:

- `identity.api.AccountExports`: the profile and every consent record (document, version, time);
  not the keyed hashes of the address and user agent, which identify nothing to the person.
- `bond.api.BondExports`: every bond the person holds or held a membership row in, what the app shows
  of it, the person's own settings, the name they gave their partner, their own block and their own
  withdrawal. **Never the partner's leave or block**, which the app never tells them (ADR-0028's
  discreet exit, doc 26 §2.1), and never the name the partner gave them (the partner's words).
- `gratitude.api.ArchiveExports`: decision 3.
- `support.api.SupportExports`: the person's tickets with whatever words they still hold (a closed one
  is emptied, ADR-0057 decision 8), and since 2026-11-10 the error `reference` sent with each (ADR-0057
  decision 11, emptied with the words); not the operator's log.
- `media.api.ExportStorage`: decision 6, and the archive's own object.

Nothing depends on `export` but `scheduling` (the job) and `app`.

**2. One row per export, `data_exports` (V53): `PREPARING` → `READY` → `EXPIRED`, or `FAILED`.** It
holds ids, instants, a size and an opaque storage key, never a word. One `PREPARING` per person (a
partial unique index); `READY` has its key, times and size, nothing else has a key (`CHECK`s). The
status a route answers is the row's **at the moment it is read**: a `READY` export past `expiresAt` is
`EXPIRED` whether or not the job has deleted it (`DataExport.statusAt`), so no URL outlives FR-094's
24 hours because a job was late.

**3. `gratitude`'s share is the archive route itself.** `ArchiveExports.page` is `GetDays.page` (the
reading `GET /bonds/{bondId}/days` uses) behind `BondAccess.membershipOf` (the guard), walked page by
page to the end. So what the export carries of the partner's words is exactly what the app shows the
person, by the one gate (BR-1, ADR-0036): their own entries; the partner's once revealed to them;
nothing for an entry still locked or never shown; a tombstone (`DELETED`, no text) for one erased after
they were shown it, **and for every entry withdrawn on a block or a leave, theirs or their partner's,
from the commit of the withdrawal**, before the erasure has run (T-20, `states.md` §9). A favourite and
a reaction are carried only on an entry read in full, as `EntryResponse` carries them. A member who
left, or whose bond ended, is still a member row and gets their archive (BR-9). No new unguarded read
of `bond` is needed: the job has a person to ask for.

**4. The API.** `POST /api/v1/me/export` → `202 {id, status: PREPARING, requestedAt, …}`; again while
one is `PREPARING` → the same export, `202`, same `id`; after `READY`, `EXPIRED` or `FAILED` → a new one
(the archive is a copy as of when it is built). `GET /api/v1/me/export/{exportId}` → `200` with
`status`, and `downloadUrl`, `expiresAt` and `sizeBytes` only while `READY`; someone else's id and an
id that names nothing are one `404 NOT_FOUND` (the query is scoped to the caller). `GET
/api/v1/me/export` → the latest, or `404` when there has never been one, so the app can draw the state
after a restart without keeping the id (issue #126's optional route). Allowed whatever the person's
bonds are, ended, left or none (BR-9). One response shape, `DataExportResponse`, for all three. **No
`Idempotency-Key`**: the dedupe of decision 2 already makes a retried `POST` the same export while it
is being prepared, which is the case a retry is for. Rate limit: `account:export:user`, **three new exports a
day per user**, greedy (doc 06 §4 names no bucket). **Only a request that starts a new export spends
one**: the service spends it after finding none being prepared, so a repeat (a client retrying a lost
answer) is free and never pushed towards a `429` (review of #162). It is spent by
`DataExportRequests`, not by `@RateLimited` on the route, which would spend it on every request; the
`429` and its `Retry-After` are the same. *Owner may overrule the number.*
Additive to the contract: oasdiff reports no breaking change.

**5. The archive is an object in the media storage, not a `media_assets` row.** Doc 04 lists a
`MediaAsset.kind` of `EXPORT`. Every rule `media` runs over that table (moderation, the tier-2
demotion, the reaper, report holds, the account deletion's release) would have to learn to leave an
export alone, and none of them applies to it; the export's own row (decision 2) already holds what the
object needs. `media` keeps it under `x/{random}.zip`, never the export's id (doc 09: keys are random),
and `ExportStorage.archiveUrl` and `deleteArchive` **refuse any key that is not an archive's**
(`StorageKey.ofArchive`), so `export` can name its own objects and no other. Doc 04 is stale here.

**6. "Original media" is the best copy kept, by the rule the app shows it by.** No photo's uploaded
bytes are ever kept (ADR-0048 decision 3: no EXIF, no GPS position), so a photo is its full variant
while it has one and its display variant after (tier 2), as WebP; a voice note is its Opus. Which
media a person gets is `MediaAsset.shownTo` and `heardBy`, the rule every route asks (ADR-0049
decision 7, ADR-0051): the owner's own once ready, held back or not, unless blocked; anyone else's only
once ready and passed moderation. `gratitude` names only media the app would give the person a URL
for, and `media` asks its own rule again when the file is copied.

**7. The archive: a zip of `moyi-export.json`, `media/<id>.webp|ogg` and `README.txt`.** The JSON
(`format: "moyi-export/1"`) holds `account`, `consents`, `bonds` (each `{bond, days}`, the bond with
the person's own `me` settings, each day `{date, status, entries}`, each entry `{id, author: ME |
PARTNER, status, text, createdAt, intendedAt, favourited, reaction, image, voice}` with media as paths
in the zip), `supportMessages` and `adminAccess`. Written as a stream, a page of days at a time, to a
temporary file, so an archive's size is bounded by the disk and not the heap; the media are copied in
after it. A photo deleted while the archive was made is left out of the zip (its path stays in the
JSON) and counted in the log by number only. *The shape is the backend's (issue #126); owner may ask
for another.*

**8. T-10's disclosure, and its seam.** The admin console's audit log (PR #131) is not on `main`, so
no administrator access is recorded anywhere. `export.api.AdminAccessLog` is the seam: the writer asks
Spring for one bean of it, and finding none writes `adminAccess: {logKept: false, statement: "No
administrator access to your account or to what you wrote is recorded. …", accesses: []}`. When the
admin module keeps its log it implements the interface, from its own side of the boundary, and the
archive carries `{logKept: true, accesses: [{at, action, justification}]}` with no change here.
**Not covered by the seam:** the operator's reads of the person's support tickets through `moyi
support` (`support_ticket_log`), which are recorded but are not "admin access" in T-10's sense. *Owner
may rule that they should be listed too.*

**9. Built by a job, with a claim; expired by a job; erased with the account.**
`scheduling.DataExportJob` runs two ShedLocked timers: every minute it asks `export` to build up to
five waiting exports (`prepareDue`), and hourly at fifty-three past it expires the ones whose 24 hours
are over (`expireDue`). A build holds **no transaction**: each export is claimed by a conditional
update (`attempts + 1`, `claimed_until = now + 15 min`, and only while `attempts < 3`), the archive is
read through the five modules in their own short reads, written, stored, and then one statement makes
it `READY` **only if the row still holds this build's claim** (`claimed_until` is the claim's token);
if it does not (an account deletion removed the row, or a withdrawal restarted it, decision 12), the
stored archive is deleted again and nothing is sent. **Every failure of a build is caught**, a checked
`IOException` (a full disk, a storage stream cut off part-way) as much as a runtime one, and the
temporary file is made inside that catch: a failed build gives back its own claim (and only its own:
`release` and `fail` ask for the token too) and is tried on a later run, the third failure makes it
`FAILED` (the person may ask again), and one export's failure never stops the rest of the batch. An
export whose attempts are spent but whose last build died before it could say so is made `FAILED` at
the start of the next run, once its claim has lapsed, and is never claimed again; without that it
would stay `PREPARING` for ever, and every new request would be answered with it (review of #162). The expiry deletes the object **before**
it marks the row `EXPIRED`, so an archive is never forgotten while stored; one it cannot delete stays
`READY` (the route already answers `EXPIRED`) and is tried again next hour. The media worker turns both
timers off (`application-media-worker.yml`). **Account deletion** (ADR-0058): a consumer
`export.account-deletion` of `AccountDeleted` deletes every export row of the person **in one
statement, `DELETE … RETURNING storage_key`**, and then each archive those rows held, inside the
delivery's transaction, so a storage outage makes the delivery retry. One statement, because a read of
the keys followed by a delete let a build that made an export READY in between leave its archive
stored with nothing naming it (review of #162): now the delete waits on that row and returns its key,
or the build finds its row gone and deletes its own archive. The ready email cannot reach a deleted
account: identity answers `NO_ACCOUNT` for one deleted or being deleted, and a build for an account
already gone fails at its first read. Idempotent: a second delivery finds nothing.

**10. FR-085's email, with no link; no push.** When an export becomes `READY` the job writes once, by
`identity.api.AccountMail` (a confirmed address only, ADR-0057 decision 6), keyed `export-ready-{id}`:
it is ready, where in the app to download it, until when (to the minute, UTC), that there is no link
**so that only someone signed in can download it** (a signed URL needs no sign-in, so a link would
hand the archive to whoever reads the mailbox for a day), and what to do if they did not ask. **No
push**, which doc 06 names: every push category is a bond's (`PushRequest.bondId`, per-bond budget and
quiet hours, ADR-0045, ADR-0046), and an account-level category would be a migration of
`notification`'s `CHECK` and a new place in the budget rules. *Owner may ask for one.* Nothing a person
wrote, no address and no key is logged: the job logs the export's id, the attempt and an exception's
class.

**11. Defaults the corpus leaves open.** The partner's revealed entries are **in** the export (doc 02:
"the full archive"; `states.md` §9: "read access to their own archive and can export"; FR-029a's whole
reason for withdrawal is that the blocked party could otherwise bulk-export the blocker's words, which
says they are in it). Entries still locked to the person are **not**. *Owner may overrule either.*

**12. A withdrawal makes the bond's exports stale (review of #162).** FR-029a and BR-10a erase the
withdrawn words for both people from the commit of the leave or block that withdrew them, and the
archive route shows them as tombstones from that commit (decision 3). An archive is a copy: one built
before that commit, or by a build that read its pages before it, would keep the words downloadable for
up to 24 hours, which T-20 forbids. So a consumer `export.withdrawal` of bond's `EntriesWithdrawn`
asks `bond.api.BondExports.accountsInBond` (an unguarded read, held by `ArchitectureTest`, for work
that has no caller) for every account in the bond, the withdrawer's and the partner's, and for each:
every `READY` export becomes `EXPIRED` and **its archive is deleted**; every `PREPARING` one loses its
claim and its attempts, so it is built again from what may be read now, and a build in flight cannot
make it `READY` (decision 9's claim token), and deletes what it stored. The person asks again for a
`READY` one that went (the app shows `EXPIRED`). Delivered at least once, idempotent. **The gap that
remains** is the outbox's own: between the withdrawal's commit and the consumer's delivery (the poller
runs every two seconds) a `READY` export's URL still works. *Owner may ask for the route to check the
bond's withdrawals itself, at the cost of a read across modules on every `GET`.*

## Consequences

**Positive:** FR-009, FR-094 and FR-085's export email exist end to end, and M5's condition (ADR-0035
question 2) is met: the block confirm can now offer an export that works before the withdrawal. T-20
holds by construction: the export has no reading of the archive of its own to drift from the app's.
Account deletion erases exports too.

**Negative:**

- No push when it is ready (decision 10); the app learns it by asking.
- The archive is a copy as of its build; an entry revealed a minute later is in the next one.
- A `READY` export costs storage for 24 hours; three a day per person bounds it.
- A withdrawal in a bond expires both members' `READY` exports (decision 12), so a person who had
  just made one may have to ask again; a URL handed out before stays usable for the seconds until the
  consumer runs.
- The admin disclosure is a statement until the admin console keeps a log (decision 8).
- In production the build needs the media storage: with it unavailable every build fails three times
  and the export is `FAILED` (`config/deploy.yml` configures R2 for the API).
- Doc 04's `MediaAsset.kind = EXPORT` is not used (decision 5).

**Neutral:** an eleventh domain module; `scheduling` depends on `export`'s `api`. The contract gains three
operations and one schema; oasdiff reports no breaking change.

## Alternatives considered

- **Build in the request, synchronously.** Rejected: an archive of years with photos takes longer than
  a request should, and doc 06 says `202`.
- **Build in an outbox consumer** (`DataExportRequested`). Rejected: a delivery runs in a transaction
  the dispatcher bounds at sixty seconds (ADR-0058's note on `gratitude`'s share), which a large archive
  would outlast on every retry; a claimed row outside any transaction has no such bound.
- **Read the tables directly from `export`.** Impossible across `internal`, and wrong: it would be a
  second copy of the gate (BR-1, the withdrawal), the one thing that must not drift.
- **Put the link in the email.** Rejected by decision 10's reason. Right if the owner prefers the
  convenience and accepts the mailbox as a second key.
- **An `Idempotency-Key` on `POST`.** Unneeded (decision 4); additive later if a client wants it.

## Open questions (the owner's)

1. Three exports a day per person (decision 4)?
2. The partner's revealed entries in the export, locked ones not (decision 11)?
3. No push, email only, and no link in the email (decision 10)?
4. List the operator's reads of support tickets under `adminAccess` (decision 8)?
5. The archive's shape (decision 7), and the best kept copy as "original media" (decision 6)?
6. A withdrawal expiring both members' ready exports, with the outbox's seconds of gap (decision 12)?

## Revisit when

The admin console keeps its audit log (implement `AdminAccessLog`); an account-level push category
exists; an archive's build approaches the claim's fifteen minutes; or the owner rules on an open
question above.
