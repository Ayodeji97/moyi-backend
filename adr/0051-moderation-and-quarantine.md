# ADR-0051 — Moderation: Rekognition on the display variant, verdicts by one pure rule, held back without a console, and child abuse material kept as evidence

**Status:** Accepted · **Date:** 2026-10-29 · **Deciders:** Daniel

## Context

Phase 4 slice D4 (spec `2026-10-27-phase-4-media-and-notifications-design.md` §4 D4, §7) gives a
photo or a voice note the verdict that lets the other member see it (FR-101, FR-061). D2 and D3 made
the rule that a partner is shown media only once it has `PASSED` (`MediaAsset.shownTo`, `heardBy`;
ADR-0048 decision 5, ADR-0049 decision 7) and gave nothing a verdict, so until now no partner has
been shown any media. The owner's rulings that bind it:

- **Q1**: illegal content only, AWS Rekognition `DetectModerationLabels` in eu-central-1, ~$2 a
  month; voice notes moderated too (added 2026-10-29), how is D4's to decide.
- **Q10**: a quarantined photo is "not shared" to its author, no reason; "processing" to the partner,
  never "removed".
- **Q16** (2026-10-29): child sexual abuse material always blocked (a known-CSAM hash list **plus**
  the classifier), never released by the operator tool, reported through the takedown runbook;
  extreme violence or gore `QUARANTINED` for review; adult nudity between members `PASSED` with a
  `sensitive` flag the API exposes (D4 names it); non-consensual imagery met by erasure, a report
  action and help text. D4 switches on exactly the vendor categories these need.

Owed to D4 by earlier slices: moderate photos already at tier 2 (ADR-0050 decision 4); say what
becomes of a `QUARANTINED` asset on no entry (ADR-0050 Owed); name the author's "not shared" on
the wire (ADR-0048 Owed); give voice notes their verdict (ADR-0049 decision 7).

## Decision

**1. Moderation is two consumers of a new event, run where media is processed, and failing closed.**
`media.process` publishes `MediaReady {mediaId}` in the transaction that makes an asset `READY`.
`media.moderate-image` and `media.moderate-voice` consume it, `StartFrom.BEGINNING`, each
subscribing only where its kind is processed **and** a moderator is configured; otherwise they
declare no event type and claim nothing. So:

- Production's API (processing `off`) never calls a vendor; the media worker (ADR-0052) does.
- `none`, the provider production names, **passes nothing**: nothing gets a verdict, so no partner is
  shown anything. Switching a moderator on later hands the consumer every asset made before, which
  is why the two kinds are two consumers: voice moderation can be switched on after photos without
  either being moderated twice.
- The author is shown their asset `READY` from the moment it is made (fail open for the author);
  the partner `PROCESSING` until it has `PASSED` (fail closed for delivery).
- **An outage is retried for as long as it lasts and costs nothing.** A moderator, list or storage
  that cannot be reached throws `ModeratorUnavailableException` (or the storage's own); the
  delivery rolls back and the outbox backs off, 2 s doubling to 15 minutes, with no last attempt.
  D2's attempt-cap rule is kept in its intent: only what the asset itself could cause counts. Here
  that is a moderator's refusal of the bytes (`InvalidImageFormatException`, `ImageTooLarge`,
  `InvalidParameter`) or the JPEG not being made from our own WebP, and those hold the asset back
  for a person (`QUARANTINED`, reason `UNMODERATABLE`), never `FAILED` and never passed. **A
  conversion that runs out of time, or a program that cannot be started, is the machine's, not the
  file's** (review of #113): `ConversionUnavailableException` or the start's own exception is
  thrown and retried like an outage. Only libvips reading the file and refusing it is a refusal. No
  cap is needed: the one step that reads our own bytes runs in the sandbox under its time limit,
  and an HTTP call cannot kill the process.
- **No lock on the asset while the vendor is asked**, as `media.process` holds none while it
  decodes. The verdict is recorded under the asset's row lock afterwards, only on an asset still
  `READY` with no verdict: one deleted, erased or released meanwhile gets none (`ModerateMediaTest`
  deletes the photo from another thread inside the moderator's call; with the asset's row locked
  it would wait).
- **But the delivery's own row lock and a pooled connection are held throughout**, as for every
  consumer (ADR-0035): the handler runs in the delivery's transaction. (An earlier draft said "no
  lock" without that qualification; the review of #113 corrected it.) So the whole of one
  moderation is bounded, well inside the delivery's 60 s: the fetch of the display variant (the
  storage's 3 s per call), the conversion (10 s, `VipsImageProcessor.MODERATION_CONVERSION_TIMEOUT`,
  below a photo's 40 s) and Rekognition's call (15 s, retries included; 7 s an attempt): **28 s**,
  held by `ModerationCopyTest`. A known-image vendor, when wired, takes its bound from the 32 s
  left. One delivery at a time per process (ADR-0050's known limit): a slow vendor delays the
  worker's other deliveries by up to that bound per photo.

**2. The verdict is one pure rule** (`ModerationPolicy`), over findings in this system's words
(`CSAM`, `EXTREME_VIOLENCE`, `ADULT_NUDITY`), never a vendor's label:

| Found | Verdict | `sensitive` |
|---|---|---|
| a known-image list matched | `BLOCKED` (reason `KNOWN_IMAGE`); the classifier is not asked | as found |
| CSAM ≥ 50 | `BLOCKED` (`CSAM_CLASSIFIER`) | as found |
| extreme violence ≥ 80 | `QUARANTINED` (`EXTREME_VIOLENCE`) | as found |
| anything else | `PASSED` | adult nudity ≥ 50 |
| the moderator refused the bytes | `QUARANTINED` (`UNMODERATABLE`) | false |

CSAM low because a wrong block costs one photo and a review; violence high because Q16 holds back
only what is extreme and a quarantine hides a person's photo from their partner; nudity low because
a blur too many costs a tap. Each threshold is tested on both sides of its edge.

**Rekognition** (`RekognitionImageModerator`, the one class that knows it; `ArchitectureTest` now
holds each AWS service to its one class) is asked with `MinConfidence` 50, its default, on the
photo's **display variant** (1280 px, ≤150 KiB; spec: "never ships the original, never the full
variant"), made into a JPEG in the sandbox because Rekognition reads JPEG and PNG, not WebP. It
sends the bytes and nothing else. Labels switched on, from moderation labels version 7:

- `ADULT_NUDITY`: the top-level `Explicit` and everything under it. **Not** the non-explicit
  nudity labels (bare back, partly exposed buttock, kissing): nothing there needs a blur.
- `EXTREME_VIOLENCE`: `Weapon Violence`, `Physical Violence`, `Blood & Gore`, `Corpses`, and
  `Graphic Violence` when it comes back with none of its five kinds. **Not** `Self-Harm` or
  `Explosions and Blasts` alone (neither is "real violence, gore"; a quarantine would hide a
  self-harm photo from the one person it may have been sent to, which is the owner's call, open
  question 4), not `Weapons` alone (a kitchen knife), nor drugs, alcohol, gestures, gambling, hate
  symbols, swimwear.
- `CSAM`: **nothing. Rekognition has no CSAM detection and no age signal**; AWS says so ("the image
  and video moderation APIs don't detect whether an image includes illegal content, such as CSAM").
  Q16's "plus the classifier" is not met by the vendor Q1 ruled (open question 1).

Throttling, provisioned throughput, Rekognition's 5xx, a timeout, a network failure and anything
else not the bytes' fault (a refused credential, a missing permission) are outages: a
misconfiguration holds photos back visibly (the outbox's dead-letter gauge) rather than
quarantining every one. Logged by class and status, never AWS's message. `moderation_scores`
keeps who looked, our categories with their confidences, the reason and **the vendor's
`ModerationModelVersion`**; never a vendor label.

**Taxonomy drift fails closed** (review of #113). The labels read are version 7's. An answer from
any other major version (or none) is an outage: retried, logged at ERROR, and visible in the
outbox's failing deliveries, never read as version 7, where a renamed label would pass what it no
longer names. Changing the mapping and `TAXONOMY_MAJOR` together is how a new version is taken.

**The region and the endpoint are pinned** (review of #113): `eu-central-1` only, a property that
names another region is refused at start; an endpoint override is accepted only on a loopback
address (a test's stand-in server), any other host refused at start; `config/deploy.yml` names
neither (`ProductionStartsWithoutMediaSecretsTest`).

Price, verified 2026-10-29 from the AWS price list (offer file of 2026-09-11, eu-central-1,
`EUC1-Group2-ImagesProcessed`): **$0.0012 an image** for the first million a month (the pricing
page's $0.0010 is us-east-1's), so **about $2.40 a month at 2,000 photos**; 1,000 a month free for
the first 12 months on an older account. Q1's "~$2" holds.

**3. The known-image list is a port with no vendor yet** (`KnownImageMatcher`, `none` by default and
in production; a `stand-in` for local runs). Asked before the classifier; a match is `BLOCKED`. It
is given a `KnownImageCandidate` (review of #113): **the display JPEG**, which a perceptual matcher
reads (PhotoDNA and Arachnid Shield's perceptual hashes survive the resize and re-encode), and **the
SHA-256 of the bytes the phone uploaded**, declared, signed and verified before the original was
deleted and kept on the row, which an exact list matches if it carries SHA-256. A list that carries
only MD5 or SHA-1 of known files needs those of the original, which are not computed or kept today:
`media.process` would compute them as it fetches the upload (it already reads every byte through a
digest) and keep them, in the same change that wires such a vendor. No perceptual hash is computed
here: PDQ or PhotoDNA hashing needs a vendor's library or service. What each candidate needs:
PhotoDNA, the image (its service hashes it); Arachnid Shield, the image or its hashes (SHA-1, MD5,
PhotoDNA); Safer, its own client-side hashes (PDQ, MD5) from its SDK; IWF, MD5/PhotoDNA of the
original. The realistic options for a small EU operator, researched 2026-10-29, none signed up to:

| Option | Fits Moyi? | Cost | What the owner must do |
|---|---|---|---|
| **Cloudflare CSAM Scanning Tool** | **No.** It scans what a zone's cache serves; Moyi's media is served only by presigned URLs on R2's S3 endpoint, which never pass a zone (spec D-8). It gives no verdict a pipeline can act on, only an email and a block | free | nothing it could do for this |
| **Microsoft PhotoDNA Cloud Service** | Yes: a REST API matching an image against hash sets; images hashed, not retained | free to qualified organisations | apply (myphotodna.microsoftmoderator.com); third-party vetting at Microsoft's discretion; runs on Azure, region not published (assume data leaves the EU) |
| **Arachnid Shield** (Canadian Centre for Child Protection) | Yes: an API against Project Arachnid's lists (exact, perceptual and PhotoDNA-type hashes) | free ("no cost tool for industry") | register for an API key (reportedly reviewed); hosted in Canada |
| **Thorn Safer** (Match, and Predict, a classifier for new material) | Yes, and the only one that would also meet Q16's classifier half | not published; contract-sized | sales contact |
| IWF hash list | a list to match ourselves, not an API | membership, quote-only (a secondary source gives £1k–£78k a year) | membership |

**Recommendation: Arachnid Shield first, PhotoDNA as the second application** (both free; apply to
both, wire whichever answers). Safer if the owner wants Q16's classifier half met and accepts a
contract. Either is one class behind `KnownImageMatcher`.

**4. What each person is shown.** Q10 and Q16, in `MediaAsset.shownTo` / `heardBy`, the one place:

- **`PASSED`**: `READY` to both, with `sensitive` (true only when it passed with nudity found).
- **`QUARANTINED`**: `NOT_SHARED` to its owner (the author on an entry), **still with its URL**
  (Q10's recommendation, which the owner took: "the author still sees their own photo, with a
  neutral marker"); `PROCESSING` to the partner, for ever.
- **`BLOCKED`**: `NOT_SHARED` to its owner **without** a URL; `PROCESSING` to the partner. Q10
  predates Q16; serving child abuse material back to its uploader would be serving it. That the
  URL is missing tells the author something Q10's "no reason" did not foresee; the author knows
  what they uploaded, and the partner is told nothing (open question 2).

On the wire (a breaking change, `breaking-api-change`): `EntryImageResponse` and
`EntryVoiceResponse` gain `status` `NOT_SHARED` and **`sensitive: boolean`**, always sent, false
unless `READY`; `GET /media/{id}` gains `sensitive` and shows `QUARANTINED` as its lifecycle status.
The client blurs a `sensitive` photo behind "tap to view" (Q16). `status` is the asset's own
(`media_assets.status`): `QUARANTINED` covers both held-back verdicts, as doc 04's lifecycle has it.

**5. Voice notes are moderated by a port with no vendor, and the owner must choose one.**
`VoiceModerator` (`none` by default and in production: no verdict, no partner hears one, as since
D3; a `stand-in` that passes everything, for local runs) on the kept Ogg Opus. Every option needs
input Q1 did not give:

| Option | Cost at 2,000 notes of ~30 s | What leaves the box | Languages | Catch |
|---|---|---|---|---|
| AWS Transcribe (batch, eu-central-1) + Comprehend `DetectToxicContent` | ~$6 + ~$0.70 a month | the audio to AWS Frankfurt (batch needs it in an S3 bucket there first), the transcript to **Ireland** (toxicity is not offered in Frankfurt) | transcription 100+; **toxicity English only** | a second vendor service and an S3 bucket; Transcribe's own toxicity is en-US and batch only |
| Transcribe streaming instead of batch | ~$10 + ~$0.70 | the same, without S3 | the same | HTTP/2 client in the JVM |
| whisper.cpp in the worker + a text classifier | $0 | nothing | ~99 to transcribe | no classifier exists for the text that is as good; CPU and ~300-400 MB on a box with none spare (spec R-A); not measured |
| Hive audio moderation | ~$30 at its old list price, now "contact sales" | the audio, region not stated | 10 | a vendor not in Q1 |

And none of them finds what Q16 is about in speech: there is no CSAM in audio for a hash list, and
"extreme violence" in a voice note is a threat, which a toxicity score is a poor judge of. **This
slice does not choose**: the port, the stand-in, the consumer and its tests are built (a voice note's
`PASSED`, `QUARANTINED` and outage paths are tested with a fake), and the owner rules (open question
3). Until then production's partners hear no voice note, which is where D3 left them.

**6. Quarantine without a console.** The operator's commands are database functions (V36),
so the rules are written once: `media_quarantine_list`, `_release`, `_delete`, `_hold`, `_block`, each writing its own
row in **`media_moderation_log`**, the table the Phase 5 console will read (every verdict
moderation gives is written there too, by `moderation:<provider>`). `scripts/moyi media quarantine
list|release|delete|hold|block` names which to run, through `docker exec -i moyi-postgres psql` on the server
(or `MOYI_OPS_PSQL`), with `MOYI_OPERATOR` naming who acts and a note required on every decision.
The spec's **[inferred]** item, "`scripts/moyi` can carry this, as it does other operator
commands", **did not hold**: `scripts/moyi` was a member's HTTP client with no operator command.
The quarantine command is its first, and the one that talks to the database (its header says so).

- **Release**: `QUARANTINED` (not blocked) → `READY`, `PASSED`; the partner is shown it next read.
- **Delete**: → `DELETED`, and `MediaReleased` published in the function's own transaction (the
  outbox's two inserts, at READ COMMITTED, as `JdbcEventPublisher` makes them;
  `MediaQuarantineCommandsTest` runs the real `media.purge` against it), so the objects go as every
  deleted asset's do (ADR-0050).
- **Hold and block** (review of #113): the runbook's own steps for a member's report are functions
  too, `media_quarantine_hold` (a `READY` asset held back, reason `REPORTED`) and
  `media_quarantine_block` (made `BLOCKED` and held as evidence, whether ready, held back, or
  deleted to its owner with its objects not yet purged), so a hand-written statement cannot drift
  from the constraints. `moyi media quarantine hold|block`.
- **The list** prints the function's seven columns in its order, **verdict first**, under a header
  that names them, and a `BLOCKED` row ends `DO NOT VIEW — runbook §2` (review of #113: a blocked
  row once read as `QUARANTINED`, the kind the runbook asks the operator to look at).
- **`BLOCKED` is final, and its evidence held, in the database**, whoever writes the row
  (`BEFORE INSERT OR UPDATE` and `BEFORE DELETE` triggers; the review of #113's four attacks, each
  with a test that went red without its guard):
  1. a verdict that becomes `BLOCKED`, by update or insert, sets `evidence_held` and the status
     `QUARANTINED` (or keeps `DELETED`), whatever the statement said;
  2. the verdict is never taken off, the status is only `QUARANTINED` or `DELETED`, and a deleted
     blocked asset is never brought back;
  3. `evidence_held` is cleared only inside `media_quarantine_delete`, which turns a
     transaction-local setting (`moyi.evidence_release`) on for its own statement and off again.
     Someone at psql who sets it by hand can clear a hold, as one who can drop the trigger can:
     these stop a mistake or a script, not the database's owner;
  4. a held row is never removed.
  The release function refuses `BLOCKED` and records `RELEASE_REFUSED`.

**7. Child abuse material is kept as evidence** (`evidence_held`, set with `BLOCKED`). Whoever
deletes it (its owner's `DELETE`, an erasure, the reaper) it is `DELETED` to them, as anything
deleted is; but `media.purge` and the owner's best-effort delete leave its objects, until the
operator, after the report the runbook requires, runs `delete`, which clears the hold and publishes
`MediaReleased` again. `ops/monitoring/media-check.sh` fails at once while anything is held, and no
longer counts a held asset as "unpurged". A demotion's purge leaves a held photo's full variant too
(review of #113: a photo blocked after its demotion was published).

**8. The rest of the lifecycle.** An unattached `QUARANTINED` asset is reaped a day after the latest
of its creation, readiness and detachment, like a `READY` one (ADR-0050's owed question). A
held-back asset cannot be claimed by an entry (the claim takes `READY` only, FR-044a); its owner's
`GET /media/{id}` says `QUARANTINED`, so a client can say it will not be shared before it is sent.
**Demotion takes only what has `PASSED`** (review of #113), not every `READY` photo. Demotion
deletes the full variant for good; a photo not yet moderated (a backlog while moderation is `none`,
or a vendor outage of days) and later found to be child abuse material would have lost evidence.
Of the two rules the review offered, "skip unless `PASSED` or moderation is `none`" and "only
`PASSED`", this takes the second: one condition in the one statement that demotes, read from the
row, with no configuration for the job to consult, and the cost of it is only that full variants
wait (90 days and more) while nothing moderates, which in production means nothing is shown to a
partner either. A quarantined photo keeps its full variant until it is released (then demoted on
its next night) or deleted. A tier-2 photo is moderated on the display variant it still has.

**Assets made `READY` before D4** have no `MediaReady`, and would never be moderated. V36 inserts
one for each `READY` asset with no verdict that has none (the moderation consumers subscribe from
the beginning, so they are handed them). Production has none; the statement makes it correct where
some exist, and adds nothing when run again (`MediaReadyBackfillTest` runs it twice).

**The media check** also alarms, once moderation is on (`MEDIA_MODERATION_ON=1`), on anything
`READY` without a verdict for 30 minutes (FR-101, doc 11's pipeline bound), and on anything held
back for the operator for more than `QUARANTINE_REVIEW_DAYS` (3 by default).

**9. The takedown runbook** is `ops/moderation/takedown.md` (doc 09:32's "documented takedown and
law-enforcement procedure"): reviewing the queue, the blocked path (do not view or copy; note what
the system holds; report to the BKA through its DSA portal, Art. 18 DSA; preserve; delete with the
report's reference), a member's report, a law-enforcement request. Every legal step is marked
**[legal: confirm]**: nothing in it is legal advice.

**10. The metric** is `gratitude_media_quarantined_total{kind,verdict}` (doc 11:50), `QUARANTINED`
and `BLOCKED` both counted.

**11. Production starts with no AWS credentials.** `moyi.media.moderation.image.provider` defaults to
`REKOGNITION`, which refuses to start without both halves of its key and names `none` as the way
out (ADR-0017 decision 3, #96's lesson). `config/deploy.yml` names `none` for photos, and for the
known-image list and voice too (their default, said so the file says what runs), and
`ProductionStartsWithoutMediaSecretsTest` holds both that and that no `stand-in` is ever named
there. The jar was started with `config/deploy.yml`'s environment and no AWS credentials and
started; without the line it refused (the pull request records both runs).

## Consequences

**Positive.** A partner is shown a photo for the first time, and only one moderation passed. An
outage delays, never fails or exposes. What is held back is reviewable, every decision recorded
with who made it, and child abuse material can be neither released nor destroyed before it is
reported. The vendor is one class; the rule is one function.

**Negative.** No CSAM is detected automatically until a hash list is wired (Rekognition has no
classifier for it). No voice note reaches a partner until the owner chooses how they are
moderated. Photos leave the box for AWS Frankfurt (the display variant only), and AWS may keep them
for service improvement unless the account opts out (owed, below). A quarantined photo keeps its full
variant past 90 days. The operator's commands are SQL functions: a migration changes them.

**Neutral.** V36: `sensitive`, `moderated_at`, `evidence_held`, constraints, the trigger, the audit
table, three functions. No new error code. Two new consumers and one new event. The AWS SDK gains
`rekognition` over the URLConnection transport already used for S3.

## Alternatives considered

- **Moderating inside `media.process`.** A vendor outage after the decode would spend decode
  attempts, so an outage could fail a photo. Rejected for decision 1.
- **A quarantine as a verdict only, the status left `READY`.** Simpler queries; but doc 04's
  lifecycle has `QUARANTINED` as a status, ADR-0050 expected it, and a `READY` status on held-back
  media is one condition away from being shown.
- **Holding blocked assets by refusing their deletion.** The owner's `DELETE` would have to lie or
  say why. Gone to them, held for the report, is both true and safe.
- **Inferring CSAM from nudity plus Rekognition's face age.** Wrong both ways on adults who look
  young and children who do not, and it would make an 18+ product's intimate photos "never
  releasable". Not Q16's classifier.
- **An actuator endpoint for the operator.** The management port is reachable from nowhere the
  operator is, and the image has no curl to reach it from inside.
- **Choosing Transcribe for voice.** Not in Q1, English-only toxicity outside Frankfurt; decision 5.

## Open questions (the owner's)

1. **CSAM classifier.** Rekognition has none. Accept hash matching alone (once wired), or add
   Thorn Safer (Match and Predict), which needs a contract?
2. **The blocked author's view, a legal question as much as a product one.** `NOT_SHARED` without
   the photo (and `GET /media/{id}` with no URL) tells the author it was blocked rather than held for
   review, which sits against the runbook's "do not tell the uploader" (tipping off). Kept as built
   on the coordinator's ruling of 2026-10-29; the runbook says the API distinguishes the two. For
   the owner, with legal advice, beside question 5: may the API tell an uploader that their upload
   was blocked, or must every held-back asset look the same to its author (Q10's "the author still
   sees their own photo" narrowed)?
3. **Voice moderation** (decision 5): which option, or none (accept that voice notes are not
   automatically moderated, and pass them), and the cost and data path that comes with it.
4. **Self-harm images**: quarantine them (Rekognition's `Self-Harm`) or let them through?
5. **The legal basis for scanning** (the research behind this ADR, not checked by a lawyer): a
   private two-person messaging app is plausibly a "number-independent interpersonal communications
   service"; scanning its content is then confidential-communications processing under ePrivacy
   (§3 TDDDG in Germany), lawful for CSAM only within Regulation (EU) 2026/1881 (which replaced the
   lapsed 2021/1232 in July 2026) and its conditions: a DPIA, the least intrusive technology,
   reporting. Whether general nudity and violence classification of private content is lawful at
   all is the same question. Needs a ruling, with advice, **before moderation is switched on in
   production**.
6. **The report route** (FR-100, `POST /reports`): doc 26:206 routes a reported photo or voice note
   to this path, but no report route exists (ADR-0036 question 5: "a later phase's"), its reason
   values (OQ-26b) and rate limit (OQ-26c) are open, and its queue is Phase 5's admin console (doc
   15:117). Not built here; until it is, reports reach the support address and the runbook's
   section 3. Build it before launch, or accept the email path? *(Amended 2026-10-30: built,
   ADR-0053: `POST /entries/{entryId}/report`, its reasons, ten a day, and the queue as functions
   beside these. The hold now records what it held an asset back from (V38).)*

## Owed

- **The owner, before Rekognition runs:** an AWS account; an AWS Organization with an **AI services
  opt-out policy** covering Rekognition (and Transcribe/Comprehend if chosen), so photos are not
  kept for AWS's model training; an IAM user whose only policy is
  `{"Effect":"Allow","Action":"rekognition:DetectModerationLabels","Resource":"*"}` (the action takes
  no resource ARN without a custom adapter); its access key in Bitwarden as
  `MOYI_MEDIA_MODERATION_REKOGNITION_ACCESS_KEY_ID` and `_SECRET_ACCESS_KEY`; those two as secrets
  in `config/deploy.yml` and `.kamal/secrets`, and the `MOYI_MEDIA_MODERATION_IMAGE_PROVIDER: none`
  line removed, **on the media worker**, which is where moderation runs. A billing alarm on the
  account.
- **The owner:** apply to Arachnid Shield and PhotoDNA (decision 3); then a `KnownImageMatcher`
  class for whichever answers. BKA DSA-portal access. The legal check of open question 5 and every
  **[legal: confirm]** in the runbook (doc 20:22-23).
- **The client (moyi-app):** `NOT_SHARED` for the author ("not shared", no reason), `sensitive`
  blurred behind "tap to view", the honest help text Q16 asks for.
- **Voice moderation**, once ruled (open question 3).
- **The report route** (open question 6): built, ADR-0053.
- **R2 and Rekognition, not run:** no real photo has been sent to Rekognition; the adapter is
  tested against a server speaking its wire protocol. One real call when the account exists.

## Revisit when

A hash list is wired. The owner rules on voice. Rekognition's taxonomy moves past version 7 (the
label names are read). The Phase 5 console replaces the script (it reads `media_moderation_log`,
and should call the same functions).
