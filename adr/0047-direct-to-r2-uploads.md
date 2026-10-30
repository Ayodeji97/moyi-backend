# ADR-0047 — Media: direct-to-R2 uploads on a signed URL, a bucket of its own, random keys, and MinIO as the test double

**Status:** Accepted · **Date:** 2026-10-28 · **Deciders:** Daniel

## Context

Phase 4 slice D1 (spec `2026-10-27-phase-4-media-and-notifications-design.md` §4 D1, §7) is the
foundation of photos and voice notes: the `media_assets` aggregate, the four `/media` routes of doc
06:181-184, and an upload that never passes through this server (FR-044, NFR-009, doc 15:111:
"never let bytes touch your API server"). D2 to D5 build the pipeline, the attachment to an entry,
moderation and the lifecycle jobs on top of it; none of them is in this slice.

Five things needed deciding.

**How the storage is told what to accept.** A presigned URL lets a phone write to the bucket with
no credential of its own. What the URL signs is all the storage will check. R2 has no presigned
POST policy (Cloudflare's presigned-URL page: GET, HEAD, PUT and DELETE only), so a size *range*
cannot be signed. The spec's **[inferred]** item: that R2 honours a signed `x-amz-checksum-sha256`.

**Where the bucket and its credentials come from**, and what production does before they exist.
The first deploy of #96 failed because a provider defaulted to the real service and refused to
start without secrets that the deploy file did not supply.

**What an object key says.** Doc 09:18 lists media object keys as high-sensitivity assets: with
the bucket's credentials a key reaches the bytes.

**What stands in for R2 in the tests.** The spec's other **[inferred]** item: that MinIO's SigV4
presigning matches R2's closely enough for PUT, HEAD, GET and DELETE. And MinIO no longer
publishes `minio/minio` on Docker Hub.

**Where `media` sits in the module graph** (spec §2.1).

## Decision

**1. The upload URL signs the content type, the exact length and the SHA-256.** The intent
(`POST /media/upload-intent {kind, contentType, sizeBytes, checksum}`) is checked against FR-042
and FR-043 in one place, the domain's `UploadDeclaration`: missing or malformed fields `422
VALIDATION_FAILED` naming each; a type the kind does not take `415 MEDIA_TYPE_UNSUPPORTED`; more
than 10 MB of image or 5 MB of voice `413 MEDIA_QUOTA_EXCEEDED` (in that order). Then
`R2ObjectStore` presigns a PUT with `Content-Type`, `Content-Length` and `x-amz-checksum-sha256`
set, so SigV4 lists all three in `X-Amz-SignedHeaders`. The response's `requiredHeaders` is read
from what the SDK signed (minus `host`), so the client is told exactly what the signature covers.
The URL lives 15 minutes (doc 05:163). Signing needs no network; an asset is recorded only after a
URL was issued.

- **MinIO, run:** a body one byte longer, a body of the same length with one byte changed, and
  another `Content-Type` are each refused and nothing is stored (`R2ObjectStoreTest`). Removing
  the checksum from the signature turns three of those tests red; removing the length turns the
  signed-headers test red (the checksum still refuses a longer body).
- **R2, not run: the [inferred] item is not settled.** Cloudflare's S3 compatibility page lists no
  checksum headers on PutObject and does not say they are refused. The length is signed whatever
  R2 does with the checksum, and completion (decision 2) compares the digest whenever the storage
  reports one. **Owed:** one manual PUT of altered bytes against the real bucket before uploads
  are switched on in production (doc 12:136 already asks one manual R2 check per release).
- **The SDK's own checksums are off unless required** (`WHEN_REQUIRED`), and chunked encoding is
  off, as Cloudflare's Java guide says. Seen: the signed headers are exactly `content-length;
  content-type;host;x-amz-checksum-sha256`, with no `x-amz-sdk-checksum-algorithm` a phone would
  not send.
- Upload content types: an image is `image/jpeg`, `image/png`, `image/heic` or `image/webp`
  (FR-042). FR-043 names no input format; a voice note is `audio/mp4`, `audio/x-m4a`,
  `audio/ogg`, `audio/amr`, `audio/webm`, `audio/wav` or `audio/x-wav`, the formats D3's
  magic-byte check reads (spec §4 D3). A type is lower-cased, and one with parameters
  (`audio/webm;codecs=opus`) is refused: the value is signed and must be sent back exactly. A
  declared type is a claim; D2 and D3 read the bytes (T-07). "MB" is a mebibyte, the larger
  reading.

**2. Completion asks the storage before any transaction opens, then decides under the row lock.**
`POST /media/{id}/complete` requires an `Idempotency-Key` (doc 06:16). It reads and authorises the
asset with no transaction open, then (for a pending upload only) sends the `HEAD`, still with no
transaction open, and only then runs `IdempotentExecution.once` as `POST /entries` does: key lock,
then the asset's `FOR UPDATE`, then **the status read again under the lock**. A pending upload whose
object has the declared length, and the declared digest when the storage reports one (MinIO does),
becomes `UPLOADED` and publishes `MediaUploaded {mediaId}` on
aggregate `Media` in the same transaction (ADR-0035). Nothing there, or something else there, is
`409 MEDIA_NOT_UPLOADED` (a new code): nothing changes, and since a refusal is never recorded, the
client may finish the PUT and complete again under the same key. An asset already past
`PENDING_UPLOAD` is complete; completing it again changes nothing and publishes nothing. A replay
is a read: an asset deleted since is the read's `404`. `idempotency_keys.result_kind` gains
`MEDIA` (V33, `common:web`'s own migration, since the table is that module's).

**Every storage call is bounded, and none runs under a database lock** (review of #106). The
first version sent the `HEAD` inside the transaction. With the SDK's defaults (three attempts with
backoff, no call timeout) a storage that accepts and never answers held the call for about 20 s,
and with it a pooled connection, the key's advisory lock and the row lock: ten such requests empty
Hikari's pool, and a deletion waiting on the row met `lock_timeout` as an unmapped `500`. Now the
storage client gives a whole call 3 s (`api-call-timeout`), one attempt 1.5 s
(`api-call-attempt-timeout`), at most 2 attempts (`max-attempts`), all
`moyi.media.storage.r2.*` properties; and the `HEAD` runs before the transaction. A storage that
does not answer costs that request about 3 s and holds nothing in the database; the answer is
`503 MEDIA_STORAGE_UNAVAILABLE` and nothing is recorded, the Idempotency-Key included
(`MediaStorageHangsTest`, against a socket that accepts and never answers, also watches `pg_locks`
for the whole call). Asking outside the lock is safe because the decision is taken under it, on
the status as it is then: a completion that lost a race to another completion finds `UPLOADED` and
answers `200` without a second event, one that lost to a deletion is the `404`
(`MediaRaceTest`, both requests seen parked on the row in `pg_locks` before it is released).
A row lock not had within `lock_timeout` (10 s in production) is **`409 MEDIA_BUSY`**, a new code,
on both completion and deletion: not `503`, since the storage is not involved, and retryable as
`FAVOURITE_BUSY` and `REACTION_BUSY` are; with no storage call under the lock it means the
database is struggling, so it should be rare. No consumer subscribes to `MediaUploaded` yet; D2's
`media.process` will.

**3. Owner only, and one `404` for everyone else.** Every read is `WHERE id = ? AND owner_user_id =
?`, so another person's asset is not found rather than found and refused, and an id that is not a
UUID is the same `404` (CLAUDE.md, doc 06:183: a partner reaches media only through an entry, BR-1).
`MediaCrossUserTest` finds every route under `/api/v1/media/{…}` by reflection and fails the build
for one it has no check for: doc 12:56's cross-*user* category, which the bond-scoped suites cannot
see.

`DELETE /media/{id}` marks the asset `DELETED` under its row lock, then, after the commit, deletes
the object, on a best-effort basis: an upload is only ever under `in/` in this slice, and the
bucket's lifecycle rule (decision 4) removes what a failed delete leaves within two days. An upload
URL also outlives a deletion: it stays valid until its 15 minutes are up, so a PUT after the delete
leaves an orphan under `in/`, which the same 2-day rule removes. D5's
`media.purge` is where an erasure that must never be given up on lives (spec D-9). "Only if
unattached": nothing attaches before D2, so the attached case cannot arise; the delete's `UPDATE`
carries `attached_entry_id IS NULL` anyway, and D2 adds its refusal with the claim that makes it
real.

**4. A bucket of its own, a token of its own, keys that say nothing.** `moyi-media`, created in
Cloudflare's EU jurisdiction, with an R2 API token scoped to that one bucket (Object Read & Write),
never the backup token. A lifecycle rule on the bucket expires `in/` after 2 days, as a backstop to
D5's reaper. Keys are `in/{uuid}` with a fresh v4 UUID, distinct from the asset's own v4 id (doc
06:14), so neither can be derived from the other and the key carries no user, bond or kind (doc
09:18). D2 writes variants under `v/{uuid}/…`. The key, the presigned URL and the token are never
logged; `StorageKey`, `PresignedUpload`, `UploadIntent` and the properties redact their
`toString`, and a storage failure's message carries neither key nor URL.

**5. The provider posture is ADR-0017's, and production names the stand-in.**
`moyi.media.storage.provider` defaults to `R2`, which refuses to start without
`moyi.media.storage.r2.endpoint`, `.bucket`, `.access-key-id` and `.secret-access-key`.
`UNAVAILABLE` is asked for by name: production's `config/deploy.yml` asks, until the bucket
exists, in a line that says so and how to switch. Under it every route that needs the storage
answers `503 MEDIA_STORAGE_UNAVAILABLE` (a new code, also the answer when R2 cannot be reached),
nothing is recorded, and the rest of the API is untouched. There is no logging stand-in, as email
and push have: an upload has to go somewhere, and a URL to nowhere would fail on the phone instead
of at the API. `ProductionStartsWithoutMediaSecretsTest` reads `config/deploy.yml` and fails if it
neither names the stand-in nor supplies all four R2 values; and the jar was started with
production's environment and no media secrets (the pull request records the run).

**6. The vendor in one class; MinIO as the test double.** `media.infra.storage.ObjectStore` is
internal (presign PUT and GET, HEAD, GET stream, DELETE); `media.api` exposes nothing
storage-shaped. `R2ObjectStore` is the only class that imports the AWS SDK v2
(`ArchitectureTest`), with the JDK's URLConnection as its transport (the SDK's Apache and Netty
clients are excluded) and path-style addressing. The tests and the `local` profile point the same
class at MinIO, so what is tested is the code that runs in production, against the same SigV4.

The image is `pgsty/minio` release `RELEASE.2026-08-04T00-00-00Z`, pinned by its digest
(`sha256:b6bfe723…e602372`, read from the tag with `docker image inspect`), a community build of the MinIO server from
its source: `minio/minio` no longer resolves on Docker Hub and quay.io's copy needs a login (both
tried, 2026-10-28). `docker-compose.yml` runs the same image, with a one-shot job that makes the
local `moyi-media` bucket.

**7. `media` is a leaf** (spec §2.1). It depends on `common` only; D2 makes `gratitude →
media.api`. `ArchitectureTest` fails on an import of, or a build dependency on, any domain module
from `modules/media`, as it does for `notification`.

## Consequences

**Positive.** No byte of a photo or a voice note passes through the API. A body other than the one
declared is refused by the storage itself, not by code of ours that could be bypassed. A
production without media credentials starts, and says so in its log. Replacing R2 is one class.

**Negative.** Whether R2 enforces the signed checksum is unproven until someone PUTs altered bytes
at the real bucket. A completion waits up to 3 s on a storage that does not answer (holding no
database connection while it does). A deleted upload's bytes may outlive the deletion by up to two days if the storage
cannot be reached at that moment. The test double is a community image whose maintainer is not
MinIO.

**Neutral.** Three new error codes beyond the spec's two (`MEDIA_NOT_UPLOADED`,
`MEDIA_STORAGE_UNAVAILABLE`, `MEDIA_BUSY`). `ResultKind` and V11's CHECK gain `MEDIA` together. The `media:intent`
bucket (30 an hour per user, doc 06 §4) joins `RateLimitBucket`.

## Alternatives considered

- **Proxy the bytes through the API.** Rejected by FR-044 and NFR-009, and by the memory budget
  (spec §6 R-A).
- **Presigned POST with a size range.** R2 does not support it.
- **Sign the length only.** Rejected: the digest costs the client one hash and lets MinIO, and R2 if
  it honours it, refuse altered bytes at the door.
- **A logging stand-in, as email and push have.** Rejected in decision 5.
- **HEAD inside the transaction, under the row lock.** The first version; replaced in decision 2
  after review, because a slow storage then held a pooled connection and two locks.
- **Delete the object before the commit, or only through D5's `media.purge`.** Before the commit
  would lose the bytes of a deletion that rolled back; waiting for D5 would leave every deleted
  upload in the bucket until then.
- **LocalStack or Adobe's S3Mock as the double.** Rejected: MinIO is what the spec names and what
  doc 12:136 assumes, and it enforces signed checksums.

## Owed

- **Before uploads are switched on in production (the owner):** create `moyi-media` in R2's EU
  jurisdiction; add the lifecycle rule expiring `in/` after 2 days; create an API token scoped to
  that bucket only, Object Read & Write; put the access key id and the secret access key in
  Bitwarden; in `config/deploy.yml` remove `MOYI_MEDIA_STORAGE_PROVIDER: unavailable`, add
  `MOYI_MEDIA_STORAGE_R2_ENDPOINT` (`https://<account id>.eu.r2.cloudflarestorage.com`) and
  `MOYI_MEDIA_STORAGE_R2_BUCKET: moyi-media`, and list `MOYI_MEDIA_STORAGE_R2_ACCESS_KEY_ID` and
  `MOYI_MEDIA_STORAGE_R2_SECRET_ACCESS_KEY` as secrets; then PUT altered bytes to one intent's URL
  and see R2 refuse them (decision 1). **Configure the storage only with or after D5 deployed**
  (ADR-0050): before it, an erased entry's photo is never released and an asset deleted then never
  has its objects purged, and nothing goes back for either.
  **Done, but for what only R2 can show** (ADR-0052, "Switched on"): the bucket, its rule and its
  token are the owner's (done); `config/deploy.yml` now names the bucket and lists the endpoint
  (it names the account, so a secret: Bitwarden's existing `R2_ENDPOINT`) and both halves of the
  token as secrets, in both roles; D5 is live. **Moved, not done: the PUT of altered bytes against
  the real bucket** is now `scripts/r2-switch-on-check.sh`, which the owner runs after the
  switch-on deploy (`ops/media-worker/README.md`, step 6). It PUTs a body one byte longer, a body
  of the signed length with one byte changed (the checksum, this decision's open item) and another
  `Content-Type`, and reports each refusal; if R2 takes the changed byte, it says whether the
  completion's digest check then refused it. Seen against MinIO only.
- **D2:** `media.process` must read the asset's status again and skip one that is `DELETED`: a
  `MediaUploaded` can name an asset deleted after it was published. And
  `media.process`, the signed GET URLs on `GET /media/{id}`, the FR-044a claim and
  `MEDIA_ATTACHED` for a delete of an attached asset.
- **D3:** confirm the formats Android's recorder produces before trusting this slice's voice list;
  `audio/aac` and `audio/3gpp` may need adding, with their magic bytes in D3's check.
- **D5:** the reaper of `PENDING_UPLOAD` past its expiry, and `media.purge`.

## Revisit when

R2 is seen to ignore a signed checksum (then completion must read the bytes, or D2's pipeline
checks the digest before anything else). A second storage provider is wanted. Completions are seen
to wait on the storage for seconds.
