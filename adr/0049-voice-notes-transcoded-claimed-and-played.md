# ADR-0049 — Voice notes: transcoded to Opus by ffmpeg in the sandbox, claimed by an entry in its own transaction, and played to their owner

**Status:** Accepted · **Date:** 2026-10-29 · **Deciders:** Daniel

## Context

Phase 4 slice D3 (spec `2026-10-27-phase-4-media-and-notifications-design.md` §4 D3, §7) does for
a voice note what D2 (ADR-0048) did for a photo: `media.process` turns an upload D1 completed
(ADR-0047) into what is kept of it, an entry claims it (FR-044a), and the entry's shape carries it.
ADR-0047 owed D3 the check of what Android's recorder writes; ADR-0048 owed it the voice half of
`media.process`, `AttachableKind.VOICE` and the end of `MEDIA_NOT_YET_SUPPORTED`.

What had to be decided:

- **What a voice note may arrive as** (FR-043 names no input format; spec §4 D3 lists the formats
  "both platforms record").
- **How ffmpeg, a decoder of everything, is held to reading only that**, on bytes a user controls
  (T-07, spec §6 R-E).
- **What is kept** (owner's rulings Q3, 60 seconds; Q4, Opus, mono, 24 kbps).
- **Who hears it**: the spec marks "voice is not moderated" **[inferred]**.
- **How much of D2's machinery is shared**, and what of it had to change to be shared.

## Decision

**1. One switch for both kinds.** `moyi.media.processing.provider` is `NATIVE` or `OFF`. `NATIVE`
(named `VIPS` in D2; it now starts two programs) runs libvips for photos and ffmpeg for voice
notes, each in the [`Sandbox`](../modules/media/src/main/kotlin/com/moyi/media/infra/processing/Sandbox.kt)
of ADR-0048, and **refuses to start** unless `ffmpeg` and `ffprobe` are found and, run through the
sandbox, `ffmpeg -version` succeeds and `ffmpeg -h encoder=libopus` names the encoder Q4 needs (an
ffmpeg built without it would fail every voice note). `OFF` turns off both, and `media.process`
then subscribes to nothing, as in ADR-0048: production's `config/deploy.yml` already names
`"off"`, and that line now covers voice notes too. One switch because the worker the owner ruled
for (Q5) carries both programs and the API container neither; a process that decoded photos and
not voice notes would be a third shape nobody asked for. `allow-unlimited-memory`, the macOS
waiver, moves up a level beside the provider: whether this OS may run a decoder unlimited is one
decision, not one per program. **Production starts without ffmpeg**: the jar was started with
`config/deploy.yml`'s environment, no media secrets and no ffmpeg or libvips on `PATH`, and
started; with the line removed it refused to start (the pull request records both runs).

**2. Seven containers, each known by its first bytes** (`VoiceFormat`), and the one declared. The
intent's list (`MediaKind.VOICE`) is now this enum's, so the intent accepts exactly what the
pipeline can read:

| Container | Declared as | First bytes | Who writes it |
|---|---|---|---|
| MPEG-4 audio | `audio/mp4`, `audio/x-m4a` | `ftyp` with major brand `M4A `, `mp42`, `mp41` or `isom` | iOS `AVAudioRecorder` (`M4A `); Android `OutputFormat.MPEG_4` (`mp42`) |
| 3GPP | `audio/3gpp` | `ftyp` with `3gp4`, `3gp5` or `3gp6` | Android `OutputFormat.THREE_GPP` (`3gp4`), its default |
| Ogg | `audio/ogg` | `OggS` | Android `OutputFormat.OGG` (API 29) |
| WebM | `audio/webm` | `1A 45 DF A3` | Android `OutputFormat.WEBM` |
| AMR | `audio/amr` | `#!AMR\n`, `#!AMR-WB\n` | Android `OutputFormat.AMR_NB`, `AMR_WB` |
| AAC in ADTS | `audio/aac` | the ADTS sync, layer bits clear | Android `OutputFormat.AAC_ADTS` |
| WAV | `audio/wav`, `audio/x-wav` | `RIFF····WAVE` | iOS linear PCM |

`audio/3gpp` and `audio/aac` are new (ADR-0047's owed check): they are two of Android's
`MediaRecorder.OutputFormat` values. The brands each platform writes are **[inferred]**: read from
AOSP's `MPEG4Writer` (`mp42`, `3gp4`) and Apple's documentation (`M4A `), not seen from a device;
ffmpeg's own muxers, which the tests use, write `M4A `, `isom` and `3gp4`. `MPEG_2_TS` (API 26) is
not accepted: no voice recorder uses it. ADTS is the weakest magic of the seven (twelve bits),
which is why decision 3 matters most there.

**3. ffmpeg reads hostile bytes, and is held to this** (`FfmpegVoiceProcessor`). Each line has a
test that went red with it removed and green restored (the pull request lists them):

- **One demuxer, named** (`-f`): the container's, from `VoiceFormat`. ffmpeg never chooses by
  probing, so none of its other demuxers (an HLS or concat playlist that opens other files, an
  image sequence) ever reads the upload. Seen: Opus in WebM declared as Ogg, and AAC in MP4
  declared as ADTS, are refused with `-f` and transcoded without it.
- **Named decoders only** (`-codec_whitelist`, on `ffprobe` and `ffmpeg` alike): the codecs that
  container carries from a phone (MP4: `aac`; 3GPP: `aac`, `amrnb`, `amrwb`; Ogg and WebM: `opus`,
  `vorbis`; AMR: `amrnb`, `amrwb`; ADTS: `aac`; WAV: five PCM sample formats). `ffprobe` opens a
  decoder for every stream and fails on one it may not open, so **a file with any other stream (a
  cover picture, a video track, an ADPCM WAV) is refused**, and no image or video decoder ever
  sees it. Seen: with the list removed, an M4A with a PNG cover and an ADPCM WAV were transcoded.
- **Files only** (`-protocol_whitelist file`, and the input passed as a `file:` URL): ffmpeg opens
  no network address and no other protocol. Within the pinned demuxers nothing opens a second
  resource (MP4's external references are off by default), so this is the second line; it is
  tested by handing the processor an `http://` URL to a local server, which received no request
  (with the list removed, it received one). **This is the only network bound in-process**: the
  sandbox clears the environment (so no `http_proxy`) but cannot take the network away;
  the worker container's network limit (spec R-E) is owed with the worker.
- **The length is read before the transcode**: `ffprobe`'s `format.duration`, refused over
  `VoiceRules.LIMIT_MS`, 61 seconds (Q3's 60 and the spec's second of grace), without the
  transcode running. A container can say less than it holds (seen: an MP4 of 90 seconds whose
  `mvhd`/`mdhd` say 10, which `ffprobe` believes), so **the transcode stops one second past the
  limit (`-t 62`) and its result's length is read again**, and a result over 61.1 seconds is
  refused too. The 100 ms of slack (`VoiceRules.ENCODER_SLACK_MS`) is measured, on a tone of exactly
  61.000 s: what Opus and the upload's decoder add is 6.5 ms from WAV, and from AAC 62.5 ms on
  ffmpeg 6.1.1 (the worker's) but 6.5 ms on 9.0.2; an ADTS note's length is an estimate that
  already overstates it (61.04 to 61.09 s), so it is refused at the probe. Seen with `-t` removed:
  the transcode of the 90-second MP4 wrote 90 seconds (review of #111). A length the container cannot
  say (`N/A`) is refused: no recorder writes one.
- **The output is bounded**: `-fs` one byte past 500 KiB (doc 06:186's "≤500 KB after
  transcoding", a KB being 1024 bytes as in ADR-0048), so ffmpeg stops writing there, and a
  result over 500 KiB is refused (`CANNOT_FIT`). A 61-second note is about 190 KiB, so this binds
  only on a transcode gone wrong.
- **Only the first audio stream, and nothing about it** (`-map 0:a:0`, `-map_metadata -1`,
  `-map_chapters -1`): no title, comment, date or recorder tag, no chapter. Seen: without either
  flag, the made-up title or chapter of the test's M4A was in the Ogg. `-map 0:a:0` cannot be
  shown red on its own: decision 3's decoder list refuses every file with a second stream first.
  **No fingerprint of this server's build** (`-fflags +bitexact -flags:a +bitexact`, review of
  #111): the Ogg's vendor string is `ffmpeg` and its encoder tag `Lavc libopus`, with no versions;
  without them they were `Lavf60.16.100` and `Lavc60.31.102`. libopus adds no vendor of its own
  there.
- **What is kept is Opus, one channel, 24 kbit/s** (Q4): `libopus`, constrained VBR (unconstrained,
  a pure tone came out at 39 kbit/s; constrained, 25), `-application voip`, in Ogg (RFC 7845),
  and is read back to be one Opus channel before it is kept.
- **The sandbox's limits** (ADR-0048 decision 3): one deadline for the note's whole processing,
  30 seconds by default (well inside the delivery's minute), past which the program is killed
  (tested with a stand-in `ffmpeg` that sleeps: refused `TIMED_OUT` in about two seconds, the
  sleeper gone); `ulimit -t` as its backstop; `ulimit -v` of **512 MiB**, measured on ffmpeg
  6.1.1 under Ubuntu 24.04: a transcode cannot start its threads at 256 MiB and runs at 384 MiB,
  with about 55 MB resident; no inherited environment; output to files, read back capped.

**4. What is kept, and where.** One file, `v/{uuid}/voice.ogg`, under the upload key's own random
part, derived as a photo's variants are (`StorageKey.forVoice`): nothing records it, a retry
writes the same key, and `MediaAsset.objectKeys` names it, so a deletion (and D5's purge) finds it
whether or not the row says the note was ready. Its content type is `audio/ogg`. `READY` sets
`duration_ms`, the length of what was transcoded (spec D-10: the length is the asset's). **No
migration**: V32 had `duration_ms`. `entries.voice_duration_ms` (V12) is left `null` and unread; it
is dead, and dropping it is a later forward migration (owed), not D3's.

**5. D2's machinery, shared rather than copied.** `media.process` is the same consumer; it
branches on the asset's kind only between "the bytes are fetched and verified" and "decide under
the row lock". What changed in D2's code to share it:

- `MediaAsset.processing` processes a voice note by the same rule (`NOT_THIS_KIND` is gone), and
  the attempt cap, which counts only decodes, counts a voice note's transcode as its decode.
  `startProcessing` and `giveUp` lost their `kind = 'IMAGE'` condition.
- `ImageRefusal` is `MediaRefusal`: the same reasons for both kinds, and one more (`TOO_LONG`).
- The hourly signing of ADR-0048 decision 6 moved from `ShowImages` into `HourlyUrls`, so a voice
  note's URL is signed by the same rule, written once.
- The claim in the entry's transaction, the decision under the row lock, the after-commit delete
  of the upload, and the derived-key clean-up are unchanged and now serve both.

**6. FR-044a for a voice note.** `MediaAttachments.claim(owner, id, AttachableKind.VOICE, entryId)`,
the same conditional update. `POST /entries` claims the voice note after the photo, in the entry's
transaction; `PATCH` claims it under the entry's lock after BR-7, releasing the one it replaces.
One refusal for every cause, `422 VALIDATION_FAILED` on `voiceMediaId`, as `imageMediaId`'s is,
and a refused voice note rolls back a photo claimed before it. **`MEDIA_NOT_YET_SUPPORTED` leaves
`ErrorCode`**: nothing throws it any more, and the contract's enum loses it (a breaking change for
a client that names it).

**7. Who hears it: its owner once it is ready; anyone else only once it has passed moderation.
Voice notes are moderated** (the owner's ruling on FR-061, 2026-10-29). The spec marked "voice is not
moderated" **[inferred]**; read against the corpus, it did not hold, and the owner settled it:

- **FR-061 (03:68)**: "media becomes visible to the non-author when it reaches READY and passes
  moderation (FR-101)". "Media" includes voice notes.
- **R-25 (17:47)**: "harmful or illegal **media** uploaded", mitigated by automated moderation.
- **Doc 26:206** routes a member's report of "a photo or voice note" to "the FR-101 moderation path".
- FR-101 (03:115), T-08 (09:32) and the owner's Q1 name images; they are read as including voice
  notes from now on. D4 moderates voice notes as well as photos; how (transcribe and moderate the
  text, or a vendor that takes audio) is D4's to decide.

So a voice note is shown as a photo is: `MediaAsset.heardBy(owner)` gives **its owner** (on an
entry, its author) the URL and the length once it is `READY`, and **anyone else** `PROCESSING`,
with no URL and no length, until the asset carries the verdict `PASSED`
(`MediaAsset.partnersMayHearIt`, the one place the rule is written). Nothing gives a voice note a
verdict until D4, so until then a partner never hears one: safe by construction, as photos were in
D2. `MediaAssetTest`, `MediaAttachmentsTest` and `EntryVoiceTest` hold the rule, and went red with
it removed (review of #111).

**8. The shape.** The entry gains `voice: {id, status, url, durationMs}` or `null`, every field
always sent, `url` and `durationMs` present exactly when `status` is `READY` (to the author once it
is ready; to the partner, decision 7). It is only ever on a
reading in full (`DisclosedEntry` carries the voice note's id there and nowhere else): a locked
entry has no voice note, field or flag (BR-8, `RevealGateTest` extended), and a tombstone's is
`null`. `GET /media/{id}` gains `durationMs`. NFR-103 (voice is never the only way to do anything)
holds by construction: text stays the required part of an entry.

## Consequences

**Positive.** A voice note never keeps a byte its recorder wrote, nor a tag or a chapter. ffmpeg,
for all it can read, reads only seven containers through their own demuxers, only a phone's
codecs, and only files; a decoder fault, a loop or a runaway allocation costs one note, not the
process. A note over a minute is refused before it is decoded, and one whose container lies about
its length is refused after. Production starts as it is, and processes nothing until the worker
exists. The first half of M4 (15:110) runs end to end on the smoke stack: an entry with a photo and
a voice note.

**Negative.** Until the worker is deployed no voice note is processed in production. Until D4
gives voice notes a verdict, a partner is shown one as `PROCESSING` and never hears it
(decision 7). ffmpeg's options are those of 6.1 (Ubuntu
24.04, the worker's) and 9.0 (checked on both); a version that renames one fails the start-up
check or the notes, not silently. The in-process network bound is ffmpeg's own protocol list; the
container's is owed. iOS's playback of Ogg Opus is not yet known (owed, below).

**Neutral.** No new error code; one removed. No migration. The media state map's rows say photo or
voice note where they hold for both. CI installs `ffmpeg` beside libvips.

## Alternatives considered

- **AAC-LC, 48 kbit/s** (Q4's fallback, "if the iPhone playback test fails"). Not taken: the
  ruling is Opus. Switching is one line of arguments and one expected codec in the tests.
- **Opus in WebM or CAF instead of Ogg.** Ogg is Opus's own container (RFC 7845) and what Android
  plays natively; CAF is iOS's. Which iOS plays is the owner's playback test (owed).
- **ffmpeg's `-format_whitelist` beside `-f`.** With the demuxer named, it adds nothing a test can
  show.
- **A network namespace (`unshare -n`) around ffmpeg.** Not available to an unprivileged process
  in a default Docker container (seen: `unshare -rn` and `unshare -n` both "Operation not
  permitted" in Ubuntu 24.04 under Docker's default profile); the worker's own network limit is
  the place for it.
- **Trusting the container's length alone.** Refused: an MP4's headers were seen to say 10 seconds
  of 90.
- **Unconstrained VBR.** The ruling's 24 kbit/s became 39 on a tone.

## Owed

- **The owner's iPhone playback test (Q4)**, with exactly what this writes: Ogg Opus, mono,
  24 kbit/s constrained VBR (`smoke.sh`'s voice section leaves one at a URL). If iOS will not play
  it, Q4 says AAC-LC 48 kbit/s for both; the container may be the cause rather than the codec (CAF
  holds Opus on iOS).
- **GATE: the media worker (provider `native`) is not deployed before D5's purge (#110, ADR-0050)
  is live in production**, so that no URL handed out before an erasure outlives it (ADR-0048 Owed).
  The FR-061 condition this gate carried is met: the owner ruled on 2026-10-29 that voice notes are
  moderated. **D4 is not a further gate**: with no verdict a partner is never given a voice note
  (or a photo), so the worker without D4 makes media visible to authors alone.
- **D4: voice notes are moderated** (decision 7). D4 decides how, and gives a voice note its
  `PASSED` (or `QUARANTINED`) as it does a photo; `MediaAsset.partnersMayHearIt` then lets the
  partner hear it, unchanged.
- **Media requires a verified email** (the owner's ruling, 2026-10-29): the `/media` routes are
  refused to an account whose address is not verified. Owed as a separate small pull request, not
  this one; it closes the open question D1 and D2 carried in their pull requests (#106, #108).
  **Done (#112)**: an upload intent, its completion and `media`'s claim by an entry refuse an
  unverified owner with bond's FR-002 answer, `403 EMAIL_NOT_VERIFIED`, checked first; reading and
  deleting one's own media, and an entry with no media, are not refused. `media` asks through
  `common.security.VerifiedEmails`, which `identity` answers (as it does `TokenRevocations`), so it
  stays a leaf (ADR-0047). The claim's refusal is not reachable through the API today: FR-002 keeps
  an unverified account out of every bond, and nothing un-verifies one.
- **The media worker** (ADR-0048 Owed), **a Kamal role** running the application's own image with its
  own command (the owner's ruling, 2026-10-29), not an accessory: its image needs `ffmpeg` with `libopus` (Ubuntu 24.04's
  `ffmpeg` package has it, and the AMR decoders), `MOYI_MEDIA_PROCESSING_PROVIDER=native`, and its
  network limited to R2 and Postgres (spec R-E), which is the only network bound around ffmpeg
  beyond its protocol list. **Built: ADR-0052** (the image carries ffmpeg 6.1.1 with libopus on both
  architectures; the network limit is put in from the host, to Postgres and Cloudflare's ranges,
  since R2 has none of its own). The gate above is met: production runs `ce41f22`, which carries
  #110.
- **A recording from each platform through the pipeline once** (an iPhone's Voice Memos M4A, an
  Android `MediaRecorder` MPEG_4 and THREE_GPP file): the brands in decision 2 are read, not seen.
- **R2, not run:** that a GET of `voice.ogg` is served with `audio/ogg`, as MinIO serves it.
  **Moved to the owner's verification of the switch-on** (ADR-0052, "Switched on"):
  `scripts/r2-switch-on-check.sh` uploads a WAV as a voice note, waits for `READY` and checks the
  URL's answer is `audio/ogg` and Ogg Opus. Seen against MinIO; owed against R2 until the owner has
  run it. The iPhone playback test above is not part of it.
- **D5:** erasure purges `voice.ogg` as it purges a photo's variants (`MediaAsset.objectKeys`
  names it; `MediaPurgeTest` holds it, red with `voice.ogg` dropped from the keys); the reaper
  takes an unattached voice note after 24 hours like a photo. **Done**: D5 merged first (#110),
  and this branch carries the test. ADR-0050 already counts on this (its D3 note).
- **`entries.voice_duration_ms`**: dead since D3 (decision 4); a later forward migration drops it.

## Revisit when

D4 chooses how voice notes are moderated. The iPhone playback test fails. ffmpeg changes
an option this uses, or a recorder is seen writing a brand decision 2 refuses. The worker is
deployed (then the network bound moves into the container too).
