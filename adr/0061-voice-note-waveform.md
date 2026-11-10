# ADR-0061 — A voice note's waveform: 64 RMS levels measured by the server from the Opus it keeps

**Status:** Accepted · **Date:** 2026-11-10 · **Deciders:** Daniel (the lead's ruling on issue #165)

## Context

The app draws a waveform beside every voice note. The server sends none, so the app draws a
stand-in shape for every note (owner's ruling on moyi-app PR #53: keep the stand-in until the
backend sends real levels). Issue #165 offered two ways: accept levels measured by the recording
phone with the upload, or compute them on the server while transcoding. The phone cannot compute
them for a note it did not record without downloading and decoding it, and a note is fetched only
when play is tapped, by design.

`media.process` already decodes every voice note (ADR-0049): ffmpeg, in the sandbox of ADR-0048,
turns the upload into one Ogg Opus and checks what it wrote. The media worker (ADR-0052) is where
that runs in production.

What had to be decided: where the levels come from, what each level measures, how they are kept,
who is given them, and what happens to the notes processed before this change, to a deleted note's
levels, and to the export.

## Decision

**1. The server measures the levels; no client sends any.** The lead's ruling on #165: one source
of truth, and nothing for a client to forge. The upload intent and completion are unchanged; a
`waveform` sent by a client would be ignored as any unknown field is.

**2. Measured from the Opus that is kept, not from the upload.** After the transcode and its checks
(ADR-0049 decision 4), `FfmpegVoiceProcessor` decodes the kept file once more, through the one
demuxer and decoder that read it back (`ogg`, `opus`, `-protocol_whitelist file`), to 16-bit mono
PCM at 8 kHz in the work directory, with the note's whole deadline, a length limit (`-t`, the
limit plus the run-on) and a byte limit (`-fs`, 992,000 bytes: 62 s × 8,000 × 2). The levels then
describe what the listener hears, ffmpeg reads only bytes this server wrote, and none of the hostile
input's paths is opened twice. 8 kHz is enough: speech's loudness lives below 4 kHz, and a 61-second
note is under a megabyte of samples. Its cost is one more short process per note.

**3. 64 levels, 0 to 255, each the RMS of one equal slice of the note in time, scaled linearly so
that the loudest slice is 255.** Slice *i* holds samples `[i·n/64, (i+1)·n/64)`. RMS rather than
peak: RMS follows how loud speech sounds, while a peak is set by its loudest single sample, so one
click or plosive fills a slice and most slices of speech look alike near the top. Scaled to the
loudest slice, because the drawing shows the shape of this note, not its absolute level (a phone
held far away would otherwise draw flat). Linear, not in decibels: the client can apply any curve it
likes to linear levels, but cannot undo one applied here. **A note whose every slice is below
−60 dBFS (RMS 32.768 of 32,768) is silent: all zeros**, so that a note of room noise is not
amplified into a loud shape. A note of fewer than 64 samples leaves some slices empty, at 0. The
rule is `media.domain.Waveform`, written once.

**4. Kept as `media_assets.waveform bytea`, exactly 64 bytes, unsigned** (V57). A byte is exactly
the range 0 to 255, so no level can be out of it and nothing has to check one; 65 bytes on disk
against a `smallint[]`'s 128 plus its array header; and no array dimensions or null elements to
rule out. A CHECK holds it to 64 bytes, and to voice notes. What it costs: `psql` shows hex, not
numbers (`get_byte(waveform, i)` reads one).

**5. Given with the note's URL, by the same rule, and only then.** `EntryVoiceResponse.waveform`
is the 64 integers when `url` is present: to the author once the note is ready, to the partner only
once it has passed moderation (FR-061, ADR-0049 decision 7), and to the author on `NOT_SHARED`
unless it was blocked. `MediaAsset.heardBy` hands it out with the key, and `PlayVoices` drops it
whenever there is no URL to give (no storage configured). Everywhere else it is `null`: `PROCESSING`,
a blocked note, a note not yet transcoded. Every field of the response is always sent (ADR-0051's
shape), so `waveform` is required and nullable. Adding a required property to a response does not
break a generated client.

**6. A note's levels may be null for good, and the client keeps its stand-in then:** every note
processed before V57, and any whose measuring failed. **A measuring that fails does not fail the
note**: the note was transcoded and checked, and its drawing is not worth it; the line
`Media {id} is ready without its waveform` (id only) says it happened. An ffmpeg that cannot decode
what it has just encoded would fail every note's levels, and that line is the alert.

**7. No backfill.** Measuring an old note means fetching its Opus from R2 and decoding it in the
media worker, a job with its own lock, batch size, outage handling and test, for notes that already
play and draw the stand-in the owner ruled acceptable on moyi-app #53. Production has a handful of
test notes (the test accounts are still to be wiped). *Proposed as a follow-up if real notes from
before this change ever matter:* a `media.waveform-backfill` job in the worker, keyed on
`kind = 'VOICE' AND status IN ('READY', 'QUARANTINED') AND waveform IS NULL`, decoding through the
same `FfmpegVoiceProcessor` code.

**8. A deleted note keeps no waveform, whoever deletes it.** The loudness of someone's voice over a
minute is theirs, and goes with the note. Five statements set `status = 'DELETED'` (the owner's
delete, an entry's erasure, account deletion's `releaseAllOwnedBy` (ADR-0058 decision 9), the
reaper, and the operator's `media_quarantine_delete`, which a report's takedown also calls), so a
`BEFORE INSERT OR UPDATE`
trigger clears `waveform` whenever the new status is `DELETED`, rather than each statement having
to remember it, and a CHECK (`status <> 'DELETED' OR waveform IS NULL`) refuses a deletion that
keeps one should the trigger ever be dropped. The note's Opus itself goes by `media.purge`, as
before. `duration_ms` is left as it was: not this change's.

**9. Not in the data export.** ADR-0059 decision 7 exports a voice note as its Ogg file, a path in
the JSON with no metadata beside it (no `durationMs` either), and the levels are derived from that
file, which the person has. Adding them would be the first voice metadata in the archive's
`moyi-export/1` format; if the archive ever carries voice metadata, the waveform goes with it.

**10. Not on `GET /media/{id}`.** The issue asks for `EntryVoiceResponse`. The author's own
`MediaResponse` could carry it by the same rule; nothing asks for it yet.

## Consequences

**Positive:** the app draws the real shape of each note with no screen change and without fetching
the note; the levels cannot be forged; one rule (`Waveform`), one place they are handed out
(`heardBy`), one place they are cleared (the trigger).

**Negative:** one more ffmpeg process per voice note in the worker (tens of milliseconds for a
minute of Opus, measured only by the tests' run times). Old notes stay on the stand-in. A trigger is
behaviour a reader of the Kotlin does not see; the store's KDoc and V57 say so. A note whose
measuring failed is not retried.

**Neutral:** the contract grows one required, nullable field; `breaking-api-change` is not needed.

## Alternatives considered

- **Levels from the recording phone with the upload** (the issue's first option). Rejected by the
  lead: a client could send any shape, and the partner would be drawn it; and two platforms would
  have to agree on one measure. Right only if the server stopped decoding notes.
- **Measured from the upload during the transcode** (a second output of the same ffmpeg). One
  decode fewer, but the levels would describe the input rather than what is heard, and the
  transcode's guards (`-t`, `-fs`) would have to be repeated per output. Right if the extra decode
  ever shows in the worker's timings.
- **Peak per slice.** Simpler, and common in players; rejected for decision 3's reasons.
- **`smallint[]`.** Readable in `psql`; rejected for decision 4's reasons.
- **Refuse a note whose levels cannot be measured.** Consistent, but a voice note would fail for a
  drawing.

## Revisit when

- The app wants more or fewer than 64 levels, or a log scale (the latter is the client's to apply).
- Real notes from before V57 matter to someone: build the backfill of decision 7.
- The export grows voice metadata (decision 9), or the author's `GET /media/{id}` needs the levels
  (decision 10).
