# ADR-0048 — Photos: processed by libvips in a sandbox, claimed by an entry in its own transaction, and shown to each reader as `media` decides

**Status:** Accepted · **Date:** 2026-10-28 · **Deciders:** Daniel

## Context

Phase 4 slice D2 (spec `2026-10-27-phase-4-media-and-notifications-design.md` §4 D2, §7) turns an
upload D1 completed into something an entry can carry: the image pipeline (`media.process`, a
consumer of `MediaUploaded`), FR-044a's claim of a photo by an entry, and the photo on the entry's
wire shape. ADR-0047 owed it four things: `media.process` skips an asset deleted after its event was
published; signed GET URLs on `GET /media/{id}`; the claim; and `MEDIA_ATTACHED` for a delete of an
attached asset.

What had to be decided:

- **Where the decoding runs, and with what.** The owner ruled (Q5) a separate worker container
  (JRE 25, ffmpeg and libvips, the same jar under a `media-worker` profile, 768 MB, 1.5 CPUs). The
  application's own image is Spring Boot's buildpack image, which has no libvips.
- **How bytes a user controls are kept from hurting the process that reads them** (T-07: magic
  bytes, size caps, re-encode every image, sandboxed processing; spec §6 R-E).
- **What is kept** (Q2: two WebP, *full* 2048 px ≤350 KB and *display* 1280 px ≤150 KB).
- **How a photo is attached to an entry** so that a race for one photo has one winner, and a
  photo nobody may attach is refused without saying whose it is (FR-044a, doc 12:56).
- **Who is shown what** (FR-101: moderated before delivery to the non-author; BR-8: a locked
  entry carries no media flag).
- **What the signed GET looks like** (doc 05:169: 24 hours, rounded to the hour, so the URL is
  stable and can be cached by).

## Decision

**1. The pipeline is in the jar, selected by `moyi.media.processing.provider`; production's API
names it off.** `VIPS`, the default, runs libvips' command-line programs and **refuses to start**
when it cannot: the programs are looked up on `PATH` (or `bin-dir`), and `vips --version` is run
through the same sandbox the photos will be (decision 3). `OFF` is asked for by name and processes
nothing: `media.process` then declares no event type, so it subscribes to nothing and claims no
delivery in that process. `config/deploy.yml` names `"off"` for the application's container,
quoted: YAML 1.1 (Kamal's, and SnakeYAML's) reads a bare `off` as the boolean `false`. Spring Boot
binds `"false"` to `OFF` anyway (seen: the jar started with it), so the quotes keep the file saying
what it means rather than relying on that leniency; `ProductionStartsWithoutMediaSecretsTest` holds
the line as the string. The jar was started with production's environment and no media secrets,
and started; without the line, it refused to start (the pull request records both runs).

`media.process` subscribes **from the beginning** (`StartFrom.BEGINNING`), so the first process
that processes, the worker, is handed every upload completed before it existed. Nothing uploaded
while processing is off is lost; it waits, `UPLOADED`.

**The worker itself is not built in this slice** (owed, below). In one process the pipeline runs on
the outbox poller's thread, so a photo's seconds delay the deliveries queued behind it (a reveal's
push among them); that, and the memory budget (spec §6 R-A), is why the decoding belongs in the
worker the owner ruled for, and why production's API never runs it.

**2. The pipeline, in order** (`ProcessUploads`):

1. **The row is read again**, never the event. `MediaAsset.processing` answers: an `UPLOADED`
   photo, or one left `PROCESSING` by an attempt that did not finish, is processed; a deleted
   upload, a ready or failed one, and anything not yet completed is left alone (ADR-0047's debt);
   a voice note is left `UPLOADED` for D3. The statement that starts an attempt repeats the
   status condition as its backstop.
2. **The attempt is recorded in a transaction of its own** (`PROCESSING`, `processing_attempts +
   1`, `REQUIRES_NEW`), committed whatever becomes of the delivery, so a photo being worked on is
   seen to be (D5's alert on `PROCESSING` for 30 minutes reads it).
3. **The work holds no lock on the asset.** The upload is read once, never past one byte more than
   its declared length (`LONGER_THAN_DECLARED`), and must have the declared SHA-256
   (`CHECKSUM_MISMATCH`). This settles, for the pipeline, the integrity question ADR-0047 left
   open about R2 and the signed checksum: whatever the storage accepted, nothing but the declared
   bytes is processed. Its first twelve bytes must be one of the four allowed formats
   (`ImageFormat`: JPEG `FFD8FF`, PNG, WebP `RIFF…WEBP`, HEIC `ftyp{heic,heix,mif1}`) and the one
   declared; the declared type is a claim, the bytes decide (T-07).
4. **The processor** (decision 3) makes the two variants, written under
   `v/{uuid}/{full,display}.webp` with the upload key's own random part (decision 4).
5. **The decision is taken under the row lock**, on the status as it is then. Still
   `PROCESSING`: `READY` with the variants and the largest one's width and height, or `FAILED`.
   Anything else means the owner deleted it meanwhile: the variants just written are deleted and
   nothing is marked. A deletion therefore never waits on a photo being processed, and a processed
   photo never outlives its deletion (`ProcessingInterleavingTest`, which holds the processor at a
   gate the test opens).
6. **After the commit the upload itself is deleted** (spec: "delete in/…"), ready or failed: the
   original bytes, with their metadata, are never kept. If the storage cannot be reached then, the
   bucket's lifecycle rule removes them within two days (ADR-0047).

**A refusal is final; an outage is not.** Every reason a photo can be refused (`ImageRefusal`) is
one the same bytes would meet again, so the photo is `FAILED`, the delivery succeeds and the log
says `Media {id} failed: {REASON}` (the id and the rule, never a key or anything the file said). A
storage that cannot be reached is thrown: the delivery rolls back, the outbox backs off, the photo
stays `PROCESSING` and is taken up again. `gratitude.media.processing.duration{kind,outcome}` (doc
11:49) times each attempt.

**3. libvips runs as a program, in a sandbox, never in this JVM** (`Sandbox`,
`VipsImageProcessor`):

- **A process of its own per step**, `vipsheader` and `vips`. A decoder that crashes, loops or
  allocates without end does it there; the JVM reads an exit code. Bindings into the JVM (FFM
  over libvips) were rejected: a native call cannot be interrupted, and a fault in it is the
  JVM's.
- **The pixel ceiling is read from the header before any decode**: `vipsheader` reports the size
  libvips itself will decode (a HEIC grid's whole size, not a tile's), and more than 64 Mi pixels
  (`ImageRules.MAX_PIXELS`: a 48-megapixel photo fits, and a 16384 × 4096 panorama) is
  `TOO_MANY_PIXELS`. A 30,000 × 30,000 PNG of a few kilobytes is refused and never decoded; with
  the check removed, libvips decodes it (seen).
- **One decode of the upload**: `vips thumbnail` to 2048 px, shrunk as it loads, turned upright
  from its orientation tag, `--fail-on error`. The two programs that read the user's bytes run
  with `VIPS_BLOCK_UNTRUSTED`, so libvips refuses its loaders not meant for untrusted input
  (ImageMagick, Matlab and the like) whatever the file claims; the caller has already allowed four
  formats only.
- **`keep=none` on every WebP save**, the one place metadata is removed: EXIF with its GPS
  position, XMP, IPTC, the ICC profile. A test writes a GPS latitude into a JPEG's EXIF by hand
  and reads the WebP's bytes: no EXIF chunk, no `Exif` header, not the latitude's bytes (and with
  `keep=none` removed, the EXIF chunk is there: seen).
- **A time limit, by killing**: one deadline for the photo's whole processing, 40 s by default,
  inside the delivery's minute (ADR-0035); a step past it is killed with anything it started.
- **A memory limit, by the kernel**: on Linux each program runs under `ulimit -v` (1 GiB by
  default) and `ulimit -t`. Measured 2026-10-28 with libvips 8.15.1 (Ubuntu 24.04, the CI runner's
  and the worker's base): a 48-megapixel HEIC peaks at 383 MB resident and needs about 500 MB of
  address space, in 1.7 s; a 48-megapixel JPEG, 81 MB in 0.2 s. A limit that cannot be set ends the
  run before the program starts (exit 125): macOS refuses `ulimit -v`, so there the processor
  starts only with `allow-unlimited-memory`, which the `local` profile and the tests set and
  production never does.
- **No inherited environment**: the program sees `PATH` and what it is given, nothing of this
  process's secrets. **Output to files**, read back to 64 KiB, so a program that writes without end
  cannot block on a pipe; nothing it prints is logged.
- **What is kept must be a WebP**, whatever the program said: under a tight memory limit libvips
  8.15 was seen to exit 0 having written an empty file.
- `ArchitectureTest` holds that `Sandbox` is the one class that starts a process.

**4. Variants, keys and the claim.** *Full* fits 2048 px and 350 KiB, *display* 1280 px and 150
KiB (Q2; a KB is 1024 bytes, as a MB is a mebibyte in ADR-0047), never enlarged; WebP quality 80,
70, 60, 50, 40 in turn, then the edge at 75% and 50%, until each fits, else `CANNOT_FIT`. A
variant's key is `v/{uuid}/{label}.webp`, the upload's own random part: a retried attempt writes
the same keys again rather than leaving orphans, and the key is still random and never the asset's
id (doc 09:18). `variants` (jsonb, V32) records each variant's key, size and bytes; `width` and
`height` are the largest's. **No migration**: V32 already had every column.

**FR-044a** is `media.api.MediaAttachments.claim(owner, mediaId, kind, entryId)`: one conditional
`UPDATE … WHERE id = ? AND owner_user_id = ? AND kind = ? AND status = 'READY' AND
attached_entry_id IS NULL`, run in the caller's transaction (`MANDATORY`: outside one, Spring
refuses the call, so a claim never commits apart from its entry). Two entries racing for one photo
are serialised on its row and the second matches nothing (`MediaAttachmentsTest`, both claims seen
parked on a held row in `pg_locks`). Its answer is a boolean with no reason: another user's photo,
a v7 id (media ids are v4, doc 06:14), one not ready, a voice note named as a photo, one on another
entry and an id that names nothing are one refusal, `422 VALIDATION_FAILED` on `imageMediaId`,
because telling them apart would tell a caller whether somebody else's photo exists. The entry is
not created with its photo dropped (spec §4 D2, ADR-0031:167's reasoning), which is the spec's
reading of doc 06:186's "rejects the attachment, never the entry": text is kept by the client and
sent again.

- **`POST /entries`** claims after the bond's `404` and `409 BOND_ARCHIVED`, a voice note's `422
  MEDIA_NOT_YET_SUPPORTED` (until D3) and the prompt's `422`, for the id the entry is about to be
  inserted under; anything refused later (BR-2's `409`, a closed day) rolls the claim back with the
  entry, and the Idempotency-Key stays free. The photo's row is locked from the claim to the commit,
  after the bond's and before the day's; nothing that locks a photo takes a bond or a day.
- **`PATCH /entries/{id}`** claims under the entry's lock, after BR-7's `409 ENTRY_IMMUTABLE`, so no
  photo is ever attached to an entry revealed meanwhile. A new photo in place of another releases
  the old one (`MediaAttachments.release`): it is its owner's alone again, as an upload never
  attached is, for D5's reaper. The same photo again changes nothing. An absent `imageMediaId`
  leaves the photo as it is; `PATCH` cannot remove one (open question, below).
- **`DELETE /media/{id}`** of an attached photo is `409 MEDIA_ATTACHED` (new code): it is the entry's
  now, and leaves with the entry. Deleting an unattached photo removes, after the commit and on a
  best-effort basis, every key it may have: its upload and both variant keys, **derived from the
  upload's, whether or not the row records them** (`MediaAsset.objectKeys`). An attempt that stored
  one variant and then failed records nothing, and a photo deleted before the retry is never
  retried, so a list read from the row would leave that variant behind (found in review of #108;
  `ProcessingInterleavingTest` red with the recorded list, green with the derived one). There is no
  lifecycle rule on `v/`, so a variant the storage could not delete then is D5's `media.purge`'s.
- **An erasure** (the author's delete, a withdrawal) nulls the entry's `image_media_id` with the
  words (BR-10, as before), and leaves the asset attached to the erased entry. Deleting its objects
  is D5's (spec D-9), not this slice's.

**5. Who is shown what.** The rule is `media`'s (`MediaAsset.shownTo`), asked through
`media.api.ImageViews.shownTo(viewer, ids)`: the photo's **owner** (on an entry, its author, since
only an owner can attach) is shown it once it is `READY`; **anyone else** only once it is ready
**and** its moderation verdict is `PASSED`. Until D4 there is no verdict, so a partner is shown
`PROCESSING`: safe by construction (FR-101). `gratitude` asks only about readings BR-1 answered in
full: `DisclosedEntry` carries the photo's id on a full reading and on nothing else, so a locked
entry has no photo, field or flag (BR-8, `RevealGateTest` extended), and a tombstone has none, a
withdrawn author's entry included from the withdrawal's commit, while its row still holds the id.

The entry's shape gains `image: {id, status, url, width, height}` or `null` (doc 06:138, spec
§4 D2), every field always sent: `status` is `READY` or `PROCESSING`, and `url`, `width` and
`height` are present exactly when it is `READY`. `PROCESSING` never says why (not processed, not
moderated, or quarantined, D4's Q10). The image shown is the largest kept: *full* while it is,
*display* after D5's demotion. `GET /media/{id}` (the owner's) gains the same `url`, `width` and
`height`.

**6. One signed URL an hour.** A GET is signed as of the start of the current hour and valid for 25
hours from then: at least 24 hours from any moment it is handed out, ending on the hour, and **the
same URL for every request in that hour** (doc 05:169), so a client can cache the bytes by URL and
the archive's `ETag`, a digest of the body, moves at most once an hour on a day with a photo. The
SDK's presigner signs as of the moment it is called and offers no other instant, so
`R2ObjectStore` signs the GET with the SDK's own SigV4 signer (`AwsV4HttpSigner`, its
`SIGNING_CLOCK` fixed to the hour, query-string auth, `UNSIGNED-PAYLOAD`, path-style). MinIO serves
a URL so signed up to an hour earlier, refuses one whose time has passed, and gives the same URL for
the same instant (`R2ObjectStoreTest`). Signing needs no network; a deployment with no storage shows
a ready photo as `PROCESSING` rather than failing the response it is part of.

## Consequences

**Positive.** No original byte of a photo is kept or served, and its GPS position never leaves the
server. A decoder fault, a bomb, a hang or a runaway allocation costs one photo, not the process. A
photo is attached exactly once, by its owner, ready, or not at all, with nothing told of anyone
else's. The partner cannot be shown an unmoderated photo, and a locked entry says nothing of one.
Production starts as it is, and processes nothing until the worker exists.

**Negative.** Until the worker is deployed no photo is processed in production (and none can be
uploaded until the bucket exists, ADR-0047). Processing in a process that serves the API delays
that process's other deliveries. `vipsheader` is native code reading untrusted bytes before the
pixel ceiling is known, in the sandbox but before any limit of ours on size. Variants deleted on a
best-effort basis can outlive their asset until D5. The pipeline's CLI options are those of libvips
8.15 to 8.18 (checked on both); a libvips that renames them fails the start-up check or the photos,
not silently.

**Neutral.** One new error code (`MEDIA_ATTACHED`). The media state machine gains `PROCESSING`,
`READY`, `READY_ATTACHED` and `FAILED`, and `VALIDATION_FAILED` becomes a row in its region, since
there the asset's state decides it. CI installs `libvips-tools` and `libheif-plugin-libde265`.

## Alternatives considered

- **libvips in this JVM, through FFM bindings.** Faster per photo; rejected in decision 3: no
  interrupt, no memory limit, a native fault is the JVM's.
- **Pure Java (ImageIO and a plugin).** No WebP encoder and no HEIC decoder without native code
  anyway.
- **ImageMagick.** A larger attack surface for the same job; libvips is what the owner ruled.
- **A pixel ceiling parsed in Kotlin from each format's header.** Four parsers of our own, and a
  HEIC grid's size is not in one box; `vipsheader` reports what libvips will decode.
- **Presigned GETs signed at the moment of the request** (the presigner's way). A new URL on every
  request: nothing caches by it, and the archive's `ETag` would change on every read of a day with
  a photo.
- **Telling the caller why a photo cannot be attached.** Rejected: it would say whether another
  user's photo exists.
- **Attaching the photo after the entry is inserted, in a second transaction.** A crash between the
  two leaves an entry naming a photo it does not hold.

## Owed

- **The media worker (the owner's Q5), before any photo is processed in production:** an image
  with JRE 25, `libvips-tools` and `libheif-plugin-libde265` on Ubuntu 24.04 (and ffmpeg for D3),
  running the same jar with `MOYI_MEDIA_PROCESSING_PROVIDER=vips`, non-root, read-only root, its
  network limited to R2 and Postgres (spec R-E, **[inferred feasible]**, not checked), `--memory=768m
  --cpus=1.5`; and in it, the web server and every consumer but `media.process` turned off, so the
  worker claims only its own deliveries. The ruling says "a Kamal accessory"; an accessory runs a
  fixed image and is not redeployed with the application, while a Kamal **role** runs the
  application's own image with its own command and options. Which of the two is the owner's to say
  at the deploy (open question, below). Until then `config/deploy.yml` keeps `"off"`.
- **R2, not run:** one GET of a photo URL signed up to an hour earlier, against the real bucket,
  when uploads are switched on (MinIO serves it; R2's SigV4 is expected to, as S3's does).
- **D3:** voice notes, still `UPLOADED` after `media.process` has passed them; D3 processes them,
  and claims `voiceMediaId` (`MEDIA_NOT_YET_SUPPORTED` until then). `AttachableKind` gains `VOICE`.
- **D4:** the verdict that lets a partner see a photo; the author's "not shared" for a quarantined
  one (Q10) is the `status` D4 adds to `ImageViewStatus`.
- **D5:** erasure releases and purges an erased entry's photo (D-9), the reaper deletes a photo
  unattached for 24 hours (a released one included) and variants a best-effort delete left behind.

## Revisit when

The worker is deployed (decision 1's in-process mode then serves development and tests only).
libvips changes a command-line option the pipeline uses. A photo is seen to need more than 64 Mi
pixels, or the worker's memory to need more than 1 GiB of address space. Clients are seen to fetch
the same photo again within the hour (the URL is meant to let them cache).
