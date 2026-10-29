# ADR-0050 — Media erasure deletes the objects; the reaper, the demotion and the mirror

**Status:** Accepted · **Date:** 2026-10-29 · **Deciders:** Daniel

## Context

Phase 4 slice D5 (spec `2026-10-27-phase-4-media-and-notifications-design.md` §4 D5, §7) is the
media lifecycle: what happens to an asset after it is made. Three things were owed to it.

- **Erasure left the bytes** (spec D-9). BR-10 and BR-10a (doc 04:195-196) null an erased entry's
  "media references"; nothing deleted the asset or its objects. ADR-0048's review made it sharper:
  a URL handed out before an erasure keeps working for up to 25 hours, the objects stay for good,
  and the owner's own `GET /media/{id}` of a photo on an erased entry still answered with a URL.
  ADR-0048 made D4 (which gives partners URLs) wait on this.
- **Nothing was ever deleted by time** (doc 06:186, doc 07:355): an upload never completed, or a
  photo never attached, stayed for ever. ADR-0047 owed the reaper of `PENDING_UPLOAD`.
- **Nothing was ever demoted** (D-16, doc 07:346-347): full-resolution variants were to be kept 90
  days, then only the ~150 KB display variant.

And the owner's ruling Q15 (spec §5, risk R-D): mirror `moyi-media` nightly to a second, locked
bucket, without the mirror becoming a copy of erased content.

## Decision

**1. Every erasure releases the entry's media, in the erasure's own transaction, from the one
routine every erasure goes through.** `media.api.MediaErasure.releaseAllOf(entryId)` turns every
asset attached to the entry into `DELETED` (detached, `deleted_at` set) and publishes one
`MediaReleased {mediaId}` per asset. It is `MANDATORY`: called outside a transaction, Spring
refuses it. `gratitude` calls it from `EraseEntry.erase`, the routine an author's `DELETE`, the
withdrawal's consumer and the close job's and a read's withdrawal pre-steps (`EraseWithdrawnEntries`)
all go through, so the four cannot come to differ, and account deletion (Phase 5) gets it by
erasing through the same routine. It is called on every `erase`, not only the one that erases: it
releases nothing twice, so a repeated call costs one statement and changes nothing. No asset is
attached to an entry erased before this (production has had no storage, so no asset exists), and
nothing backfills one.

- **Keyed by the entry, not by ids** (the spec wrote `release(ids)`). Which asset is attached where
  is `media`'s own record; keyed by it, a caller cannot forget an id (the voice note, once D3
  attaches one, is released with no change in `gratitude`).
- **Not the same as `MediaAttachments.release`**, which a `PATCH` uses to let a replaced photo go:
  that photo stays `READY`, its owner's, and is the reaper's a day later (decision 3).
- **Indexed** (review of #110). A withdrawal asks it once per entry, all in one delivery with a
  60-second limit, so an unindexed `attached_entry_id` would make each call a scan of the table.
  V34 adds a partial index (`WHERE attached_entry_id IS NOT NULL`). Measured on a laptop's Postgres
  18 with 1,000,000 assets (900,000 attached), the release statement for one entry: **before**, a
  `Seq Scan on media_assets`, "Rows Removed by Filter: 999999", 23,276 buffers read, **48.8 ms**;
  **after**, `Index Scan using media_assets_attached_entry_idx`, 3 buffers read, **0.091 ms**. The
  reviewer measured 90-95 ms a call without it, which for a member with a year of daily entries
  (365 calls) is past the delivery's limit, so that withdrawal could never finish.

**2. `media.purge` deletes the objects, and never gives up.** A consumer of `MediaReleased` and
`MediaDemoted` on the outbox (ADR-0035), in the delivery's transaction. For `MediaReleased` it
deletes every key the asset may have: the upload and, for a photo, each variant's key **derived**
from the upload's (`StorageKey.forVariant`), whether or not the row records it, so a variant an
interrupted attempt left behind goes too; then it stamps `purged_at` (V34). For `MediaDemoted` it
deletes the full variant. A storage that cannot be reached throws, the delivery is rolled back and
backed off (2 s doubling to 15 minutes) and offered again, with no last attempt, as the withdrawal
is: an object left in the bucket is a photo its author took back. Deleting what is not there
succeeds, so a purge delivered twice, or after a best-effort delete, changes nothing.

- **Every path that deletes an asset publishes `MediaReleased`**, not only erasure: the reaper; the
  owner's own `DELETE /media/{id}`, which still deletes at once on a best-effort basis (D1's
  behaviour, and the smoke run's check) but now has the purge behind it, because the variants
  under `v/` had no lifecycle rule; and `media.process` finding the photo it just made deleted under
  it, since the owner's purge may have run before those variants were written; and `media.process`
  meeting a deleted photo on which an attempt had begun (`processing_attempts > 0`), because that
  attempt may have written a variant after the purge and then failed, and its retry is the last
  thing that will ever look at the photo (review of #110). ADR-0048's
  "best-effort, the lifecycle rule is the fallback" now has a guarantee behind it for `v/` too.
- **Promptly.** The outbox polls every 2 s, so an erased photo's objects go seconds after the
  erasure commits, and the URL a partner (from D4) or the owner was handed reaches nothing from
  then on: the 25-hour window ADR-0048 described is closed by deleting what the URL points at, not
  by revoking the URL, which a presigned URL cannot be.
- **It subscribes only where there is a storage** (`ObjectStore.configured`), as `media.process`
  subscribes only where there is a processor. Production runs with the storage `unavailable`
  (ADR-0047): there `media.purge` declares no type, claims nothing, and so neither fails nor logs
  on every poll. The first process with a storage subscribes it, and `StartFrom.BEGINNING` hands it
  every release published before then. (No asset can exist without a storage, so today there is
  nothing to hand.)
- **It holds no lock on the asset while it reaches the storage** (ADR-0047 decision 2): it reads
  the row, deletes, and only then writes `purged_at`.

**3. The reaper, hourly.** One statement picks (in a `MATERIALIZED` CTE, so the locking select runs once) and changes,
under the row locks, up to a batch of
assets **attached to nothing** that are either `PENDING_UPLOAD` an hour past their URL's expiry, or
`READY`/`FAILED` for a day counted from the **latest of their creation, their readiness and the
moment an entry let them go** (`detached_at`, V34, stamped by `MediaAttachments.release`). The
readiness counts because processing can take hours in an outage and the owner must still have a
day to attach; the detachment counts because a photo taken off an entry is its owner's for a day,
as one never attached is. `detached_at` is not backfilled: no asset exists in production (its
storage has always been `unavailable`), and for one that did, a missing value only means its day
is counted from its readiness. `UPLOADED` and `PROCESSING` are not the reaper's: an asset stuck there is
the alarm's (decision 6). Each deleted asset publishes `MediaReleased`.

- **An attached asset is never reaped.** `attached_entry_id IS NULL` is in the statement that
  picks and changes, and a row a claim is changing at that moment is skipped (`FOR UPDATE SKIP
  LOCKED`) rather than waited for: the job never waits on a person's request, and the next run sees
  whether the claim committed. A reaper that holds the row first wins, and the claim, which waits
  on it, then finds the row `DELETED` and is refused (`AttachReapRaceTest`, both orders, each
  synchronised on the held lock).

**4. The demotion, nightly at 03:40 UTC.** One statement demotes up to a batch of photos that are
`READY`, **attached**, **tier 1**, **made more than 90 days ago** (`ready_at`), and **hold a
display variant**: tier 2, only the display variant recorded, the width and height made the
display's. Irreversible, so every condition is in the statement that changes the row, under its
lock, and each is tested on both sides of its edge. Each demoted photo publishes `MediaDemoted`;
`media.purge` deletes the full variant. The rendering needed no change: `MediaAsset.shownTo`
already shows the largest variant kept.

- **D5 ships before D4**, the spec's order reversed (ADR-0048 made D4 wait for D5). The spec put D4
  first "so nothing is ever demoted that was never moderated". Demotion keeps the display variant,
  which is the one D4 moderates (spec §4 D4), so a photo demoted before D4 can still be moderated;
  D4 must moderate photos that are already tier 2 (Owed).

**5. Both jobs are in `scheduling` and touch only the database.** `scheduling → media.api`
(`MediaLifecycle`: `reap`, `demote`), "when, never what", as the close job. ShedLock locks
`media-reaper` and `media-demotion`; batches of 500, each its own transaction, until one comes back
short or a run has changed 20,000, after which it says so at WARN and leaves the rest to the next
run. Neither reaches the storage, so neither can be failed or slowed by it, and both run the same
with the storage `unavailable`: production starts and runs them without a bucket (seen, decision
7). The spec lists "erasure" among "three ShedLocked jobs"; it describes it as a call and a
consumer, and that is what it is: the outbox's poller is its timer, and its retry the outbox's.

**6. The P2 alarm and its runbook.** `ops/monitoring/media-check.sh`, a Healthchecks.io heartbeat
every quarter-hour as the close-job check is: it fails at once on a photo `PROCESSING` for 30
minutes (doc 11:97), a `DELETED` asset not purged after a day (an erasure that keeps failing is a
privacy matter someone must hear about), or either job not having **finished** a run on time; and
silence alarms too. ShedLock's `locked_at` says only that a run began, so a job that throws on every
run would look alive to it (review of #110): each job instead records the end of a run that did not
throw in `job_runs` (V35, `scheduling`'s own table), and the alarm reads that. A job that has begun
and never finished counts as late; one that has never begun does not. `ops/monitoring/media-pipeline-stuck.md`
is doc 11:111's runbook, including what happens when a storage that was configured is made
`unavailable` again (the purge's deliveries wait, and the alarm says so after a day).

**7. The media mirror (Q15).** `ops/media-mirror/`: a nightly systemd timer (04:30 UTC) runs the
MinIO client `mc`, from the image `docker-compose.yml` already pins, so nothing is installed on the
server: `mc mirror` of `v/` only (never `in/`, whose bytes still carry EXIF), copying what the target
lacks, **never deleting or overwriting** (no `--remove`, no `--overwrite`), from a read-only token on
`moyi-media` to a write token on a second bucket with a **30-day bucket lock** and a **31-day
lifecycle rule**.

- **How an erasure reaches the mirror.** The mirror does not delete; the lock would refuse it
  anyway. Every copy expires 31 days after it was written; the next night, a live object is copied
  again (it is in the source and missing from the target), and an erased one is not (the purge
  deleted it from the source). So an erased object leaves the mirror at most 31 days after its last
  copy (plus the provider's lifecycle lag, about a day on R2): **erased media can survive in the
  mirror for up to about 32 days.** That is the order of the database's 30-day rolling backups (doc
  07 §7), and it carries the same duty: **a restore from the mirror must re-apply the erasures** (doc
  07:357), deleting again every object of an asset that is `DELETED` in the database restored beside
  it; `ops/media-mirror/README.md` has the procedure. Demoted full variants leave the same way. The price is that each live object is written again about once a month: at the spec's
  steady state (~1 M objects, ~150 GB) about $7.40 a month on R2, writes ~$4.50 of it; today,
  within the free tier.
- **mc exits 0 when it cannot reach the target**, reporting the error only as a JSON line; the
  script fails a run on any error line as well as on the exit code, and the test proves the check
  is needed. Credentials reach the container by name (`docker run -e MC_HOST_src`), never on a
  command line; the failure reason is redacted of URLs, hosts and keys before the journal and the
  `/fail` ping.
- **Where the target lives is the owner's** (spec R-D: a second R2 bucket shares the account as a
  failure domain). The README lays out both and recommends another provider's EU bucket with object
  lock, naming no vendor, and notes that a versioned provider needs its lifecycle rule to expire
  noncurrent versions and delete markers too, or an erased object survives as an old version.
- **The restore drill gains one optional step**: with the mirror's read credentials in Bitwarden,
  it lists `v/`, downloads one object and checks it is a WebP; without them it says it skipped.
- Tested against two throwaway MinIO servers on a private Docker network (`test-mirror.sh`): `v/`
  copied and `in/` not; a second run copies nothing; an erased object whose copy expired is not
  copied back; a live one is; the heartbeat and `/fail`; no key printed. Not run against R2.

## Consequences

- An erased photo is gone from the storage seconds after the erasure, and a URL handed out before
  is dead from then on; the owner's own `GET` of it is `404`. ADR-0048's condition on D4 is met.
- A storage outage holds erasures back for as long as it lasts, and loses none: the purge's
  delivery stays due, the alarm says so after a day.
- Unattached uploads and photos cost a day of storage at most; full variants 90 days.
- `media_assets` gains `detached_at` and `purged_at` (V34); `MediaAsset` gains `tier`. Three
  partial indexes: the erasure's lookup by entry, and the two jobs' candidates. `job_runs` (V35).
- **Known limit: one outbox worker.** The outbox delivers one delivery at a time per process, on
  the poller's one thread (ADR-0035 decision 7). A bulk purge (a large reaping, or a withdrawal of a
  long history) while R2 hangs costs each purge delivery up to its storage client's bound for each
  key (3 s a call, three keys for a photo) before it fails and backs off, and in
  that time the same worker delivers nothing else: withdrawals and pushes wait behind it. Bounded
  (each failure backs off, so a run moves on), and accepted at this scale; a worker per consumer
  is the cure if it is ever seen.
- **Ordering gate for the owner: configure R2 storage only with or after D5 deployed.** Storage
  configured before D5 would let photos be attached to entries that are then erased with no release
  (their assets stay `READY` and their objects stay for good, since V34 backfills nothing), and let
  assets be deleted before V34 with no `MediaReleased` ever published for them. ADR-0047's set-up
  list says so too.
- Shared with D3, built beside this: `ProcessUploads` (one more collaborator) and the
  `SwitchableStore` test double (a delete switch). Whichever merges second merges both.

## Alternatives considered

- **Deleting the objects inside the erasure's transaction.** A rollback after the delete would
  lose the bytes of an entry that was never erased; and a storage outage would fail the erasure
  itself (an author's `DELETE`, a withdrawal) rather than delay the purge.
- **Revoking issued URLs.** A presigned URL cannot be revoked short of rotating the bucket's token,
  which would revoke every URL of every photo. Deleting the object is the revocation.
- **`release(ids)`, as the spec wrote it.** Decision 1: the attachment is `media`'s record; a list
  of ids is a copy of it that can drift.
- **A sweep job for erasure** (find assets on erased entries). `media` cannot read entries (it is a
  leaf, spec §2.1), and the outbox already gives at-least-once with retry for ever.
- **Reaping by `created_at` alone.** A photo whose processing waited out an outage, or one just
  taken off an entry, would be deleted within the hour of becoming attachable.
- **`mc mirror --remove` against an unlocked target.** Erasure would reach the mirror the next
  night, but a token that can delete on the target is a token that can empty the backup, which is
  what the lock is for (ADR-0043 made the same choice for the database backups).

## Owed

- **Phase 5's account deletion and its bond-deletion job** must erase entries through `EraseEntry`
  (or call `MediaErasure.releaseAllOf` for each entry they remove), or an account's photos outlive
  it. Neither exists yet.
- **D3:** `AttachableKind.VOICE`, and nothing else here: a voice note attached to an erased entry is
  released by decision 1 as it stands, and purged by decision 2 once D3 lands: D3 adds the voice
  note's `voice.ogg` to `MediaAsset.objectKeys`, so with both merged the purge deletes it too. D3
  keeps the `MediaReleased` call in its own version of `media.process`'s decision.
- **D4:** moderate photos already at tier 2 (decision 4); a `QUARANTINED` photo on no entry is not
  reaped by this slice's rule (only `READY` and `FAILED` are): D4 says what becomes of it.
- **The owner, before the mirror runs:** choose the target's provider and jurisdiction; make the
  bucket with its lock and lifecycle rule; the two tokens; the Bitwarden fields and the install
  steps in `ops/media-mirror/README.md`; then one run by hand, the first time `mc` meets the real
  providers.
- **The owner, for the documents this changes (not edited here; the corpus is read-only to a
  slice):** doc 07 §7's retention table gains the mirror (erased media kept up to about 32 days in
  the mirror, re-applied on a restore) and the purge (an erased photo's objects deleted within
  seconds, retried until the storage answers); the Privacy Policy says the same, beside the
  database backups' 30 days and D-16's demotion.
- **The owner, once the media tables are deployed:** install the media check
  (`ops/monitoring/README.md`) and prove its alert reaches him.

## Revisit when

A purge is seen failing for a reason that retrying cannot mend (a key the storage refuses as such).
Uploads outgrow a run's budget of 20,000 an hour. The mirror's monthly re-copy is seen to cost more
than the photos it protects.
