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
| `QUARANTINED` | `REPORTED` (a member reported its entry, section 3) | "processing" | "not shared", with the photo | the operator: `moyi reports dismiss` or `uphold` |

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

## 3. A member reports an entry, its photo or its voice note (FR-100, ADR-0053)

Members report through the app: `POST /entries/{entryId}/report`, one of five reasons, and an
optional detail in their own words. The moment a report is made, the entry's photo and voice note are
held back from the reporter (they see "processing"; the author sees "not shared", exactly as for
anything moderation holds back, and is told nothing else). The entry's words stay visible. A report
of sexual content involving a minor is held back the same way and marked for priority review; **the
route never blocks**: you decide.

`ops/monitoring/media-check.sh` fails while a report has been open more than `REPORT_REVIEW_DAYS`
(2 by default), and **at once** while a `MINOR_SEXUAL_CONTENT` report is open.

```
moyi reports list                      # open reports, priority first: id, reason, entry, held, detail?, when
moyi reports show <reportId>           # the reporter's detail and what the report holds back (recorded)
```

A `MINOR_SEXUAL_CONTENT` row ends `DO NOT VIEW — runbook §2`, and so does `show`'s reason line.

1. **Sexual content involving a minor** (`MINOR_SEXUAL_CONTENT`): do not open its photo or voice
   note. **[legal: confirm** whether the report alone is ground enough to block and report, or a
   review is required, in your jurisdiction**]**. Then:
   - `moyi reports uphold <reportId> block "report <date>"`: everything the report holds is made
     `BLOCKED` and held as evidence (`media_quarantine_block`), never released; then section 2 from
     step 1.
   - Or, if it is plainly not that (the detail says it is a mistake, the reporter wrote the wrong
     reason): decide it as the next step does, under its real reason.
2. **Non-consensual intimate imagery** (`NON_CONSENSUAL`; Q16: no classifier can judge consent):
   take the reporter's word. `moyi reports uphold <reportId> delete "NCII, <date>"`. The photo and
   voice note are deleted and purged within seconds. The author's own withdraw and erase (D5) remain
   theirs. Do not tell the author.
3. **Violence or self-harm, harassment, anything else**: look at the photo as section 1 says (only a
   row that is not marked DO NOT VIEW), read the detail, and decide:
   - not what was reported: `moyi reports dismiss <reportId> "<why>"`. What the report held back is
     given back exactly as it was: shown to the reporter again if it had passed moderation, or handed
     back to moderation if it had not been moderated yet. Unless another open report, or moderation
     itself, holds it: then it stays held.
   - what was reported: `moyi reports uphold <reportId> delete "<what was found>"`.
   - a report about **words** (harassment, a threat): the report holds nothing back, and upholding it
     deletes nothing; the words stay unless their author erases them. Reading an entry's words is
     content access (doc 09: an admin's, with justification): read them only to decide a report that
     names them, and say so in the note. There is no takedown of words in v1 (ADR-0053, question 3);
     a threat to someone's safety is section 4's territory and the reporter's to take to the police.
4. Every decision empties the detail the reporter wrote (it is needed only to decide) and is
   recorded with your name and note in `entry_report_log`; what became of the media is in
   `media_moderation_log`. `uphold … delete` never deletes a `BLOCKED` asset: it says so
   (`BLOCKED_KEPT`), and that evidence goes only by section 2.
5. The reporter is not told the decision (the route says a person will read it). The author is never
   contacted about a report.

The functions are the database's (V38, V39: `entry_report_list`, `_read`, `_dismiss`, `_uphold`,
calling V36's `media_quarantine_*`), so the rules cannot drift from the constraints, and they are
tested against a migrated database (`ReportCommandsTest`, `MediaReportHoldsTest`). `moyi media
quarantine hold|block` remain for a report that reached you another way (the support address).

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
- A known-CSAM hash list (ADR-0051 decision 3): until one is wired, `BLOCKED` can come only from a
  classifier, and Rekognition has none, so in practice nothing is blocked automatically.
