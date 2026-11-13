# ADR-0065 — Photos moderated by Rekognition in the media worker; voice notes pass unchecked

**Status:** Accepted, amended the same day (below) · **Date:** 2026-11-13 · **Deciders:** Daniel · **Amends:** ADR-0051
(production's providers: photos switched on, voice `allow`), ADR-0049 decision 7 (a voice note
reaches a partner once it has a `PASSED` verdict: it still does, and the verdict is `allow`'s)

## Context

ADR-0051 made moderation fail closed. Production named `none` for photos and voice notes, so
nothing got a verdict, and a partner was never shown a photo or played a voice note.

On 2026-11-13 the owner and his partner tested the app on an iPhone against production. He sent a
photo and a voice note; he saw both, she saw neither ("processing"). That was the design working,
but it meant the product's media did not work for anyone.

The owner asked what moderation would cost. Rekognition's `DetectModerationLabels` is a Group 2
image API: about $0.001 per image for the first million a month, and 1,000 images a month free
for a new account's first 12 months (AWS pricing page, read 2026-11-13; check it before relying
on the figure). Two testers cost nothing; 1,000 couples sending a photo a day each, about $60 a
month. No vendor moderates voice notes (ADR-0051 decision 5), and none is wired. The owner first
chose to pass both unchecked, for cost, and on hearing the figure chose Rekognition for photos.

## Decision

1. **Photos: Rekognition, in the media worker only.** The worker's role names
   `MOYI_MEDIA_MODERATION_IMAGE_PROVIDER: rekognition` and alone holds the IAM user's key
   (`rekognition:DetectModerationLabels` and nothing else, ADR-0051), from the vault through
   `.kamal/secrets`. The API keeps `none`: it processes nothing, so it moderates nothing, and it
   never holds the key. The worker's network must reach Rekognition (AWS's `eu-central-1` ranges in
   its egress limit, `ops/media-worker/README.md`).
2. **Voice notes: a moderator named `allow`, which passes every voice note unchecked.** It is a
   real provider (`MediaModerationProperties.VoiceProvider.ALLOW`), not a stand-in: it moderates
   here, so the voice consumer subscribes, and its verdict is an ordinary `PASSED` with nothing
   found. Its name, `allow`, is what `moderation_scores` and the audit log record, so a voice note
   passed this way can always be told from one a moderator listened to. "Unchecked" means no one
   judges what it says: a voice note whose file is missing or unreadable is still held back for
   the operator (`UNMODERATABLE`), never passed. Photos have no `allow`.
3. **Media already sent is moderated too.** Under `none` neither consumer subscribed to anything,
   so neither was ever stored as a subscriber (`ConsumerRegistry.register` stores only declared
   types). On the first deploy each subscribes for the first time and, starting from the beginning
   (`StartFrom.BEGINNING`, ADR-0035), is handed every `MediaReady` ever published: old photos go
   to Rekognition, old voice notes pass. No data fix.
4. **Reports still work.** A partner can report a photo or a voice note; it is held back
   (ADR-0053) and decided by a person, as before.

## Owed

- A known-image (CSAM hash) list (ADR-0051 decision 3) and a voice vendor (decision 5) stay owed.
  When a voice vendor is wired, voice notes passed by `allow` keep their verdict; a sweep that
  re-moderates them is possible (each says `allow` in its record) and is not built.

## Consequences

- **Owner steps before the deploy:** the AWS account, the IAM user and its key, the two vault
  fields `REKOGNITION_ACCESS_KEY_ID` and `REKOGNITION_SECRET_ACCESS_KEY` in `moyi-prod`, and the
  worker's egress (`EXTRA_HTTPS_CIDRS`). Without the vault fields the deploy stops at
  `kamal secrets fetch`, before anything changes. Without the egress every call is refused and
  retried as an outage, and the partner keeps seeing "processing".
- **After the deploy:** set `MEDIA_MODERATION_ON=1` in the monitoring env
  (ops/monitoring/README.md), so media left `READY` for 30 minutes without a verdict raises the
  alarm. The deploy log should show `Event consumer registered: consumer=media.moderate-image …
  backfilled=N` and the same for `media.moderate-voice`.
- Rekognition sees each photo's display variant (1280 px, as a JPEG) in AWS's Frankfurt region.
  The privacy notice must say so; check that it does before the deploy.
- A voice note that would have been held back by a moderator reaches the partner until it is
  reported. Accepted by the owner: a voice note carries no image.
- The restore drill (`ops/backup/restore-drill.sh`) keeps `none` for both: a drill must not
  moderate.
- `ProductionStartsWithoutMediaSecretsTest` holds the split: the worker's secrets are the
  database's, the bucket's and Rekognition's; the API's never include Rekognition's.

## Amended 2026-11-13: photos pass unchecked until the AWS account is upgraded

Deployed as `a08c1dd`, every call to Rekognition was refused: `AccessDeniedException`, from a
service control policy AWS applies to accounts on its new **free plan**. That plan assigns the
account one region (here Stockholm, `eu-north-1`) and denies the rest; `eu-central-1` needs the
paid plan. Rekognition has no Stockholm endpoint (`rekognition.eu-north-1.amazonaws.com` does
not resolve; AWS's endpoint list has none), so no free-plan region can serve decision 1.

The owner chose, for now, not to upgrade: **photos pass unchecked too**, through a photo
`allow` provider (`MediaModerationProperties.ImageProvider.ALLOW`), the same in kind as the
voice one: it records `allow` as the moderator, so these verdicts stay distinguishable. The
media worker names it; everything else of decision 1 stays in place (the worker's key from the
vault, AWS's `eu-central-1` ranges in its egress limit), so switching on is one line:
`MOYI_MEDIA_MODERATION_IMAGE_PROVIDER: rekognition` in the worker's env, then a deploy.

- The upgrade screen says it costs nothing, keeps the account's credits ($120 on 2026-11-13),
  and caps spend at a monthly limit (the owner chose $20). Owed: upgrade, then switch on, before
  the app is public. Photos passed by `allow` keep their verdict.
- The photos that waited while Rekognition refused them are passed on this deploy: their
  deliveries are still being retried, and the next attempt meets `allow`.
- Nothing stops an image a moderator would hold back, including child sexual abuse material,
  from reaching a partner until it is reported. Accepted by the owner for two known testers;
  not for the public.
