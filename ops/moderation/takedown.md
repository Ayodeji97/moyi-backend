# Runbook: what moderation held back, takedowns, and law-enforcement requests

**Covers:** T-08 (doc 09:32: "quarantine + review queue; reporting flow; documented takedown and
law-enforcement procedure"), R-25 (doc 17:47), FR-101, and the owner's ruling Q16. The code is
ADR-0051's. Doc 20:22-23's launch checklist ("content moderation and takedown policy written",
"law-enforcement request procedure written") is met only once the owner has had the parts marked
**[legal: confirm]** checked by someone qualified. Nothing here is legal advice.

**Who runs it:** the operator (today, the owner). Every step below is `scripts/moyi media
quarantine …` on the server, with `MOYI_OPERATOR` set to your name. Every look and every decision is
written to `media_moderation_log`, under that name.

**Or the admin console, once it is switched on** (A2, ADR-0063; `ops/admin/README.md`): an account
with the `MODERATOR` role (or `ADMIN`) does the same steps under **Reports** and **Quarantine**. The
console calls the same database functions, so every rule below holds unchanged; it records you as
`admin:<your account id>` in the same logs, and adds a row to `audit_log` for every look and decision.
An uphold, a block and a delete ask for a fresh authenticator code (one proven in the last five
minutes). The console is **off in production** until the legal checks of ADR-0063 are done; until
then, the script is the path. What stays on the server either way: deleting `BLOCKED` evidence
(section 2 step 4), and holding or blocking an asset that is not in the queue (`moyi media quarantine
hold|block`, for a report that came by email).

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
only at `QUARANTINED` rows (section 1). Never open a `BLOCKED` one. A row whose last column
(`reports`) is above 0 ends `held by a report: moyi reports — runbook §3`: an open member's report
holds it, and it is decided there, never here.

## 1. Something is QUARANTINED (extreme violence, or nothing could moderate it)

Not urgent: nobody but its author can see it. Look at the queue once a day while it is not empty.

1. `moyi media quarantine list` (console: **Quarantine**). Skip every row marked `held by a report`:
   section 3 decides it, and `release` refuses it (`REPORT_HELD`, recorded) while the report is open.
   The console offers no decision on such a row; it links to the report.
2. To decide, you have to see it, **if its verdict is `QUARANTINED`** (never a `BLOCKED` row: section
   2). The photo is its author's and private; look only at the one you are deciding on. Get its display variant's key from the row
   (`SELECT storage_key FROM media_assets WHERE id = '<id>'`, then `v/<the part after in/>/display.webp`)
   and fetch it with the R2 dashboard. Do not save a copy.
   *Console:* **Quarantine → the asset → View** shows the display variant in the page, streamed
   through the application (no storage URL), and records the look as `MEDIA_VIEWED`. The console
   never shows a `BLOCKED` asset, nor one held by a `MINOR_SEXUAL_CONTENT` or `NON_CONSENSUAL` report:
   it answers "not found", and records the refusal.
3. Decide:
   - **A false positive** (a film still, a cooking video, a costume, a painting):
     `moyi media quarantine release <id> "<why, in a few words>"`. The partner sees it at their next
     read. One you held by hand before moderation had looked at it is not passed by your release: it
     goes back to moderation (`REMODERATED`).
   - **Real violence or gore:** `moyi media quarantine delete <id> "<why>"`. Its objects leave the
     storage within seconds. If it shows a crime against a person (not a film), treat it as section 3.
   - **If it is child sexual abuse material:** do not delete it. Block it as evidence, then section 2.
     On the server that is `moyi media quarantine block <id> "<why>"`; in the console, **Block as
     evidence** is the first form on the asset's page. It becomes `BLOCKED` and is never released.
     A `DELETED` asset cannot be blocked this way, purged or not (V62): a removal may already be
     listed in its author's data export as "removed", and a block after it would tell them what
     happened (ADR-0063). The script says so, and the console answers "Already deleted". Evidence
     its author deleted while a report held it is not lost by this rule: the report's
     `uphold … block` keeps it (section 3, step 1).
   - *Console:* the asset's page offers **Block as evidence**, **Release** and **Delete**, each with a
     note (1 to 400 characters, never shown to the member). Block and delete ask for a code.
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
   then. **On the server only:** the console refuses to delete `BLOCKED` evidence (ADR-0063, the
   owner's ruling 4). When you may delete it:
   `moyi media quarantine delete <id> "reported: <authority> <reference>, <date>"`. The note is the
   record that it was reported; write the reference. Its objects leave the storage within seconds.
   **[legal: confirm]** how long it must be kept before deletion if the authority says nothing.
5. **The mirror** (ADR-0050 decision 7): if the asset was ever made READY before it was blocked (it
   is moderated seconds after processing, but a mirror run in that window would copy it), what was
   kept of it may be in the media mirror bucket until that copy's 31-day expiry: a photo's display
   and full variants (`display.webp`, `full.webp`), a voice note's Opus and its AAC copy
   (`voice.ogg`, `voice.m4a`, ADR-0060), all under `v/<the part after in/>/`. The mirror's lock
   prevents deleting them early. Tell the authority that a copy exists in a locked
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

*In the console:* **Reports** is `list`, in the same order, priority first, each row with its age
in days; **Reports → the report** is `show` (the reporter's detail, what it holds, its history), with
the decisions below as forms on that page: **Dismiss**, **Reclassify**, and **Uphold** (delete or
block). Each form carries the reason the page showed: if another moderator reclassified the report
meanwhile, nothing is decided and the page says to read it again. A priority report offers only
"Block as evidence, then runbook §2", and shows its media as `DO NOT VIEW`. A report holding nothing
(words, a member) shows one **Uphold** that records the decision (`delete` for an ordinary report,
`block` for a priority one), as the commands below say to type.

1. **Sexual content involving a minor** (`MINOR_SEXUAL_CONTENT`, marked DO NOT VIEW). **You never
   open its photo or voice note, at any step.** What you do:
   1. `moyi reports show <reportId>` and read only the reporter's detail.
   2. Unless the detail itself plainly says the reason was chosen by mistake (for example "this is me,
      an adult, I did not want it shared"), block without viewing:
      `moyi reports uphold <reportId> block "report <date>"` (console: the report's only uphold,
      "Block as evidence"). Everything the report holds is made
      `BLOCKED` and held as evidence, never released, even if its author deleted it meanwhile.
   3. Then section 2 from step 1: note what the system holds (no image), report to the authorities,
      preserve, and delete only as section 2 step 4 says.
   4. If the detail does show the reason was wrong: `moyi reports reclassify <reportId> <reason> "<what
      in the detail shows it>"` (recorded with the old and new reasons), then decide it under that
      reason, from step 2 or 3 below. **`uphold … delete` refuses a minor report** until it is
      reclassified: deleting would destroy what may be evidence.

   **[legal: confirm** whether a member's report alone is ground enough to block and report without
   anyone viewing it, or a review by someone qualified is required, in your jurisdiction**]**.
2. **Non-consensual intimate imagery** (`NON_CONSENSUAL`; Q16: no classifier can judge consent):
   take the reporter's word. `moyi reports uphold <reportId> delete "NCII, <date>"`. The photo and
   voice note are deleted and purged within seconds. The author's own withdraw and erase (D5) remain
   theirs. Do not tell the author.
3. **Violence or self-harm, harassment, anything else** (never a DO NOT VIEW report: that is step 1):
   look at the photo as section 1 says (console: **View** beside the held photo on the report's page),
   read the detail, and decide:
   - not what was reported: `moyi reports dismiss <reportId> "<why>"`. What the report held back is
     given back exactly as it was: shown to the reporter again if it had passed moderation, or handed
     back to moderation if it had not been moderated yet. Unless another open report, or moderation
     itself, holds it: then it stays held.
   - what was reported: `moyi reports uphold <reportId> delete "<what was found>"`.
   - a report about **words** (harassment, a threat): the report holds nothing back, and upholding it
     deletes nothing; the words stay unless their author erases them. **Decide it from the reporter's
     detail.** Reading an entry's words is content access (FR-112, doc 09: an `ADMIN`'s, with a typed
     justification). The owner ruled (2026-11-11, ADR-0063 ruling 2) that **once the console is
     switched on, entry text is no longer read at `psql`** to decide a report, and A5 (ADR-0064) is
     where it is read: on the report's page, an `ADMIN` (not a `MODERATOR`) opens **Read the entry's
     words**, types why (1 to 400 characters) and a fresh authenticator code (every read needs a new
     one, and the five-minute window of the decisions does not count), for an open report whose reason
     is harassment, violence or self-harm, or other. For an image reason whose detail is about words,
     reclassify first. If the author has erased the entry, the page says so and you decide from the
     reporter's detail. The read is in `audit_log` and in the report's history (`moyi reports show`
     prints `words read by <actor> at <time>`). Until the console is on, the `psql` rule stands: read
     the words only to decide a report that names them, and say so in the note. There is no takedown of words in v1 (ADR-0053, question 3);
     a threat to someone's safety is section 4's territory and the reporter's to take to the police.
   - a report about **the other member** (target `MEMBER`, ADR-0053 decision 14; `show` names
     the reported member's `bond_members` id and the bond): it holds nothing back and names no
     entry. Read the detail and decide as for words: `dismiss`, or `uphold … delete` to record it
     upheld (nothing is taken down; there is no action on an account in v1). A `MINOR_SEXUAL_CONTENT`
     member report is upheld by `block` (nothing to block; it records the decision) after step 1's
     report to the authority, or reclassified first. The reported member is never told.
4. Every decision empties the detail the reporter wrote (it is needed only to decide) and is
   recorded with your name (in the console, `admin:<your account id>`) and note in
   `entry_report_log`; what became of the media is in `media_moderation_log`; a console decision is
   also one row in `audit_log`. `uphold … delete` never deletes a `BLOCKED` asset: it says so
   (`BLOCKED_KEPT`), and that evidence goes only by section 2.
5. **What a report holds is kept while it is open, whoever deletes it** (ADR-0053 decision 8a). The
   author may delete or withdraw the entry, or edit the photo out and delete it; to them it is gone,
   but its objects stay until you decide, and a report made after such an erasure still finds them.
   Dismissing, or upholding with `delete`, lets the purge run; `block` keeps them as evidence. That
   is a legal hold on a member's erased media: decide within the alarm's two days.
6. The reporter is not told the decision (the route says a person will read it). The author is never
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
