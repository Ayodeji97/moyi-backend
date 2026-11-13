# ADR-0065 — Photos and voice notes pass unchecked while the app is in private testing

**Status:** Accepted, temporary · **Date:** 2026-11-13 · **Deciders:** Daniel · **Amends:** ADR-0051
(production's provider for photos and voice notes), ADR-0049 decision 7 (a voice note reaches a
partner once it has a `PASSED` verdict: it still does, and the verdict is now `allow`'s)

## Context

ADR-0051 made moderation fail closed. Production names `none` for photos and voice notes, so
nothing gets a verdict, and a partner is never shown a photo or played a voice note until the
owner supplies Rekognition's key (photos) and picks a voice vendor (no vendor is wired).

On 2026-11-13 the owner and his partner tested the app on an iPhone against production. He sent a
photo and a voice note; he saw both, she saw neither ("processing"). That was the design working,
but it means the product's media does not work for anyone until moderation is paid for.

The owner asked what moderation would cost. Rekognition's `DetectModerationLabels` is a Group 2
image API: about $0.001 per image for the first million a month, with 1,000 images a month free
for a new account's first 12 months (AWS pricing page, read 2026-11-13; check it before relying
on the figure). For two testers that is nothing; for 1,000 couples sending a photo a day each,
about $60 a month. Voice notes have no vendor and no cost either way.

## Decision

1. **A moderator named `allow`, for photos and for voice notes, passes everything unchecked.** It
   is a real provider (`MediaModerationProperties.ImageProvider.ALLOW`,
   `VoiceProvider.ALLOW`), wired like the others, not a stand-in: it moderates here, so the two
   consumers subscribe, and its verdict is an ordinary `PASSED` with nothing found, sensitive
   false. Its name, `allow`, is what `moderation_scores` and the audit log record, so a photo passed
   this way can always be told from one a moderator looked at.
2. **Production names `allow` for both** (`config/deploy.yml`). The known-image list stays `none`.
3. **Media already sent is passed too.** The consumers start from the beginning
   (`StartFrom.BEGINNING`, ADR-0035), so on the first deploy with `allow`, every photo and voice
   note that waited under `none` gets its verdict and its partner sees it. No data fix.
4. **Reports still work.** A partner can report a photo or a voice note; it is held back
   (ADR-0053) and decided by a person, as before.
5. **The owner's reason is cost, and the condition is private testing.**

## Owed

- **Before the app is public: photos back to Rekognition** (ADR-0051). Remove the image line from
  `config/deploy.yml`, add the key pair as secrets, and open the media worker's network to
  Rekognition (`ops/media-worker/README.md`). Media already passed stays passed; only new uploads
  are checked. A sweep that re-moderates media passed by `allow` is possible later (each one says
  `allow` in its record) and is not built.
- A known-image (CSAM hash) list (ADR-0051 decision 3) and a voice vendor (decision 5) stay owed.

## Consequences

- Partners see each other's photos and hear each other's voice notes as soon as they are ready.
- Nothing stops an image that Rekognition would hold back, including child sexual abuse material,
  from reaching a partner, until a partner reports it. With two known testers that risk is
  accepted by the owner; with the public it would not be, which is why the return is owed and
  `config/deploy.yml` says so beside the line.
- `ProductionStartsWithoutMediaSecretsTest` accepts `allow` for photos, and still refuses a
  stand-in in every role.
