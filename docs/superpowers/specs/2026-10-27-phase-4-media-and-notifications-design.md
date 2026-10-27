# Phase 4 — media and notifications

**Status:** owner's rulings recorded 2026-10-27 (§5); for review · **Date:** 2026-10-27 · **Author:** Claude, for Daniel
**Covers:** doc 15 §3 Phase 4 (`15-roadmap-and-milestones.md:100-111`) — direct-to-R2
uploads, the image and voice pipelines, moderation and quarantine, the media lifecycle, FCM
with preferences, quiet hours, dedupe and the push budget, all dispatched through the outbox.
Eight slices, D1–D5 and N1–N3, ending at **M4**.
**Base:** `main` @ `4bc5ae0`. Migrations are numbered from **V27**, the next free after V26,
which a peer session has reserved; renumber at merge if the sequence has moved.

Read `2026-09-28-the-daily-loop-design.md` first: this document assumes bond-days, entries,
the reveal, the close job and the outbox poller (ADR-0035) exist.

Corpus references are `NN:line` into `Gratitude/documents/NN-*.md` (read-only, not edited
here). Anything marked **[inferred]** was reasoned or recalled, not read or run, and must be
checked before a slice relies on it.

---

## 1. What Phase 4 must satisfy

### 1.1 Media

| Source | Requirement |
|---|---|
| 03:52 FR-042 | At most one image per entry: JPEG/PNG/HEIC/WebP, ≤10 MB before processing |
| 03:53 FR-043 | At most one voice note: ≤60 s, ≤5 MB, transcoded server-side "to Opus/AAC" |
| 03:54 FR-044, 08:22 NFR-009 | Uploads go straight to object storage on short-lived presigned URLs; the API never proxies bytes |
| 03:55 FR-044a, 12:56 | On submission, every referenced asset is the caller's, `READY`, and attached to no other entry. Its own test category, because the cross-tenant suite cannot see a cross-*user* leak |
| 03:115 FR-101, 08:19 NFR-006 | Images are moderated before **delivery to the non-author**, not before reveal; the day reveals on text, and the partner sees "photo still processing" until `READY` |
| 04:117-118, 04:175-182 | `MediaAsset` and its lifecycle: `PENDING_UPLOAD → UPLOADED → PROCESSING → READY \| QUARANTINED \| FAILED`, and `DELETED` |
| 04:197 BR-8, 06:148 | A locked entry carries no media presence flag. Unchanged by this phase, and re-tested |
| 05:161-175 | The pipeline: intent → PUT → complete → worker verifies checksum and magic bytes, strips EXIF, makes variants, transcodes voice, moderates. "Non-negotiable: EXIF stripping … content-type verification … per-user upload quotas" |
| 06:14 | Media ids are UUID **v4** (a v7 id leaks its creation time, which BR-8 suppresses) |
| 06:16 | `Idempotency-Key` is required on media completion |
| 06:181-186, 06:226 | The four `/media` routes; 30 intents/hour/user; retained bytes ≤500 KB image + ≤500 KB voice per entry per member; unattached media reaped after 24 h; `413 MEDIA_QUOTA_EXCEEDED` "rejects the attachment, never the entry" |
| 06:188, 07:346-347, 08:112 NFR-084, D-16 | Tiering: full-resolution variants 90 days, then one ~150 KB display variant kept indefinitely; irreversible and disclosed |
| 08:68 NFR-047, 08:113 NFR-083 | Signed URLs only, no public bucket; R2 egress stays €0 |
| 09:31 T-07 | Magic-byte checks, size caps, **re-encode every image, never serve the original bytes**, sandboxed processing |
| 09:32 T-08, 17:47 R-25 | Moderation, quarantine, review queue, a takedown and law-enforcement procedure |
| 09:40 T-16 | Quotas and presign rate limits against cost amplification |
| 11:49-50, 11:97, 11:111 | `gratitude_media_processing_duration_seconds`, `gratitude_media_quarantined_total`; P2 alert on anything `PROCESSING` >30 min; a `media-pipeline-stuck.md` runbook (NFR-074, 08:102) |
| ADR-0031:167, ADR-0031:976, ADR-0032:117 | `422 MEDIA_NOT_YET_SUPPORTED` on submit and `PATCH` becomes a real write path |
| 04:195 BR-10a, 04:196 BR-10 | Every erasure nulls "media references" — and Phase 4 must make that an erasure of the **objects**, not only of the ids (§3, D-9) |

### 1.2 Notifications

| Source | Requirement |
|---|---|
| 03:92 FR-080 | Pushes for: daily reminder, partner joined, reveal, milestone, on this day |
| 03:93 FR-081 | Each member controls reminder time (own zone), categories, quiet hours |
| 03:94 FR-082 | ≤3 pushes per Bond per member per day; ≤6 per member per day across Bonds; **one slot per Bond per day reserved for reveal-class**; over-budget non-reserved messages are dropped, not queued |
| 03:95 FR-083 | Invalid tokens pruned on delivery failure |
| 03:96 FR-084, 09:38 T-14, 08:65 NFR-044 | No entry text in any payload |
| 03:98 FR-086 | Idempotent dispatch, retried with exponential backoff |
| 03:68 FR-061, 03:72 FR-065, 08:19 NFR-006, 12:77 | Both members notified within 60 s of reveal (p95); exactly one push per member per reveal, with no content |
| 03:86 FR-076, 09:33 T-09 | Never tell one member the other has not written |
| 03:106 FR-092, ADR-0039:92, ADR-0039:428 | On-this-day push: opt-in, per member, private, at most one a week |
| 05:67, 25:133 | FCM HTTP v1 with service-account OAuth2 (the legacy API is gone) |
| 07:271-272 | `notification_log` with `unique (dedupe_key)`; 90-day retention (07:353) |
| 25:69 | Push is a hint, never the source of truth |
| 25:118 | `PushSender` is our type in the owning module's `api`; the vendor appears in one `internal` class |
| 09:120-122, 21:15 OQ-05, 17:55 R-31 | FCM is a US transfer: SCCs, a TIA, minimal identifiers |
| 11:57, 11:66, 11:136 | `gratitude_push_sent_total{category,status}`; a span around `notificationDispatcher.send`; chaos test "FCM returns errors → retries, no user-visible failure" |
| ADR-0035:163, ADR-0035:600-602 | A consumer with an external effect needs the event id; the notification consumer starts from `NOW` |
| ADR-0034:143, ADR-0034:240 | The consumer **must not** subscribe to `StreakBroken` |
| ADR-0037:281 | No push for a reaction |
| ADR-0041:41, :109, :312-318 | Milestone push: **both members or neither** (H-M5); none on a Bond that takes no writes; dedupe on `bondDayId`; `StartFrom.NOW` |
| ADR-0040:141 | No milestone push for a withheld day on an ended Bond |
| ADR-0030:168 | Phase 4 decides whether a pending two-party proposal earns a push |
| ADR-0025:37, :43 | Push tokens fold duplicate device rows on `(user_id, push_token)` and fill `revoked_at` |

### 1.3 The milestone

**M4** (15:110): an entry with a photo and a voice note round-trips end to end, and a test
device receives **exactly one** push at reveal — Android first, iOS once Apple enrolment
completes (15:109 requires enrolment to be done already). Write-up #3 (15:111): "Direct-to-R2
uploads: never let bytes touch your API server."

---

## 2. Two structural facts that shape every slice

### 2.1 The module graph forbids the obvious design

As built: `identity → notification` (`modules/identity/build.gradle.kts:70`, for
`EmailSender`, ADR-0017:19), `bond → identity`, `gratitude → bond, identity`,
`scheduling → gratitude`. So **`notification` can read neither devices, nor members, nor
quiet hours**: each of those lives in a module that already depends on it. Doc 05:44 gives
`notification` "push + email dispatch, preferences, quiet hours, dedupe", and doc 07:52 puts
`push_token` on identity's `devices`; together they need a cycle.

**Proposed shape** (needs an ADR in N1):

- `notification` stays a leaf. It owns the **transport and the ledger**: push tokens (its own
  table, keyed by identity's `device_id` with no FK), the notification log with its dedupe key,
  the budget, the quiet-hours arithmetic, and the FCM adapter.
- The **decision of who is told what** lives with the facts: a consumer in `gratitude` for
  `DayRevealed`, `DayClosed` (the SOLO unlock), `MilestoneReached` and the on-this-day sweep; a
  consumer in `bond` for a partner joining. Each resolves recipients and their settings and
  calls `notification.api.Notifications.request(PushRequest)` with the member's zone, quiet
  hours, category and dedupe key **already resolved** — values, not ids to look up.
- `identity` keeps the route that registers a token, and calls
  `notification.api.PushTokens.register/forget` (an allowed direction) on registration, session
  revocation and logout.
- `media` is a leaf too: it depends on `common` only. `gratitude → media.api` for the
  FR-044a claim and for rendering. An avatar (`users.avatar_media_id`) would make it
  `identity → media`, still acyclic. Avatars are not in doc 15's Phase 4 list and are **out of
  scope**.

The alternative is to move `EmailSender`'s port out of `notification` (into `common`) so that
`notification` may depend on `bond` and `identity`. That reverses ADR-0017 decision 1 and
makes the module that is meant to be extracted first (05:24, ADR-0001:22) the one with the most
inbound knowledge. Rejected unless the owner prefers it.

### 2.2 The outbox's contract does not fit a push as it stands

`EventConsumer.handle` runs **inside the delivery's transaction**, with a 60 s limit
(`common/events/.../EventConsumer.kt`), and retries **never stop** (ADR-0035:154-157,
`Backoff.kt`: 2 s doubling to a 15-minute cap). For a withdrawal that is right. For a push it
means:

1. An FCM call made inside `handle` holds a pooled connection and the delivery's row lock for
   the length of an HTTP round trip. Acceptable at this scale with a short client timeout
   (5 s) **[inferred]**; written down so it is a choice.
2. "No last attempt" would deliver a reveal push hours late, after its day, into tomorrow's
   budget. So the **push consumer acknowledges an expired request as `EXPIRED`** instead of
   sending it. The outbox keeps its rule; the push carries its own deadline (`stale_after`).
3. At-least-once means a push can be sent twice if the commit fails after FCM accepted it.
   Doc 07:272's "makes double-sending structurally impossible" is too strong; the unique key
   makes double *requests* impossible. Say so in the ADR, and test the part that is true.

**Proposed dispatch path** (one queue, the outbox's):

```
gratitude consumer `gratitude.notify` (in its delivery tx)
   → notification.api.Notifications.request(PushRequest…)
       INSERT notifications … ON CONFLICT (dedupe_key) DO NOTHING
       if inserted: publish OutboxEvent("Notification", id, "PushRequested", {notificationId})
notification consumer `notification.push` (its own delivery tx)
   → load row; expired? → EXPIRED
   → category off? → SUPPRESSED;  in quiet hours? → reschedule (throw a typed "not yet")
   → budget: reserve under pg_advisory_xact_lock(user) or → DROPPED_BUDGET
   → tokens for the user → FCM v1, 5 s timeout
   → UNREGISTERED/INVALID token → delete it (FR-083); transient → throw (backoff, FR-086)
   → SENT, provider_message_id
```

A consumer publishing from inside a delivery transaction is new; ADR-0035 says publishers run
at READ COMMITTED — check that the delivery transaction satisfies it before N1 relies on it.
**[inferred]** that it does.

---

## 3. Where the corpus disagrees with itself or with the code

Stated, not resolved. Each names what this spec assumes until the owner says otherwise.

| # | Disagreement | Assumed here |
|---|---|---|
| D-1 | **Push token location.** 07:52 and ADR-0025:43 put it on `identity.devices`; §2.1's graph makes `notification` unable to read it | `notification` owns tokens; `devices.push_token` (V8) becomes dead and is dropped by a later forward migration. ADR-0025's fold asks `notification.api` for the device holding a token |
| D-2 | **Where preferences live.** 06:84 has a user-level `GET/PUT /me/notification-preferences` with quiet hours and reminder time; 04:79, 07:81-82, 06:110-111 and the built V9 + `BondMemberSettingsController` have them **per bond member** | Per member (built). Category toggles go in the existing unmapped `bond_members.notification_prefs` (V9:80-82). 06:84 is not built |
| D-3 | **Moderation timing.** 09:32 T-08 and 17:47 R-25 say "before reveal"; FR-101 (03:115) says before delivery to the non-author, explicitly *not* before reveal | FR-101 |
| D-4 | **Per-user quota.** 06:186-188, 08:112 NFR-084 and D-16 resolved per-entry caps; 07:384 still says "the 50 MB/user quota", 10:148 and OQ-17 (21:27) still offer "25 MB/user", 05:175 says "per-user upload quotas". Audit A-01 (23:64) raised this | Per-entry caps plus the 30/hour intent limit; OQ-17's quota half is moot |
| D-5 | **Variants vs the cap.** 06:186 allows "one image variant ≤500 KB"; 07:346 and 05:168 keep "variants" (thumb, display) in tier 1; 07:347 keeps "~150 KB display" | Two variants summing ≤500 KB (Q2) |
| D-6 | **Voice codec.** FR-043 says "Opus/AAC"; 05:168 and 15:104 say Opus | Opus (Q4) |
| D-7 | **Export of originals.** FR-094 (03:108) exports "original media"; T-07 (09:31) never serves original bytes and D-16 deletes full resolution | Originals are never retained; export means the best variant held at the time |
| D-8 | **CDN-cacheable signed URLs.** 05:169-171 wants hour-rounded signed GETs served from the edge. R2 presigned URLs work **only** on the S3 API domain, not a custom domain ([Cloudflare docs](https://developers.cloudflare.com/r2/api/s3/presigned-urls/)), so they are not edge-cached; edge caching needs a custom domain, which NFR-047 (08:68) forbids being public | Presigned GETs on the S3 endpoint, hour-rounded expiry kept (it still lets the client cache by URL). Edge caching is deferred; a Worker validating our own signature is the path if wanted |
| D-9 | **Erasure leaves the bytes.** BR-10/BR-10a (04:195-196) null "media references"; nothing deletes the object, so a withdrawn photo would stay in R2 | Every erasure path calls `media.api.release(ids)` in its transaction; a `media.purge` consumer deletes the objects |
| D-10 | **`voiceDurationMs` twice.** 04:89 puts it on `Entry`, 07:155 too, and 07:238 puts `duration_ms` on `media_assets` | On the asset only; the entry response reads it from there (CLAUDE.md: a rule lives in one place) |
| D-11 | **`presign:{mediaId}` in Redis** (07:325) vs the built posture: Valkey holds rate-limit buckets only (`config/deploy.yml`, ADR-0023) | Postgres only |
| D-12 | **The SOLO unlock emits no `DayRevealed`.** `CloseDay` publishes `DayClosed` for it; `RevealDay` publishes `DayRevealed` only for a two-entry reveal | The SOLO-unlock push (ruled yes, Q8) is driven by `DayClosed` and the day's status |
| D-13 | **No event for a partner joining.** FR-080 lists the push; `AcceptInvite` publishes nothing | N3 adds `MemberJoined {bondId}` from `bond` |
| D-14 | **"Both or neither" vs per-member settings.** ADR-0041 H-M5 vs FR-081 (each member mutes categories) and FR-082 (per-member budget) | H-M5 governs the *decision*, made once at Bond level; a member's own mute, quiet hours or budget suppress only that member's copy, which tells neither anything about the other (Q9) |
| D-15 | **Reveal vs quiet hours.** FR-061 wants the push within 60 s; FR-081 gives quiet hours | Hold until quiet hours end; NFR-006 is measured outside quiet hours (Q7) |
| D-16 | **Which "day" the budget counts.** FR-082 says "per day" across Bonds; a member has a different `reminder_timezone` in each of up to three Bonds (FR-025) and the user has no zone | Per-Bond cap counts that member's reminder-zone day; the 6/day cap counts a rolling 24 h |
| D-17 | **ADR-0035's "first handler is notifications"** (05:157 already amended) and 07:271's `notification_log` without the columns a budget and a deadline need | The table is `notifications` with the columns in §4 N1 |
| D-18 | **The memory budget has no room.** 10:37-51 sums to 8.0 GB with zero headroom (25:111) and includes a staging stack; there is no line for media processing | §6, R-A |

---

## 4. Slices, in build order

Common to all: the Definition of Done (`.github/PULL_REQUEST_TEMPLATE.md`); `./gradlew build`
and `scripts/smoke.sh` run before review; the state map's data rows for every new route;
`OpenApiContractTest` regenerated and its diff read; a Bruno request per route; a mechanism
test that goes red when the guard is removed. New error codes go in `common:web`'s `ErrorCode`.

### D1 — the media aggregate and the presigned upload

**Endpoints** (06:181-184): `POST /api/v1/media/upload-intent`
`{kind: IMAGE|VOICE, contentType, sizeBytes, checksum(sha256, base64)}` →
`{mediaId, uploadUrl, expiresAt, requiredHeaders}`; `POST /media/{id}/complete`
(Idempotency-Key required, 06:16) → `UPLOADED` after a `HEAD` confirms size; `GET /media/{id}`
(owner only — a partner reaches media only through an entry, BR-1); `DELETE /media/{id}`
(owner, unattached only). All non-owner answers are `404`.

**Rules:** `sizeBytes` and `contentType` checked against FR-042/043 at intent (`413
MEDIA_QUOTA_EXCEEDED`, `415 MEDIA_TYPE_UNSUPPORTED`); `media:intent` bucket 30/hour/user
(06:226) on the existing Bucket4j limiter; presign TTL 15 min; `Content-Length` and the
checksum header signed into the URL so R2 refuses a different body **[inferred: confirm R2
honours a signed `x-amz-checksum-sha256`; R2 has no presigned POST policy, so a size range
cannot be signed — the exact length can]**. Object keys are random (`in/{uuid}`), carrying no
user, bond or kind (09:18).

**Port:** `media.api` exposes nothing storage-shaped; `media.infra.R2ObjectStore` implements an
internal `ObjectStore` (presign PUT/GET, HEAD, GET stream, DELETE) with the AWS SDK v2 S3
client confined to that one class (25:118). A new bucket `moyi-media`, EU jurisdiction, with a
token scoped to it alone — never the backup token. An R2 lifecycle rule expires `in/` after
2 days as a backstop to the reaper.

**Migration V27** `media_assets` per 07:235-245, plus `attached_entry_id uuid` (no FK — it is
`gratitude`'s), `tier smallint`, `processing_attempts`; CHECKs on `kind`, `status`; indexes
`(status, created_at)` and `(owner_user_id)` (07:244-245).

**Module:** `modules/media` (exists, empty). Events: `MediaUploaded {mediaId}` published by
`complete`.

**Tests:** Testcontainers **MinIO** for the S3 contract **[inferred: MinIO's SigV4 presign
behaviour matches R2's closely enough for PUT/HEAD/GET/DELETE; one manual R2 check per release,
as 12:136 already says]**; an upload with a different length or checksum is refused; a second
user's `GET` is `404`; intent limit; idempotent `complete`. **Smoke:** a section that uploads a
small JPEG to MinIO in the smoke compose file and completes it.

### D2 — the image pipeline, attachment and rendering

**Pipeline** (`media.process` consumer of `MediaUploaded`, running where Q5 decides):
download → sha256 matches → magic bytes (JPEG `FFD8FF`, PNG `89504E47`, WebP `RIFF…WEBP`,
HEIC `ftyp{heic,heix,mif1}`) agree with the declared type → pixel-count ceiling before decode
(decompression bombs, T-07) → decode, auto-orient, **drop all metadata** → encode the variants
(Q2) under the per-entry cap, lowering quality until they fit → write `v/{uuid}/{variant}` →
delete `in/…` → `READY` with `width`, `height`, `variants`. Unrecoverable input → `FAILED`;
transient → throw and let the outbox back off.

**Attachment:** `SubmitEntry` and `PATCH` lose `MEDIA_NOT_YET_SUPPORTED`. FR-044a becomes
`media.api.MediaAttachments.claim(owner, mediaId, kind, entryId)` inside the entry's
transaction: one conditional `UPDATE … WHERE owner = ? AND status = 'READY' AND
attached_entry_id IS NULL`, so a race for one asset has one winner. Failure is `422` naming the
field (the entry is not created with the media silently dropped: ADR-0031:167's reasoning).

**Rendering:** the entry shape (06:138) gains `image: {id, status, url?, width?, height?}`. The
**author** gets a URL once `READY`; the **partner** gets one only once `READY` **and**
`moderation_verdict = PASSED`, else `{status: PROCESSING}` (FR-101). Until D4 there is no
verdict, so partners see "processing" — safe by construction. A locked entry is unchanged:
no `image` key at all (BR-8). Signed GET TTL 24 h, expiry rounded up to the hour (05:169).

**Migration:** none expected. **Tests:** a JPEG carrying GPS EXIF comes back with none (read the
bytes, not a flag); a PNG renamed `.jpg` is `FAILED`; a 30,000×30,000 bomb is refused before
decode; a v7 id or another user's id cannot be attached (the new FR-044a category, 12:56); two
entries racing one asset; the BR-8 leakage test extended to an entry that has an image;
`media_processing_duration_seconds` recorded. **Smoke:** upload → wait for `READY` → submit
entry with `imageMediaId` → author sees URL, partner sees `PROCESSING`.

### D3 — voice

Same consumer, `kind = VOICE`: magic bytes for the formats both platforms record
(`ftypM4A`/`ftypmp4`, `OggS`, `#!AMR`, WebM `1A45DFA3`, WAV) → probe duration, refuse >60 s
(+1 s grace) → transcode to mono Opus at the bitrate Q4 fixes, metadata stripped → ≤500 KB →
`READY` with `duration_ms`. The entry shape gains `voice: {id, status, url?, durationMs?}`;
voice is not moderated (FR-101 names images) **[inferred: the corpus has no voice moderation
requirement; confirm]**. NFR-103: nothing requires listening.

**Tests:** a 61 s file is refused; a WAV renamed `.m4a` is refused; output plays (probe the
codec of the result); the transcoder is killed at its timeout. **Smoke:** an entry with photo and
voice — the first half of M4.

### D4 — moderation and quarantine

`ImageModerator` port in `media.api`'s sense (25:118), one vendor class (Q1). Runs after the
image is re-encoded, on the display variant (never ships the original, never the full variant,
fewer bytes leave the box). Verdict `PASSED | QUARANTINED`, scores kept in
`moderation_scores` (07:240). Vendor down → retry with backoff, partner keeps seeing
"processing" (fail closed for delivery, open for the author). `QUARANTINED`: the partner never
gets a URL; the author's view is Q10. Review queue: there is no admin console until Phase 5, so
D4 ships `scripts/moyi media quarantine list|release|delete` against the database, audit-logged
to a table the console will read **[inferred: `scripts/moyi` can carry this, as it does other
operator commands]**, and the takedown/law-enforcement runbook (09:32).

**Migration V28** if a quarantine audit table is needed before Phase 5's `audit_log`.
**Tests:** a fake moderator returning each verdict; partner's view per verdict; vendor timeout →
retry; `gratitude_media_quarantined_total`. **Smoke:** none against a real vendor; the fake is
selected by profile, as `LOG` is for email (ADR-0017).

### D5 — the lifecycle jobs

Three ShedLocked jobs in `scheduling`, calling `media.api` (so `scheduling → media`):

- **Reaper, hourly:** `PENDING_UPLOAD` past expiry + 1 h → `DELETED`; `READY`/`FAILED`
  unattached for 24 h (06:186, 07:355) → `DELETED`. Objects deleted by `media.purge`.
- **Demotion, nightly (D-16):** attached, tier 1, `ready_at` < now − 90 days → delete the full
  variant, keep the display, tier 2. Irreversible; the in-app disclosure is the client's and the
  Privacy Policy's.
- **Erasure (D-9):** every gratitude erasure — `DELETE`, withdrawal, later account deletion —
  calls `media.api.release(ids)` in its own transaction; `MediaReleased` → `media.purge`
  deletes the objects. `media.purge` retries forever, like withdrawal, because an erasure
  must never be given up on.

**Tests:** clock-driven (the shared `MutableClock`), each job twice in a row changes nothing the
second time; a withdrawn entry's objects are gone from MinIO; an attached asset is never reaped.
**Smoke:** none (time-based); the state map records the jobs. The `media-pipeline-stuck.md`
runbook and the P2 alert (11:97) land here.

**The media mirror (Q15).** A nightly systemd timer beside `ops/backup` copies new `v/` objects
from `moyi-media` to a second bucket with a bucket lock, using a read-only token on the source and
a write-only-in-practice token on the target; a Healthchecks.io heartbeat as for the database
backup; the restore drill gains one step that fetches a mirrored object. Object deletions
(erasure, D-9) must reach the mirror too, or the mirror becomes a copy of erased content: the
mirror's lifecycle expires a deleted key within the erasure window, and that is tested.

### N1 — push tokens, the notification ledger, FCM through the outbox

**Endpoints:** `PUT /api/v1/me/devices/current/push-token {token}` (the device is the session's,
via the `sid` claim, ADR-0025) and `DELETE` of the same. This replaces 06:82-83's
`POST /me/devices` and `DELETE /me/devices/{id}`, because devices are created at sign-in, not
registered separately (ADR-0025:17). Route in `identity`, storage in `notification` (§2.1).

**Migration V29** (`notification`): `push_tokens(device_id pk, user_id, platform, token,
updated_at)`, `unique (token)`; `notifications(id, user_id, bond_id, category, dedupe_key
unique, reserved bool, not_before, stale_after, status, provider_message_id, error_class,
created_at, sent_at)` with a CHECK on `status ∈ REQUESTED, SENT, SUPPRESSED, DROPPED_BUDGET,
EXPIRED, FAILED_PERMANENT`. The token is a secret: redacted `toString`, never logged (11:25).

**Code:** `notification.api.PushSender` port; `FcmPushSender` (RestClient, HTTP v1, OAuth2
service-account bearer — hand-rolled JWT exchange with the RS256 machinery already in
`common:security`, or Google's auth library confined to the class; either way one class);
`LoggingPushSender` for `local` and tests, as with email. The `notification.push` consumer per
§2.2. Payload: **localisation keys and an opaque route**, no strings authored on the server
(`title_loc_key`/`body_loc_key` on both platforms, Q6), `data: {bondId, kind}`. Collapse key
per category and Bond, so a held reveal replaces rather than stacks.

**Tests:** WireMock for FCM: `UNREGISTERED` prunes the token, `503`/`429` backs off, `400` on a
bad payload is `FAILED_PERMANENT` not retried for ever; the same event delivered twice sends
once (dedupe key); a request past `stale_after` is `EXPIRED`; the log-capture test proves no
token in any line. **Smoke:** register a token, `LoggingPushSender` records one send.

### N2 — preferences, quiet hours, the budget

**Endpoint:** `GET/PUT /api/v1/bonds/{id}/members/me/notifications {categories: {reminder,
partnerJoined, reveal, milestone, onThisDay}}` — a **separate** sub-resource, because the
existing settings `PUT` is a full replacement in which an absent field is cleared (06:111), so
adding a field there would silently mute every client that does not send it. Stored in
`bond_members.notification_prefs` (V9; mapped for the first time). On-this-day defaults
**off** (FR-092 opt-in); the others per Q7. Cross-tenant test required (CLAUDE.md).

**Pure domain** (12:32): `QuietHours.contains(instant, zone, start, end)` across midnight and DST;
`PushBudget.decide(sentToday, reservedUsed, isReserved)` implementing FR-082's reservation —
reveal-class takes the reserved slot first, then the remainder; others take only the remainder;
the cross-Bond cap of 6 applies to all. Reservation is decided under
`pg_advisory_xact_lock` on the user; the concurrency test synchronises on that lock being held.

**Daily reminder** (`scheduling`, every 5 minutes, ShedLock): members whose
`reminder_time_local` in `reminder_timezone` fell in the last tick, on Bonds that take writes,
who have **not themselves** written for the Bond-day containing that instant. Copy never refers
to the partner (FR-076). Dedupe `reminder:{memberId}:{bondDayId}`.

**Tests:** property tests for quiet hours over 45-minute zones and DST gaps; FR-082's own
example (reminders cannot consume the 22:00 reveal slot); three Bonds × three pushes stops at
six; a member who wrote gets no reminder.

### N3 — the consumers: reveal, solo unlock, milestone, partner joined, on this day

`gratitude.notify` (`StartFrom.NOW`, ADR-0041:316-317) subscribes to `DayRevealed`,
`DayClosed`, `MilestoneReached` — **never** `StreakBroken` (ADR-0034:240) or `StreakExtended`,
and nothing for reactions (ADR-0037:281). `bond.notify` subscribes to a new `MemberJoined
{bondId}` published by `AcceptInvite` (D-13), and to a new `ProposalOpened {bondId, proposalId,
proposerId}` published wherever a proposal row is created (`ChangeTimezone`, and the deletion
request if it creates one **[inferred: check `EndBond`]**); it pushes to the *other* member only,
once per proposal, never a reminder (Q13). Each:

- asks the Bond whether it takes writes, and stops if not (ADR-0041:313-315, ADR-0040:141);
- resolves **both** members and requests one push each (H-M5) — the decision is never made from
  one member's activity;
- dedupe keys: `reveal:{bondDayId}:{userId}`, `solo:{bondDayId}:{userId}` (Q8: yes),
  `milestone:{bondDayId}:{userId}` (ADR-0041:316), `joined:{bondId}:{userId}`,
  `proposal:{proposalId}:{userId}` (Q13: yes);
- `stale_after`: reveal and solo end of the following Bond-day; milestone 24 h; joined 24 h;
  proposal 24 h.

The solo push (Q8) uses its own message key, worded as an opening and never as a miss: the
same register as the reveal ("Today's words are open."), nothing that names who did not write.
Its copy goes through the T-09/FR-076 check with the rest of `copy.md`.

On this day: a weekly sweep in `scheduling` for members who opted in, at most one per week per
member (FR-092), only where `GET /on-this-day` would answer non-empty.

**Tests:** the M4 test — two members, second entry → exactly one `SENT` row per member, no
content in the captured payload (12:77); the same `DayRevealed` delivered twice → one each; an
ended Bond's `MilestoneReached` → nothing; `ArchitectureTest` pins that no consumer subscribes to
`StreakBroken`. **Smoke:** reveal → two logged sends.

**M4 itself** is not a test: a real Android phone with a debug build receives exactly one push at
reveal against production or a local FCM project. The client work that makes this possible
(doc 15 Phase 8) does not exist yet; a throwaway Android harness that registers a token and
shows a notification is the cheapest way **[inferred]**; ruled so (Q12).

### Order and why

D1 → D2 → D3 → D4 → D5 → N1 → N2 → N3. Media first because it is the larger unknown (native
tooling, memory, R2 behaviour) and its first slice is independent of everything; N1 can start in
parallel with D3 if a second session takes it, since the two share no table or module. D4
before D5 so nothing is ever demoted that was never moderated.

---

## 5. Owner's rulings

Answered 2026-10-27 through the Phase 4 Rulings form. Thirteen follow the recommendation; **two
depart from it (Q8, Q13)** and one adds a question the draft left in the risks (Q15). The
reasoning each recommendation carried is in this file's history (`git log -p`).

| # | Question | Ruling |
|---|---|---|
| 1 | Moderation | **Illegal content only** (CSAM-adjacent and extreme-violence labels, never nudity); **AWS Rekognition `DetectModerationLabels`, eu-central-1**, ~$2/month at 2k images. Verify the price on the AWS page at D4 |
| 2 | Image variants | **Two WebP:** *full* 2048 px ≤350 KB, *display* 1280 px ≤150 KB; no server thumbnail; tier 2 keeps *display* only |
| 3 | Voice length | **60 s** |
| 4 | Voice format | **Opus, mono, 24 kbps**, after a half-day iPhone playback test; **AAC-LC 48 kbps** for both if it fails; never both |
| 5 | Where processing runs | **A separate worker container** (JRE 25 + ffmpeg + libvips, same jar, `media-worker` profile), `--memory=768m --cpus=1.5`, a Kamal accessory |
| 6 | Push copy | **Localisation keys only**; no partner or Bond name on a lock screen; owner writes the final words |
| 7 | Defaults | Quiet hours **off**; a reveal inside them is **held to their end**; all categories on except on-this-day (opt-in) |
| 8 | Solo-unlock push | **Push it** (departs from the recommendation). Constraint carried into N3: a neutral "opened" wording with its own key, nothing that says who did not write; FR-076's no-guilt rule still governs the copy |
| 9 | H-M5's reading | **The decision is shared**; a member's own mute, quiet hours or budget may hold back only their copy |
| 10 | Quarantined photo | The author sees it marked **"not shared"**, no reason; the partner sees **"processing"**, never "removed" |
| 11 | Apple enrolment | **Enrol now as an individual**, accept ≈€26/month, close OQ-17. The owner already has a personal Apple developer account: it serves if it is a paid Apple Developer Program membership (a free Apple ID cannot send push); otherwise enrol that same account into the Program. M4 proceeds on Android regardless |
| 12 | M4 device | **A throwaway Android harness**, outside the client repo |
| 13 | Pending-proposal push | **Push it** (departs from the recommendation): one push to the other member per proposal, no reminders, no name; needs a new `ProposalOpened` event (N3) |
| 14 | FCM transfer (OQ-05) | **Accept** with SCCs and a short TIA; payloads carry only keys and opaque ids |
| 15 | Media backup (R-D) | **Mirror `moyi-media` nightly** to a second, locked bucket; built in D5 |

---

## 6. Risks on a one-box deploy

| # | Risk | Mitigation |
|---|---|---|
| R-A | **Memory.** 10:37-51 already sums to 8.0 GB (25:111). Live today: app 2 g (+2 g during a deploy), Postgres 2 g, Valkey 384 m, kamal-proxy and the backup timer. A worker at 768 m fits only because staging's 1.0 GB is not deployed **[inferred: no staging on moyi-prod-1]**; a full HEIC decode of a 48 MP photo can take several hundred MB of native memory **[inferred]** | Worker capped at 768 m with libvips' streaming access and a pixel ceiling; one job at a time; doc 10 §2.1 amended to carry the worker line and drop staging from the box |
| R-B | **CPU.** Four Ampere vCPUs serve API, Postgres and processing. Per item: a 60 s voice note to Opus well under a second, an image resize/encode around a second **[inferred, measure on the box as Argon2 was]**. ≤2,000 entries/day (07:382) is nothing on average; evening peaks are bursts | `--cpus=1.5` on the worker so the reveal path keeps its cores; processing is async, so a queue is a delay, never an error; NFR-006 is measured on text |
| R-C | **R2 cost.** Storage $0.015/GB-month, Class A $4.50/M, Class B $0.36/M, egress free, 10 GB and 1 M Class A free each month ([R2 pricing](https://developers.cloudflare.com/r2/pricing)). At 2,000 users the D-16 steady state of ~150 GB is ~$2.10/month, as 10:124 says; a worst-case abuser is bounded by 30 intents/hour × 10 MB, and the reaper removes what is never attached | Cost alert on the R2 dashboard; the intent limit; the bucket lifecycle backstop |
| R-D | **No backup for media.** Backups are `pg_dump` only (`ops/backup`); R2 loss loses every photo and voice note (10:187 mirrors only Postgres) | Ruled (Q15): a nightly mirror of `moyi-media` to a second, bucket-locked R2 bucket in another jurisdiction or provider **[decide at D5: same-account R2 shares the account as a failure domain]**; a slice in D5 |
| R-E | **Native code on untrusted input** (T-07). libvips and ffmpeg have a CVE history | The worker runs non-root, read-only root, no network but R2 and Postgres **[inferred feasible on a Kamal accessory]**, per-job timeout and `ulimit`; images rebuilt on a schedule |
| R-F | **FCM's 60 s budget.** The outbox poller ticks every 2 s, then two hops (gratitude consumer → `PushRequested` → push consumer) | Two ticks ≈ 4 s; the reveal-latency histogram (11:44) proves it |
| R-G | **Push is unreliable by nature** (17:30 R-14) | Already the rule: the client is correct with no push at all (25:69) |
| R-H | **Apple's lead time** (15:109) | Q11: enrol now (owner's existing Apple account, if it is a paid Program membership) |

---

## 7. What each slice must add to the ADRs

- **D1:** direct-to-R2 uploads, the bucket and token split, the object-key scheme, MinIO as the
  test double.
- **D2/D3:** where processing runs (Q5), the variant and codec targets (Q2, Q4), the FR-044a
  claim, the partner-visibility rule.
- **D4:** the moderation policy and vendor (Q1), quarantine without an admin console.
- **D5:** media erasure as object deletion (D-9), and its reach into the media mirror (Q15).
- **N1:** the module split of §2.1, token ownership (D-1), the push consumer's deadline and
  at-least-once honesty (§2.2).
- **N2/N3:** the budget's reservation and its day (D-16), quiet hours vs reveal (D-15),
  H-M5's reading (D-14), which events push and which never do.
