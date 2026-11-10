# ADR-0060 — Voice notes are also kept as AAC-LC in MP4, so that iOS can stream them

**Status:** Accepted · **Date:** 2026-11-10 · **Deciders:** the lead of the 2026-11-10 batch, for
issue #152 (the format, the bitrate, the field name and "no backfill" were ruled; decisions 2, 3, 5
and 6 were made in building it, and are **the owner's to overrule**: Open questions, below)

## Context

ADR-0049 keeps one copy of a voice note: Ogg Opus, mono, 24 kbit/s (`v/{uuid}/voice.ogg`), and
`EntryVoiceResponse.url` is a signed GET of it. iOS's AVFoundation cannot open Ogg at all; it
decodes Opus only from CAF. The client (moyi-app PR #48, its `docs/backend-contract-gaps.md` row
30) works around it by fetching the whole file into memory and moving its packets into CAF before
`AVAudioPlayer` can start, so on a slow connection nothing plays until everything has arrived.
Android plays the Ogg as served.

ADR-0049's Owed list already foresaw this: "If iOS will not play it, Q4 says AAC-LC 48 kbit/s for
both; the container may be the cause rather than the codec (CAF holds Opus on iOS)". FR-043 says a
voice note is "transcoded server-side to Opus/AAC".

The lead ruled (2026-11-10): the media worker **also** produces AAC-LC in MP4 (`.m4a`), mono, about
32 kbit/s, with `+faststart`; Ogg Opus stays the primary copy; the response gains `urlAac`, given
under exactly the rule and expiry of `url`; no backfill. AAC in MP4 is the one format AVPlayer
streams on every iOS version the app supports; Opus in CAF is not progressively streamed by
AVPlayer as reliably, and MP4 needs its `moov` index before the audio for AVPlayer to start before
the end has arrived.

## Decision

1. **A second copy, AAC-LC in MP4, beside the Opus.** ffmpeg's own encoder (`aac`, built into
   every ffmpeg; never `libfdk_aac`, which Ubuntu does not ship and whose licence forbids
   redistributing it), `-profile:a aac_low`, mono, `-b:a 32000`, `-movflags +faststart` (the
   `moov` atom is moved before `mdat`), `-f mp4`. Kept at `v/{uuid}/voice.m4a`
   (`StorageKey.forVoiceAac`), derived from the upload's key exactly as `voice.ogg` is, and served
   as `audio/mp4` (`VoiceRules.AAC_CONTENT_TYPE`). The Ogg Opus is unchanged: `url` still reaches
   it, Android still plays it, and it is still the copy moderated and exported (decisions 4, 5).
   Measured on ffmpeg 9.0.2: a 61 s tone is 259 KB of AAC (34 kbit/s) beside 287 KB of Opus.

2. **Made from the kept Opus, never from the upload.** The AAC step reads `voice.ogg` from the
   work directory, through the same named demuxer and decoder the result is read back with
   (`Reading.KEPT`: Ogg, Opus only), after that Opus has been probed and accepted. So the hostile
   upload is decoded once, and **the duration limit runs once**: the note is the one already
   judged, and the AAC step applies no limit of its own. It checks only that what it wrote is one
   channel of AAC whose length is the Opus's within `VoiceRules.AAC_SLACK_MS` (200 ms; measured
   6.5 ms shorter on 9.0.2, at 2 s and at 61 s): `VoiceRules.isTheSameNote`. The cost is a
   second lossy generation (Opus 24 kbit/s, then AAC 32 kbit/s); for speech that is inaudible
   next to the first, and the alternative (both from the upload) would decode the hostile bytes
   twice and let the two copies' lengths, and contents, diverge. The step shares the note's one
   `Sandbox.Deadline`, its `-fs` byte cap, `-map_metadata -1`, `-map_chapters -1` and the
   `+bitexact` flags, so the m4a carries no tag of the upload and no version of this server's
   build, as the Ogg does not.

3. **Best effort: a variant that cannot be made never refuses the note.** If the encode fails, runs
   out of the deadline, exceeds the cap, or is not the same note, **or the storage cannot take it**
   (an `ObjectStoreUnavailableException` on its PUT, after the Opus's succeeded: review of #182), the
   note is `READY` with its Opus alone in that same delivery, not retried, `aac_key` stays null, and
   `urlAac` is null; the log says so (a WARN for the storage, `Opus only` on the ready line; the id,
   never a key). Whatever part of a failed PUT reached the storage is at the derived key, so the
   note's deletion takes it (decision 4). An ffmpeg without the `aac` encoder would make every note so, silently, so the worker
   refuses to start without it (`MediaProcessingConfiguration`, beside the `libopus` check), the
   image's build checks it (`app/Dockerfile`), and CI checks the built image on both architectures.

4. **Recorded on the row, deleted by derivation.** `media_assets.aac_key` (V59) is written by
   `markVoiceReady` in the same statement that makes the note `READY`, and only once the object is
   stored; a `CHECK` keeps it to voice notes. It is what is **played**. What is **deleted** does not
   read it: `MediaAsset.objectKeys` now names the upload, `voice.ogg` and `voice.m4a` for every
   voice note, recorded or not, so an attempt that wrote the m4a and then failed leaves nothing a
   deletion cannot find (the reason ADR-0047 derived every key). Every place that deletes or
   copies a voice object, each with the test that holds it:

   | Path | Mechanism | Test |
   |---|---|---|
   | The owner's `DELETE /media/{id}` | `OwnMedia.delete`: `objectKeys`, best effort, then `media.purge` | `ProcessUploadsTest` "a ready voice note its owner deletes leaves nothing" |
   | An erasure (withdrawal, edit, block) | `releaseAllOf` then `MediaReleased` to `media.purge` (`objectKeys`) | `MediaPurgeTest` "an erased entry's voice note is gone … its Opus and its AAC" |
   | The reaper (unattached for a day) | `reap` then `MediaReleased` to `media.purge` | `MediaPurgeTest` "a voice note on no entry that the reaper deletes …" |
   | Account deletion (ADR-0058) | `releaseAllOwnedBy` then `media.purge` | `MediaAccountDeletionTest` "every asset of the person goes …" |
   | The operator's `media_quarantine_delete`, and a report's takedown and let-go (V36, V38), which use it or publish the same event | `MediaReleased` to `media.purge` | `MediaQuarantineCommandsTest` "delete takes a held voice note's upload, Opus and AAC …" |
   | A note deleted while it was transcoded | `ProcessUploads.decide`: the Opus and the stored m4a, best effort, then `media.purge` | `ProcessingInterleavingTest` "a voice note deleted while it is transcoded …" |
   | Five attempts that never finished | `ProcessUploads.giveUp`: `objectKeys` | via `objectKeys` (`MediaAssetTest`) |
   | The nightly mirror (ADR-0050, Q15) | copies everything under `v/`; nothing names the m4a | `ops/media-mirror/test-mirror.sh`, whose live key K2 is now a `voice.m4a` |
   | A restore from the mirror | the runbook's SQL lists every `v/` key of a deleted asset | `ops/media-mirror/README.md` (it listed only `.webp` keys, so missed `voice.ogg` too: fixed) |
   | The data export (ADR-0059) | copies the Opus only (decision 5) | `ExportStorageTest` "a voice note with an AAC variant is exported as its Opus alone" |

   Evidence-held and report-held notes keep their objects as before (`keepsObjects`): the m4a is
   one of them and stays with the Ogg.

5. **Moderation, the length and the export see the Opus alone.** `MediaReady` is published once,
   when the note becomes `READY` with both copies; `media.moderate-voice` reads
   `MediaAsset.moderatedKey`, still `voice.ogg`, and gives one verdict, which governs both URLs.
   `durationMs` is the Opus's. The export puts one copy of the note in the archive, the Ogg Opus:
   the m4a is the same sound in a second container, made by this server, and FR-094's "original
   media" is not served by a second derivative.

6. **The API: `urlAac` beside `url`, by `url`'s rule.** `MediaAsset.heardBy` is still the one
   place the rule is written; its `Heard.Ready` and `Heard.NotShared` carry the AAC key beside the
   Opus's, so the partner gets `urlAac` exactly when they get `url` (once revealed and `PASSED`), a
   held-back note gives it to its author (`NOT_SHARED`), and a blocked one gives it to nobody.
   `PlayVoices` signs both at the same instant (`HourlyUrls`), so they expire together. It is null
   whenever `url` is, and for a note with no variant. `toString` redacts it, as `url`. Not added to
   `GET /media/{id}` (`MediaResponse`): the issue asked for the entry's voice note, and the author
   previewing a just-recorded note has the recording on the phone (Open questions).

7. **No backfill.** A note processed before this has `aac_key` null and `urlAac` null, and the iOS
   client keeps its in-memory repackaging for exactly those. A backfill would be a new pipeline
   consumer that re-reads each Opus from R2, transcodes it in the worker, and stores it, with its
   own attempt accounting and its own interleaving with deletion (the reasons `ProcessUploads` is
   as careful as it is), for the voice notes made since the worker went live on 2026-11-05, which
   are the owner's test accounts, owed a wipe. Proposed as a follow-up only if real users' notes
   predate this deploy.

## Consequences

- iOS can stream a voice note: `urlAac` is an `.m4a` whose index comes first. The client can drop
  `OggOpus.toCaf` for any note that has one, and keep it for those that do not.
- Each voice note keeps two objects; for speech the m4a is about 1.3 times the Opus (32 against 24
  kbit/s), so a voice note's kept bytes about double. Doc 06:186's "one voice note ≤500 KB after
  transcoding" is now held **per copy** (`-fs` and the check use `VoiceRules.MAX_KEPT_BYTES` for
  each), so a 61 s note may keep up to about 0.6 MB in all (measured 546 KB for a 61 s tone, the
  worst case Opus's constrained VBR makes). Photos dominate the media budget, so this is small, but
  it is the owner's to confirm (Open questions).
- The worker spends one more ffmpeg run per note, on at most 61 s of Opus, inside the same deadline.
- V59 is claimed while V54 to V58 belong to parallel pull requests (#176's V57 among them). Flyway
  runs without `outOfOrder`, so **V59 must not reach production before every lower version that
  will ever exist has**: merge this after them, or renumber before merging.

## Alternatives considered

- **Opus in CAF** (the issue's first suggestion): the same Opus packets, no second encode. Ruled
  out: AVPlayer does not reliably stream CAF progressively, which is what the issue is about.
- **Opus in MP4** (`.m4a` with Opus): AVPlayer's support depends on the iOS version; AAC does not.
- **AAC only, at 48 kbit/s, for both platforms** (ADR-0049's Q4 fallback): one copy, but every
  Android note grows by half, and the moderated, exported copy changes format under notes already
  kept. The ruling keeps Opus as the primary.
- **The AAC made from the upload**: decodes hostile bytes twice, and two transcodes of one upload
  can disagree on its length near the limit; decision 2.
- **A variant that fails the note**: an iOS-only convenience would then cost Android its note.

## Open questions (the owner's)

1. The bitrate: the lead ruled about 32 kbit/s; ADR-0049's Q4 fallback named 48 kbit/s for AAC.
2. Doc 06:186's 500 KB per voice note, now per copy (Consequences).
3. Whether `GET /media/{id}` should carry `urlAac` too (decision 6).
4. A backfill, if real users' notes predate this (decision 7).

## Revisit when

The iPhone playback test (ADR-0049 Owed) is run against `urlAac`, and against `url` through CAF.
AVPlayer is seen to stream Opus in CAF or MP4 on every supported iOS version (then one copy may
suffice). ffmpeg's native AAC encoder changes its defaults. The storage budget for media is
revisited.
