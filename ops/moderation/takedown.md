# Runbook: what moderation held back, takedowns, and law-enforcement requests

**Covers:** T-08 (doc 09:32: "quarantine + review queue; reporting flow; documented takedown and
law-enforcement procedure"), R-25 (doc 17:47), FR-101, and the owner's ruling Q16. The code is
ADR-0051's. Doc 20:22-23's launch checklist ("content moderation and takedown policy written",
"law-enforcement request procedure written") is met only once the owner has had the parts marked
**[legal: confirm]** checked by someone qualified. Nothing here is legal advice.

**Who runs it:** the operator (today, the owner). There is no admin console before Phase 5; every
step below is `scripts/moyi media quarantine …` on the server, with `MOYI_OPERATOR` set to your
name. Every look and every decision is written to `media_moderation_log`, under that name.

```
ssh root@91.98.71.136
export MOYI_OPERATOR=daniel           # who is acting; recorded with every command
moyi media quarantine list            # what is held back: verdict, id, kind, status, reason, attached?, when
```

(`moyi` is `scripts/moyi` from a checkout of this repository on the server, or copied there. It
reaches the database through `docker exec -i moyi-postgres psql`; nothing else is needed.)

## What the verdicts mean

| Verdict | Reason | What the partner sees | What the author sees | Who may change it |
|---|---|---|---|---|
| `PASSED` | | the photo or voice note; blurred if `sensitive` | the same | nobody needs to |
| `QUARANTINED` | `EXTREME_VIOLENCE` | "processing", for ever | "not shared", with the photo | the operator: release or delete |
| `QUARANTINED` | `UNMODERATABLE` (the moderator refused the bytes) | "processing" | "not shared", with the photo | the operator: release or delete |
| `BLOCKED` | `KNOWN_IMAGE` (a hash list matched) or `CSAM_CLASSIFIER` | "processing" | "not shared", **without** the photo | nobody can release it. The operator deletes it, after reporting |

The partner is never told "removed" (Q10), and the author is never told why (Q10). Do not contact
either member about a quarantine except as section 4 says.

**But the API does tell the author which of the two it is.** A quarantined photo is "not shared" to
its author *with* the photo; a blocked one *without* it, and `GET /media/{id}` says `QUARANTINED` and
gives no URL. An author who uploaded child abuse material can therefore tell that it was blocked
rather than held for review. That sits against section 2's "do not tell the uploader"; it is an open
question for the owner, with legal advice (ADR-0051, open question 2), and the API is not changed
until it is answered.

**In the list, the verdict comes first, and a `BLOCKED` row ends `DO NOT VIEW — runbook §2`.** Look
only at `QUARANTINED` rows (section 1). Never open a `BLOCKED` one.

## 1. Something is QUARANTINED (extreme violence, or nothing could moderate it)

Not urgent: nobody but its author can see it. Look at the queue once a day while it is not empty.

1. `moyi media quarantine list`.
2. To decide, you have to see it, **if its verdict is `QUARANTINED`** (never a `BLOCKED` row: section
   2). The photo is its author's and private; look only at the one you are deciding on. Get its display variant's key from the row
   (`SELECT storage_key FROM media_assets WHERE id = '<id>'`, then `v/<the part after in/>/display.webp`)
   and fetch it with the R2 dashboard. Do not save a copy.
3. Decide:
   - **A false positive** (a film still, a cooking video, a costume, a painting):
     `moyi media quarantine release <id> "<why, in a few words>"`. The partner sees it at their next
     read.
   - **Real violence or gore:** `moyi media quarantine delete <id> "<why>"`. Its objects leave the
     storage within seconds. If it shows a crime against a person (not a film), treat it as section 3.
   - An unattached quarantined asset is deleted by the hourly reaper a day after it was made, as any
     unattached upload is (ADR-0050). An attached one waits for you.

## 2. Something is BLOCKED (child sexual abuse material)

**Urgent.** `ops/monitoring/media-check.sh` fails at once while any BLOCKED asset exists, so you
will hear of it from Healthchecks.io within a quarter of an hour.

What the system has already done:

- The asset is served to nobody, its author included. No URL to it is issued.
- It cannot be released, by the tool or by any SQL (the trigger of V36).
- If its entry is erased (the author deletes it, a withdrawal), the asset is let go but **not
  deleted**: the evidence stays until you have reported it. The reaper never takes it.

What you do:

1. **Do not view, download, copy or forward it.** Possession and distribution are offences
   (§184b StGB in Germany). You do not need to see it to report it: a hash list match or the
   classifier is the ground. **[legal: confirm** whether a human review is required or advisable
   before reporting in your jurisdiction.**]**
2. Note what the system holds, without the image:
   ```
   SELECT a.id, a.owner_user_id, a.kind, a.created_at, a.moderated_at, a.moderation_scores,
          a.attached_entry_id, u.email
   FROM media_assets a JOIN users u ON u.id = a.owner_user_id WHERE a.id = '<id>';
   ```
   The uploader's account, the time, the verdict's reason. IP addresses are not kept by this
   system (only an HMAC of one for rate limiting, ADR-0023), so none can be given.
3. **Report it.** **[legal: confirm** each of these for a German/EU provider**]**:
   - **The BKA**, through its Digital Services Act reporting portal: Art. 18 DSA obliges a hosting
     provider to notify suspected criminal offences involving a threat to life or safety, and child
     sexual abuse is within it. Access to the portal is granted by the BKA by email; **apply before
     launch**, not on the day (https://bka.de, "Digital Services Act").
   - Optionally, a hotline: jugendschutz.net or the eco Beschwerdestelle (both INHOPE members) in
     Germany. NCMEC (US) is a US duty, voluntary for an EU provider, unless a vendor's terms
     (PhotoDNA's, say) require it.
   - **[legal: confirm]** whether the account must be preserved, suspended, or left untouched until
     the authority answers. Do not tell the uploader (tipping off). Do not tell the partner.
4. **Preserve, then delete, as the authority directs.** The asset and its row stay as they are until
   then. When you may delete it:
   `moyi media quarantine delete <id> "reported: <authority> <reference>, <date>"`. The note is the
   record that it was reported; write the reference. Its objects leave the storage within seconds.
   **[legal: confirm]** how long it must be kept before deletion if the authority says nothing.
5. **The mirror** (ADR-0050 decision 7): if the asset was ever made READY before it was blocked (it
   is moderated seconds after processing, but a mirror run in that window would copy it), its
   display and full variants may be in the media mirror bucket until that copy's 31-day expiry. The
   mirror's lock prevents deleting them early. Tell the authority that a copy exists in a locked
   backup and when it expires.

## 3. A member reports a photo or voice note (doc 26:206)

**There is no report route yet** (FR-100; doc 26 §1, ADR-0036 question 5: "no report route exists
yet — a later phase's"; owed, ADR-0051). Until there is, reports reach the support address. When one
does:

1. Find the asset: the reporter names the day; the entry's `image_media_id` / `voice_media_id`
   gives the id. Check the reporter is a member of that bond: a report about a bond you are not in
   is not a report about this media.
2. **Non-consensual or third-party intimate imagery** (Q16: no classifier can judge consent):
   hold it back at once, then delete it:
   `moyi media quarantine hold <id> "report: NCII, <date>"`, then
   `moyi media quarantine delete <id> "report: NCII, <date>"`. `hold` takes a `READY` asset only
   (one already held back needs none; go straight to `delete`), sets the verdict, its time and the
   reason `REPORTED`, and records who and why. Tell the reporter it is gone, and nothing else. The
   author's own withdraw/erase (D5) remains open to them.
3. Child sexual abuse material: `moyi media quarantine block <id> "report: <date>"` first, then section
   2 from step 1. `block` makes it `BLOCKED` and holds it as evidence, whether it is ready, held back,
   or already deleted to its owner with its objects not yet purged; it says `GONE` if they are. From
   then nothing can release it and nothing purges it (the database holds both, ADR-0051 decision 6),
   and the media check fails, as it should, until the report is made and the operator deletes it.

   Do not write these as statements of your own: `hold` and `block` are the database's functions, so
   they cannot drift from its constraints, and they are tested against a migrated database
   (`MediaQuarantineCommandsTest`).
4. Anything else: decide as section 1.

## 4. A law-enforcement request

**[legal: confirm** the whole of this section**]**.

1. Verify the request came from the authority (call back on a number you found yourself, never one
   in the request).
2. Disclose only what the order covers. What this system can give: account details (email, display
   name, created), bond membership, the media rows and their objects while they exist, entry
   timestamps. Entry text is the users' words and is disclosed only under an order that names it.
3. Preserve on request. The system has no general preservation hold: only `BLOCKED` keeps an asset
   from its author's erasure and from the reaper. For anything else a preservation request names,
   export it (the rows, and for media the object, from the R2 dashboard) to where the authority
   directs, at once: an author's own erasure deletes media within seconds (ADR-0050), and the
   database backups keep 30 days. Say so in the reply.
4. Record what was asked, what was given and when, outside this repository.

## 5. Owed before this runbook is complete

- The owner's legal check of every **[legal: confirm]** above, and of whether scanning private
  messages for CSAM is lawful for Moyi under ePrivacy and §3 TDDDG, and under which conditions
  (Regulation (EU) 2026/1881, which replaced the lapsed 2021/1232, permits it for
  number-independent interpersonal communication services on conditions: a DPIA, the least
  intrusive technology, reporting). See ADR-0051, open question 3.
- BKA DSA-portal access, applied for before launch.
- The report route (FR-100), so that section 3 starts from a queue rather than an email.
- A known-CSAM hash list (ADR-0051 decision 3): until one is wired, `BLOCKED` can come only from a
  classifier, and Rekognition has none, so in practice nothing is blocked automatically.
