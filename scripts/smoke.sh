#!/usr/bin/env bash
# Smoke test for the moyi-backend API against a locally running instance.
#
# The follow-up to #162 (ADR-0059 decision 13: an archive's object deleted after the commit that
# forgets it, from a debt V54 holds) adds no probe; the data-export section's "its URL no longer
# downloads it" after a withdrawal is the one that would show the deletion moved too late. Last run
# on 2026-11-10, on fix/export-followups, from a jar of the branch's tree on e273a41, on a fresh
# database that applied 49 migrations to V54, against its own compose project (moyi-41k: Postgres
# 55491, Valkey 56411, MinIO 59491), the jar booted by hand (local profile, the close, reminders,
# held-pushes, account-deletion and data-export crons at */5) and --attach: 1602 passed, 0 failed,
# 1 skipped (the tag across the erasure, the poller having won the race); export_archive_deletions
# empty after it.
#
# Issue #165 adds four voice-note probes and three for a deleted one: the waveform media.process
# measured (ADR-0061), 64 levels of 0-255 with the loudest 255 on the author's entry, 64 bytes on the
# asset, the same levels to the listener once the note has passed, none on the locked day; and a
# loose voice note deleted by its owner keeps no waveform on its row. Last run on 2026-11-10, on
# feat/voice-waveform (main e273a416), from a jar of the branch's tree, on a fresh database that
# applied 49 migrations to V57, against its own compose project (moyi-42f: Postgres 55660, Valkey
# 56660, MinIO 57660), the jar booted by hand (local profile, the five crons at */5) and --attach:
# 1610 passed, 0 failed, 0 skipped. A run before it stopped in the media section when Colima
# killed that project's MinIO (exit 137, memory shared with four other sessions), not the code.
#
# Issue #146 adds one probe to the reveal-push section: each member's REVEAL row keeps the revealed
# day's own date (`bond_date`, V58), which the consumer sends as `data.date`. The log provider's line
# leaves the payload out, so the ledger is where a run sees it. Last run on 2026-11-10, on
# feat/push-data-date-146 (from e273a416 plus the branch's tree), against its own compose project
# (moyi-42b: Postgres 55620, Valkey 56620, MinIO 57621), the jar booted by hand (local profile, the
# close, reminders, held-pushes, account-deletion and data-export crons at */5) and --attach, on a
# fresh database: 1604 passed, 0 failed, 0 skipped.
#
# Issue #164 adds eleven entry-media probes in the voice section: removeImage or removeVoice with the
# id it would replace is 422 on removeImageWithoutImageMediaId / removeVoiceWithoutVoiceMediaId and
# changes nothing; removeImage, then removeVoice, take each off, released as a replaced one is (on no
# entry, detached_at set, still READY); removing both again is 200; both go back on. Last run on
# 2026-11-10, on feat/entry-media-remove, from a jar of the branch's tree on e273a41, on a fresh
# database, against its own compose project (moyi-42e: Postgres 55650, Valkey 56650, MinIO 57650),
# the jar booted by hand (local profile, the five crons at */5) and --attach: 1612 passed, 1 failed
# (typed codes' Retry-After, 8639 for 8640, a one-second boundary this change does not touch),
# 1 skipped (the tag across the erasure, the poller having won the race).
#
# Issue #169 adds the reset-code check: an unknown address and a wrong code one 422, the right code
# 204 twice (nothing spent), a missing code 400, the reset by the same code still 200 and a check of
# the spent code 422; three wrong checks and two wrong resets burn the code for both; and the
# eleventh typed code of the day is 429 on the check too. Last run on 2026-11-10, on
# feat/reset-code-check (main e273a41), from a jar of that tree, on a fresh database, against its own
# compose project (moyi-42d: Postgres 55640, Valkey 56640, MinIO 57640), the jar booted by hand (local
# profile, the five crons at */5) and --attach: 1619 passed, 0 failed, 0 skipped. A first run on the
# same stack failed three: two probes of mine that took one address past its ten typed codes a day
# (fixed by a flush before them), and the close job, after a four-minute host stall closed the
# pool's connections (an infrastructure fault; the fresh run is clean).
#
# Issue #149 changes the moderation section: the red (held-back) photo, refused on an entry until
# now, is put on one in a bond of its own and answered 201 NOT_SHARED with its URL to its author,
# still QUARANTINED with its verdict, PROCESSING with nothing of it to the partner, and READY to the
# partner once the operator releases it. Last run on 2026-11-10, on fix/claim-held-media from a jar of
# 93230258's tree, against its own compose project (moyi-42e: Postgres 55650, Valkey 56650, MinIO
# 57650), the jar booted by hand (local profile, the close, reminders, held-pushes, account-deletion
# and data-export crons at */5) and --attach: on a fresh database 1616 passed, 1 failed (late entries:
# "the job has run since", the ShedLock floor named under #126 below), 0 skipped; again on the same
# server, 1616 passed, 0 failed, 1 skipped (the tag across the erasure, the poller having won the race).
#
# Issue #152 adds, in the voice notes section, the AAC copy iOS streams (ADR-0060): the author's
# entry carries urlAac beside url, the listener's too once passed; it downloads as audio/mp4, starts
# with an MP4 `ftyp` box, has its `moov` index before `mdat`, is one channel of AAC-LC by ffprobe,
# holds nothing of the upload's title, and is in no log line; the deleted fake voice note leaves no
# voice.m4a either. Last run on 2026-11-10, on feat/voice-aac-variant, from a jar of 29a35044's tree,
# against its own compose project (moyi-42d: Postgres 55640, Valkey 56640, MinIO 57640), the jar
# booted by hand (local profile, the close, reminders, held-pushes, account-deletion and data-export
# crons at */5) and --attach: on a fresh database 1611 passed, 0 failed, 0 skipped.
#
# Issue #126 adds the data-export section: 401 without a token, 404 for a latest never asked for,
# 202 PREPARING and the same export again, one 404 for someone else's id and a missing one, the job
# (on a five-second cron) making it READY with a URL good for exactly 24 hours, the zip downloaded
# from MinIO, the email without a link; then the partner blocks and withdraws, and that export is
# EXPIRED and its URL dead (ADR-0059 decision 12); a new one holds the withdrawn entry as a blank
# placeholder; a repeat while one is prepared spends none of the three a day, the fourth new one is
# 429; no words or signed URL in the log; and in the deletion section, the deleted account's export is
# gone. Last run on 2026-11-09, on feat/data-export after the review of #162, from a jar of 104f0bd's
# tree, against its own compose project (moyi-41j: Postgres 55481, Valkey 56401, MinIO 59481), the jar
# booted by hand (local profile, the close, reminders, held-pushes, account-deletion and data-export
# crons at */5) and --attach: on a fresh database 1602 passed, 1 failed (late entries: "the job has
# run since", the close job's 30 s ShedLock floor against the probe's 30 s wait; nothing of the
# export's), 0 skipped; again on the same server, 1603 passed, 0 failed, 0 skipped.
#
# Issue #125 adds the account-deletion section: the password asked for again (422 absent or blank,
# 403 INVALID_CREDENTIALS wrong, without WWW-Authenticate), the 202 with its two windows, the
# idempotent repeat, the pending request on GET /me, one email, sign-in kept, a cancel and the one
# 404, then the window moved back by SQL and the job (on a five-second cron, held 30 s by ShedLock)
# carrying it out: the account anonymised and shut, the partner's bond archived, the person's entry a
# flagged tombstone, the day's status kept. Last run on 2026-11-09, on feat/account-deletion, from a
# jar of the branch's tree on 30f5873, against its own compose project (moyi-41i: Postgres 55471,
# Valkey 56391, MinIO 59471), the jar booted by hand (local profile, the account-deletion cron at
# */5) and --attach: 1560 passed, 0 failed, 0 skipped, on a fresh database, after the review fixes of
# #158. An earlier run (1555, 4 failed, 1 skipped: the tag across the erasure) waited 20 s for the
# job, which ShedLock's 30 s hold outlasts, and read psql's command tag with an UPDATE's RETURNING.
#
# Issue #135 adds the typed-codes section: the six digits in the verification and reset emails,
# read from the log; {email, code} verifies and resets; an unknown address is the same bytes as a
# wrong code; five wrong burn the code while its link still works; a new email retires the old
# code; a used code is refused with its link spent; ten typed codes a day per address and twenty
# presentations an hour per client address, each to its 429. Last run on 2026-11-09, on
# feat/typed-email-codes, from a jar of 303a1df (main at f906903, #150, merged in), on a fresh
# database that applied 44 migrations to V51, against its own compose project (moyi-41h:
# Postgres 55458, Valkey 56348, MinIO 59275; 55448 and 59266 were held by a forward docker did
# not list), the jar booted by hand (local profile) and --attach: 1454 passed, 0 failed,
# 1 skipped (the tag across the erasure, the poller having won the race).
#
# Issue #132 (the late-entry grace, ADR-0033 decision 15) adds a late-entries section: a fresh bond
# moved back three days into a region zone where it is between 01:00 and 05:59, the job writing
# the day past its grace and not yesterday, an entry with an intendedAt before midnight counting for
# yesterday, one older than twelve hours landing on today, settlesAt on today and on the streak's
# OPEN squares, and yesterday still open after more runs. Last run on 2026-11-09, on
# feat/late-entry-grace after the review fixes (e131b048, main f906903 merged in), from a jar of
# that tree, on a fresh database, against compose project moyi-41f (Postgres 55461, Valkey 56361,
# MinIO 59261), the jar booted by hand (local profile) and --attach: 1408 passed, 0 failed,
# 0 skipped. The run before (main c6428c1 merged in) had one failure: the sign-in limit's
# Retry-After, 179 for 180, a second boundary in a probe this change does not touch; it did not
# recur.
#
# Issue #170 adds two probes: BondResponse.timezoneChangeAllowedFrom null on a bond whose zone never
# moved, and equal to the 409's allowedFrom once it has.
#
# The contract-precision PR (#137 to #140, #142) adds five probes: an error body's traceId (32 hex
# digits, a new one per request) and a TIMEZONE_CHANGE_TOO_SOON refusal's typed allowedFrom, equal to
# the date its detail names, with its traceId on the log line for that refusal. Every "identical
# body" probe now compares bodies without the traceId, which differs per request by design. Last
# run on 2026-11-08, on fix/contract-precision, from a jar of 03df6263 (main at f9bef43, #148, merged
# in), on a fresh database, against its own compose project (moyi-41a: Postgres 55441, Valkey
# 56341, MinIO 59241), the jar booted by hand (local profile) and --attach: 1365 passed, 0 failed,
# 0 skipped. An earlier run on the branch failed 27 identical-body probes on the traceId, before
# they compared without it; one run with a media worker booted beside the API (to measure
# metaspace) failed 3 media probes, because the worker took those events and its log is not read.
#
# What it is written to check (a run is the proof, not this header): the
# packaged application boots against the compose Postgres,
# and every identity endpoint that exists so far — registration, verification,
# sign-in, refresh rotation, logout, password reset — answers with the status
# and the error code the API contract (doc 06) promises — on the happy path AND on
# the edges that have bitten before: malformed JSON, wrong method, reused
# token, unknown address, duplicate registration. Since Phase 2 it also proves
# that two accounts can pair by a code and that their bond is invisible to a
# third (M2, T-02, T-06), and since slice B3 that a bond can be ended and that a
# block is indistinguishable from a leave from the other side — the same bytes
# and the same ETag (doc 26 §2.1, T-09). Since slice F it drives
# every rate limit in doc 06 §4 to its 429 (FR-012, ADR-0023) and checks the
# X-RateLimit-* headers and Retry-After on the way, against the compose Valkey.
# Since the C1 rework it is written to check that a confirmed zone change is
# reported at once and decides no date until the current Bond-day ends (BR-6,
# ADR-0031). Since the C1 follow-up it also checks the Idempotency-Key contract
# on the wire, the media refusal, the author's own today, ALREADY_MEMBER, and
# that typographic text comes back as it was sent. Since slice C2 it checks the
# reveal on the wire: an edit before it, both members reading each other after
# it, ENTRY_IMMUTABLE, the tombstone a delete leaves, a replay of a deleted
# entry, and a timed bond waiting in PENDING_REVEAL. Since slice C3 it runs
# the close job for real, on a five-second schedule, and checks it took its
# lock, failed on nothing and left no day past its grace unclosed. Since slice C4 it
# reads the streak: one on the first day both have written, and on today's
# payload, and zero while a day waits on its reveal time. Since slice C5a it
# ends bonds with the outbox poller running: a block and a leave that ask for
# it have their author's entries erased within ten seconds, a
# block told to keep them leaves them readable, an author deletes on an ended
# bond, and a misspelt flag ends nothing; and the poller announces no delivery
# in the log. Since slice C5b it reads the archive: the feed and one day of it
# for each member, the 422 of each unreadable parameter, the two 404s, a day
# only one has written, the shape of an entry the caller was never shown (an
# author and a status, no other key), nothing at all once such an entry is
# erased (on the feed, the day and today), and no time of erasure on any shape,
# a bookmark set and taken off and never shown to the other, what may not be
# marked and by whom, If-None-Match answered 304 on a tag that moves with the
# caller's own mark and not with the partner's, the archive as JSON whatever
# JSON was asked for and a 406 with no tag for a caller who will take none
# (the same 406 for a stranger), and the tombstone a withdrawal leaves in it:
# no empty page with a cursor before the erasure, and the same tag after it.
# Since slice C5c it reacts: a reaction set, replaced and taken back, read by
# both members on today, the feed and the day; the author's tag moving with it
# and with nothing else the partner does; one's own entry refused, the one 404
# on both verbs for everything the caller was never shown, and each body that
# is not one known type; after an ending that withdraws nothing, no new
# reaction and the old one not removable either; and after a withdrawal, the
# reaction on the withdrawn entry gone with its words under a tag that does
# not move twice, the withdrawer's own left where it was and no longer theirs
# to take back, and both verbs answering as they do for an entry deleted by
# hand. It holds no row: 409 REACTION_BUSY and FAVOURITE_BUSY, and the 204 a
# held row gets on a tombstone, are the integration tests' alone.
# Since slice C6a it reads the day's prompt: one for the bond before the
# second member arrives, the same id for both members and each one's own
# language, the same bytes after the partner opens, writes and reveals, an
# entry recorded with the prompt it answered and nobody sent that back, one
# 422 for every id that names no prompt after the bond's 404, the prompt gone
# from the row with the entry's words, and none on an ended bond.
# Since slice C6b it searches: case and accents folded, whole words, the page
# a page of days and a cursor that no cache may keep; the partner's unrevealed
# words finding nothing and one's own found; one 422 on q for blank, long and
# NUL, carrying none of it, after the bond's 404, which is the same bytes
# whatever q is, if Tomcat can decode it (a q that is not UTF-8 is everyone's
# 400, member or stranger, after authentication); an erased entry's words gone
# from the row and from search.
# And it reads on-this-day: last year's day, put in by hand, for both members;
# and after a leave, the same nothing for both, while search still reads.
# Since slice C6c it reads milestones: nothing reached and 7 next on a new bond; the seventh
# complete day today, six earlier ones put in by hand, reached the moment both wrote and the
# same for both; and after a leave, what was reached kept and nothing next, for both alike.
# Since issue #141 it reads the run that ended and why a day is a rest day: 21 past days put in
# by hand unevaluated, the bond's pairing moved back to the first, evaluated by the real close
# job into a rest day a freeze paid for, a date a zone change stepped over, and a run of sixteen
# that an empty day ended; the same bytes for both members.
# Last run on 2026-11-08, on feat/streak-ended-run-and-freeze-reason (main d8315f5), from a jar of
# that tree, on a fresh database that applied 41 migrations to V43, against its own compose project
# (moyi-41b: Postgres 55442, Valkey 56342, MinIO 59243), the jar booted by hand (local profile) and
# --attach: 1347 passed, 0 failed, 1 skipped (the tag across the erasure, the poller having won the race).
# Since the favourites rulings (ADR-0036 questions 5 and 7) every day of the archive names the
# partner and today does not, and a favourite's row holds no time (V26).
# Since the entry-text rulings (ADR-0031, the owner's rulings 1 and 2) an entry of only
# zero-width characters is the blank 422, on a submission and an edit, and 499 letters and an
# ellipsis are 500 as typed and a 201, while 500 and a trailing space are 501 and a 422.
# Every day it reads is today, but for last year's and the milestones' six
# before today, put in by hand: no day ends during a run, so paging and past
# days are the tests' to show. Since Phase 4 N1 it registers a push token for
# the session's device, replaces and removes it, sees a logout forget it, a
# deviceless or ended session answered 404, and the token in no log line; no
# push is sent by a route, since nothing requests one before N3. Since Phase 4
# N2 it reads and sets a member's push toggles, sees the settings PUT leave
# them alone and a stranger told the bond's 404, and runs the daily reminder
# (and the release of held pushes) every five seconds: the member who wrote is
# not reminded, the one who did not is, held by her quiet hours, released when
# they are made to have passed, and taken by the logging provider. Since Phase 4
# N3 a reveal on the wire sends one push to each member, logged twice by the logging
# provider and SENT once each in the ledger, with neither the words nor the token in any
# line, and the join asks one push of the member who was there. Since Phase 4
# D1 it asks for an upload URL, sees the storage (the compose MinIO) refuse a body
# other than the one declared and take the declared one, completes the upload
# (409 before the PUT, a replay under the same key, 422 without one), reads it as
# its owner and is told 404 as another user, refuses a GIF (415) and 11 MB (413),
# deletes it and sees the object gone from MinIO, and finds the URL in no log
# line (a voice note since D2, so that the pipeline leaves it UPLOADED). Since
# Phase 4 D2 it uploads a JPEG carrying a GPS position in its EXIF and waits for
# libvips (on PATH) to make it READY: a URL and a size for its owner, the same URL
# twice in the hour, a WebP at that URL with no EXIF and no position, the upload
# gone from MinIO; a PNG declared as a JPEG FAILED; the photo attached to an
# entry, 409 MEDIA_ATTACHED to its deletion, the partner's locked day without a
# trace of it and, once revealed, PROCESSING with no URL; another user's photo and
# a failed one refused on imageMediaId; and the URL in no log line. Since Phase 4
# D3 it uploads an M4A carrying a made-up title and waits for ffmpeg (on PATH) to
# make it READY: an Ogg Opus of one channel at its URL, served as audio/ogg, with
# no title, its length on the asset; a 62-second WAV and a WAV declared as an M4A
# FAILED; and, with a photo, M4's first half: an entry with both, the voice note
# locked away from the partner until the reveal and then PROCESSING to them, as
# the photo is, until D4 moderates it (the owner's ruling on FR-061; since D4, READY to the listener
# once the local stand-in has passed it). Since Phase 4 D4 it reaches every verdict through the
# stand-in moderators, which decide by colour: a red square QUARANTINED (its author reads it
# QUARANTINED with its URL; since #149 it is put on an entry all the same, NOT_SHARED to its author
# and PROCESSING to the partner until released), a magenta one PASSED and sensitive (the
# partner is shown it READY, sensitive true), a blue one BLOCKED (no URL even for its author, held as
# evidence); the operator's moyi media quarantine lists them, refuses to release the blocked one,
# releases the red one and deletes the blue one, whose objects media.purge then removes; the audit
# log has every look and decision, the metric is scraped, the log names verdicts by id only. D4
# last run on 2026-10-29, on feat/media-d4-moderation (main f3f4338), from a jar of that tree, on a
# fresh database that applied 35 migrations to V36, against its own compose project (moyi-d4:
# Postgres 55449, Valkey 63809, MinIO 59018), the jar booted by hand (local profile, the stand-in
# moderators) on libvips 8.18.7 and ffmpeg 9.0.2, and --attach: 1194 passed, 0 failed, 0 skipped,
# 32 sections. Again after the review of #113 (7730439 and its fixes), on a fresh database, the
# same way: 1194 passed, 0 failed, 1 skipped (the tag across the erasure, the poller having won the
# race). The reveal push's log-line count that once found 0, and reminders left REQUESTED, were a
# stray jar of the author's on the same database taking the deliveries (its jar file replaced under
# it: NoClassDefFoundError), not the code; the probe now polls and says what it saw. The reports
# section (FR-100, ADR-0053): the reporter's 404s (unknown, locked, an outsider), 409 on their own
# entry, 422s, the report and its replay, what each member is shown before and after, the
# operator's list, show, dismiss and a priority report upheld by blocking, 429 on the eleventh, and
# the detail in no log line; since the review of #118, delete refused on a minor report, and a
# reported entry erased by its author whose photo stays in MinIO until the dismissal, then is purged.
# Last run on 2026-10-30, after that review, on feat/report-route (main e88944b), from a jar of
# 2c0e3bf, on a fresh database that applied 38 migrations to V39, against its own compose project
# (moyi-report: Postgres 55454, Valkey 63814, MinIO 59028), the jar booted by hand (local profile)
# and --attach: 1273 passed, 0 failed, 0 skipped, 33 sections. Before it, from 3e8a78a: 1254, 0, 0.
# Since issue #128 (ADR-0056) it reads the legal documents with no token, its ETag and a 304 on
# it; sees registration record the server's versions and not the one the request sent, GET /me
# report them, re-consent answer 204 twice and add no row, and 401 without a token. Last run on
# 2026-11-08, on feat/meta-legal-and-consent-versions, from a jar of the tree this header was
# committed with (only this paragraph added after), on a fresh database that applied 40
# migrations to V41, against its own compose project (moyi-41c: Postgres 55443, Valkey 56343,
# MinIO 59245), the jar booted by hand (local profile) and --attach: 1339 passed, 0 failed,
# 0 skipped, 35 sections.
# Since in-app support (FR-102, ADR-0057) it writes to support: 401 without a token, 422 on subject
# and on body (each repeating nothing sent), a 201 and its replay under the key (same body, one
# ticket), the sixth in a day 429 with Retry-After, the acknowledgement logged once by the logging
# provider with the ticket's id and without a word of the message, and the operator's `moyi support`
# list, show and close (the words emptied, each look logged). Last run on 2026-11-08, on
# feat/support-messages, from a jar of a470027 (main's #147 merged in), on a fresh database migrated
# to V47, against its own compose project (moyi-41d), --attach: 1389 passed, 0 failed, 0 skipped.
# Before it, from a jar of a6e5b08 (main's #145 and #148 merged in, the CHECK at V47),
# on a fresh database that applied every migration to V47, against its own compose project
# (moyi-41d: Postgres 55444, Valkey 56344, MinIO 59247), the jar booted by hand (local profile)
# and --attach: 1384 passed, 0 failed, 0 skipped. Before it, from 14ce084 (before the merge, the
# CHECK then a decimal V45.1): 1350, 0 and 1 skipped (the tag across the erasure).
# Since the member report (#127, ADR-0053 decision 14) a section of its own: a bond with nobody to
# report and a stranger the same 404, 422 on reason, 201 with {id, reason} and its replay, one MEMBER
# row holding nothing, the reported member's GET /bonds/{id} and GET /today byte for byte and ETag for
# ETag the same after it, 201 after a block, the operator's list and dismissals, the detail in no log.
# Last run on 2026-11-09 on feat/report-member after main's #150 (f906903) was merged in, from a
# jar of that merge (a1ce621), on a fresh database that applied 44 migrations to V48, against its
# own compose project (moyi-41e: Postgres 55445, Valkey 56345, MinIO 59249), the jar booted by hand
# (local profile) and --attach: 1424 passed, 0 failed, 1 skipped (the tag across the erasure, the
# poller having won the race), 38 sections. Before the merge, at V46 (since renumbered V48): 1362,
# 0, 1 skipped, 35 sections; the run before that failed one probe, the entry reports' list check
# still expecting the list's old columns.
# Since the admin console A1 (FR-110, FR-111, FR-116, ADR-0055) it signs in to /admin: a refused address
# (X-Forwarded-For through the trusted loopback) gets the API's own 401, byte for byte, with or without a
# bearer, and no cookie; an operator invited by admin_invite (the token stored only as its hash) is
# enrolled in the browser, a wrong code enrolling nothing and the link spent; a sign-in needs the password
# and then a code (oathtool, else the section is skipped and says so), one factor reaching nothing, a
# wrong code and a replayed one refused, no CSRF token a 403, sign-out ending the session, five failures
# locking the account; and audit_log holds the rows, none with an email, while UPDATE and DELETE on it
# raise. It cannot show the console OFF (404 with a bearer): the local profile has it on, and
# AdminDisabledTest shows it. Last run on 2026-11-11, on feat/admin-console-a1 after main (ca77fe4) was
# merged in and the migration renumbered V60, at 818a00f (its jar built from the same main sources), on a
# fresh database that applied 54 migrations to V60, against its own compose project (moyi-131s: Postgres
# 55531, Valkey 56531, MinIO 59531), the jar booted by hand (local profile, the five crons at */5) and
# --attach: 1716 passed, 0 failed, 1 skipped (the tag across the erasure, the poller having won the
# race), 42 admin checks among them. Before the merge, on 2026-11-06, at V42: 1369, 0, 0, 35 sections.
# Since semantic search S1 (FR-204, ADR-0054) it runs with the embedding provider `none`, as the
# local profile and production name it: search 200 by words alone; "Remember when" 200 from the
# words' ranking alone (and a plain question whose words are not all in one entry answers no day),
# its q and limit each one 422, a stranger's 404; PATCH true a 422 ASSERT_FALSE on the field; a
# vector put in by hand gone once one member turns the setting off and the outbox runs; back on
# by both members (a request, a repeat, a cancel, an agreement), a solo bond alone; and an ended
# bond taking `false` and nothing else (decision 9, provisional). Last run on 2026-11-02, on
# feat/semantic-search-s1, from a jar of e9daa6f, on a fresh database that applied 40 migrations
# to V41, against its own compose project (moyi-s1: Postgres 55461, Valkey 56401, MinIO 59291),
# the jar booted by hand (local profile) and --attach: 1327 passed, 0 failed, 0 skipped, 34
# sections. The run before it, on the same jar, failed one probe of its own: it had asked
# "remember" a plain question and expected the words' ranking to find it (see the section). D3 last run
# on 2026-10-29, after main (d2429fc D2, 66032c0 D5) was merged in, from a jar of
# that merge, on a fresh database that applied 34 migrations to V35, against its own
# compose project (moyi-d3: Postgres 55443, Valkey 63803, MinIO 59006), the jar
# booted by hand on libvips 8.18.7 and ffmpeg 9.0.2, and --attach: 1151 passed, 0
# failed, 2 skipped (the tag across the erasure, the poller having won the race),
# 31 sections. Since the owner's ruling of 2026-10-29 (ADR-0049) an account that
# never verified is refused an upload intent and a completion with 403
# EMAIL_NOT_VERIFIED, and is not refused a read or a delete. Last run on
# 2026-10-29 on main f3f4338 with that change, from a jar of that tree, on a
# fresh database that applied 34 migrations to V35, against its own compose
# project (moyi-vemail: Postgres 55448, Valkey 63808, MinIO 59016), the jar booted
# by hand, and --attach: 1160 passed, 0 failed, 0 skipped. Since Phase 4
# D5 the photographer then deletes the entry the photo is on: the owner's GET of
# it is 404, the row DELETED with one MediaReleased, media.purge stamps it within
# seconds, the URL handed out before answers 404 and MinIO holds nothing of it.
# D5 last run on 2026-10-29, after the review of #110 (V34 and V35), on D2 0e73c8a (with
# main df44dcc), from a jar of that tree, on a fresh database that applied 34 migrations
# to V35, against
# its own compose project (moyi-d5: Postgres 55445, Valkey 63805, MinIO 59010), the
# jar booted by hand on libvips from PATH, and --attach: 1110 passed, 0 failed, 0
# skipped, 30 sections.
# D2's: last run on
# 2026-10-29, after main (df44dcc: #101, #102, #103, #105, #109) was merged in, from a jar of
# that merge, on the moyi-d2 database (32 migrations to V33; Postgres 55441, Valkey 63801,
# MinIO 59002), libvips 8.18.7, --attach: 1102 passed, 0 failed, 0 skipped. Before it, on
# 2026-10-28 for D2, from a jar of this branch on main 7656192, on a fresh database
# that applied 32 migrations to V33, against its own compose project (moyi-d2:
# Postgres 55441, Valkey 63801, MinIO 59002), the jar booted by hand on libvips
# 8.18.7, and --attach: 1081 passed, 0 failed, 1 skipped (the tag across the
# erasure, the poller having won the race), 29 sections. Since the display-name rule (ADR-0015, amended 2026-10-28) it sends a name holding
# U+202E and is told 422 VALID_DISPLAY_NAME on displayName. Last run on 2026-10-29, for
# feat/display-name-controls after main (d9c2de4, #105) was merged in, from a jar of
# 7e421b3's code, on a fresh database that applied V1 to V27 and V29 to V33, against its own
# compose project (moyi-0f: Postgres 55443, Valkey 56390, MinIO 59273) and --attach: 1064
# passed, 0 failed, 1 skipped (the tag across the erasure, the poller having won the race),
# 28 sections. Before it, on 2026-10-28, for
# feat/display-name-controls at b52ee87 (the review's fixes: a lone surrogate refused, a
# name of only no-break spaces a 422), from a jar of that commit, on a fresh database that
# applied V1 to V27 and V29 to V33 in that order, against its own compose project (moyi-0f:
# Postgres 55443, Valkey 56390, MinIO 59100) and --attach: 1046 passed, 0 failed,
# 1 skipped (the tag across the erasure, the poller having won the race), 28 sections.
# Before it, on 2026-10-28, for that branch on main at 7656192, from a jar of the branch, on a fresh
# database that applied V1 to V27 and V29 to V33 in that order, against its own compose
# project (moyi-0f: Postgres 55443, Valkey 56390, MinIO 59100) and --attach: 1046 passed,
# 0 failed, 1 skipped (the tag across the erasure, the poller having won the race),
# 28 sections. Before them,
# on 2026-10-29, for the entry-text rulings (ADR-0031 rulings 1 and 2), after
# main (51df716, #103) was merged in, from a jar of b534158's code, on a fresh database that
# applied V1 to V27 and V29 to V33, against its own compose project (moyi-0f: Postgres 55443,
# Valkey 56390, MinIO 59273) and --attach: 1062 passed, 0 failed, 1 skipped (the tag across
# the erasure, the poller having won the race), 28 sections; on 2026-10-28 the same branch
# after #106, from 091bfa2, 1051, 0 and 0 in 28. Before it,
# on 2026-10-29, for no reaction taken back after an ending (ADR-0037
# q11), after main (df153d3, #102) was merged in, from a jar of f7e0654's code, on a fresh
# database that applied V1 to V27 and V29 to V33, against its own compose project (moyi-0f:
# Postgres 55443, Valkey 56390, MinIO 59273) and --attach: 1057 passed, 0 failed, 0 skipped,
# 28 sections; on 2026-10-28 the same branch after #106, from 61510be, 1046, 0 and 0 in 28.
# Before them, on 2026-10-29, for withdrawEntries required on /block, after main (e85ba1d,
# #101) was merged in, from a jar of 6c6c3cd's code, on a fresh database that applied V1
# to V27 and V29 to V33, against its own compose project (moyi-0f: Postgres 55443, Valkey
# 56390, MinIO 59273) and --attach: 1056 passed, 0 failed, 0 skipped, 28 sections. Two runs
# before it on the same jar stopped in the media section with 503: another project's MinIO
# held this project's port. Before them, on 2026-10-28, for withdrawEntries required on
# /block (ADR-0035 q1, q11), after main (35119af, #106) was
# merged in, from a jar of 11d1e68's code, on a fresh database that applied V1 to V27
# and V29 to V33, against its own compose project (moyi-0f: Postgres 55443, Valkey
# 56390, MinIO 59100) and --attach: 1053 passed, 0 failed, 0 skipped, 28 sections. Before it, on 2026-10-28,
# for an unseen erasure shown as nothing (ADR-0032 q2), after main (35119af, #106) was
# merged in, from a jar of 28157c5's code, on a fresh database that applied V1 to V27
# and V29 to V33, against its own compose project (moyi-0f: Postgres 55443, Valkey
# 56390, MinIO 59100) and --attach: 1048 passed, 0 failed, 0 skipped, 28 sections. Before it, on 2026-10-28,
# after main (b2da5a7, the squash of #100, V31) was merged
# into D1, from a jar of that merge, on a fresh database that applied V1 to V27 and V29
# to V33 in that order, against its own compose project (moyi-d1: Postgres 55440, Valkey
# 63800, MinIO 59000) and --attach: 1045 passed, 0 failed, 0 skipped, 28 sections.
# Before it, on 2026-10-28, for D1 rebased onto main at 0a663d5 (#99, V30),
# from a jar of that tree, on a fresh database that applied V1 to V27, V29, V30,
# V32 and V33, against its own compose project (moyi-d1: Postgres 55440, Valkey
# 63800, MinIO 59000) and --attach: 1034 passed, 0 failed, 0 skipped, 27 sections;
# and the same again from a jar of 9718c61 (the review's fixes: storage calls
# bounded and outside the transaction, MinIO pinned by digest), on a fresh database.
# Before them, N3's own runs: on 2026-10-28,
# after main (0a663d5, the squash of #99) was merged into N3, from a jar of that merge, on a
# fresh database that applied V1 to V27, V29, V30 and V31, against its own compose project
# (moyi-n3: Postgres 55438, Valkey 63798) and --attach: 1009 passed, 0 failed, 2 skipped
# (both the tag across the erasure, the poller having won the race), 27 sections. Before
# it, on 2026-10-28, after N2 with main and #96 (eb999d2) was merged into N3, from a jar of that merge, on a
# fresh database that applied V1 to V27, V29, V30 and V31, against its own compose project
# (moyi-n3: Postgres 55438, Valkey 63798) and --attach: 1011 passed, 0 failed, 0 skipped,
# 27 sections. Before it, on 2026-10-28,
# after the rebased N1 and N2 (2062b68) were merged into N3, from a jar of that merge,
# against its own compose project (moyi-n3: Postgres 55438, Valkey 63798) and --attach:
# 1008 passed, 0 failed, 0 skipped, 27 sections. The first run on that fresh database
# failed three reminder probes: the job's ShedLock holds at least thirty seconds and the
# probe waited twenty, so it now waits forty. Before it, on 2026-10-28,
# for N3 after the rulings on its independent review (the on-this-day week counted from
# the ledger, V31), from a jar of that code, against a fresh database of its own compose
# project (moyi-n3: Postgres on 55438, Valkey on 63798) and --attach: 1004 passed,
# 0 failed, 0 skipped, 27 sections. Before it, N3's first code, 1004, 0 and 0 on that
# project's database after a first run, which had failed one probe of its own, which had
# assumed the join's push was settled before a toggle PUT a second later (it is read when
# the consumer runs), and now allows either. Before them, on
# 2026-10-28, with main merged in (#96 as bd94436, #104's V27), from a jar of that
# merge, on a fresh database that applied V1 to V27, V29 and V30, against its own
# compose project (Postgres 55436, Valkey 63796) and --attach: 1000 passed, 0 failed,
# 0 skipped, 26 sections. Before it, on 2026-10-28, after N1 was rebased onto main
# (9fc8444) and merged in, from a jar of that merge, against its own compose project
# (Postgres 55436, Valkey 63796) and --attach: 997 passed, 0 failed, 0 skipped, 26
# sections. Before it, after the review of #99 (a reminder called off by writing, a
# half-hour lookback), from a jar of that code on the pre-rebase N1, against a fresh
# database and --attach, its own compose project (Postgres on 55436, Valkey on 63796):
# 992 passed, 0 failed, 1 skipped (the tag across the erasure, the poller having won
# the race), 26 sections; the change's first jar, 993, 0 and 0; and the run before
# that found that a reminder falling before its bond began failed the whole sweep
# (ADR-0045).
# For N1, on 2026-10-28,
# from a jar of 56a7941 (that change with main and #104, V27, merged in), against
# a compose project of its own (moyi-n1, Postgres 55434, Valkey 63794) and
# --attach: 959 passed, 0 failed, 1 skipped (the tag across the erasure, the
# poller having won the race), 25 sections. Before it, on 6ecece5: 957 passed,
# 0 failed, 0 skipped.
# Before it, for ME_TOO after main
# (#97, V26) was merged in, from a jar of a56ac42's code, against a database of
# its own and --attach, under compose project moyi-0f with Postgres on 55443 and
# Valkey on 56390: 939 passed, 0 failed, 0 skipped, 24 sections. Before it, for
# ME_TOO (ADR-0037 question 1, V27), from a jar of 91afa4d's code, against a database of its
# own and --attach, under compose project moyi-0f with Postgres on 55453 and
# Valkey on 56391: 935 passed, 0 failed, 0 skipped, 24 sections. Before it, on 2026-10-27, after the favourites
# rulings' review fixes, from a jar of f737173's code, against a database of its
# own and --attach, Postgres on 55432: 936 passed, 0 failed, 0 skipped, 24
# sections. Before it, for the favourites rulings, from a jar of 86ae0c9's code, against a database of its own and
# --attach, Postgres on 55432 because another project's container held 5432:
# 935 passed, 0 failed, 1 skipped (a tag-across-the-erasure check, the poller
# having won the race), 24 sections. Before it, on 2026-10-24, for the production
# configuration (health at /healthz, the scrape on MGMT_PORT, problem types on
# api.getmoyi.app), from a jar of b4386b9's code, against a database of its own
# and --attach, Postgres on 55432: 932 passed, 0 failed, 0 skipped, 24 sections.
# Before it, on 2026-10-22, for the two rulings of
# ADR-0040 (what an ended bond reveals), from 6445d92's jar (its code is
# 7c8d408's), against a database of its own and --attach, Postgres on port
# 55432 because another project's container held 5432: 930 passed, 0 failed,
# 0 skipped, 24 sections; no probe changed, the rulings being the tests'
# to show. Before it, for slice C6c, by its
# final review, from 95ba089's jar: 930 passed, 0 failed, 0 skipped, 24
# sections. Before it, against a database of its own (MOYI_DB, below), from
# 1e1fc07 (named as it stands after the rebase onto dcb3b36; the jar was built
# from the same tree before it; the commits after it change only tools, the
# state map and the record), with
# --attach to a jar booted by hand exactly as below but on Postgres's port
# 55432, because another project's container held 5432: 930 passed, 0 failed,
# 0 skipped, 24 sections. The run before it, on the same jar and a fresh
# database, 929, 1 skipped (a tag-across-the-erasure check, the poller having
# won the race) and 0 failed in 24. Before them, for C6b, from 569b420 (the
# commit after it changed only this header and ADR-0039), 902, 0 and 0 in 23.
# Before it, from e1ac2be (its jar's code 50c269a's, both named as they stand
# after the rebase onto 25fa9c1), 902, 0 and 0 in 23; the final review's run at
# c5b98b6 skipped one tag-across-the-erasure check, the poller having won the
# race. On 2026-10-21, at 683af97 (its jar's code
# is 0f04535's), 852, 0 and 0 in 22; the run before that, on the same
# head, skipped the two tag-across-the-erasure checks because the poller won
# the race, which is what they skip on. On 2026-10-20, from 0f04535, 850, 0
# and 0 in 22; on 2026-10-15, from dfac89b, 814, 0 and 0 in 21 (the
# first run to count its skips); on 2026-10-13 it
# was 814 and 0, no skips, in 21, and on 2026-10-09 647 and 0 in 20. A run on 2026-10-07, with the
# poller held idle, failed the four probes that then depended on it; three did
# after that, the C5b section adds its own, and that run has not been
# repeated. The pull request
# that last changed this file names the commit the
# jar was built from; a later commit is unproven until it is run again.
#
# If the application refuses to start on a Flyway checksum mismatch: V11, V12
# and V13 were edited in place while unmerged (ADR-0031), so a database that
# applied an earlier copy of them no longer matches. Resetting the checksums
# is NOT the repair: it only stops Flyway refusing and re-runs nothing, and V11
# and V12 changed table shape, so the app would then fail on missing columns.
# The repair is for the DEV database only and is never this script's to run.
# It was executed once, on 2026-10-03 (ADR-0031, Owed, records what it found):
#   DROP TABLE IF EXISTS entries, bond_days, idempotency_keys, bond_anchor_intervals CASCADE;
#   DELETE FROM flyway_schema_history WHERE version IN ('11','12','13');
# then start the application so Flyway re-applies V11-V13 (V13 backfills the
# timeline for existing bonds). It destroys that database's entries, days and
# idempotency keys; users, bonds and invites are untouched.
#
# What it does not prove: anything the unit and integration tests already
# prove. This is the "run it, do not read it" check (docs/learning-log.md,
# 2026-09-19 onward): green tests are a claim about where you ran them.
#
# Usage:
#   scripts/smoke.sh              # builds the jar, boots it, probes, stops it
#   scripts/smoke.sh --no-build   # reuse app/build/libs/*.jar
#   PORT=18080 MGMT_PORT=18081 scripts/smoke.sh   # boot on other ports
#   BASE=http://localhost:8080 scripts/smoke.sh --attach  # probe a server you started yourself;
#                                                          # needs MOYI_LOG=<its log file> to read the emailed link
#   MOYI_JAVA=/path/to/jdk-25/bin/java scripts/smoke.sh   # boot with that JDK instead of the one found
#   MOYI_DB=moyi_smoke scripts/smoke.sh                    # a database of its own, created if absent
#                                                          # (with --attach it only names the database the
#                                                          # checks read: start your server against the same one)
#
# MOYI_DB is for a branch that carries a migration `main` does not have yet.
# The compose Postgres is shared by every checkout and every worktree, and a
# migration applied to its `moyi` database stays applied: every other branch
# then refuses to start ("detected applied migration not resolved locally"),
# and if the migration is edited before it merges, that database needs the
# hand repair described above. A database of its own costs a few seconds and
# is dropped with `docker compose exec postgres dropdb -U moyi <name>`. The
# rate-limit buckets are still the shared Valkey's, flushed as before.
#
# The jar is compiled for JDK 25 (build-logic's jvmToolchain), which is not
# necessarily the `java` on PATH: Gradle downloads its own toolchain and
# SDKMAN's `current` is only on PATH in an interactive shell. Booting the jar
# on JDK 21 dies at once with UnsupportedClassVersionError — and the old loop
# below waited its full ninety seconds before saying "timed out". Both found
# on 2026-09-24 by running this script from a non-interactive shell.
set -euo pipefail

cd "$(dirname "$0")/.."
PORT="${PORT:-8080}"
# The actuator's own port (application.yml management.server.port).
MGMT_PORT="${MGMT_PORT:-8081}"
MOYI_DB="${MOYI_DB:-moyi}"
[[ "$MOYI_DB" =~ ^[a-z_][a-z0-9_]*$ ]] || { echo "MOYI_DB must be a plain lower-case database name, got '$MOYI_DB'" >&2; exit 2; }
BASE="${BASE:-http://localhost:$PORT}"
API="$BASE/api/v1"
MGMT_BASE="${MGMT_BASE:-http://localhost:$MGMT_PORT}"
BUILD=1
ATTACH=0
for arg in "$@"; do
  case "$arg" in
    --no-build) BUILD=0 ;;
    --attach)   ATTACH=1 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

PASS=0; FAIL=0; SKIP=0
pass() { PASS=$((PASS+1)); printf '  ok   %s\n' "$1"; }
fail() { FAIL=$((FAIL+1)); printf '  FAIL %s\n       %s\n' "$1" "$2"; }
# A check this run could not make (psql unreachable, or the timing it needs did
# not happen): counted, so that "0 failed" is never read as "all checked".
skip() { SKIP=$((SKIP+1)); printf '  skip %s\n' "$1"; }

# The first JDK that can run the jar, in order of how deliberately it was
# chosen: MOYI_JAVA, JAVA_HOME, SDKMAN's current and then any SDKMAN 25+,
# macOS's java_home, and finally whatever `java` is. Prints its path.
MIN_JAVA=25
# The version line, not the first line: a set JAVA_TOOL_OPTIONS or _JAVA_OPTIONS
# makes the JVM print "Picked up …" above it, and every JDK would then look too old.
java_major() { "$1" -version 2>&1 | grep -m1 'version "' | sed -E 's/.*"([0-9]+)[^"]*".*/\1/'; }
find_java() {
  local candidate
  for candidate in "${MOYI_JAVA:-}" "${JAVA_HOME:+$JAVA_HOME/bin/java}" \
      "$HOME/.sdkman/candidates/java/current/bin/java" "$HOME"/.sdkman/candidates/java/*/bin/java \
      "$(/usr/libexec/java_home -v "$MIN_JAVA+" 2>/dev/null)/bin/java" "$(command -v java || true)"; do
    [ -n "$candidate" ] && [ -x "$candidate" ] || continue
    [ "$(java_major "$candidate")" -ge "$MIN_JAVA" ] 2>/dev/null && { echo "$candidate"; return 0; }
  done
  return 1
}

# Empties every rate-limit bucket in the compose Valkey. The buckets are the
# point of slice F, and they are also what makes a second run of this script —
# or the lockout flow below, which signs in more than five times — collide with
# the first. Flushing between sections keeps each probe about one thing.
flush_buckets() { docker compose exec -T redis valkey-cli FLUSHALL >/dev/null 2>&1 || true; }

# expect <label> <expected-status> [expected-substring-in-body] -- <curl args>
# Leaves the body in LAST_BODY and the response headers in LAST_HEADERS, so a
# follow-up `header_is` can check a header of the SAME response rather than
# re-issuing the request — which would spend another rate-limit token.
expect() {
  local label="$1" want="$2" needle="${3:-}"; shift 3; [ "${1:-}" = "--" ] && shift
  local body hdrs status
  body="$(mktemp)"; hdrs="$(mktemp)"
  # JSON by default; a probe that names its own Content-Type gets only that one.
  local -a headers=(-H 'Content-Type: application/json')
  case "$*" in *Content-Type:*) headers=() ;; esac
  status="$(curl -s -o "$body" -D "$hdrs" -w '%{http_code}' "${headers[@]}" "$@")"
  local text; text="$(cat "$body")"; rm -f "$body"
  LAST_HEADERS="$(tr -d '\r' < "$hdrs")"; rm -f "$hdrs"
  if [ "$status" != "$want" ]; then
    fail "$label" "expected HTTP $want, got $status: ${text:0:200}"
  elif [ -n "$needle" ] && [[ "$text" != *"$needle"* ]]; then
    fail "$label" "expected body to contain '$needle': ${text:0:200}"
  else
    pass "$label ($status)"
  fi
  # An error body's traceId is the id of its own request's trace (ADR-0024, amended 2026-11-08): different on
  # every request by design, and so different between two refusals that are otherwise byte for byte the same.
  # LAST_BODY is the body without it, which is what every "identical body" probe compares (the id is random per
  # request, so it says nothing about the bond or the account); LAST_RAW_BODY is the body as sent.
  LAST_RAW_BODY="$text"
  LAST_BODY="$(printf '%s' "$text" | sed -E 's/,"traceId":"[0-9a-f]{32}"//')"
}

# header_is <label> <header-name> <expected-value> — checked against LAST_HEADERS.
header_is() {
  local label="$1" name="$2" want="$3" got
  # `|| true`: under `set -eo pipefail` a header that is simply absent made
  # grep fail the assignment and the whole script exit 1 without a FAIL line.
  got="$(printf '%s\n' "$LAST_HEADERS" | grep -i "^$name:" | head -1 | cut -d' ' -f2- || true)"
  if [ "$got" = "$want" ]; then pass "$label ($name: $got)"; else fail "$label" "expected $name: $want, got '${got:-<absent>}'"; fi
}

# keep_body <name> — with SMOKE_BODIES=<dir>, writes LAST_BODY there as <name>.json: real bodies of each
# shape a generated client must decode (a partner's entry LOCKED, REVEALED and DELETED; a problem with its
# extension members), for testing a client's decoder against (#140). Nothing is written without the variable.
keep_body() { [ -n "${SMOKE_BODIES:-}" ] || return 0; mkdir -p "$SMOKE_BODIES"; printf '%s' "$LAST_RAW_BODY" > "$SMOKE_BODIES/$1.json"; }

# What has to be undone however the script ends: the app it booted, and the
# prompts section's translations, which it puts into the shared library for one
# run. Under `set -e` a run can die anywhere; left behind, those `PT~` texts
# would be served to every pt-* user of this database, and the next run's
# insert would find them already there and skip its pt-PT probe.
APP_PID=""
PR_TRANSLATIONS_IN=0
on_exit() {
  if [ "$PR_TRANSLATIONS_IN" = 1 ]; then sql "DELETE FROM prompt_texts WHERE text LIKE 'PT~ %'" >/dev/null || true; fi
  if [ -n "$APP_PID" ]; then kill "$APP_PID" 2>/dev/null || true; wait "$APP_PID" 2>/dev/null || true; fi
}
trap on_exit EXIT

if [ "$ATTACH" = 0 ]; then
  command -v docker >/dev/null || { echo "docker is required"; exit 1; }
  # MinIO and its one-shot bucket job too: the media section uploads to it (ADR-0047).
  docker compose up -d postgres minio minio-bucket >/dev/null
  if [ "$MOYI_DB" != "moyi" ]; then
    for _ in $(seq 1 30); do docker compose exec -T postgres pg_isready -U moyi -d moyi >/dev/null 2>&1 && break; sleep 1; done
    docker compose exec -T postgres pg_isready -U moyi -d moyi >/dev/null 2>&1 || { echo "postgres did not become ready"; exit 1; }
    docker compose exec -T postgres psql -U moyi -d postgres -Atc "SELECT 1 FROM pg_database WHERE datname = '$MOYI_DB'" | grep -q 1 \
      || docker compose exec -T postgres createdb -U moyi "$MOYI_DB"
    echo "using database $MOYI_DB, not the shared moyi"
  fi
  if [ "$BUILD" = 1 ]; then
    echo "building the jar…"
    ./gradlew -q :app:bootJar
  fi
  JAR="$(ls app/build/libs/app-*-SNAPSHOT.jar | grep -v plain | head -1)"
  JAVA="$(find_java)" || { echo "no JDK $MIN_JAVA or newer found: set MOYI_JAVA or JAVA_HOME, or install one (README: sdk install java 25.0.4-tem)"; exit 1; }
  MOYI_LOG="$(mktemp -t moyi-smoke.XXXXXX.log)"
  echo "booting $JAR (local profile) on $("$JAVA" -version 2>&1 | head -1), log: $MOYI_LOG"
  "$JAVA" -jar "$JAR" --spring.profiles.active=local --server.port="$PORT" --management.server.port="$MGMT_PORT" \
    --spring.datasource.url="jdbc:postgresql://localhost:5432/$MOYI_DB" \
    --moyi.scheduling.close.cron='*/5 * * * * *' \
    --moyi.scheduling.reminders.cron='*/5 * * * * *' --moyi.scheduling.held-pushes.cron='*/5 * * * * *' \
    --moyi.scheduling.account-deletion.cron='*/5 * * * * *' --moyi.scheduling.data-export.cron='*/5 * * * * *' >"$MOYI_LOG" 2>&1 &
  APP_PID=$!
  for _ in $(seq 1 90); do
    grep -q "Started MoyiApplication" "$MOYI_LOG" && break
    grep -q "APPLICATION FAILED" "$MOYI_LOG" && { echo "the application failed to start:"; grep -A3 "APPLICATION FAILED" "$MOYI_LOG"; exit 1; }
    # A JVM that could not even load the main class is gone long before any
    # Spring banner: notice, rather than wait out the timeout.
    kill -0 "$APP_PID" 2>/dev/null || { echo "the application exited before it started:"; head -5 "$MOYI_LOG"; exit 1; }
    sleep 1
  done
  grep -q "Started MoyiApplication" "$MOYI_LOG" || { echo "timed out waiting for startup"; tail -20 "$MOYI_LOG"; exit 1; }
else
  : "${MOYI_LOG:?--attach needs MOYI_LOG=<path to the log file of the running server>}"
fi

EMAIL="smoke-$(date +%s)-$RANDOM@example.com"
PASSWORD="correct horse battery"
register_body() { printf '{"email":"%s","password":"%s","displayName":"%s","locale":"%s","acceptedTermsVersion":"2026-09-01","over18":true}' "$1" "$PASSWORD" "${3:-Smoke}" "${2:-en}"; }

# A previous run's buckets must not count against this one.
flush_buckets

echo; echo "health"
expect "GET /healthz is UP" 200 '"status":"UP"' -- "$BASE/healthz"
expect "the actuator is not on the application's port" 404 '"code":"NOT_FOUND"' -- "$BASE/actuator/prometheus"
expect "the Prometheus scrape is on the management port" 200 'moyi_rate_limit_backend_unavailable_total' -- "$MGMT_BASE/actuator/prometheus"

echo; echo "registration (FR-001, FR-011, ADR-0015)"
# Each probe arrives from its own address. Registration is three an hour per
# IP (FR-012), spent BEFORE validation, so seven probes from one address would
# meet the limit on the fourth; the local profile trusts loopback as a proxy,
# so X-Forwarded-For chooses the client. The rate-limit section below is where
# that limit is the subject.
EMAILS_BEFORE="$(grep -c "Email NOT sent" "$MOYI_LOG" || true)"
expect "valid registration is 201 with an empty body" 201 "" -- -X POST "$API/auth/register" -H 'X-Forwarded-For: 203.0.113.1' -d "$(register_body "$EMAIL")"
[ -z "$LAST_BODY" ] && pass "…and the body really is empty" || fail "empty body" "got: ${LAST_BODY:0:100}"
expect "same address again (different case) is still 201" 201 "" -- -X POST "$API/auth/register" -H 'X-Forwarded-For: 203.0.113.2' -d "$(register_body "$(echo "$EMAIL" | tr a-z A-Z)")"
expect "7-character password is 422 VALID_PASSWORD" 422 '"code":"VALID_PASSWORD"' -- -X POST "$API/auth/register" -H 'X-Forwarded-For: 203.0.113.3' -d '{"email":"x@example.com","password":"short12","displayName":"S","acceptedTermsVersion":"1","over18":true}'
expect "breached password is 422 NOT_BREACHED" 422 '"code":"NOT_BREACHED"' -- -X POST "$API/auth/register" -H 'X-Forwarded-For: 203.0.113.4' -d '{"email":"x@example.com","password":"password","displayName":"S","acceptedTermsVersion":"1","over18":true}'
expect "over18=false is 422 on field over18" 422 '"field":"over18"' -- -X POST "$API/auth/register" -H 'X-Forwarded-For: 203.0.113.5' -d '{"email":"x@example.com","password":"correct horse battery","displayName":"S","acceptedTermsVersion":"1","over18":false}'
# A display name holds no control or format character but ZWNJ and ZWJ (ADR-0015,
# amended 2026-10-28). U+202E travels as the JSON escape, so this file holds no
# literal of it, and the answer names the rule, never the name.
expect "a display name with U+202E is 422 VALID_DISPLAY_NAME" 422 '"code":"VALID_DISPLAY_NAME"' -- -X POST "$API/auth/register" -H 'X-Forwarded-For: 203.0.113.8' -d "$(register_body "x@example.com" en 'Ad\u202Ea')"
[[ "$LAST_BODY" == *'"field":"displayName"'* ]] && pass "…on the field displayName" || fail "display name field" "got: ${LAST_BODY:0:200}"
expect "malformed JSON is 400 MALFORMED_REQUEST" 400 '"code":"MALFORMED_REQUEST"' -- -X POST "$API/auth/register" -H 'X-Forwarded-For: 203.0.113.6' -d '{"email": '
expect "wrong content type is 415" 415 '"code":"UNSUPPORTED_MEDIA_TYPE"' -- -X POST "$API/auth/register" -H 'X-Forwarded-For: 203.0.113.7' -H 'Content-Type: text/plain' -d 'x'
# Since E1 (ADR-0019) the resource server answers before routing does: anything
# under /api/v1 that is not a permitted public POST needs a bearer token, so an
# unknown route is 401, not 404 — and the 401 still carries a code.
expect "unknown route under /api/v1 is 401 UNAUTHENTICATED" 401 '"code":"UNAUTHENTICATED"' -- "$API/auth/nope"

echo; echo "resource server (ADR-0019)"
expect "GET /me without a token is 401 UNAUTHENTICATED" 401 '"code":"UNAUTHENTICATED"' -- "$API/me"
# Doc 06 §2, ADR-0024 (amended 2026-11-08): every error body carries the W3C id of its request's trace.
[[ "$LAST_RAW_BODY" =~ \"traceId\":\"[0-9a-f]{32}\" ]] && pass "…and the error body carries a traceId, 32 hex digits" || fail "traceId on an error body" "${LAST_RAW_BODY:0:250}"
FIRST_TRACE="$(python3 -c "import json,sys; print(json.load(sys.stdin).get('traceId',''))" <<<"$LAST_RAW_BODY" 2>/dev/null || true)"
expect "…and a second request is answered under a trace of its own" 401 '"traceId":"' -- "$API/me"
[[ -n "$FIRST_TRACE" && "$LAST_RAW_BODY" =~ \"traceId\":\"[0-9a-f]{32}\" && "$LAST_RAW_BODY" != *"$FIRST_TRACE"* ]] && pass "…a different traceId" || fail "traceId per request" "first '$FIRST_TRACE', then ${LAST_RAW_BODY:0:250}"
if curl -s -o /dev/null -D - "$API/me" | grep -qi "^www-authenticate: bearer"; then pass "…with a WWW-Authenticate: Bearer challenge"; else fail "WWW-Authenticate" "header missing on the 401"; fi
expect "GET /me with a garbage token is 401" 401 '"code":"UNAUTHENTICATED"' -- "$API/me" -H 'Authorization: Bearer not-a-jwt'

echo; echo "email verification (FR-002, ADR-0018)"
# The email is sent after the commit, on another thread. Poll for a NEW log
# line rather than sleeping a fixed time: a loaded machine can take longer
# than any constant, and an old line from an earlier run must not count.
for _ in $(seq 1 40); do
  [ "$(grep -c "Email NOT sent" "$MOYI_LOG" || true)" -gt "$EMAILS_BEFORE" ] && break
  sleep 0.25
done
if [ "$(grep -c "Email NOT sent" "$MOYI_LOG" || true)" -gt "$EMAILS_BEFORE" ]; then pass "verification email was written to the log (provider=log)"; else fail "email in log" "no new 'Email NOT sent' line in $MOYI_LOG within 10 s"; fi
SECRET="$(grep -oE 'token=[A-Za-z0-9_-]+' "$MOYI_LOG" | tail -1 | cut -d= -f2 || true)"
if [ "${#SECRET}" = 43 ]; then pass "link carries a 43-character secret"; else fail "secret in link" "got '${SECRET}'"; fi
if grep -qi "$EMAIL" "$MOYI_LOG"; then fail "address never logged" "the address appears in the log"; else pass "address never appears in the log (masked to the domain)"; fi
expect "garbage token is 422 VERIFICATION_TOKEN_INVALID" 422 '"code":"VERIFICATION_TOKEN_INVALID"' -- -X POST "$API/auth/verify-email" -d '{"token":"not-a-token"}'
expect "blank token is 422 VALIDATION_FAILED" 422 '"code":"VALIDATION_FAILED"' -- -X POST "$API/auth/verify-email" -d '{"token":" "}'
# A scanner's GET cannot spend a token: only POST is public here, so the filter
# chain refuses it before the controller is ever reached.
expect "GET on verify-email cannot spend a token (401)" 401 '"code":"UNAUTHENTICATED"' -- "$API/auth/verify-email?token=$SECRET"
expect "real token verifies: 200, empty body" 200 "" -- -X POST "$API/auth/verify-email" -d "{\"token\":\"$SECRET\"}"
expect "same token again is 410 VERIFICATION_TOKEN_EXPIRED" 410 '"code":"VERIFICATION_TOKEN_EXPIRED"' -- -X POST "$API/auth/verify-email" -d "{\"token\":\"$SECRET\"}"
expect "resend for a verified address is 202, empty" 202 "" -- -X POST "$API/auth/resend-verification" -d "{\"email\":\"$EMAIL\"}"
expect "resend for an unknown address is 202, identical" 202 "" -- -X POST "$API/auth/resend-verification" -d '{"email":"nobody-here@example.com"}'
expect "resend for a malformed address is 422" 422 '"code":"VALIDATION_FAILED"' -- -X POST "$API/auth/resend-verification" -d '{"email":"not-an-address"}'

echo; echo "rate limiting (FR-012, ADR-0023)"
# Every bucket in doc 06 §4 that the identity module owes, driven to its 429
# against the real jar and the compose Valkey. Each flow uses an address no
# other section uses, so nothing here leaks into the probes that follow — and
# a 429 writes nothing, so the database checks at the end are unaffected. It
# runs after verification because the fresh accounts it registers send
# verification emails of their own, and that section reads the LAST link in
# the log.
#
# Registration: a fresh account (201) shows the headers on a success; two 422s
# spend tokens too, because the interceptor runs before validation; the fourth
# request is the 429. Fresh addresses rather than the duplicate path, because
# the duplicate path makes Postgres name the address in a constraint message
# that Hibernate logs — see the "address never logged" probe below.
RL_STAMP="$(date +%s)-$RANDOM"
expect "a limited response carries X-RateLimit-* on success (201)" 201 "" -- -X POST "$API/auth/register" -H 'X-Forwarded-For: 203.0.113.20' -d "$(register_body "rl-a-$RL_STAMP@example.com")"
header_is "…limit is three an hour per address" X-RateLimit-Limit 3
header_is "…with two left" X-RateLimit-Remaining 2
if printf '%s\n' "$LAST_HEADERS" | grep -qiE '^X-RateLimit-Reset: [0-9]{10}'; then pass "…and a reset time in Unix seconds"; else fail "X-RateLimit-Reset" "missing or not epoch seconds"; fi
expect "a 422 spends a token too (validation runs after the limiter)" 422 '"code":"VALIDATION_FAILED"' -- -X POST "$API/auth/register" -H 'X-Forwarded-For: 203.0.113.20' -d '{"email":"x@example.com","password":"short12","displayName":"S","acceptedTermsVersion":"1","over18":true}'
header_is "…one left" X-RateLimit-Remaining 1
expect "third attempt from the address is still answered" 422 '"code":"VALIDATION_FAILED"' -- -X POST "$API/auth/register" -H 'X-Forwarded-For: 203.0.113.20' -d '{"email":"x@example.com","password":"short12","displayName":"S","acceptedTermsVersion":"1","over18":true}'
header_is "…none left" X-RateLimit-Remaining 0
expect "the fourth registration from one address is 429 RATE_LIMITED" 429 '"code":"RATE_LIMITED"' -- -X POST "$API/auth/register" -H 'X-Forwarded-For: 203.0.113.20' -d "$(register_body "$EMAIL")"
[[ "$LAST_BODY" == *"Please wait 20 minutes before trying again."* ]] && pass "…announcing the wait, not the failure (states.md §1b)" || fail "429 copy" "got: ${LAST_BODY:0:200}"
header_is "…with Retry-After: three an hour, greedy, is one token every twenty minutes" Retry-After 1200
header_is "…and X-RateLimit-Remaining: 0" X-RateLimit-Remaining 0
if printf '%s\n' "$LAST_HEADERS" | grep -qi '^Content-Type: application/problem+json'; then pass "…in the problem shape"; else fail "429 content type" "not application/problem+json"; fi
expect "another address is unaffected" 201 "" -- -X POST "$API/auth/register" -H 'X-Forwarded-For: 203.0.113.24' -d "$(register_body "rl-b-$RL_STAMP@example.com")"
# Sign-in: five attempts per fifteen minutes per address — counted for an
# address that has no account, so the 429 is not an oracle (T-18). No Argon2
# is paid for the sixth: the bucket is consumed before the verify.
LIMITED_EMAIL="limit-$(date +%s)-$RANDOM@example.com"
for i in 1 2 3 4 5; do
  curl -s -o /dev/null -H 'Content-Type: application/json' -H 'X-Forwarded-For: 203.0.113.21' -X POST "$API/auth/login" -d "$(printf '{"email":"%s","password":"wrong %s"}' "$LIMITED_EMAIL" "$i")"
done
expect "the sixth sign-in for one (unknown) address in fifteen minutes is 429" 429 '"code":"RATE_LIMITED"' -- -X POST "$API/auth/login" -H 'X-Forwarded-For: 203.0.113.21' -d "$(printf '{"email":"%s","password":"wrong 6"}' "$LIMITED_EMAIL")"
header_is "…Retry-After: one token back every three minutes" Retry-After 180
header_is "…X-RateLimit-Limit is the per-email five, the bucket that decided" X-RateLimit-Limit 5
[[ "$LAST_BODY" == *"Please wait 3 minutes before trying again."* ]] && pass "…copy names the three minutes" || fail "429 copy" "got: ${LAST_BODY:0:200}"
# Resend: once a minute per address, the cooldown states.md §1 promised.
expect "first resend for an unknown address is 202" 202 "" -- -X POST "$API/auth/resend-verification" -H 'X-Forwarded-For: 203.0.113.22' -d "{\"email\":\"$LIMITED_EMAIL\"}"
expect "a second inside a minute is 429" 429 '"code":"RATE_LIMITED"' -- -X POST "$API/auth/resend-verification" -H 'X-Forwarded-For: 203.0.113.22' -d "{\"email\":\"$LIMITED_EMAIL\"}"
header_is "…Retry-After: 60" Retry-After 60
[[ "$LAST_BODY" == *"Please wait a minute before trying again."* ]] && pass "…copy says a minute" || fail "429 copy" "got: ${LAST_BODY:0:200}"
# Password reset: three an hour per address, identical for an address nobody has.
for i in 1 2 3; do
  expect "forgot-password $i of 3 for an unknown address is 202" 202 "" -- -X POST "$API/auth/forgot-password" -H 'X-Forwarded-For: 203.0.113.23' -d "{\"email\":\"$LIMITED_EMAIL\"}"
done
expect "the fourth is 429" 429 '"code":"RATE_LIMITED"' -- -X POST "$API/auth/forgot-password" -H 'X-Forwarded-For: 203.0.113.23' -d "{\"email\":\"$LIMITED_EMAIL\"}"
header_is "…Retry-After: 1200" Retry-After 1200
flush_buckets

echo; echo "sign-in and sessions (FR-003, FR-004, ADR-0020–0022)"
# The account is verified by now, so this is the ordinary sign-in; the
# unverified sign-in FR-002 allows is covered by the endpoint tests.
jget() { python3 -c "import json,sys; print(json.load(sys.stdin)['$1'])"; }
login_body() { printf '{"email":"%s","password":"%s","device":{"platform":"ANDROID","appVersion":"smoke","osVersion":"16"}}' "$1" "$2"; }
expect "login is 200 with a token pair and the profile" 200 '"expiresIn":900' -- -X POST "$API/auth/login" -d "$(login_body "$EMAIL" "$PASSWORD")"
ACCESS="$(printf '%s' "$LAST_BODY" | jget accessToken)"; REFRESH="$(printf '%s' "$LAST_BODY" | jget refreshToken)"
[[ "$LAST_BODY" == *'"emailVerified":true'* ]] && pass "…and the profile says the address is verified" || fail "profile" "emailVerified not true: ${LAST_BODY:0:120}"
expect "GET /me with the access token is 200" 200 "\"email\":\"$EMAIL\"" -- "$API/me" -H "Authorization: Bearer $ACCESS"
header_is "…and every authenticated request is counted against the per-user bucket (doc 06 §4)" X-RateLimit-Limit 120
# Errors Spring raises for itself — no such route, wrong method — arrive in
# the contract's shape too: `type` (required by contracts/openapi.json), our
# prose title, and no implementation detail. Found by this smoke test on
# 2026-09-24: a trailing slash was a 404 with `"type":null` and the detail
# "No static resource api/v1/me." on an API that serves none.
expect "a trailing slash is a 404 in the contract's shape" 404 '"type":"https://api.getmoyi.app/problems/not-found"' -- "$API/me/" -H "Authorization: Bearer $ACCESS"
[[ "$LAST_BODY" == *'"detail":"No such resource."'* && "$LAST_BODY" != *"static resource"* ]] && pass "…that says only 'No such resource.'" || fail "404 detail" "${LAST_BODY:0:160}"
expect "PUT /me is 405 METHOD_NOT_ALLOWED with a type" 405 '"type":"https://api.getmoyi.app/problems/method-not-allowed"' -- -X PUT "$API/me" -H "Authorization: Bearer $ACCESS"

echo; echo "legal documents and recorded consent (FR-011, FR-103, ADR-0056)"
# The versions are the local profile's (application-local.yml): local-1 for all three.
# Registration above sent acceptedTermsVersion 2026-09-01, which must have been ignored.
expect "GET /meta/legal without a token is 200 with the configured versions" 200 '"terms":{"version":"local-1","url":"http://localhost:3000/terms"}' -- "$API/meta/legal"
[[ "$LAST_BODY" == *'"privacy":{"version":"local-1","url":"http://localhost:3000/privacy"}'* && "$LAST_BODY" == *'"dataProcessingSummary":{"version":"local-1","url":"http://localhost:3000/data-processing"}'* ]] \
  && pass "…and the privacy policy and data-processing summary" || fail "legal body" "${LAST_BODY:0:240}"
header_is "…which any cache may keep and must revalidate" Cache-Control "public, no-cache"
LEGAL_TAG="$(printf '%s\n' "$LAST_HEADERS" | grep -i '^ETag:' | head -1 | cut -d' ' -f2- || true)"
[ -n "$LEGAL_TAG" ] && pass "…with an ETag" || fail "legal ETag" "no ETag on GET /meta/legal"
expect "GET /meta/legal with that ETag as If-None-Match is 304" 304 "" -- "$API/meta/legal" -H "If-None-Match: $LEGAL_TAG"
[ -z "$LAST_BODY" ] && pass "…with no body" || fail "304 body" "got: ${LAST_BODY:0:100}"
expect "GET /me says which versions were accepted at registration" 200 '"legalAccepted":{"terms":{"version":"local-1"' -- "$API/me" -H "Authorization: Bearer $ACCESS"
[[ "$LAST_BODY" == *'"privacy":{"version":"local-1","acceptedAt":"'* && "$LAST_BODY" != *"2026-09-01"* ]] \
  && pass "…the privacy policy too, and nowhere the version the client sent" || fail "legalAccepted" "${LAST_BODY:0:300}"
expect "POST /me/legal-consent is 204 with no body" 204 "" -- -X POST "$API/me/legal-consent" -H "Authorization: Bearer $ACCESS"
expect "…and again, idempotently, 204" 204 "" -- -X POST "$API/me/legal-consent" -H "Authorization: Bearer $ACCESS"
expect "POST /me/legal-consent without a token is 401 UNAUTHENTICATED" 401 '"code":"UNAUTHENTICATED"' -- -X POST "$API/me/legal-consent"
CONSENTS="$(docker compose exec -T postgres psql -U moyi -d "$MOYI_DB" -Atc "SELECT string_agg(c.document || '=' || c.version, ',' ORDER BY c.document) FROM consent_records c JOIN users u ON u.id = c.user_id WHERE u.email = '$EMAIL'" 2>/dev/null || echo "psql-unavailable")"
case "$CONSENTS" in
  "AGE_18=local-1,PRIVACY=local-1,TERMS=local-1") pass "the account holds three consent rows, all the server's version, none added by re-consent" ;;
  psql-unavailable) skip "consent rows (psql unavailable)" ;;
  *) fail "consent rows" "expected the three local-1 rows, got '$CONSENTS'" ;;
esac
expect "wrong password is 401 INVALID_CREDENTIALS" 401 '"code":"INVALID_CREDENTIALS"' -- -X POST "$API/auth/login" -d "$(login_body "$EMAIL" "not the password")"
WRONG="$LAST_BODY"
expect "unknown address is 401 too" 401 '"code":"INVALID_CREDENTIALS"' -- -X POST "$API/auth/login" -d "$(login_body "nobody-$RANDOM@example.com" "not the password")"
[ "$WRONG" = "$LAST_BODY" ] && pass "…with a byte-identical body (no account oracle)" || fail "identical 401s" "bodies differ"
expect "an oversized password is 422, not work" 422 '"code":"VALIDATION_FAILED"' -- -X POST "$API/auth/login" -d "$(login_body "$EMAIL" "$(printf 'x%.0s' $(seq 1 600))")"
# Lockout: four more wrong attempts make five; the right password is then
# refused with the same 401, silently, for a minute. The per-email bucket
# (five per fifteen minutes) fires BEFORE the lockout on an unflushed run —
# that layering is deliberate (ADR-0023) — so the buckets are emptied here to
# let the lockout, the durable control, be observed on its own.
flush_buckets
for i in 2 3 4 5; do curl -s -o /dev/null -H 'Content-Type: application/json' -X POST "$API/auth/login" -d "$(login_body "$EMAIL" "wrong $i")"; done
expect "after five failures the RIGHT password is refused, identically" 401 '"code":"INVALID_CREDENTIALS"' -- -X POST "$API/auth/login" -d "$(login_body "$EMAIL" "$PASSWORD")"
LOCK="$(docker compose exec -T postgres psql -U moyi -d "$MOYI_DB" -Atc "SELECT failed_attempts || '|' || CAST(EXTRACT(EPOCH FROM (locked_until - now())) AS int) FROM credentials c JOIN users u ON u.id=c.user_id WHERE u.email='$EMAIL'" 2>/dev/null || echo "psql-unavailable")"
case "$LOCK" in 5\|5[5-9]|5\|60) pass "locked for a minute after five failures ($LOCK)";; psql-unavailable) skip "lock check (psql unavailable)";; *) fail "lockout row" "expected 5|55..60, got '$LOCK'";; esac
docker compose exec -T postgres psql -U moyi -d "$MOYI_DB" -Atc "UPDATE credentials SET locked_until = now() - interval '1 second' FROM users u WHERE u.id = credentials.user_id AND u.email='$EMAIL'" >/dev/null 2>&1 || true
flush_buckets
expect "once the lock expires the right password signs in again" 200 '"accessToken"' -- -X POST "$API/auth/login" -d "$(login_body "$EMAIL" "$PASSWORD")"
REFRESH="$(printf '%s' "$LAST_BODY" | jget refreshToken)"
expect "refresh rotates: 200 with a new pair" 200 '"refreshToken"' -- -X POST "$API/auth/refresh" -d "{\"refreshToken\":\"$REFRESH\"}"
REFRESH2="$(printf '%s' "$LAST_BODY" | jget refreshToken)"
[ "$REFRESH2" != "$REFRESH" ] && pass "…and the new refresh token differs from the old" || fail "rotation" "same refresh token returned"
# The grace window (ADR-0021, amended 2026-11-08, issue #136): a refresh whose
# answer was lost may be retried for 30 s while the successor is unused. The
# WARN "Refresh token REUSE detected" is written inside the revoking
# transaction, before the response, so counting it is a synchronous check that
# no reuse (and so no reuse email) happened.
reuse_lines() { grep -c "Refresh token REUSE detected" "$MOYI_LOG" || true; }
REUSES_BEFORE="$(reuse_lines)"
expect "a second refresh of the same token within 30 s is 200 with a new pair" 200 '"refreshToken"' -- -X POST "$API/auth/refresh" -d "{\"refreshToken\":\"$REFRESH\"}"
REFRESH_RETRY="$(printf '%s' "$LAST_BODY" | jget refreshToken)"
[ -n "$REFRESH_RETRY" ] && [ "$REFRESH_RETRY" != "$REFRESH2" ] && pass "…a different token from the one it voids" || fail "grace retry" "same or no refresh token returned"
[ "$(reuse_lines)" = "$REUSES_BEFORE" ] && pass "…with no reuse detected, so no reuse email" || fail "grace retry" "a reuse was logged for the retry"
grep -q "Refresh retried inside the grace window" "$MOYI_LOG" && pass "…logged as a grace retry" || fail "grace retry log" "no 'Refresh retried inside the grace window' line"
expect "the voided successor presented afterwards is 401 TOKEN_REUSE_DETECTED" 401 '"code":"TOKEN_REUSE_DETECTED"' -- -X POST "$API/auth/refresh" -d "{\"refreshToken\":\"$REFRESH2\"}"
[ "$(reuse_lines)" -gt "$REUSES_BEFORE" ] && pass "…detected as a reuse (the person is emailed)" || fail "voided successor" "no reuse logged"
expect "…and the family is dead: the retry's token is REFRESH_TOKEN_INVALID" 401 '"code":"REFRESH_TOKEN_INVALID"' -- -X POST "$API/auth/refresh" -d "{\"refreshToken\":\"$REFRESH_RETRY\"}"
flush_buckets
expect "login again (a family to use the successor of)" 200 '"refreshToken"' -- -X POST "$API/auth/login" -d "$(login_body "$EMAIL" "$PASSWORD")"
REFRESH_A="$(printf '%s' "$LAST_BODY" | jget refreshToken)"
expect "refresh it" 200 '"refreshToken"' -- -X POST "$API/auth/refresh" -d "{\"refreshToken\":\"$REFRESH_A\"}"
REFRESH_B="$(printf '%s' "$LAST_BODY" | jget refreshToken)"
expect "refresh the successor (now used)" 200 '"refreshToken"' -- -X POST "$API/auth/refresh" -d "{\"refreshToken\":\"$REFRESH_B\"}"
REFRESH_C="$(printf '%s' "$LAST_BODY" | jget refreshToken)"
expect "presenting the rotated token after its successor was used is 401 TOKEN_REUSE_DETECTED" 401 '"code":"TOKEN_REUSE_DETECTED"' -- -X POST "$API/auth/refresh" -d "{\"refreshToken\":\"$REFRESH_A\"}"
expect "…and the successor died with the family: REFRESH_TOKEN_INVALID" 401 '"code":"REFRESH_TOKEN_INVALID"' -- -X POST "$API/auth/refresh" -d "{\"refreshToken\":\"$REFRESH_C\"}"
# The window itself, 30 s, is not waited out here: RefreshGraceWindowTest moves
# a clock past it. A token past the window answers as a used successor's does.
expect "a never-issued refresh token is 401 REFRESH_TOKEN_INVALID" 401 '"code":"REFRESH_TOKEN_INVALID"' -- -X POST "$API/auth/refresh" -d '{"refreshToken":"never-issued"}'
expect "login again (fresh family)" 200 '"refreshToken"' -- -X POST "$API/auth/login" -d "$(login_body "$EMAIL" "$PASSWORD")"
REFRESH3="$(printf '%s' "$LAST_BODY" | jget refreshToken)"; ACCESS3="$(printf '%s' "$LAST_BODY" | jget accessToken)"
expect "logout is 204" 204 "" -- -X POST "$API/auth/logout" -d "{\"refreshToken\":\"$REFRESH3\"}"
expect "…and idempotent" 204 "" -- -X POST "$API/auth/logout" -d "{\"refreshToken\":\"$REFRESH3\"}"
expect "the logged-out token cannot refresh" 401 '"code":"REFRESH_TOKEN_INVALID"' -- -X POST "$API/auth/refresh" -d "{\"refreshToken\":\"$REFRESH3\"}"
expect "logout-all without a bearer token is 401" 401 '"code":"UNAUTHENTICATED"' -- -X POST "$API/auth/logout-all"
expect "logout-all with one is 204" 204 "" -- -X POST "$API/auth/logout-all" -H "Authorization: Bearer $ACCESS3"

echo; echo "sessions (FR-007, ADR-0025)"
# Two sign-ins, two sessions: the list is the caller's live refresh-token
# families, most recently seen first, and marks the one the bearer belongs to.
expect "sign in on a phone" 200 '"refreshToken"' -- -X POST "$API/auth/login" -d "$(login_body "$EMAIL" "$PASSWORD")"
PHONE_ACCESS="$(printf '%s' "$LAST_BODY" | jget accessToken)"; PHONE_REFRESH="$(printf '%s' "$LAST_BODY" | jget refreshToken)"
expect "sign in on a watch" 200 '"refreshToken"' -- -X POST "$API/auth/login" -d "$(printf '{"email":"%s","password":"%s","device":{"platform":"WEAR","appVersion":"smoke","osVersion":"5.1"}}' "$EMAIL" "$PASSWORD")"
WATCH_ACCESS="$(printf '%s' "$LAST_BODY" | jget accessToken)"
expect "GET /auth/sessions lists both, the watch current" 200 '"current":true' -- "$API/auth/sessions" -H "Authorization: Bearer $WATCH_ACCESS"
[[ "$LAST_BODY" == *'"platform":"WEAR"'* && "$LAST_BODY" == *'"platform":"ANDROID"'* ]] && pass "…with both devices" || fail "devices in list" "${LAST_BODY:0:200}"
PHONE_SESSION="$(python3 -c "import json,sys; print(next(s['id'] for s in json.load(sys.stdin)['sessions'] if not s['current']))" <<<"$LAST_BODY")"
expect "GET /auth/sessions without a bearer is 401" 401 '"code":"UNAUTHENTICATED"' -- "$API/auth/sessions"
expect "DELETE somebody else's (a random id) is 404 NOT_FOUND" 404 '"code":"NOT_FOUND"' -- -X DELETE "$API/auth/sessions/$(python3 -c 'import uuid; print(uuid.uuid4())')" -H "Authorization: Bearer $WATCH_ACCESS"
expect "DELETE a value that is not an id is 404 too" 404 '"code":"NOT_FOUND"' -- -X DELETE "$API/auth/sessions/not-a-session" -H "Authorization: Bearer $WATCH_ACCESS"
expect "DELETE the phone's session from the watch is 204" 204 "" -- -X DELETE "$API/auth/sessions/$PHONE_SESSION" -H "Authorization: Bearer $WATCH_ACCESS"
expect "…and the phone's refresh token is dead" 401 '"code":"REFRESH_TOKEN_INVALID"' -- -X POST "$API/auth/refresh" -d "{\"refreshToken\":\"$PHONE_REFRESH\"}"
expect "…while the phone's access token still lists one session" 200 '"sessions":[{' -- "$API/auth/sessions" -H "Authorization: Bearer $PHONE_ACCESS"
expect "DELETE the same session again is 404" 404 '"code":"NOT_FOUND"' -- -X DELETE "$API/auth/sessions/$PHONE_SESSION" -H "Authorization: Bearer $WATCH_ACCESS"
WATCH_SESSION="$(curl -s "$API/auth/sessions" -H "Authorization: Bearer $WATCH_ACCESS" | python3 -c "import json,sys; print(next(s['id'] for s in json.load(sys.stdin)['sessions'] if s['current']))")"
expect "DELETE the current session is a logout: 204" 204 "" -- -X DELETE "$API/auth/sessions/$WATCH_SESSION" -H "Authorization: Bearer $WATCH_ACCESS"
expect "…and the watch now lists no sessions" 200 '"sessions":[]' -- "$API/auth/sessions" -H "Authorization: Bearer $WATCH_ACCESS"
expect "an unknown platform is 422 on device.platform" 422 '"field":"device.platform"' -- -X POST "$API/auth/login" -d "$(printf '{"email":"%s","password":"%s","device":{"platform":"BLACKBERRY","appVersion":"1","osVersion":"7"}}' "$EMAIL" "$PASSWORD")"
flush_buckets


echo; echo "password reset (FR-004, T-17, ADR-0022)"
EMAILS_BEFORE="$(grep -c "Email NOT sent" "$MOYI_LOG" || true)"
expect "forgot-password for the account is 202, empty" 202 "" -- -X POST "$API/auth/forgot-password" -d "{\"email\":\"$EMAIL\"}"
expect "forgot-password for a stranger is 202, identical" 202 "" -- -X POST "$API/auth/forgot-password" -d '{"email":"nobody-here@example.com"}'
for _ in $(seq 1 40); do [ "$(grep -c "Email NOT sent" "$MOYI_LOG" || true)" -gt "$EMAILS_BEFORE" ] && break; sleep 0.25; done
RESET="$(grep -oE 'reset-password\?token=[A-Za-z0-9_-]+' "$MOYI_LOG" | tail -1 | cut -d= -f2 || true)"
if [ "${#RESET}" = 43 ]; then pass "reset link lands on the reset page with a 43-character secret"; else fail "reset link" "got '${RESET}'"; fi
expect "a breached new password is refused per field, token not spent" 422 '"code":"NOT_BREACHED"' -- -X POST "$API/auth/reset-password" -d "{\"token\":\"$RESET\",\"password\":\"password\"}"
NEW_PASSWORD="a different good password"
expect "reset with a good password is 200" 200 "" -- -X POST "$API/auth/reset-password" -d "{\"token\":\"$RESET\",\"password\":\"$NEW_PASSWORD\"}"
expect "the same link again is 410 PASSWORD_RESET_TOKEN_EXPIRED" 410 '"code":"PASSWORD_RESET_TOKEN_EXPIRED"' -- -X POST "$API/auth/reset-password" -d "{\"token\":\"$RESET\",\"password\":\"$NEW_PASSWORD\"}"
expect "the old password no longer signs in" 401 '"code":"INVALID_CREDENTIALS"' -- -X POST "$API/auth/login" -d "$(login_body "$EMAIL" "$PASSWORD")"
expect "the new one does" 200 '"accessToken"' -- -X POST "$API/auth/login" -d "$(login_body "$EMAIL" "$NEW_PASSWORD")"
sleep 1
for needle in "$PASSWORD" "$NEW_PASSWORD" "$REFRESH" "$REFRESH2" "$ACCESS"; do if grep -qF -- "$needle" "$MOYI_LOG"; then fail "secret in log" "a password or token appears in the log"; SECRET_LEAK=1; fi; done
[ "${SECRET_LEAK:-0}" = 0 ] && pass "no password, refresh token or access token appears in the log"

echo; echo "typed codes (#135, ADR-0018 and ADR-0022 amended 2026-11-08)"
# The verification and reset emails carry six digits beside the link; verify-email and
# reset-password take {email, code} in place of the token. Each probe comes from its own
# X-Forwarded-For, so the twenty-an-hour per-IP bucket the two routes share is not what
# this section ends up testing; the ten-a-day per-address bucket is driven at the end.
flush_buckets
# The last six-digit code in the log: the line after "It works for N minutes:" in the email
# body, whether the log writes the body's newlines as they are or escaped.
code_from_log() { python3 -c "import re,sys; m=re.findall(r'It works for \d+ minutes:(?:\\\\n|\s)*(\d{6})', open(sys.argv[1]).read()); print(m[-1] if m else '')" "$MOYI_LOG"; }
await_email() { local before="$1"; for _ in $(seq 1 40); do [ "$(grep -c "Email NOT sent" "$MOYI_LOG" || true)" -gt "$before" ] && break; sleep 0.25; done; }
other_code() { printf '%06d' $(( (10#$1 + 1) % 1000000 )); }
CODE_EMAIL="code-$(date +%s)-$RANDOM@example.com"
before="$(grep -c "Email NOT sent" "$MOYI_LOG" || true)"
expect "register for the code probes" 201 "" -- -X POST "$API/auth/register" -H 'X-Forwarded-For: 203.0.113.140' -d "$(register_body "$CODE_EMAIL")"
await_email "$before"
CODE1="$(code_from_log)"; LINK1="$(grep -oE 'token=[A-Za-z0-9_-]+' "$MOYI_LOG" | tail -1 | cut -d= -f2 || true)"
[[ "$CODE1" =~ ^[0-9]{6}$ ]] && pass "the email carries a six-digit code beside the link" || fail "code in email" "got '$CODE1'"
WRONG1="$(other_code "$CODE1")"
expect "a wrong code is 422 VERIFICATION_TOKEN_INVALID" 422 '"code":"VERIFICATION_TOKEN_INVALID"' -- -X POST "$API/auth/verify-email" -H 'X-Forwarded-For: 203.0.113.141' -d "{\"email\":\"$CODE_EMAIL\",\"code\":\"$WRONG1\"}"
WRONG_BODY="$LAST_BODY"
expect "an unknown address is the same bytes as a wrong code" 422 '"code":"VERIFICATION_TOKEN_INVALID"' -- -X POST "$API/auth/verify-email" -H 'X-Forwarded-For: 203.0.113.142' -d "{\"email\":\"nobody-$RANDOM@example.com\",\"code\":\"$CODE1\"}"
[ "$LAST_BODY" = "$WRONG_BODY" ] && pass "…byte for byte, but the traceId" || fail "unknown address vs wrong code" "'${LAST_BODY:0:200}' vs '${WRONG_BODY:0:200}'"
[[ "$LAST_RAW_BODY" != *"$CODE1"* ]] && pass "…and the code is not echoed" || fail "code echoed" "${LAST_RAW_BODY:0:200}"
expect "a five-digit code is 422 VALIDATION_FAILED, before any lookup" 422 '"code":"VALID_VERIFICATION_CODE"' -- -X POST "$API/auth/verify-email" -H 'X-Forwarded-For: 203.0.113.143' -d "{\"email\":\"$CODE_EMAIL\",\"code\":\"12345\"}"
expect "a token and a code together is 422 VALIDATION_FAILED" 422 '"code":"VALIDATION_FAILED"' -- -X POST "$API/auth/verify-email" -H 'X-Forwarded-For: 203.0.113.144' -d "{\"token\":\"$LINK1\",\"email\":\"$CODE_EMAIL\",\"code\":\"$CODE1\"}"

# A new email retires the old code; its link stays live (ADR-0018 decision 6).
before="$(grep -c "Email NOT sent" "$MOYI_LOG" || true)"
expect "resend for the pending address is 202" 202 "" -- -X POST "$API/auth/resend-verification" -H 'X-Forwarded-For: 203.0.113.145' -d "{\"email\":\"$CODE_EMAIL\"}"
await_email "$before"
CODE2="$(code_from_log)"
if [ "$CODE2" = "$CODE1" ]; then skip "a new email retires the old code (the two codes happen to be equal)"; else
  expect "a new email retires the old code" 422 '"code":"VERIFICATION_TOKEN_INVALID"' -- -X POST "$API/auth/verify-email" -H 'X-Forwarded-For: 203.0.113.146' -d "{\"email\":\"$CODE_EMAIL\",\"code\":\"$CODE1\"}"
fi
expect "the code verifies by address: 200, empty body" 200 "" -- -X POST "$API/auth/verify-email" -H 'X-Forwarded-For: 203.0.113.147' -d "{\"email\":\"$(echo "$CODE_EMAIL" | tr a-z A-Z)\",\"code\":\"$CODE2\"}"
expect "a used code is refused, and so is its link" 422 '"code":"VERIFICATION_TOKEN_INVALID"' -- -X POST "$API/auth/verify-email" -H 'X-Forwarded-For: 203.0.113.148' -d "{\"email\":\"$CODE_EMAIL\",\"code\":\"$CODE2\"}"
# The first email's link was live when the code verified the account, so it was deleted: not recognised.
expect "…the older link, retired by the verification, is 422" 422 '"code":"VERIFICATION_TOKEN_INVALID"' -- -X POST "$API/auth/verify-email" -H 'X-Forwarded-For: 203.0.113.149' -d "{\"token\":\"$LINK1\"}"

# Burning: five wrong codes, then the right one is refused; the link beside it still works.
BURN_EMAIL="burn-$(date +%s)-$RANDOM@example.com"
before="$(grep -c "Email NOT sent" "$MOYI_LOG" || true)"
expect "register for the burn probes" 201 "" -- -X POST "$API/auth/register" -H 'X-Forwarded-For: 203.0.113.150' -d "$(register_body "$BURN_EMAIL")"
await_email "$before"
BURN_CODE="$(code_from_log)"; BURN_LINK="$(grep -oE 'token=[A-Za-z0-9_-]+' "$MOYI_LOG" | tail -1 | cut -d= -f2 || true)"
for i in 1 2 3 4 5; do
  expect "wrong code $i of 5 is 422" 422 '"code":"VERIFICATION_TOKEN_INVALID"' -- -X POST "$API/auth/verify-email" -H "X-Forwarded-For: 203.0.113.15$i" -d "{\"email\":\"$BURN_EMAIL\",\"code\":\"$(other_code "$BURN_CODE")\"}"
done
expect "the right code after five wrong ones is refused: the code is burned" 422 '"code":"VERIFICATION_TOKEN_INVALID"' -- -X POST "$API/auth/verify-email" -H 'X-Forwarded-For: 203.0.113.156' -d "{\"email\":\"$BURN_EMAIL\",\"code\":\"$BURN_CODE\"}"
[ "$LAST_BODY" = "$WRONG_BODY" ] && pass "…the same bytes as a wrong code" || fail "burned vs wrong" "'${LAST_BODY:0:200}'"
expect "…while the link beside it still verifies: 200" 200 "" -- -X POST "$API/auth/verify-email" -H 'X-Forwarded-For: 203.0.113.157' -d "{\"token\":\"$BURN_LINK\"}"

# Reset by code.
before="$(grep -c "Email NOT sent" "$MOYI_LOG" || true)"
expect "forgot-password for the code account is 202" 202 "" -- -X POST "$API/auth/forgot-password" -H 'X-Forwarded-For: 203.0.113.160' -d "{\"email\":\"$CODE_EMAIL\"}"
await_email "$before"
RESET_CODE="$(code_from_log)"; RESET_LINK="$(grep -oE 'reset-password\?token=[A-Za-z0-9_-]+' "$MOYI_LOG" | tail -1 | cut -d= -f2 || true)"
CODE_PASSWORD="a third good password"
expect "reset by code for an unknown address" 422 '"code":"PASSWORD_RESET_TOKEN_INVALID"' -- -X POST "$API/auth/reset-password" -H 'X-Forwarded-For: 203.0.113.161' -d "{\"email\":\"nobody-$RANDOM@example.com\",\"code\":\"$RESET_CODE\",\"password\":\"$CODE_PASSWORD\"}"
UNKNOWN_RESET_BODY="$LAST_BODY"
expect "reset by a wrong code" 422 '"code":"PASSWORD_RESET_TOKEN_INVALID"' -- -X POST "$API/auth/reset-password" -H 'X-Forwarded-For: 203.0.113.162' -d "{\"email\":\"$CODE_EMAIL\",\"code\":\"$(other_code "$RESET_CODE")\",\"password\":\"$CODE_PASSWORD\"}"
[ "$LAST_BODY" = "$UNKNOWN_RESET_BODY" ] && pass "reset by code for an unknown address is the same bytes as a wrong code" || fail "reset unknown vs wrong" "'${LAST_BODY:0:200}' vs '${UNKNOWN_RESET_BODY:0:200}'"
# #169: the check says whether the code is right before the password is typed; it spends nothing
# of the code, but every check is one of the address's ten typed codes a day, as the reset is. This
# address has spent five above, and the probes below would take it past ten: start the day afresh.
flush_buckets
CHECK_BODY() { printf '{"email":"%s","code":"%s"}' "$1" "$2"; }
expect "reset-code check for an unknown address is 422" 422 '"code":"PASSWORD_RESET_TOKEN_INVALID"' -- -X POST "$API/auth/reset-password/check" -H 'X-Forwarded-For: 203.0.113.220' -d "$(CHECK_BODY "nobody-$RANDOM@example.com" "$RESET_CODE")"
UNKNOWN_CHECK_BODY="$LAST_BODY"
expect "reset-code check with a wrong code is 422" 422 '"code":"PASSWORD_RESET_TOKEN_INVALID"' -- -X POST "$API/auth/reset-password/check" -H 'X-Forwarded-For: 203.0.113.221' -d "$(CHECK_BODY "$CODE_EMAIL" "$(other_code "$RESET_CODE")")"
[ "$LAST_BODY" = "$UNKNOWN_CHECK_BODY" ] && pass "…the same bytes as an unknown address" || fail "check unknown vs wrong" "'${LAST_BODY:0:200}' vs '${UNKNOWN_CHECK_BODY:0:200}'"
expect "reset-code check with the right code is 204" 204 "" -- -X POST "$API/auth/reset-password/check" -H 'X-Forwarded-For: 203.0.113.222' -d "$(CHECK_BODY "$(echo "$CODE_EMAIL" | tr a-z A-Z)" "$RESET_CODE")"
expect "…and again: a check spends nothing" 204 "" -- -X POST "$API/auth/reset-password/check" -H 'X-Forwarded-For: 203.0.113.223' -d "$(CHECK_BODY "$CODE_EMAIL" "$RESET_CODE")"
expect "a reset-code check without a code is 400" 400 '"code":"MALFORMED_REQUEST"' -- -X POST "$API/auth/reset-password/check" -H 'X-Forwarded-For: 203.0.113.224' -d "{\"email\":\"$CODE_EMAIL\"}"
if [ "$RESET_CODE" != "$CODE2" ]; then
  expect "the verification code is not a reset code" 422 '"code":"PASSWORD_RESET_TOKEN_INVALID"' -- -X POST "$API/auth/reset-password" -H 'X-Forwarded-For: 203.0.113.163' -d "{\"email\":\"$CODE_EMAIL\",\"code\":\"$CODE2\",\"password\":\"$CODE_PASSWORD\"}"
fi
expect "a breached password by code is refused per field, code not spent" 422 '"code":"NOT_BREACHED"' -- -X POST "$API/auth/reset-password" -H 'X-Forwarded-For: 203.0.113.164' -d "{\"email\":\"$CODE_EMAIL\",\"code\":\"$RESET_CODE\",\"password\":\"password\"}"
expect "reset by address and code is 200" 200 "" -- -X POST "$API/auth/reset-password" -H 'X-Forwarded-For: 203.0.113.165' -d "{\"email\":\"$CODE_EMAIL\",\"code\":\"$RESET_CODE\",\"password\":\"$CODE_PASSWORD\"}"
expect "…the same code again is 422, never 410" 422 '"code":"PASSWORD_RESET_TOKEN_INVALID"' -- -X POST "$API/auth/reset-password" -H 'X-Forwarded-For: 203.0.113.166' -d "{\"email\":\"$CODE_EMAIL\",\"code\":\"$RESET_CODE\",\"password\":\"$CODE_PASSWORD\"}"
expect "…and the link beside it is spent: 410" 410 '"code":"PASSWORD_RESET_TOKEN_EXPIRED"' -- -X POST "$API/auth/reset-password" -H 'X-Forwarded-For: 203.0.113.167' -d "{\"token\":\"$RESET_LINK\",\"password\":\"$CODE_PASSWORD\"}"
expect "a reset-code check of the spent code is 422" 422 '"code":"PASSWORD_RESET_TOKEN_INVALID"' -- -X POST "$API/auth/reset-password/check" -H 'X-Forwarded-For: 203.0.113.225' -d "$(CHECK_BODY "$CODE_EMAIL" "$RESET_CODE")"
expect "the password set by code signs in" 200 '"accessToken"' -- -X POST "$API/auth/login" -H 'X-Forwarded-For: 203.0.113.168' -d "$(login_body "$CODE_EMAIL" "$CODE_PASSWORD")"
# #169: wrong checks are wrong tries at the reset code, on the same five. Three wrong checks and
# two wrong resets burn it, so neither the check nor the reset then takes the right code.
# The burn account has spent six of its ten typed codes today above: start the day afresh.
flush_buckets
before="$(grep -c "Email NOT sent" "$MOYI_LOG" || true)"
expect "forgot-password for the burn account is 202" 202 "" -- -X POST "$API/auth/forgot-password" -H 'X-Forwarded-For: 203.0.113.230' -d "{\"email\":\"$BURN_EMAIL\"}"
await_email "$before"
BURN_RESET_CODE="$(code_from_log)"
for i in 1 2 3; do
  expect "wrong reset-code check $i of 3 is 422" 422 '"code":"PASSWORD_RESET_TOKEN_INVALID"' -- -X POST "$API/auth/reset-password/check" -H "X-Forwarded-For: 203.0.113.23$i" -d "$(CHECK_BODY "$BURN_EMAIL" "$(other_code "$BURN_RESET_CODE")")"
done
for i in 4 5; do
  expect "wrong reset by code $i of 5 is 422" 422 '"code":"PASSWORD_RESET_TOKEN_INVALID"' -- -X POST "$API/auth/reset-password" -H "X-Forwarded-For: 203.0.113.23$i" -d "{\"email\":\"$BURN_EMAIL\",\"code\":\"$(other_code "$BURN_RESET_CODE")\",\"password\":\"$CODE_PASSWORD\"}"
done
expect "the right reset-code check after five wrong tries is refused: checks and resets share the five" 422 '"code":"PASSWORD_RESET_TOKEN_INVALID"' -- -X POST "$API/auth/reset-password/check" -H 'X-Forwarded-For: 203.0.113.236' -d "$(CHECK_BODY "$BURN_EMAIL" "$BURN_RESET_CODE")"
expect "…and so is the right reset" 422 '"code":"PASSWORD_RESET_TOKEN_INVALID"' -- -X POST "$API/auth/reset-password" -H 'X-Forwarded-For: 203.0.113.237' -d "{\"email\":\"$BURN_EMAIL\",\"code\":\"$BURN_RESET_CODE\",\"password\":\"$CODE_PASSWORD\"}"
# The code reaches the log only inside the logged email (provider=log, local only): no other line holds it.
CODE_LEAK=0; for c in "$CODE1" "$CODE2" "$BURN_CODE" "$RESET_CODE" "$BURN_RESET_CODE"; do [ "$(grep -wF -- "$c" "$MOYI_LOG" | grep -vE "^\s*$c\s*\$" | grep -vc 'It works for' || true)" -gt 0 ] && CODE_LEAK=1; done
[ "$CODE_LEAK" = 0 ] && pass "no code appears in the log outside the logged email" || fail "code in log" "a code appears on a log line that is not the email's"

# Ten typed codes a day per address, both routes together, the same for an address with no account.
flush_buckets
LIMIT_CODE_EMAIL="nobody-codes-$RANDOM@example.com"
for i in $(seq 1 10); do
  expect "typed code $i of 10 for an unknown address is 422" 422 '"code":"VERIFICATION_TOKEN_INVALID"' -- -X POST "$API/auth/verify-email" -H "X-Forwarded-For: 203.0.113.$((170 + i))" -d "{\"email\":\"$LIMIT_CODE_EMAIL\",\"code\":\"000000\"}"
done
expect "the eleventh, on reset-password, is 429" 429 '"code":"RATE_LIMITED"' -- -X POST "$API/auth/reset-password" -H 'X-Forwarded-For: 203.0.113.181' -d "{\"email\":\"$LIMIT_CODE_EMAIL\",\"code\":\"000000\",\"password\":\"$CODE_PASSWORD\"}"
header_is "…waiting for one try back" "Retry-After" "8640"
expect "…and on the reset-code check, 429 too" 429 '"code":"RATE_LIMITED"' -- -X POST "$API/auth/reset-password/check" -H 'X-Forwarded-For: 203.0.113.238' -d "$(CHECK_BODY "$LIMIT_CODE_EMAIL" "000000")"
# Twenty an hour per client address on the two routes, links and codes alike.
flush_buckets
for i in $(seq 1 20); do
  expect "presentation $i of 20 from one address" 422 "" -- -X POST "$API/auth/verify-email" -H 'X-Forwarded-For: 203.0.113.190' -d "{\"token\":\"not-a-token-$i\"}"
done
expect "the twenty-first from that address is 429" 429 '"code":"RATE_LIMITED"' -- -X POST "$API/auth/reset-password" -H 'X-Forwarded-For: 203.0.113.190' -d "{\"token\":\"not-a-token\",\"password\":\"$CODE_PASSWORD\"}"
flush_buckets

etag_of() { printf '%s\n' "$LAST_HEADERS" | grep -i '^etag:' | head -1 | cut -d' ' -f2-; }

echo; echo "bonds (FR-020, FR-022, FR-025, T-02, ADR-0026)"
flush_buckets
expect "sign in as the verified account" 200 '"accessToken"' -- -X POST "$API/auth/login" -d "$(login_body "$EMAIL" "$NEW_PASSWORD")"
BOND_ACCESS="$(printf '%s' "$LAST_BODY" | jget accessToken)"
bond_body() { printf '{"name":"%s","type":"COUPLE","anchorTimezone":"Africa/Lagos"}' "$1"; }

expect "POST /bonds is 201, pending its second member" 201 '"status":"PENDING_MEMBER"' -- -X POST "$API/bonds" -H "Authorization: Bearer $BOND_ACCESS" -d "$(bond_body "Us")"
BOND_ID="$(printf '%s' "$LAST_BODY" | jget id)"
CODE="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['code'])" <<<"$LAST_BODY")"
BOND_ETAG="$(etag_of)"
[[ "$BOND_ETAG" == \"0-* ]] && pass "…with a representation ETag" || fail "ETag" "$BOND_ETAG"
# The alphabet is states.md §2's thirty symbols; 0/O, 1/I/L and U are absent
# because a code is read aloud down a phone line (T-06, ADR-0026).
if [[ "$CODE" =~ ^[23456789ABCDEFGHJKMNPQRSTVWXYZ]{6}$ ]]; then pass "…code $CODE is six characters of the 30-symbol alphabet"; else fail "invite code" "got '$CODE'"; fi
# The base is the `local` profile's moyi.bond.invite.link-base-url; application.yml has none.
if [[ "$LAST_BODY" == *"\"link\":\"http://localhost:3000/i/$CODE\""* ]]; then pass "…and the link carries it"; else fail "invite link" "${LAST_BODY:0:200}"; fi
# states.md §8: never the other member's settings. And a user id is identity's
# to hand out, not this module's to echo.
if [[ "$LAST_BODY" != *userId* && "$LAST_BODY" != *reminderTimezone* ]]; then pass "…and no userId or partner settings in the body"; else fail "response leakage" "${LAST_BODY:0:200}"; fi

expect "GET /bonds lists it" 200 "\"id\":\"$BOND_ID\"" -- "$API/bonds" -H "Authorization: Bearer $BOND_ACCESS"
expect "GET /bonds/{id} is 200 for the owner" 200 '"role":"OWNER"' -- "$API/bonds/$BOND_ID" -H "Authorization: Bearer $BOND_ACCESS"
header_is "…with the ETag" ETag "$BOND_ETAG"

expect "a fixed-offset zone is 422 on anchorTimezone" 422 '"field":"anchorTimezone"' -- -X POST "$API/bonds" -H "Authorization: Bearer $BOND_ACCESS" -d '{"name":"Us","type":"COUPLE","anchorTimezone":"Etc/GMT+3"}'
expect "an unknown type is 422 on type" 422 '"field":"type"' -- -X POST "$API/bonds" -H "Authorization: Bearer $BOND_ACCESS" -d '{"name":"Us","type":"THROUPLE","anchorTimezone":"Africa/Lagos"}'
# A non-breaking space passes `@NotBlank` and is blank to Kotlin, so before the
# review of #40 this was a 500 on a well-formed request (ADR-0029 §13).
expect "a name of one non-breaking space is 422, not 500" 422 '"field":"name"' -- -X POST "$API/bonds" -H "Authorization: Bearer $BOND_ACCESS" -d '{"name":"\u00a0","type":"COUPLE","anchorTimezone":"Africa/Lagos"}'

expect "second bond is 201" 201 "" -- -X POST "$API/bonds" -H "Authorization: Bearer $BOND_ACCESS" -d "$(bond_body "Two")"
expect "third bond is 201" 201 "" -- -X POST "$API/bonds" -H "Authorization: Bearer $BOND_ACCESS" -d "$(bond_body "Three")"
expect "a fourth open bond is 409 BOND_LIMIT_REACHED" 409 '"code":"BOND_LIMIT_REACHED"' -- -X POST "$API/bonds" -H "Authorization: Bearer $BOND_ACCESS" -d "$(bond_body "Four")"

# A third account: registered, never verified. It may sign in (FR-002) and it
# may not create a bond — and somebody else's bond does not exist for it.
STRANGER="stranger-$(date +%s)-$RANDOM@example.com"
expect "register a stranger" 201 "" -- -X POST "$API/auth/register" -H 'X-Forwarded-For: 203.0.113.30' -d "$(register_body "$STRANGER")"
expect "the stranger signs in, unverified" 200 '"accessToken"' -- -X POST "$API/auth/login" -d "$(login_body "$STRANGER" "$PASSWORD")"
STRANGER_ACCESS="$(printf '%s' "$LAST_BODY" | jget accessToken)"
expect "an unverified account cannot create a bond: 403 EMAIL_NOT_VERIFIED" 403 '"code":"EMAIL_NOT_VERIFIED"' -- -X POST "$API/bonds" -H "Authorization: Bearer $STRANGER_ACCESS" -d "$(bond_body "Mine")"
expect "the stranger's GET of someone else's bond is 404 NOT_FOUND" 404 '"code":"NOT_FOUND"' -- "$API/bonds/$BOND_ID" -H "Authorization: Bearer $STRANGER_ACCESS"
STRANGER_404="$(printf '%s' "$LAST_BODY" | sed 's/"instance":"[^"]*"/"instance":"-"/')"
expect "a random id is 404 too" 404 '"code":"NOT_FOUND"' -- "$API/bonds/$(python3 -c 'import uuid; print(uuid.uuid4())')" -H "Authorization: Bearer $BOND_ACCESS"
RANDOM_404="$(printf '%s' "$LAST_BODY" | sed 's/"instance":"[^"]*"/"instance":"-"/')"
# T-02: a 403 for the stranger would confirm the bond is real. The bodies must
# be indistinguishable apart from the path the caller typed.
[ "$STRANGER_404" = "$RANDOM_404" ] && pass "…with a byte-identical body (no existence oracle)" || fail "identical 404s" "bodies differ"
expect "a value that is not an id is 404 as well" 404 '"code":"NOT_FOUND"' -- "$API/bonds/not-a-bond" -H "Authorization: Bearer $BOND_ACCESS"
expect "the stranger's list is empty" 200 '"bonds":[]' -- "$API/bonds" -H "Authorization: Bearer $STRANGER_ACCESS"
if grep -qF -- "$CODE" "$MOYI_LOG"; then fail "code in log" "the invite code appears in the log"; else pass "the invite code never appears in the log"; fi

echo; echo "invites and pairing — M2 (FR-022, FR-023, FR-024, T-06, ADR-0027)"
flush_buckets
# A second account, verified, so it may join (FR-002).
JOINER="joiner-$(date +%s)-$RANDOM@example.com"
EMAILS_BEFORE="$(grep -c "Email NOT sent" "$MOYI_LOG" || true)"
expect "register the joiner" 201 "" -- -X POST "$API/auth/register" -H 'X-Forwarded-For: 203.0.113.40' -d "$(register_body "$JOINER")"
for _ in $(seq 1 40); do [ "$(grep -c "Email NOT sent" "$MOYI_LOG" || true)" -gt "$EMAILS_BEFORE" ] && break; sleep 0.25; done
JOINER_TOKEN="$(grep -oE 'token=[A-Za-z0-9_-]+' "$MOYI_LOG" | tail -1 | cut -d= -f2 || true)"
expect "verify the joiner" 200 "" -- -X POST "$API/auth/verify-email" -d "{\"token\":\"$JOINER_TOKEN\"}"
expect "the joiner signs in" 200 '"accessToken"' -- -X POST "$API/auth/login" -d "$(login_body "$JOINER" "$PASSWORD")"
JOINER_ACCESS="$(printf '%s' "$LAST_BODY" | jget accessToken)"

# BOND_ID and CODE are the bond and code from the previous section.
expect "the joiner resolves the code and sees who invited them" 200 '"inviterDisplayName":"Smoke"' -- "$API/invites/$CODE" -H "Authorization: Bearer $JOINER_ACCESS"
[[ "$LAST_BODY" == *'"bondName":"Us"'* ]] && pass "…and the bond's name" || fail "preview" "${LAST_BODY:0:200}"
expect "a lowercase code resolves too" 200 '"bondName"' -- "$API/invites/$(echo "$CODE" | tr 'A-Z' 'a-z')" -H "Authorization: Bearer $JOINER_ACCESS"
expect "a malformed code is 422 on the field" 422 '"field":"code"' -- "$API/invites/ABC" -H "Authorization: Bearer $JOINER_ACCESS"

expect "the joiner accepts: 200, and the bond is ACTIVE" 200 '"status":"ACTIVE"' -- -X POST "$API/invites/$CODE/accept" -H "Authorization: Bearer $JOINER_ACCESS"
MEMBERS="$(python3 -c "import json,sys; d=json.load(sys.stdin); print(len(d['members']))" <<<"$LAST_BODY")"
[ "$MEMBERS" = 2 ] && pass "…with both members in it" || fail "members" "expected 2 members, got $MEMBERS"
[[ "$LAST_BODY" == *'"invite":null'* ]] && pass "…and no live invite left" || fail "invite cleared" "${LAST_BODY:0:250}"
expect "the creator sees the same active bond" 200 '"status":"ACTIVE"' -- "$API/bonds/$BOND_ID" -H "Authorization: Bearer $BOND_ACCESS"

# FR-024: every way a code fails is one answer. A spent code and one that was
# never issued must be indistinguishable.
# A *verified* caller: the stranger above is deliberately unverified, and
# FR-002 is checked before the code is, so they would be refused earlier.
expect "the spent code is 404 INVITE_NOT_USABLE" 404 '"code":"INVITE_NOT_USABLE"' -- -X POST "$API/invites/$CODE/accept" -H "Authorization: Bearer $JOINER_ACCESS"
SPENT_404="$(printf '%s' "$LAST_BODY" | sed 's/"instance":"[^"]*"/"instance":"-"/')"
expect "a code that never existed is 404 too" 404 '"code":"INVITE_NOT_USABLE"' -- -X POST "$API/invites/ZZZZZZ/accept" -H "Authorization: Bearer $JOINER_ACCESS"
NEVER_404="$(printf '%s' "$LAST_BODY" | sed 's/"instance":"[^"]*"/"instance":"-"/')"
[ "$SPENT_404" = "$NEVER_404" ] && pass "…with a byte-identical body (FR-024, one answer)" || fail "one answer" "bodies differ"
expect "a full bond takes no new invite: 409 BOND_FULL" 409 '"code":"BOND_FULL"' -- -X POST "$API/bonds/$BOND_ID/invites" -H "Authorization: Bearer $BOND_ACCESS"
expect "an unverified account is refused before the code is even read: 403" 403 '"code":"EMAIL_NOT_VERIFIED"' -- -X POST "$API/invites/$CODE/accept" -H "Authorization: Bearer $STRANGER_ACCESS"
expect "a non-member cannot invite into it: 404" 404 '"code":"NOT_FOUND"' -- -X POST "$API/bonds/$BOND_ID/invites" -H "Authorization: Bearer $STRANGER_ACCESS"
expect "…and still cannot read it" 404 '"code":"NOT_FOUND"' -- "$API/bonds/$BOND_ID" -H "Authorization: Bearer $STRANGER_ACCESS"

# Revoking, on a bond that still has room.
expect "a second bond for the joiner" 201 '"code"' -- -X POST "$API/bonds" -H "Authorization: Bearer $JOINER_ACCESS" -d '{"name":"Two","type":"FRIENDS","anchorTimezone":"Europe/London"}'
SECOND_BOND="$(printf '%s' "$LAST_BODY" | jget id)"
SECOND_CODE="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['code'])" <<<"$LAST_BODY")"
SECOND_INVITE="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['id'])" <<<"$LAST_BODY")"
expect "issuing a new invite is 201" 201 '"code"' -- -X POST "$API/bonds/$SECOND_BOND/invites" -H "Authorization: Bearer $JOINER_ACCESS"
THIRD_CODE="$(printf '%s' "$LAST_BODY" | jget code)"
expect "…and the replaced code no longer works" 404 '"code":"INVITE_NOT_USABLE"' -- "$API/invites/$SECOND_CODE" -H "Authorization: Bearer $BOND_ACCESS"
expect "the new one does" 200 '"bondName":"Two"' -- "$API/invites/$THIRD_CODE" -H "Authorization: Bearer $BOND_ACCESS"
expect "revoking another bond's invite id is 404" 404 '"code":"NOT_FOUND"' -- -X DELETE "$API/bonds/$SECOND_BOND/invites/$SECOND_INVITE" -H "Authorization: Bearer $JOINER_ACCESS"
THIRD_INVITE="$(docker compose exec -T postgres psql -U moyi -d "$MOYI_DB" -Atc "SELECT id FROM bond_invites WHERE code='$THIRD_CODE'" 2>/dev/null | tr -d '[:space:]')"
expect "revoking the live one is 204" 204 "" -- -X DELETE "$API/bonds/$SECOND_BOND/invites/$THIRD_INVITE" -H "Authorization: Bearer $JOINER_ACCESS"
expect "…and it stops working at once" 404 '"code":"INVITE_NOT_USABLE"' -- "$API/invites/$THIRD_CODE" -H "Authorization: Bearer $BOND_ACCESS"
expect "revoking it again is 404" 404 '"code":"NOT_FOUND"' -- -X DELETE "$API/bonds/$SECOND_BOND/invites/$THIRD_INVITE" -H "Authorization: Bearer $JOINER_ACCESS"

if grep -qF -- "$CODE" "$MOYI_LOG" || grep -qF -- "$THIRD_CODE" "$MOYI_LOG"; then fail "code in log" "an invite code appears in the log"; else pass "no invite code appears in the log"; fi

echo; echo "ending — leave and block (FR-026, FR-029, T-09, doc 26 §2.1, ADR-0028)"
flush_buckets
# V9's own migration filename is "bond_bonds_members_invites_blocks", and
# Flyway logs that description verbatim on a genuinely fresh database — a
# grep of the WHOLE log for the word below is a false FAIL on the very first
# boot against an empty volume, found running this section against one after
# `docker compose down -v`. The check below is scoped to what this section
# itself wrote, the same way the email-polling checks scope to "since before".
LOG_LINES_BEFORE_ENDING="$(wc -l < "$MOYI_LOG" | tr -d ' ')"

# Registers, verifies and signs in one account, leaving its token in
# ACCOUNT_ACCESS. Factored out because this section needs four accounts and the
# sequence was already written twice inline above. Every account has the same
# display name ("Smoke", from register_body) unless it is given one, which is what
# lets two bonds be compared byte for byte below. A different X-Forwarded-For each
# time, so the per-IP registration bucket is not what this section ends up testing.
verified_account() {
  local label="$1" ip="$2" locale="${3:-en}" name="${4:-Smoke}" email before token
  email="end-$label-$(date +%s)-$RANDOM@example.com"
  before="$(grep -c "Email NOT sent" "$MOYI_LOG" || true)"
  expect "register $label" 201 "" -- -X POST "$API/auth/register" -H "X-Forwarded-For: $ip" -d "$(register_body "$email" "$locale" "$name")"
  for _ in $(seq 1 40); do [ "$(grep -c "Email NOT sent" "$MOYI_LOG" || true)" -gt "$before" ] && break; sleep 0.25; done
  token="$(grep -oE 'token=[A-Za-z0-9_-]+' "$MOYI_LOG" | tail -1 | cut -d= -f2 || true)"
  expect "verify $label" 200 "" -- -X POST "$API/auth/verify-email" -d "{\"token\":\"$token\"}"
  expect "$label signs in" 200 '"accessToken"' -- -X POST "$API/auth/login" -d "$(login_body "$email" "$PASSWORD")"
  ACCOUNT_ACCESS="$(printf '%s' "$LAST_BODY" | jget accessToken)"
  ACCOUNT_EMAIL="$email"
}


# Two pairs, built identically, ended differently. Doc 26 §2.1 is about what the
# OTHER member can see, so anything that differs between the two archived bonds
# is something a blocked person could use to tell they were blocked (T-09).
verified_account "leaver" "203.0.113.50";  LEAVER_ACCESS="$ACCOUNT_ACCESS"
verified_account "stayer" "203.0.113.51";  STAYER_ACCESS="$ACCOUNT_ACCESS"
verified_account "blocker" "203.0.113.52"; BLOCKER_ACCESS="$ACCOUNT_ACCESS"
verified_account "blocked" "203.0.113.53"; BLOCKED_ACCESS="$ACCOUNT_ACCESS"

expect "a bond to leave is 201" 201 '"status":"PENDING_MEMBER"' -- -X POST "$API/bonds" -H "Authorization: Bearer $LEAVER_ACCESS" -d "$(bond_body "Us")"
LEFT_BOND="$(printf '%s' "$LAST_BODY" | jget id)"
LEFT_CODE="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['code'])" <<<"$LAST_BODY")"
LEFT_INVITE="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['id'])" <<<"$LAST_BODY")"
expect "the other member joins it" 200 '"status":"ACTIVE"' -- -X POST "$API/invites/$LEFT_CODE/accept" -H "Authorization: Bearer $STAYER_ACCESS"
expect "leaving is 204" 204 "" -- -X POST "$API/bonds/$LEFT_BOND/leave" -H "Authorization: Bearer $LEAVER_ACCESS"

expect "a bond to block in is 201" 201 '"status":"PENDING_MEMBER"' -- -X POST "$API/bonds" -H "Authorization: Bearer $BLOCKER_ACCESS" -d "$(bond_body "Us")"
BLOCK_BOND="$(printf '%s' "$LAST_BODY" | jget id)"
BLOCK_CODE="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['code'])" <<<"$LAST_BODY")"
expect "the other member joins that one too" 200 '"status":"ACTIVE"' -- -X POST "$API/invites/$BLOCK_CODE/accept" -H "Authorization: Bearer $BLOCKED_ACCESS"
# On this route the flag is required (ADR-0035 questions 1 and 11): a block that
# does not say what becomes of the entries is the 400 an unreadable body gets,
# and ends nothing. It is asked for after the guard, so a stranger who sends no
# body is still the one 404.
expect "a block with no body is 400 MALFORMED_REQUEST" 400 '"code":"MALFORMED_REQUEST"' -- -X POST "$API/bonds/$BLOCK_BOND/block" -H "Authorization: Bearer $BLOCKER_ACCESS"
BARE_REFUSAL="$LAST_BODY"
expect "…and with no Content-Type either" 400 '"code":"MALFORMED_REQUEST"' -- -X POST "$API/bonds/$BLOCK_BOND/block" -H "Content-Type:" -H "Authorization: Bearer $BLOCKER_ACCESS"
expect "…and so is {}" 400 '"code":"MALFORMED_REQUEST"' -- -X POST "$API/bonds/$BLOCK_BOND/block" -H "Authorization: Bearer $BLOCKER_ACCESS" -d '{}'
expect "…and a flag that is null" 400 '"code":"MALFORMED_REQUEST"' -- -X POST "$API/bonds/$BLOCK_BOND/block" -H "Authorization: Bearer $BLOCKER_ACCESS" -d '{"withdrawEntries": null}'
expect "…the same bytes as a body that cannot be read" 400 '"code":"MALFORMED_REQUEST"' -- -X POST "$API/bonds/$BLOCK_BOND/block" -H "Authorization: Bearer $BLOCKER_ACCESS" -d '{"withdrawEntries": '
[ "$LAST_BODY" = "$BARE_REFUSAL" ] && pass "…byte for byte" || fail "absent-flag refusal" "absent: ${BARE_REFUSAL:0:160} unreadable: ${LAST_BODY:0:160}"
expect "…and the bond is still open" 200 '"status":"ACTIVE"' -- "$API/bonds/$BLOCK_BOND" -H "Authorization: Bearer $BLOCKED_ACCESS"
expect "a stranger's block with no body is the one 404, not that 400" 404 '"code":"NOT_FOUND"' -- -X POST "$API/bonds/$BLOCK_BOND/block" -H "Authorization: Bearer $STRANGER_ACCESS"
expect "blocking is 204 — the same 204" 204 "" -- -X POST "$API/bonds/$BLOCK_BOND/block" -H "Authorization: Bearer $BLOCKER_ACCESS" -d '{"withdrawEntries": true}'

# The archive stays readable for both, including whoever left (states.md §9).
expect "the member who left still reads the bond" 200 '"status":"ARCHIVED"' -- "$API/bonds/$LEFT_BOND" -H "Authorization: Bearer $LEAVER_ACCESS"
[[ "$LAST_BODY" == *'"invite":null'* ]] && pass "…and its code is gone from the body" || fail "invite cleared" "${LAST_BODY:0:200}"
expect "the one who stayed reads it too" 200 '"status":"ARCHIVED"' -- "$API/bonds/$LEFT_BOND" -H "Authorization: Bearer $STAYER_ACCESS"
LEFT_VIEW="$LAST_BODY"; LEFT_ETAG="$(etag_of)"
expect "the blocked member reads theirs, unaware" 200 '"status":"ARCHIVED"' -- "$API/bonds/$BLOCK_BOND" -H "Authorization: Bearer $BLOCKED_ACCESS"
BLOCK_VIEW="$LAST_BODY"; BLOCK_ETAG="$(etag_of)"

# Doc 26 §2.1 on the wire: normalise the ids and timestamps two different bonds
# cannot share, and what is left must be the same bytes.
if python3 - "$LEFT_VIEW" "$BLOCK_VIEW" <<'PYEOF'
import re, sys

def normalise(body):
    ids = {}
    body = re.sub(
        r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
        lambda m: ids.setdefault(m.group(0), "uuid-%d" % len(ids)),
        body,
    )
    return re.sub(r"\d{4}-\d{2}-\d{2}T[0-9:.]+Z", "timestamp", body)

left, blocked = normalise(sys.argv[1]), normalise(sys.argv[2])
if left != blocked:
    print(left, file=sys.stderr)
    print(blocked, file=sys.stderr)
    sys.exit(1)
PYEOF
then pass "a block is indistinguishable from a leave, byte for byte (doc 26 §2.1)"
else fail "discreet exit" "the two archived bonds do not read the same"; fi
# The ETag prefix is the row version: a block that wrote once more than a leave would
# show here and nowhere else.
[ "${LEFT_ETAG%%-*}" = "${BLOCK_ETAG%%-*}" ] && pass "…and the version prefixes match, so the version counts no blocks ($LEFT_ETAG)" || fail "discreet exit etag" "leave $LEFT_ETAG vs block $BLOCK_ETAG"
[[ "${BLOCK_VIEW,,}" != *block* ]] && pass "…and no response anywhere says block" || fail "discreet exit wording" "${BLOCK_VIEW:0:200}"

# An archived bond takes no writes (BR-9, the design's §6.3).
expect "leaving twice is 409 BOND_ARCHIVED" 409 '"code":"BOND_ARCHIVED"' -- -X POST "$API/bonds/$LEFT_BOND/leave" -H "Authorization: Bearer $STAYER_ACCESS"
expect "inviting into an archived bond is 409" 409 '"code":"BOND_ARCHIVED"' -- -X POST "$API/bonds/$LEFT_BOND/invites" -H "Authorization: Bearer $STAYER_ACCESS"
expect "revoking its invite is 409 too" 409 '"code":"BOND_ARCHIVED"' -- -X DELETE "$API/bonds/$LEFT_BOND/invites/$LEFT_INVITE" -H "Authorization: Bearer $LEAVER_ACCESS"
expect "the code it carried is dead" 404 '"code":"INVITE_NOT_USABLE"' -- "$API/invites/$LEFT_CODE" -H "Authorization: Bearer $STAYER_ACCESS"

# Block is the one write an archived bond accepts, and it repeats (FR-029).
expect "blocking an archived bond is 204" 204 "" -- -X POST "$API/bonds/$LEFT_BOND/block" -H "Authorization: Bearer $STAYER_ACCESS" -d '{"withdrawEntries": true}'
expect "blocking again is 204" 204 "" -- -X POST "$API/bonds/$LEFT_BOND/block" -H "Authorization: Bearer $STAYER_ACCESS" -d '{"withdrawEntries": true}'
BLOCK_ROWS="$(docker compose exec -T postgres psql -U moyi -d "$MOYI_DB" -Atc "SELECT count(*) FROM blocks WHERE bond_id='$LEFT_BOND'" 2>/dev/null | tr -d '[:space:]' || echo psql-unavailable)"
case "$BLOCK_ROWS" in psql-unavailable) skip "block row count";; 1) pass "…and one row, not two";; *) fail "block rows" "expected 1, got '$BLOCK_ROWS'";; esac

# A non-member is refused before the bond's state is even looked at (T-02).
expect "a stranger cannot leave someone else's bond" 404 '"code":"NOT_FOUND"' -- -X POST "$API/bonds/$LEFT_BOND/leave" -H "Authorization: Bearer $STRANGER_ACCESS"
expect "…nor block in it" 404 '"code":"NOT_FOUND"' -- -X POST "$API/bonds/$LEFT_BOND/block" -H "Authorization: Bearer $STRANGER_ACCESS" -d '{"withdrawEntries": true}'

# FR-029: the pair cannot be put back in touch, whichever of them holds the code.
expect "the blocker starts a new bond" 201 '"code"' -- -X POST "$API/bonds" -H "Authorization: Bearer $BLOCKER_ACCESS" -d "$(bond_body "Again")"
REPAIR_CODE="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['code'])" <<<"$LAST_BODY")"
expect "the blocked account cannot join it: 404 INVITE_NOT_USABLE" 404 '"code":"INVITE_NOT_USABLE"' -- -X POST "$API/invites/$REPAIR_CODE/accept" -H "Authorization: Bearer $BLOCKED_ACCESS"

# FR-025: leaving frees the slot the limit counts.
# The bond they left is archived and no longer counts, so all three open slots
# are free again — a fourth create is the one that would have been refused.
expect "the leaver opens another bond" 201 "" -- -X POST "$API/bonds" -H "Authorization: Bearer $LEAVER_ACCESS" -d "$(bond_body "Two")"
expect "and another" 201 "" -- -X POST "$API/bonds" -H "Authorization: Bearer $LEAVER_ACCESS" -d "$(bond_body "Three")"
expect "a third open one is 201, because the bond they left frees its slot" 201 "" -- -X POST "$API/bonds" -H "Authorization: Bearer $LEAVER_ACCESS" -d "$(bond_body "Four")"
expect "…and a fourth is 409 BOND_LIMIT_REACHED" 409 '"code":"BOND_LIMIT_REACHED"' -- -X POST "$API/bonds" -H "Authorization: Bearer $LEAVER_ACCESS" -d "$(bond_body "Five")"

if tail -n "+$((LOG_LINES_BEFORE_ENDING + 1))" "$MOYI_LOG" | grep -qiE '\bblock'; then fail "block in log" "the log says block"; else pass "the log never says who blocked whom"; fi

echo; echo "settings — the conditional update (FR-027, doc 06 §1, ADR-0029)"
flush_buckets
verified_account "settler" "203.0.113.60"; SETTLER_ACCESS="$ACCOUNT_ACCESS"
expect "a bond to configure is 201" 201 '"name":"Us"' -- -X POST "$API/bonds" -H "Authorization: Bearer $SETTLER_ACCESS" -d "$(bond_body "Us")"
SET_BOND="$(printf '%s' "$LAST_BODY" | jget id)"
SET_INITIAL_ETAG="$(etag_of)"
SET_ETAG="$SET_INITIAL_ETAG"

# The four ways a condition can be wrong, before the one way it can be right.
expect "a PATCH with no If-Match is 428" 428 '"code":"PRECONDITION_REQUIRED"' -- -X PATCH "$API/bonds/$SET_BOND" -H "Authorization: Bearer $SETTLER_ACCESS" -d '{"name":"Us two"}'
expect "a stale If-Match is 412" 412 '"code":"PRECONDITION_FAILED"' -- -X PATCH "$API/bonds/$SET_BOND" -H "Authorization: Bearer $SETTLER_ACCESS" -H 'If-Match: "7"' -d '{"name":"Us two"}'
# ADR-0029's two deliberate departures from RFC 9110, probed on the wire so
# they stay decisions rather than drifting into accidents.
expect "If-Match: * is 428, deliberately" 428 '"code":"PRECONDITION_REQUIRED"' -- -X PATCH "$API/bonds/$SET_BOND" -H "Authorization: Bearer $SETTLER_ACCESS" -H 'If-Match: *' -d '{"name":"Us two"}'
expect "a weak validator is 412" 412 '"code":"PRECONDITION_FAILED"' -- -X PATCH "$API/bonds/$SET_BOND" -H "Authorization: Bearer $SETTLER_ACCESS" -H "If-Match: W/$SET_INITIAL_ETAG" -d '{"name":"Us two"}'
expect "the right If-Match is 200" 200 '"name":"Us two"' -- -X PATCH "$API/bonds/$SET_BOND" -H "Authorization: Bearer $SETTLER_ACCESS" -H "If-Match: $SET_INITIAL_ETAG" -d '{"name":"Us two","strictMode":true}'
SET_ETAG="$(etag_of)"
[ "$SET_ETAG" != "$SET_INITIAL_ETAG" ] && pass "…with a new ETag" || fail "ETag" "unchanged after patch"
expect "the same If-Match again is 412 — it is spent" 412 '"code":"PRECONDITION_FAILED"' -- -X PATCH "$API/bonds/$SET_BOND" -H "Authorization: Bearer $SETTLER_ACCESS" -H "If-Match: $SET_INITIAL_ETAG" -d '{"name":"Us three"}'

expect "an empty patch is 422" 422 '"code":"VALIDATION_FAILED"' -- -X PATCH "$API/bonds/$SET_BOND" -H "Authorization: Bearer $SETTLER_ACCESS" -H "If-Match: $SET_ETAG" -d '{}'
# FR-027 makes the anchor zone two-party and once per 30 days, which is B5's
# endpoint. Ignoring the field here would report success for a change that
# never happened.
expect "the anchor zone is refused here, not ignored" 422 'anchorTimezone' -- -X PATCH "$API/bonds/$SET_BOND" -H "Authorization: Bearer $SETTLER_ACCESS" -H "If-Match: $SET_ETAG" -d '{"anchorTimezone":"Europe/London"}'
expect "the type is patchable" 200 '"type":"FRIENDS"' -- -X PATCH "$API/bonds/$SET_BOND" -H "Authorization: Bearer $SETTLER_ACCESS" -H "If-Match: $SET_ETAG" -d '{"type":"FRIENDS"}'
SET_ETAG="$(etag_of)"
[[ "$LAST_BODY" == *'"maxMembers":2'* ]] && pass "…and the seats do not move with it" || fail "maxMembers" "${LAST_BODY:0:200}"
expect "a reveal time can be set" 200 '"revealTimeLocal":"21:00"' -- -X PATCH "$API/bonds/$SET_BOND" -H "Authorization: Bearer $SETTLER_ACCESS" -H "If-Match: $SET_ETAG" -d '{"revealTimeLocal":"21:00"}'
SET_ETAG="$(etag_of)"
expect "a named null clears it" 200 '"revealTimeLocal":null' -- -X PATCH "$API/bonds/$SET_BOND" -H "Authorization: Bearer $SETTLER_ACCESS" -H "If-Match: $SET_ETAG" -d '{"revealTimeLocal":null}'
SET_ETAG="$(etag_of)"
[[ "$LAST_BODY" == *'"strictMode":true'* ]] && pass "…and leaves the setting it did not name" || fail "patch isolation" "${LAST_BODY:0:250}"
# A patch whose values are already the row's values writes nothing, so the
# version does not move and the other member's ETag stays valid. Found by this
# script: the probe below expected a bump and there was none, because clearing
# an already-null field changes nothing (Hibernate's dirty check).
expect "a patch that changes nothing is 200 and moves no version" 200 '"revealTimeLocal":null' -- -X PATCH "$API/bonds/$SET_BOND" -H "Authorization: Bearer $SETTLER_ACCESS" -H "If-Match: $SET_ETAG" -d '{"revealTimeLocal":null}'
header_is "…the same ETag it was given" ETag "$SET_ETAG"

# The member's own settings: no condition, and nobody else's to see.
expect "the member reads their own settings" 200 '"reminderTimeLocal":"20:00"' -- "$API/bonds/$SET_BOND/members/me/settings" -H "Authorization: Bearer $SETTLER_ACCESS"
expect "and replaces them" 200 '"quietHoursEnd":"07:00"' -- -X PUT "$API/bonds/$SET_BOND/members/me/settings" -H "Authorization: Bearer $SETTLER_ACCESS" -d '{"nicknameForOther":"Ada","reminderTimeLocal":"07:30","reminderTimezone":"Europe/London","quietHoursStart":"22:00","quietHoursEnd":"07:00"}'
expect "a PUT that omits a field clears it" 200 '"nicknameForOther":null' -- -X PUT "$API/bonds/$SET_BOND/members/me/settings" -H "Authorization: Bearer $SETTLER_ACCESS" -d '{"reminderTimeLocal":"21:00"}'
[[ "$LAST_BODY" == *'"reminderTimezone":"Europe/London"'* ]] && pass "…but keeps the zone, which is not the caller's to lose" || fail "zone reset" "${LAST_BODY:0:250}"
expect "one quiet hour without the other is 422" 422 'quietHours' -- -X PUT "$API/bonds/$SET_BOND/members/me/settings" -H "Authorization: Bearer $SETTLER_ACCESS" -d '{"reminderTimeLocal":"21:00","quietHoursStart":"22:00"}'
expect "a missing reminder time is 422 rather than a silent 20:00" 422 'reminderTimeLocal' -- -X PUT "$API/bonds/$SET_BOND/members/me/settings" -H "Authorization: Bearer $SETTLER_ACCESS" -d '{}'
# A settings write is one member's business, so the bond's ETag must not move.
expect "the bond is where the last PATCH left it" 200 '"type":"FRIENDS"' -- "$API/bonds/$SET_BOND" -H "Authorization: Bearer $SETTLER_ACCESS"
header_is "…with the version the last PATCH produced, unmoved by the settings write" ETag "$SET_ETAG"

# T-02: a non-member is refused before the header is even read.
expect "a stranger patching it is 404, headers and all" 404 '"code":"NOT_FOUND"' -- -X PATCH "$API/bonds/$SET_BOND" -H "Authorization: Bearer $STRANGER_ACCESS" -H "If-Match: $SET_ETAG" -d '{"name":"Mine"}'
expect "…404 without a condition too, not 428" 404 '"code":"NOT_FOUND"' -- -X PATCH "$API/bonds/$SET_BOND" -H "Authorization: Bearer $STRANGER_ACCESS" -d '{"name":"Mine"}'
expect "…and cannot read its settings either" 404 '"code":"NOT_FOUND"' -- "$API/bonds/$SET_BOND/members/me/settings" -H "Authorization: Bearer $STRANGER_ACCESS"

# BR-9: an archived bond keeps its settings readable and takes no writes.
expect "leaving it is 204" 204 "" -- -X POST "$API/bonds/$SET_BOND/leave" -H "Authorization: Bearer $SETTLER_ACCESS"
expect "…the PATCH is then 409 BOND_ARCHIVED" 409 '"code":"BOND_ARCHIVED"' -- -X PATCH "$API/bonds/$SET_BOND" -H "Authorization: Bearer $SETTLER_ACCESS" -H 'If-Match: "5"' -d '{"name":"Us four"}'
expect "…the settings PUT is too" 409 '"code":"BOND_ARCHIVED"' -- -X PUT "$API/bonds/$SET_BOND/members/me/settings" -H "Authorization: Bearer $SETTLER_ACCESS" -d '{"reminderTimeLocal":"21:00"}'
expect "…and the settings are still readable" 200 '"reminderTimeLocal":"21:00"' -- "$API/bonds/$SET_BOND/members/me/settings" -H "Authorization: Bearer $SETTLER_ACCESS"
if grep -qE '"(nickname|quietHours)' "$MOYI_LOG"; then fail "settings in log" "a member's settings appear in the log"; else pass "no member setting appears in the log"; fi

echo; echo "two-party consent — the shared zone and closing the box (FR-027, FR-028, BR-6, ADR-0030)"
flush_buckets
verified_account "proposer" "203.0.113.70"; PROPOSER_ACCESS="$ACCOUNT_ACCESS"
verified_account "agreer" "203.0.113.71";   AGREER_ACCESS="$ACCOUNT_ACCESS"
expect "a bond for two is 201" 201 '"anchorTimezone":"Africa/Lagos"' -- -X POST "$API/bonds" -H "Authorization: Bearer $PROPOSER_ACCESS" -d "$(bond_body "Us")"
CONSENT_BOND="$(printf '%s' "$LAST_BODY" | jget id)"
CONSENT_CODE="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['code'])" <<<"$LAST_BODY")"
expect "the other member joins" 200 '"status":"ACTIVE"' -- -X POST "$API/invites/$CONSENT_CODE/accept" -H "Authorization: Bearer $AGREER_ACCESS"
# #170: a zone never moved may move now, so the bond names no date.
[[ "$LAST_BODY" == *'"timezoneChangeAllowedFrom":null'* ]] && pass "…and timezoneChangeAllowedFrom is null: a change is allowed now" || fail "timezoneChangeAllowedFrom" "${LAST_BODY:0:300}"

# states.md §8's three steps, on the wire.
expect "proposing a zone is 200 and moves nothing yet" 200 '"proposedTimezone":"Europe/London"' -- -X PATCH "$API/bonds/$CONSENT_BOND/timezone" -H "Authorization: Bearer $PROPOSER_ACCESS" -d '{"anchorTimezone":"Europe/London"}'
TIMEZONE_PROPOSAL_ID="$(python3 -c "import json,sys; print(json.load(sys.stdin)['pendingTimezoneChange']['id'])" <<<"$LAST_BODY")"
[[ "$LAST_BODY" == *'"anchorTimezone":"Africa/Lagos"'* ]] && pass "…the bond still says Africa/Lagos" || fail "premature move" "${LAST_BODY:0:250}"
expect "the proposer cannot confirm their own: 409" 409 '"code":"PROPOSAL_NEEDS_OTHER_MEMBER"' -- -X POST "$API/bonds/$CONSENT_BOND/timezone/confirm" -H "Authorization: Bearer $PROPOSER_ACCESS" -d "{\"proposalId\":\"$TIMEZONE_PROPOSAL_ID\"}"
expect "a second proposal is 409 PROPOSAL_PENDING" 409 '"code":"PROPOSAL_PENDING"' -- -X PATCH "$API/bonds/$CONSENT_BOND/timezone" -H "Authorization: Bearer $AGREER_ACCESS" -d '{"anchorTimezone":"Asia/Tokyo"}'
expect "the other member confirms: 200 and the zone moves" 200 '"anchorTimezone":"Europe/London"' -- -X POST "$API/bonds/$CONSENT_BOND/timezone/confirm" -H "Authorization: Bearer $AGREER_ACCESS" -d "{\"proposalId\":\"$TIMEZONE_PROPOSAL_ID\"}"
[[ "$LAST_BODY" == *'"pendingTimezoneChange":null'* ]] && pass "…and nothing is pending any more" || fail "pending not cleared" "${LAST_BODY:0:250}"
# FR-027's month, as a 409 with a date rather than a 429 with a retry (ADR-0030).
expect "a change within thirty days is 409 TIMEZONE_CHANGE_TOO_SOON" 409 '"code":"TIMEZONE_CHANGE_TOO_SOON"' -- -X PATCH "$API/bonds/$CONSENT_BOND/timezone" -H "Authorization: Bearer $PROPOSER_ACCESS" -d '{"anchorTimezone":"Asia/Tokyo"}'
[[ "$LAST_BODY" == *"can change again from"* ]] && pass "…and the detail names the date it becomes allowed" || fail "no date" "${LAST_BODY:0:250}"
keep_body problem-timezone-too-soon
# #142: the same date, typed, for a client to put into its own words: an extension member, YYYY-MM-DD.
TOO_SOON="$(python3 -c "
import json, sys, datetime, re
body = json.load(sys.stdin)
named = datetime.datetime.strptime(re.search(r'from (\d{1,2} \w+ \d{4})', body['detail']).group(1), '%d %B %Y').date()
print(body.get('allowedFrom') == named.isoformat(), body.get('allowedFrom'), body.get('traceId', ''))" <<<"$LAST_RAW_BODY" 2>/dev/null || echo "unreadable")"
case "$TOO_SOON" in True\ ????-??-??\ *) pass "…and allowedFrom carries that date, typed (${TOO_SOON:5:10})" ;; *) fail "allowedFrom" "got '$TOO_SOON' from ${LAST_BODY:0:300}" ;; esac
# The refusal is logged at WARN; the body's traceId is the one that log line carries.
TOO_SOON_TRACE="${TOO_SOON##* }"
if [ "${#TOO_SOON_TRACE}" = 32 ] && grep "TIMEZONE_CHANGE_TOO_SOON -> 409" "$MOYI_LOG" | grep -q "$TOO_SOON_TRACE"; then
  pass "…and its traceId is on the log line for that refusal"
else
  fail "traceId in the log" "'$TOO_SOON_TRACE' not on a TIMEZONE_CHANGE_TOO_SOON line of $MOYI_LOG"
fi
# #170: the bond names the same date before any attempt, to both members.
TOO_SOON_DATE="${TOO_SOON:5:10}"
expect "the bond names the date the zone may change again" 200 "\"timezoneChangeAllowedFrom\":\"$TOO_SOON_DATE\"" -- "$API/bonds/$CONSENT_BOND" -H "Authorization: Bearer $AGREER_ACCESS"
expect "a fixed-offset zone is 422 on the field" 422 '"field":"anchorTimezone"' -- -X PATCH "$API/bonds/$CONSENT_BOND/timezone" -H "Authorization: Bearer $PROPOSER_ACCESS" -d '{"anchorTimezone":"Etc/GMT+3"}'
expect "a zone of one non-breaking space is 422, not 500" 422 '"field":"anchorTimezone"' -- -X PATCH "$API/bonds/$CONSENT_BOND/timezone" -H "Authorization: Bearer $PROPOSER_ACCESS" -d '{"anchorTimezone":"\u00a0"}'

# Cancelling a proposal, on a second bond where the month has not been spent.
expect "a second bond for the pair" 201 '"code"' -- -X POST "$API/bonds" -H "Authorization: Bearer $AGREER_ACCESS" -d "$(bond_body "Two")"
SECOND_CONSENT="$(printf '%s' "$LAST_BODY" | jget id)"
SECOND_CONSENT_CODE="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['code'])" <<<"$LAST_BODY")"
expect "…joined" 200 '"status":"ACTIVE"' -- -X POST "$API/invites/$SECOND_CONSENT_CODE/accept" -H "Authorization: Bearer $PROPOSER_ACCESS"
expect "a proposal on it is 200" 200 '"proposedTimezone":"Asia/Tokyo"' -- -X PATCH "$API/bonds/$SECOND_CONSENT/timezone" -H "Authorization: Bearer $AGREER_ACCESS" -d '{"anchorTimezone":"Asia/Tokyo"}'
expect "either member may cancel it: 204" 204 "" -- -X DELETE "$API/bonds/$SECOND_CONSENT/timezone" -H "Authorization: Bearer $PROPOSER_ACCESS"
expect "cancelling again is 404" 404 "" -- -X DELETE "$API/bonds/$SECOND_CONSENT/timezone" -H "Authorization: Bearer $PROPOSER_ACCESS"
expect "…and a fresh proposal is allowed" 200 '"proposedTimezone":"Asia/Tokyo"' -- -X PATCH "$API/bonds/$SECOND_CONSENT/timezone" -H "Authorization: Bearer $AGREER_ACCESS" -d '{"anchorTimezone":"Asia/Tokyo"}'

# Closing the box: one route asks and agrees (FR-028, states.md §9).
expect "the first deletion request is 202, and nothing is deleted" 202 '"requestedByMemberId"' -- -X POST "$API/bonds/$CONSENT_BOND/deletion-request" -H "Authorization: Bearer $PROPOSER_ACCESS"
[[ "$LAST_BODY" == *'"status":"ACTIVE"'* ]] && pass "…the bond is still ACTIVE" || fail "premature deletion" "${LAST_BODY:0:250}"
expect "repeating it is an idempotent 202" 202 '"status":"ACTIVE"' -- -X POST "$API/bonds/$CONSENT_BOND/deletion-request" -H "Authorization: Bearer $PROPOSER_ACCESS"
expect "the other member's request confirms it: PENDING_DELETION" 202 '"status":"PENDING_DELETION"' -- -X POST "$API/bonds/$CONSENT_BOND/deletion-request" -H "Authorization: Bearer $AGREER_ACCESS"
[[ "$LAST_BODY" == *'"deletionScheduledFor":"'* ]] && pass "…with a date thirty days out" || fail "no schedule" "${LAST_BODY:0:250}"

# BR-9 during the cooling-off: readable, and no other write.
CONSENT_ETAG="$(curl -sS -o /dev/null -D - "$API/bonds/$CONSENT_BOND" -H "Authorization: Bearer $PROPOSER_ACCESS" | tr -d '\r' | awk 'tolower($1)=="etag:"{print $2}')"
expect "a PATCH during the cooling-off is 409 BOND_ARCHIVED" 409 '"code":"BOND_ARCHIVED"' -- -X PATCH "$API/bonds/$CONSENT_BOND" -H "Authorization: Bearer $PROPOSER_ACCESS" -H "If-Match: $CONSENT_ETAG" -d '{"name":"Us two"}'
expect "…so is a settings write" 409 '"code":"BOND_ARCHIVED"' -- -X PUT "$API/bonds/$CONSENT_BOND/members/me/settings" -H "Authorization: Bearer $PROPOSER_ACCESS" -d '{"reminderTimeLocal":"07:30"}'
expect "…and a zone proposal" 409 '"code":"BOND_ARCHIVED"' -- -X PATCH "$API/bonds/$CONSENT_BOND/timezone" -H "Authorization: Bearer $PROPOSER_ACCESS" -d '{"anchorTimezone":"Asia/Tokyo"}'
expect "both members still read it" 200 '"status":"PENDING_DELETION"' -- "$API/bonds/$CONSENT_BOND" -H "Authorization: Bearer $AGREER_ACCESS"

# The escape hatch, which is what makes the thirty days a cooling-off.
expect "either member calls it off: 204" 204 "" -- -X DELETE "$API/bonds/$CONSENT_BOND/deletion-request" -H "Authorization: Bearer $AGREER_ACCESS"
expect "…and the bond is ACTIVE again" 200 '"status":"ACTIVE"' -- "$API/bonds/$CONSENT_BOND" -H "Authorization: Bearer $PROPOSER_ACCESS"
[[ "$LAST_BODY" == *'"deletionScheduledFor":null'* ]] && pass "…with no date on it" || fail "schedule not cleared" "${LAST_BODY:0:250}"
expect "cancelling nothing is 404" 404 "" -- -X DELETE "$API/bonds/$CONSENT_BOND/deletion-request" -H "Authorization: Bearer $AGREER_ACCESS"

# T-02 on all five routes.
expect "a stranger cannot propose a zone" 404 '"code":"NOT_FOUND"' -- -X PATCH "$API/bonds/$CONSENT_BOND/timezone" -H "Authorization: Bearer $STRANGER_ACCESS" -d '{"anchorTimezone":"Asia/Tokyo"}'
expect "…nor confirm one" 404 '"code":"NOT_FOUND"' -- -X POST "$API/bonds/$CONSENT_BOND/timezone/confirm" -H "Authorization: Bearer $STRANGER_ACCESS" -d "{\"proposalId\":\"$TIMEZONE_PROPOSAL_ID\"}"
expect "…nor cancel one" 404 '"code":"NOT_FOUND"' -- -X DELETE "$API/bonds/$CONSENT_BOND/timezone" -H "Authorization: Bearer $STRANGER_ACCESS"
expect "…nor ask for a deletion" 404 '"code":"NOT_FOUND"' -- -X POST "$API/bonds/$CONSENT_BOND/deletion-request" -H "Authorization: Bearer $STRANGER_ACCESS"
expect "…nor cancel one" 404 '"code":"NOT_FOUND"' -- -X DELETE "$API/bonds/$CONSENT_BOND/deletion-request" -H "Authorization: Bearer $STRANGER_ACCESS"

# ADR-0030's decision: an archived bond refuses a deletion request whichever way
# it ended, so the blocked member cannot tell a block from a leave by trying it.
verified_account "ender" "203.0.113.72";  ENDER_ACCESS="$ACCOUNT_ACCESS"
verified_account "stayer2" "203.0.113.73"; STAYER2_ACCESS="$ACCOUNT_ACCESS"
expect "a bond to leave" 201 '"code"' -- -X POST "$API/bonds" -H "Authorization: Bearer $ENDER_ACCESS" -d "$(bond_body "Us")"
LEFT_CONSENT="$(printf '%s' "$LAST_BODY" | jget id)"
LEFT_CONSENT_CODE="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['code'])" <<<"$LAST_BODY")"
expect "…joined" 200 '"status":"ACTIVE"' -- -X POST "$API/invites/$LEFT_CONSENT_CODE/accept" -H "Authorization: Bearer $STAYER2_ACCESS"
expect "…and left" 204 "" -- -X POST "$API/bonds/$LEFT_CONSENT/leave" -H "Authorization: Bearer $ENDER_ACCESS"
expect "a deletion request on it is 409 BOND_ARCHIVED" 409 '"code":"BOND_ARCHIVED"' -- -X POST "$API/bonds/$LEFT_CONSENT/deletion-request" -H "Authorization: Bearer $STAYER2_ACCESS"
LEFT_REFUSAL="$(printf '%s' "$LAST_BODY" | sed 's/"instance":"[^"]*"/"instance":"-"/')"
verified_account "blocker2" "203.0.113.74"; BLOCKER2_ACCESS="$ACCOUNT_ACCESS"
verified_account "blocked2" "203.0.113.75"; BLOCKED2_ACCESS="$ACCOUNT_ACCESS"
expect "a bond to block in" 201 '"code"' -- -X POST "$API/bonds" -H "Authorization: Bearer $BLOCKER2_ACCESS" -d "$(bond_body "Us")"
BLOCK_CONSENT="$(printf '%s' "$LAST_BODY" | jget id)"
BLOCK_CONSENT_CODE="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['code'])" <<<"$LAST_BODY")"
expect "…joined" 200 '"status":"ACTIVE"' -- -X POST "$API/invites/$BLOCK_CONSENT_CODE/accept" -H "Authorization: Bearer $BLOCKED2_ACCESS"
expect "…and blocked" 204 "" -- -X POST "$API/bonds/$BLOCK_CONSENT/block" -H "Authorization: Bearer $BLOCKER2_ACCESS" -d '{"withdrawEntries": true}'
expect "the blocked member's deletion request is 409 too" 409 '"code":"BOND_ARCHIVED"' -- -X POST "$API/bonds/$BLOCK_CONSENT/deletion-request" -H "Authorization: Bearer $BLOCKED2_ACCESS"
BLOCK_REFUSAL="$(printf '%s' "$LAST_BODY" | sed 's/"instance":"[^"]*"/"instance":"-"/')"
[ "$LEFT_REFUSAL" = "$BLOCK_REFUSAL" ] && pass "…byte-identical to the left bond's refusal (doc 26 §2.1, ADR-0030)" || fail "deletion oracle" "the two refusals differ"

# The second review of #41: a member who walks out during the cooling-off must
# not be able to revoke the agreement on the way, or the member still in the
# bond is left holding something they consented to destroy and can never delete.
verified_account "walker" "203.0.113.76"; WALKER_ACCESS="$ACCOUNT_ACCESS"
verified_account "holder" "203.0.113.77"; HOLDER_ACCESS="$ACCOUNT_ACCESS"
expect "a bond the two agree to close" 201 '"code"' -- -X POST "$API/bonds" -H "Authorization: Bearer $WALKER_ACCESS" -d "$(bond_body "Us")"
WALK_BOND="$(printf '%s' "$LAST_BODY" | jget id)"
WALK_CODE="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['code'])" <<<"$LAST_BODY")"
expect "…joined" 200 '"status":"ACTIVE"' -- -X POST "$API/invites/$WALK_CODE/accept" -H "Authorization: Bearer $HOLDER_ACCESS"
expect "…both ask" 202 "" -- -X POST "$API/bonds/$WALK_BOND/deletion-request" -H "Authorization: Bearer $WALKER_ACCESS"
expect "…and it is counting down" 202 '"status":"PENDING_DELETION"' -- -X POST "$API/bonds/$WALK_BOND/deletion-request" -H "Authorization: Bearer $HOLDER_ACCESS"
expect "one of them leaves mid-cooling-off: 204" 204 "" -- -X POST "$API/bonds/$WALK_BOND/leave" -H "Authorization: Bearer $WALKER_ACCESS"
expect "…and cannot then call the deletion off: 404" 404 "" -- -X DELETE "$API/bonds/$WALK_BOND/deletion-request" -H "Authorization: Bearer $WALKER_ACCESS"
expect "…the bond is still counting down for the member who is in it" 200 '"status":"PENDING_DELETION"' -- "$API/bonds/$WALK_BOND" -H "Authorization: Bearer $HOLDER_ACCESS"
expect "…whose own cancel still works: 204" 204 "" -- -X DELETE "$API/bonds/$WALK_BOND/deletion-request" -H "Authorization: Bearer $HOLDER_ACCESS"
expect "…leaving an archived bond that knows when it ended" 200 '"status":"ARCHIVED"' -- "$API/bonds/$WALK_BOND" -H "Authorization: Bearer $HOLDER_ACCESS"
[[ "$LAST_BODY" != *'"archivedAt":null'* ]] && pass "…with archivedAt set, like every other archived bond" || fail "null archivedAt" "${LAST_BODY:0:250}"

echo; echo "gratitude — the bond-day and the first entry (FR-041, BR-1, BR-2, BR-10, C1, ADR-0031)"
flush_buckets
verified_account "author" "203.0.113.70"; AUTHOR_ACCESS="$ACCOUNT_ACCESS"
verified_account "partner" "203.0.113.71"; PARTNER_ACCESS="$ACCOUNT_ACCESS"
expect "a bond to write into is 201" 201 '"status":"PENDING_MEMBER"' -- -X POST "$API/bonds" -H "Authorization: Bearer $AUTHOR_ACCESS" -d "$(bond_body "Us")"
GRAT_BOND="$(printf '%s' "$LAST_BODY" | jget id)"
GRAT_CODE="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['code'])" <<<"$LAST_BODY")"
# The creator trying their own code — usually by scanning it. Refused before
# the code is consumed, which the partner's join on the next line then proves.
expect "the creator accepting their own code is 409 ALREADY_MEMBER" 409 '"code":"ALREADY_MEMBER"' -- -X POST "$API/invites/$GRAT_CODE/accept" -H "Authorization: Bearer $AUTHOR_ACCESS"
expect "the partner joins" 200 '"status":"ACTIVE"' -- -X POST "$API/invites/$GRAT_CODE/accept" -H "Authorization: Bearer $PARTNER_ACCESS"

# The whole slice on the wire, one round trip per fact: a first entry, the
# same member's second, the same key replayed, and the same key reused with a
# different body.
AUTHOR_KEY="$(python3 -c 'import uuid; print(uuid.uuid4())')"
ENTRY_TEXT="grateful for the light this morning"
expect "the first entry is 201, SUBMITTED" 201 '"status":"SUBMITTED"' -- -X POST "$API/bonds/$GRAT_BOND/entries" -H "Authorization: Bearer $AUTHOR_ACCESS" -H "Idempotency-Key: $AUTHOR_KEY" -d "{\"text\":\"$ENTRY_TEXT\"}"
ENTRY_ID="$(printf '%s' "$LAST_BODY" | jget id)"

expect "the same member's second entry today is 409 ENTRY_ALREADY_EXISTS" 409 '"code":"ENTRY_ALREADY_EXISTS"' -- -X POST "$API/bonds/$GRAT_BOND/entries" -H "Authorization: Bearer $AUTHOR_ACCESS" -H "Idempotency-Key: $(python3 -c 'import uuid; print(uuid.uuid4())')" -d '{"text":"a second entry, same day"}'

expect "replaying the same key with the same body is 201 again" 201 "\"id\":\"$ENTRY_ID\"" -- -X POST "$API/bonds/$GRAT_BOND/entries" -H "Authorization: Bearer $AUTHOR_ACCESS" -H "Idempotency-Key: $AUTHOR_KEY" -d "{\"text\":\"$ENTRY_TEXT\"}"
header_is "…and says it was replayed" Idempotency-Replayed true
ENTRY_ROWS="$(docker compose exec -T postgres psql -U moyi -d "$MOYI_DB" -Atc "SELECT count(*) FROM entries WHERE id='$ENTRY_ID'" 2>/dev/null | tr -d '[:space:]' || echo psql-unavailable)"
case "$ENTRY_ROWS" in psql-unavailable) skip "entry row count";; 1) pass "…and exactly one row landed in entries";; *) fail "entry rows" "expected 1, got '$ENTRY_ROWS'";; esac

expect "the same key with a different body is 422 IDEMPOTENCY_KEY_REUSED" 422 '"code":"IDEMPOTENCY_KEY_REUSED"' -- -X POST "$API/bonds/$GRAT_BOND/entries" -H "Authorization: Bearer $AUTHOR_ACCESS" -H "Idempotency-Key: $AUTHOR_KEY" -d '{"text":"this is not the body that key reserved"}'

# The author's own read of the day: their entry in full, as they wrote it.
expect "the author's today carries their own entry" 200 "\"text\":\"$ENTRY_TEXT\"" -- "$API/bonds/$GRAT_BOND/today" -H "Authorization: Bearer $AUTHOR_ACCESS"
[[ "$LAST_BODY" == *'"myEntry":{'*"\"text\":\"$ENTRY_TEXT\""*'"status":"SUBMITTED"'* ]] && pass "…as myEntry, SUBMITTED" || fail "myEntry shape" "${LAST_BODY:0:300}"

# The Idempotency-Key contract (doc 06 §1): required, and at most 255
# characters. Both are refused before the body is read or anything is written.
expect "an entry with no Idempotency-Key is 422 VALIDATION_FAILED" 422 '"code":"VALIDATION_FAILED"' -- -X POST "$API/bonds/$GRAT_BOND/entries" -H "Authorization: Bearer $PARTNER_ACCESS" -d '{"text":"no key was sent with this"}'
[[ "$LAST_BODY" == *'"field":"Idempotency-Key"'* ]] && pass "…and the errors entry names the header" || fail "missing-key body" "${LAST_BODY:0:250}"
LONG_KEY="$(python3 -c 'print("k" * 256)')"
expect "a key of 256 characters is 422 on the header" 422 '"field":"Idempotency-Key","code":"SIZE"' -- -X POST "$API/bonds/$GRAT_BOND/entries" -H "Authorization: Bearer $PARTNER_ACCESS" -H "Idempotency-Key: $LONG_KEY" -d '{"text":"the key is too long"}'

# Since D3 a voice note is attached as a photo is (the voice notes section near the
# end): an id that names none of the caller's is refused on its field, never ignored.
expect "an entry naming a voice note nobody has is 422 on voiceMediaId" 422 '"field":"voiceMediaId"' -- -X POST "$API/bonds/$GRAT_BOND/entries" -H "Authorization: Bearer $PARTNER_ACCESS" -H "Idempotency-Key: $(python3 -c 'import uuid; print(uuid.uuid4())')" -d "{\"text\":\"listen to this\",\"voiceMediaId\":\"$(python3 -c 'import uuid; print(uuid.uuid4())')\"}"

# BR-1/BR-8: the partner's own read of the day sees PARTIAL and a locked
# entry — the author and the status, nothing else, on the wire and in the log.
expect "the partner's today is PARTIAL, with the entry locked" 200 '"status":"PARTIAL"' -- "$API/bonds/$GRAT_BOND/today" -H "Authorization: Bearer $PARTNER_ACCESS"
[[ "$LAST_BODY" == *'"partnerEntry":{"authorMemberId"'*'"status":"LOCKED"'* ]] && pass "…partnerEntry is exactly an author and a status" || fail "locked shape" "${LAST_BODY:0:250}"
keep_body today-partner-locked
[[ "$LAST_BODY" != *"$ENTRY_TEXT"* ]] && pass "…and the text never appears in the response" || fail "text leaked in response" "${LAST_BODY:0:250}"
if grep -qF "$ENTRY_TEXT" "$MOYI_LOG"; then fail "text in log" "the entry text appears in the log"; else pass "…nor anywhere in the log"; fi

# FR-041, ADR-0029 §13's own edge, restated for an entry's text: a lone
# non-breaking space is blank by the domain's definition and must be a 422 on
# the field, not a 500 from an uncaught IllegalArgumentException.
expect "an entry of one non-breaking space is 422 on text, not 500" 422 '"field":"text"' -- -X POST "$API/bonds/$GRAT_BOND/entries" -H "Authorization: Bearer $PARTNER_ACCESS" -H "Idempotency-Key: $(python3 -c 'import uuid; print(uuid.uuid4())')" -d '{"text":"\u00a0"}'

# ADR-0031, ruling 2 (the owner, 2026-10-21): a text of only zero-width
# characters (Unicode's default-ignorable set) is refused as blank, the same
# 422 on the field. A zero-width space, a joiner and a byte-order mark, sent as
# the escapes a client that escapes non-ASCII would send.
expect "an entry of only zero-width characters is 422 on text, as blank" 422 '"field":"text"' -- -X POST "$API/bonds/$GRAT_BOND/entries" -H "Authorization: Bearer $PARTNER_ACCESS" -H "Idempotency-Key: $(python3 -c 'import uuid; print(uuid.uuid4())')" -d '{"text":"\u200b\u200d\ufeff"}'

# An unpaired UTF-16 surrogate has no UTF-8 form: it used to be a 201 whose row
# held '?' in its place (found 2026-10-04). It is a 422 on the field now.
expect "an entry with an unpaired surrogate is 422 on text" 422 '"field":"text"' -- -X POST "$API/bonds/$GRAT_BOND/entries" -H "Authorization: Bearer $PARTNER_ACCESS" -H "Idempotency-Key: $(python3 -c 'import uuid; print(uuid.uuid4())')" -d '{"text":"thank you\ud800"}'

# C2: an author can edit while the partner's entry is still absent.
EDITED_TEXT="thank you for another quiet morning"
expect "PATCH before reveal changes the author's words" 200 "\"text\":\"$EDITED_TEXT\"" -- -X PATCH "$API/entries/$ENTRY_ID" -H "Authorization: Bearer $AUTHOR_ACCESS" -d "{\"text\":\"$EDITED_TEXT\"}"
if grep -qF "$EDITED_TEXT" "$MOYI_LOG"; then fail "text in log" "the edited entry's text appears in the log"; else pass "…and the edited words are not in the log"; fi
expect "the partner cannot edit the author's entry" 404 '"code":"NOT_FOUND"' -- -X PATCH "$API/entries/$ENTRY_ID" -H "Authorization: Bearer $PARTNER_ACCESS" -d '{"text":"not mine"}'

# Ruling P12: an entry is stored exactly as sent. Each of these characters is
# one NFKC would rewrite (… to three full stops, ™ to TM, ﬁ to fi). The partner
# writes last in this section: the probes above need them not to have written.
TYPOGRAPHIC_TEXT="wait… thank you™ — ﬁne"
expect "an entry with typographic characters is 201, and echoes them" 201 "\"text\":\"$TYPOGRAPHIC_TEXT\"" -- -X POST "$API/bonds/$GRAT_BOND/entries" -H "Authorization: Bearer $PARTNER_ACCESS" -H "Idempotency-Key: $(python3 -c 'import uuid; print(uuid.uuid4())')" -d "{\"text\":\"$TYPOGRAPHIC_TEXT\"}"
expect "…and its author's today shows the same characters, stored as sent" 200 "\"text\":\"$TYPOGRAPHIC_TEXT\"" -- "$API/bonds/$GRAT_BOND/today" -H "Authorization: Bearer $PARTNER_ACCESS"
if grep -qF "$TYPOGRAPHIC_TEXT" "$MOYI_LOG"; then fail "text in log" "the typographic entry's text appears in the log"; else pass "…and it is not in the log either"; fi

# The second submission revealed both entries in one transaction.
expect "both submissions make today REVEALED" 200 '"status":"REVEALED"' -- "$API/bonds/$GRAT_BOND/today" -H "Authorization: Bearer $AUTHOR_ACCESS"
[[ "$LAST_BODY" == *"\"text\":\"$TYPOGRAPHIC_TEXT\""* ]] && pass "…and the author reads the partner's words" || fail "reveal" "partner text absent"
expect "the partner reads the author's edited words" 200 "\"text\":\"$EDITED_TEXT\"" -- "$API/bonds/$GRAT_BOND/today" -H "Authorization: Bearer $PARTNER_ACCESS"
# Slice C4: the streak. A day is counted when the close job evaluates it, and
# today is added as soon as both have written — so the first shared day reads one.
expect "the streak counts today once both have written" 200 '"current":1' -- "$API/bonds/$GRAT_BOND/streak" -H "Authorization: Bearer $PARTNER_ACCESS"
keep_body streak
[[ "$LAST_BODY" == *'"days":[{"date":"'*'"status":"COMPLETE","reason":null,"settlesAt":null}]'* ]] && pass "…and its calendar is that one day, COMPLETE" || fail "streak calendar" "${LAST_BODY:0:250}"
[[ "$LAST_BODY" == *'"lastRunLength":null,"lastRunBrokenOn":null'* ]] && pass "…and no run has ended: both sent, both null" || fail "streak ended run" "${LAST_BODY:0:250}"
expect "…and today carries the same streak" 200 '"streak":{"current":1,"longest":1,"freezesAvailable":0,"strictMode":false}' -- "$API/bonds/$GRAT_BOND/today" -H "Authorization: Bearer $AUTHOR_ACCESS"
expect "PATCH after reveal is ENTRY_IMMUTABLE" 409 '"code":"ENTRY_IMMUTABLE"' -- -X PATCH "$API/entries/$ENTRY_ID" -H "Authorization: Bearer $AUTHOR_ACCESS" -d '{"text":"too late"}'
expect "the partner cannot delete the author's entry" 404 '"code":"NOT_FOUND"' -- -X DELETE "$API/entries/$ENTRY_ID" -H "Authorization: Bearer $PARTNER_ACCESS"
expect "DELETE after reveal succeeds" 204 '' -- -X DELETE "$API/entries/$ENTRY_ID" -H "Authorization: Bearer $AUTHOR_ACCESS"
expect "repeated DELETE succeeds" 204 '' -- -X DELETE "$API/entries/$ENTRY_ID" -H "Authorization: Bearer $AUTHOR_ACCESS"
expect "the partner sees the tombstone" 200 '"status":"DELETED"' -- "$API/bonds/$GRAT_BOND/today" -H "Authorization: Bearer $PARTNER_ACCESS"
[[ "$LAST_BODY" != *"$EDITED_TEXT"* ]] && pass "…and the deleted words are gone" || fail "erasure" "deleted text returned"
expect "POST replay after DELETE returns its tombstone" 201 '"status":"DELETED"' -- -X POST "$API/bonds/$GRAT_BOND/entries" -H "Authorization: Bearer $AUTHOR_ACCESS" -H "Idempotency-Key: $AUTHOR_KEY" -d "{\"text\":\"$ENTRY_TEXT\"}"

# Doc 04 §8.3a: the creator may write before their partner joins, and the day
# that write lands on opens SUSPENDED, not OPEN, so neither the close job nor
# the streak walk (neither built yet) mistake it for an ordinary day.
expect "a lone creator's bond is 201" 201 '"status":"PENDING_MEMBER"' -- -X POST "$API/bonds" -H "Authorization: Bearer $AUTHOR_ACCESS" -d "$(bond_body "Solo for now")"
SOLO_BOND="$(printf '%s' "$LAST_BODY" | jget id)"
expect "writing before a partner has joined is still 201" 201 '"status":"SUBMITTED"' -- -X POST "$API/bonds/$SOLO_BOND/entries" -H "Authorization: Bearer $AUTHOR_ACCESS" -H "Idempotency-Key: $(python3 -c 'import uuid; print(uuid.uuid4())')" -d '{"text":"writing alone, for now"}'
SOLO_ENTRY="$(printf '%s' "$LAST_BODY" | jget id)"
expect "…and the day it landed on opens SUSPENDED, not OPEN" 200 '"status":"SUSPENDED"' -- "$API/bonds/$SOLO_BOND/today" -H "Authorization: Bearer $AUTHOR_ACCESS"

expect "DELETE before reveal frees the author's slot" 204 '' -- -X DELETE "$API/entries/$SOLO_ENTRY" -H "Authorization: Bearer $AUTHOR_ACCESS"
expect "the author can write a replacement on the same day" 201 '"status":"SUBMITTED"' -- -X POST "$API/bonds/$SOLO_BOND/entries" -H "Authorization: Bearer $AUTHOR_ACCESS" -H "Idempotency-Key: $(python3 -c 'import uuid; print(uuid.uuid4())')" -d '{"text":"replacement after deletion"}'
SOLO_REPLACEMENT="$(printf '%s' "$LAST_BODY" | jget id)"
expect "today deterministically selects the live replacement" 200 '"text":"replacement after deletion"' -- "$API/bonds/$SOLO_BOND/today" -H "Authorization: Bearer $AUTHOR_ACCESS"

# ADR-0031, ruling 1 (the owner, 2026-10-21): the 500 are counted as typed, on
# the text as sent. 499 letters and a typographic ellipsis are 500 so; under
# NFKC they were 502 and a 422. Untrimmed: a trailing space is counted too.
expect "DELETE frees the slot once more" 204 '' -- -X DELETE "$API/entries/$SOLO_REPLACEMENT" -H "Authorization: Bearer $AUTHOR_ACCESS"
AS_TYPED_TEXT="$(python3 -c 'print("a" * 499 + "\u2026")')"
expect "499 letters and an ellipsis are 500 as typed, and are 201" 201 "\"text\":\"$AS_TYPED_TEXT\"" -- -X POST "$API/bonds/$SOLO_BOND/entries" -H "Authorization: Bearer $AUTHOR_ACCESS" -H "Idempotency-Key: $(python3 -c 'import uuid; print(uuid.uuid4())')" -d "{\"text\":\"$AS_TYPED_TEXT\"}"
AS_TYPED_ENTRY="$(printf '%s' "$LAST_BODY" | jget id)"
expect "an edit to 500 letters and a trailing space is 501, 422 on text" 422 '"field":"text"' -- -X PATCH "$API/entries/$AS_TYPED_ENTRY" -H "Authorization: Bearer $AUTHOR_ACCESS" -d "{\"text\":\"$(python3 -c 'print("a" * 500)') \"}"
expect "an edit to only zero-width characters is 422 on text" 422 '"field":"text"' -- -X PATCH "$API/entries/$AS_TYPED_ENTRY" -H "Authorization: Bearer $AUTHOR_ACCESS" -d '{"text":"\u2060\u00ad"}'
expect "…and the entry is as it was" 200 "\"text\":\"$AS_TYPED_TEXT\"" -- "$API/bonds/$SOLO_BOND/today" -H "Authorization: Bearer $AUTHOR_ACCESS"

echo; echo "gratitude — a timed reveal waits in its own status (FR-062, ADR-0032)"
flush_buckets
verified_account "early" "203.0.113.78"; EARLY_ACCESS="$ACCOUNT_ACCESS"
verified_account "late" "203.0.113.79";  LATE_ACCESS="$ACCOUNT_ACCESS"
# 23:59 in Lagos: not yet, on any run that is not in that last minute.
expect "a bond that reveals at 23:59 is 201" 201 '"revealTimeLocal":"23:59"' -- -X POST "$API/bonds" -H "Authorization: Bearer $EARLY_ACCESS" -d '{"name":"Evenings","type":"COUPLE","anchorTimezone":"Africa/Lagos","revealTimeLocal":"23:59"}'
TIMED_BOND="$(printf '%s' "$LAST_BODY" | jget id)"
TIMED_CODE="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['code'])" <<<"$LAST_BODY")"
expect "…joined" 200 '"status":"ACTIVE"' -- -X POST "$API/invites/$TIMED_CODE/accept" -H "Authorization: Bearer $LATE_ACCESS"
TIMED_TEXT="kept until the evening"
expect "the first writes" 201 '"status":"SUBMITTED"' -- -X POST "$API/bonds/$TIMED_BOND/entries" -H "Authorization: Bearer $EARLY_ACCESS" -H "Idempotency-Key: $(python3 -c 'import uuid; print(uuid.uuid4())')" -d "{\"text\":\"$TIMED_TEXT\"}"
expect "the second writes, and is not shown a reveal" 201 '"status":"SUBMITTED"' -- -X POST "$API/bonds/$TIMED_BOND/entries" -H "Authorization: Bearer $LATE_ACCESS" -H "Idempotency-Key: $(python3 -c 'import uuid; print(uuid.uuid4())')" -d '{"text":"mine can wait too"}'
expect "both have written and the day is PENDING_REVEAL, not PARTIAL" 200 '"status":"PENDING_REVEAL"' -- "$API/bonds/$TIMED_BOND/today" -H "Authorization: Bearer $LATE_ACCESS"
[[ "$LAST_BODY" == *'"status":"LOCKED"'* && "$LAST_BODY" != *"$TIMED_TEXT"* ]] && pass "…and the partner's entry is still locked, its words withheld" || fail "timed reveal" "${LAST_BODY:0:250}"
expect "a day still waiting on its reveal time is not in the streak yet" 200 '"current":0' -- "$API/bonds/$TIMED_BOND/streak" -H "Authorization: Bearer $EARLY_ACCESS"
# Today, until both have written, is OPEN on the calendar to both: who has written so far is GET /today's to say.
[[ "$LAST_BODY" == *'"status":"OPEN","reason":null,"settlesAt":"'*'Z"}]'* && "$LAST_BODY" != *PARTIAL* && "$LAST_BODY" != *PENDING_REVEAL* && "$LAST_BODY" != *SOLO* ]] && pass "…and its calendar says OPEN, not who has written" || fail "streak calendar vocabulary" "${LAST_BODY:0:250}"
expect "a stranger asking for a bond's streak gets the one 404" 404 '"code":"NOT_FOUND"' -- "$API/bonds/$TIMED_BOND/streak" -H "Authorization: Bearer $AUTHOR_ACCESS"

echo; echo "gratitude — a confirmed zone change decides no date until tomorrow (BR-6, ADR-0031 §3)"
flush_buckets
verified_account "mover" "203.0.113.72";  MOVER_ACCESS="$ACCOUNT_ACCESS"
verified_account "stayer" "203.0.113.73"; STAYER_ACCESS="$ACCOUNT_ACCESS"
expect "a bond anchored on Africa/Lagos is 201" 201 '"anchorTimezone":"Africa/Lagos"' -- -X POST "$API/bonds" -H "Authorization: Bearer $MOVER_ACCESS" -d "$(bond_body "Us")"
HANDOFF_BOND="$(printf '%s' "$LAST_BODY" | jget id)"
HANDOFF_CODE="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['code'])" <<<"$LAST_BODY")"
expect "the other member joins" 200 '"status":"ACTIVE"' -- -X POST "$API/invites/$HANDOFF_CODE/accept" -H "Authorization: Bearer $STAYER_ACCESS"

# The check is only worth making if the two zones disagree about today's date
# right now, so the new zone is chosen by the clock: Kiritimati (+14) is already
# on tomorrow from 11:00 in Lagos (+1); before that, Pago Pago (-11) is still on
# yesterday. Either way the Lagos date and the new zone's date differ, and an
# implementation that let the confirmed zone decide today's date would file the
# entry below on the wrong one. Prints "<new zone> <Lagos date> <new zone's date>".
zone_plan() {
  python3 - <<'PY'
from datetime import datetime
from zoneinfo import ZoneInfo
lagos = datetime.now(ZoneInfo("Africa/Lagos"))
zone = "Pacific/Kiritimati" if lagos.hour >= 11 else "Pacific/Pago_Pago"
print(zone, lagos.date().isoformat(), datetime.now(ZoneInfo(zone)).date().isoformat())
PY
}
read -r NEW_ZONE LAGOS_DATE NEW_ZONE_DATE <<<"$(zone_plan)"
[ "$LAGOS_DATE" != "$NEW_ZONE_DATE" ] && pass "the zones disagree about today: Lagos $LAGOS_DATE, $NEW_ZONE $NEW_ZONE_DATE" || fail "zone plan" "Lagos and $NEW_ZONE agree on $LAGOS_DATE, so this section would prove nothing"

expect "proposing $NEW_ZONE is 200" 200 "\"proposedTimezone\":\"$NEW_ZONE\"" -- -X PATCH "$API/bonds/$HANDOFF_BOND/timezone" -H "Authorization: Bearer $MOVER_ACCESS" -d "{\"anchorTimezone\":\"$NEW_ZONE\"}"
HANDOFF_PROPOSAL_ID="$(python3 -c "import json,sys; print(json.load(sys.stdin)['pendingTimezoneChange']['id'])" <<<"$LAST_BODY")"
expect "the other member confirms: 200" 200 "\"anchorTimezone\":\"$NEW_ZONE\"" -- -X POST "$API/bonds/$HANDOFF_BOND/timezone/confirm" -H "Authorization: Bearer $STAYER_ACCESS" -d "{\"proposalId\":\"$HANDOFF_PROPOSAL_ID\"}"

# The request is recorded the instant consent completes (B5, unchanged)…
expect "GET /bonds/{id} reports the new zone at once" 200 "\"anchorTimezone\":\"$NEW_ZONE\"" -- "$API/bonds/$HANDOFF_BOND" -H "Authorization: Bearer $MOVER_ACCESS"
# …and decides nothing yet: today is still the old zone's day, for a read and
# for a write, on a day that had no row when the change was confirmed. That
# last part is what a zone string copied at row creation could not do.
expect "today is 200 before anyone has written" 200 '"bondDay"' -- "$API/bonds/$HANDOFF_BOND/today" -H "Authorization: Bearer $MOVER_ACCESS"
HANDOFF_TODAY_BODY="$LAST_BODY"
# Re-planned after the read, for the reason given below the write: the date
# is only asserted if Lagos midnight did not pass between the plan and the GET.
read -r _ LAGOS_DATE_AT_READ _ <<<"$(zone_plan)"
if [ "$LAGOS_DATE_AT_READ" != "$LAGOS_DATE" ]; then
  skip "the pre-write date check (Lagos midnight passed mid-section)"
else
  [[ "$HANDOFF_TODAY_BODY" == *"\"bondDay\":{\"date\":\"$LAGOS_DATE\""* ]] && pass "…and it is still the Lagos date, $LAGOS_DATE" || fail "today's date" "expected bondDay.date $LAGOS_DATE: ${HANDOFF_TODAY_BODY:0:250}"
fi
expect "an entry written straight after the change is 201" 201 '"status":"SUBMITTED"' -- -X POST "$API/bonds/$HANDOFF_BOND/entries" -H "Authorization: Bearer $MOVER_ACCESS" -H "Idempotency-Key: $(python3 -c 'import uuid; print(uuid.uuid4())')" -d '{"text":"written the minute we agreed to move"}'
HANDOFF_ENTRY_BODY="$LAST_BODY"
# Re-planned after the write: if Lagos midnight passed between the plan and the
# entry, the date this section expects is no longer the one the server used.
read -r _ LAGOS_DATE_AFTER _ <<<"$(zone_plan)"
if [ "$LAGOS_DATE_AFTER" != "$LAGOS_DATE" ]; then
  skip "the landing-date checks (Lagos midnight passed mid-section)"
else
  [[ "$HANDOFF_ENTRY_BODY" == *"\"date\":\"$LAGOS_DATE\""* ]] && pass "…and it lands on the old zone's date, $LAGOS_DATE" || fail "entry date" "expected date $LAGOS_DATE: ${HANDOFF_ENTRY_BODY:0:250}"
  [[ "$HANDOFF_ENTRY_BODY" != *"\"date\":\"$NEW_ZONE_DATE\""* ]] && pass "…not on $NEW_ZONE's, $NEW_ZONE_DATE" || fail "entry redated" "the entry was filed under the new zone: ${HANDOFF_ENTRY_BODY:0:250}"
  expect "the partner's today is that same day, PARTIAL" 200 "\"bondDay\":{\"date\":\"$LAGOS_DATE\",\"status\":\"PARTIAL\",\"settlesAt\":\"" -- "$API/bonds/$HANDOFF_BOND/today" -H "Authorization: Bearer $STAYER_ACCESS"
  # The rows behind it: the day keeps the zone it began under, and the timeline
  # holds a closed Lagos interval followed by an open one that starts later.
  DAY_ZONE="$(docker compose exec -T postgres psql -U moyi -d "$MOYI_DB" -Atc "SELECT anchor_timezone FROM bond_days WHERE bond_id='$HANDOFF_BOND' AND date='$LAGOS_DATE'" 2>/dev/null | tr -d '[:space:]' || echo psql-unavailable)"
  case "$DAY_ZONE" in psql-unavailable) skip "the bond_days check";; Africa/Lagos) pass "…the day's row keeps Africa/Lagos";; *) fail "day zone" "expected Africa/Lagos, got '$DAY_ZONE'";; esac
  INTERVALS="$(docker compose exec -T postgres psql -U moyi -d "$MOYI_DB" -Atc "SELECT string_agg(zone || CASE WHEN effective_to IS NULL THEN ':open' ELSE ':closed' END || CASE WHEN effective_from > now() THEN ':future' ELSE ':past' END, ',' ORDER BY effective_from) FROM bond_anchor_intervals WHERE bond_id='$HANDOFF_BOND'" 2>/dev/null | tr -d '[:space:]' || echo psql-unavailable)"
  case "$INTERVALS" in psql-unavailable) skip "the timeline check";; "Africa/Lagos:closed:past,$NEW_ZONE:open:future") pass "…and the timeline hands over later: $INTERVALS";; *) fail "timeline" "expected Africa/Lagos:closed:past,$NEW_ZONE:open:future, got '$INTERVALS'";; esac
fi

echo; echo "withdrawal — ending a bond and taking one's words back, with the poller running (FR-029a, spec §6.7, ADR-0035)"
flush_buckets
LOG_LINES_BEFORE_WITHDRAWAL="$(wc -l < "$MOYI_LOG" | tr -d ' ')"
# What this section is for: the two halves of a withdrawal meeting in the real
# process. `bond` records it and publishes an event in the ending's transaction;
# the outbox poller, on its own thread every two seconds, hands the event to
# `gratitude`, which erases the rows. The tests run the dispatcher by hand; here
# nothing does, and an entry is only erased if the poller, the registration at
# start-up and the consumer are all really wired in the jar.

# sql <query> — one value from the run's database, whitespace removed.
# Whether psql can be reached at all is asked once, here: when it cannot, every
# sql read is "psql-unavailable" and the checks that read it skip, counted. When
# it can, a query that fails is a broken check, not a missing tool: it prints
# "psql-error: <psql's message>", which no check expects, so it is a FAIL.
if docker compose exec -T postgres psql -U moyi -d "$MOYI_DB" -Atc "SELECT 1" >/dev/null 2>&1; then PSQL_OK=1; else PSQL_OK=0; fi
sql() {
  [ "$PSQL_OK" = 1 ] || { echo psql-unavailable; return 0; }
  local out err; err="$(mktemp)"
  if out="$(docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U moyi -d "$MOYI_DB" -Atc "$1" 2>"$err")"; then
    printf '%s\n' "$out" | tr -d '[:space:]'
  else
    printf 'psql-error: %s\n' "$(tr -s '[:space:]' ' ' <"$err" | cut -c1-200)"
  fi
  rm -f "$err"
}
new_key() { python3 -c 'import uuid; print(uuid.uuid4())'; }

# revealed_bond <label> <a's token> <b's token> <a's text> <b's text> — a bond
# both have written in today, so each has read the other. Leaves its id in
# RB_BOND and the two entries' ids in RB_A_ENTRY and RB_B_ENTRY.
revealed_bond() {
  local label="$1" a="$2" b="$3" a_text="$4" b_text="$5" code
  expect "$label: a bond" 201 '"status":"PENDING_MEMBER"' -- -X POST "$API/bonds" -H "Authorization: Bearer $a" -d "$(bond_body "Us")"
  RB_BOND="$(printf '%s' "$LAST_BODY" | jget id)"
  code="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['code'])" <<<"$LAST_BODY")"
  expect "$label: …joined" 200 '"status":"ACTIVE"' -- -X POST "$API/invites/$code/accept" -H "Authorization: Bearer $b"
  expect "$label: one writes" 201 '"status":"SUBMITTED"' -- -X POST "$API/bonds/$RB_BOND/entries" -H "Authorization: Bearer $a" -H "Idempotency-Key: $(new_key)" -d "{\"text\":\"$a_text\"}"
  RB_A_ENTRY="$(printf '%s' "$LAST_BODY" | jget id)"
  expect "$label: the other writes" 201 "\"text\":\"$b_text\"" -- -X POST "$API/bonds/$RB_BOND/entries" -H "Authorization: Bearer $b" -H "Idempotency-Key: $(new_key)" -d "{\"text\":\"$b_text\"}"
  RB_B_ENTRY="$(printf '%s' "$LAST_BODY" | jget id)"
  expect "$label: the day is REVEALED and the second reads the first" 200 "\"text\":\"$a_text\"" -- "$API/bonds/$RB_BOND/today" -H "Authorization: Bearer $b"
  [[ "$LAST_BODY" == *'"status":"REVEALED"'* ]] || fail "$label: reveal" "${LAST_BODY:0:250}"
}

# today_field <myEntry|partnerEntry> <field> — from LAST_BODY: the value, "null"
# for a JSON null, "absent" for a key that is not there.
today_field() {
  python3 -c "
import json, sys
entry = json.load(sys.stdin).get('$1') or {}
value = entry.get('$2', 'absent')
print('null' if value is None else value)" <<<"$LAST_BODY"
}

# await_erased <entry id> — asks the database once a second, for at most ten
# seconds, whether that entry's row has lost its words, and leaves what it last
# saw in ERASED_ROW ("1|DELETED" when it has) and the seconds it took in
# ERASED_AFTER. This is the one wait in the script that is the point: the
# erasure is the poller's, two seconds apart, and nothing in the request that
# ended the bond does it. Bounded, and it stops the moment the row is erased.
await_erased() {
  local entry="$1" started; started="$(date +%s)"
  for _ in $(seq 1 10); do
    ERASED_ROW="$(sql "SELECT (text IS NULL)::int || '|' || status FROM entries WHERE id='$entry'")"
    if [ "$ERASED_ROW" = "1|DELETED" ] || [ "$ERASED_ROW" = "psql-unavailable" ]; then break; fi
    sleep 1
  done
  ERASED_AFTER="$(( $(date +%s) - started ))"
}

# erased_check <label> — the verdict on what await_erased last saw.
erased_check() {
  case "$ERASED_ROW" in
    psql-unavailable) skip "$1 (psql not reachable through docker compose)" ;;
    "1|DELETED") pass "$1 (the row has no text and is DELETED, within ${ERASED_AFTER}s)" ;;
    *) fail "$1" "ten seconds on, the row is '$ERASED_ROW', not '1|DELETED': the poller has not erased it" ;;
  esac
}

verified_account "withdrawer" "203.0.113.80"; TAKER_ACCESS="$ACCOUNT_ACCESS"
verified_account "reader" "203.0.113.81";     READER_ACCESS="$ACCOUNT_ACCESS"
verified_account "keeper" "203.0.113.82";     KEEPER_ACCESS="$ACCOUNT_ACCESS"
verified_account "other" "203.0.113.83";      OTHER_ACCESS="$ACCOUNT_ACCESS"

# Three bonds, each with a day both have written and read. No two of these
# texts are the same, so each can be looked for by itself.
TAKEN_TEXT="thank you for waiting up for me"
READER_TEXT="thank you for the long way home"
KEPT_TEXT="thank you for the seat by the window"
OTHER_TEXT="thank you for remembering the umbrella"
LEFT_TEXT="thank you for the last of the oranges"
STAYED_TEXT="thank you for holding the door"
revealed_bond "to be withdrawn from" "$TAKER_ACCESS" "$READER_ACCESS" "$TAKEN_TEXT" "$READER_TEXT"
TAKEN_BOND="$RB_BOND"; TAKEN_ENTRY="$RB_A_ENTRY"; READER_ENTRY="$RB_B_ENTRY"
revealed_bond "to be ended keeping the words" "$KEEPER_ACCESS" "$OTHER_ACCESS" "$KEPT_TEXT" "$OTHER_TEXT"
KEPT_BOND="$RB_BOND"; KEPT_ENTRY="$RB_A_ENTRY"; OTHER_ENTRY="$RB_B_ENTRY"
revealed_bond "to be left" "$TAKER_ACCESS" "$OTHER_ACCESS" "$LEFT_TEXT" "$STAYED_TEXT"
LEAVE_BOND="$RB_BOND"; LEFT_ENTRY="$RB_A_ENTRY"

# A body the route cannot read is refused before anything is decided: a
# misspelt flag must not fall through to the default, which on this route
# erases. And it is refused the same for somebody who is not in the bond, who
# would otherwise learn from a 404 against a 400 that the bond exists.
expect "a misspelt flag to /block is 400 MALFORMED_REQUEST" 400 '"code":"MALFORMED_REQUEST"' -- -X POST "$API/bonds/$KEPT_BOND/block" -H "Authorization: Bearer $KEEPER_ACCESS" -d '{"withdrawEntry": false}'
MEMBER_REFUSAL="$LAST_BODY"
expect "…and the same body from a stranger is the same 400" 400 '"code":"MALFORMED_REQUEST"' -- -X POST "$API/bonds/$KEPT_BOND/block" -H "Authorization: Bearer $READER_ACCESS" -d '{"withdrawEntry": false}'
[ "$LAST_BODY" = "$MEMBER_REFUSAL" ] && pass "…byte for byte, so it says nothing of who is in the bond" || fail "malformed-body oracle" "member: ${MEMBER_REFUSAL:0:160} stranger: ${LAST_BODY:0:160}"
expect "…and the bond is still open" 200 '"status":"ACTIVE"' -- "$API/bonds/$KEPT_BOND" -H "Authorization: Bearer $OTHER_ACCESS"
REFUSED_ROWS="$(sql "SELECT (SELECT count(*) FROM blocks WHERE bond_id='$KEPT_BOND') + (SELECT count(*) FROM bond_entry_withdrawals WHERE bond_id='$KEPT_BOND')")"
case "$REFUSED_ROWS" in psql-unavailable) skip "the refused-ending row check";; 0) pass "…with nothing recorded against it";; *) fail "refused ending" "expected no block and no withdrawal, got '$REFUSED_ROWS' rows";; esac

# The ending that keeps the words goes FIRST, on purpose. It publishes no
# event, so there is nothing of its own to wait for; but every erasure seen
# below is the poller having run after this bond ended, and its text is read
# again at the end of the section, ten seconds or more from here.
expect "a block that says to keep the entries is 204" 204 "" -- -X POST "$API/bonds/$KEPT_BOND/block" -H "Authorization: Bearer $KEEPER_ACCESS" -d '{"withdrawEntries": false}'
KEPT_SINCE="$(date +%s)"

# A block that says to take the entries (FR-029a). It must say: the flag is required here.
expect "a block that takes the entries is 204" 204 "" -- -X POST "$API/bonds/$TAKEN_BOND/block" -H "Authorization: Bearer $TAKER_ACCESS" -d '{"withdrawEntries": true}'
# At once, and before any poller has run: the read is gated on the record `bond` wrote in that request.
expect "the partner's very next read no longer carries the words" 200 '"status":"DELETED"' -- "$API/bonds/$TAKEN_BOND/today" -H "Authorization: Bearer $READER_ACCESS"
[[ "$LAST_BODY" != *"$TAKEN_TEXT"* ]] && pass "…not anywhere in the body" || fail "withdrawn text read" "${LAST_BODY:0:300}"
await_erased "$TAKEN_ENTRY"
erased_check "the poller erases the withdrawn entry"
expect "the partner's today, once it is erased" 200 '"status":"REVEALED"' -- "$API/bonds/$TAKEN_BOND/today" -H "Authorization: Bearer $READER_ACCESS"
[ "$(today_field partnerEntry text)|$(today_field partnerEntry status)" = "null|DELETED" ] && pass "…the withdrawn entry is a tombstone: text null, DELETED" || fail "tombstone" "${LAST_BODY:0:300}"
keep_body today-partner-deleted
[ "$(today_field myEntry text)" = "$READER_TEXT" ] && pass "…and the partner's own entry is whole" || fail "partner's own entry" "${LAST_BODY:0:300}"
READER_ROW="$(sql "SELECT (text = '$READER_TEXT')::int || '|' || status FROM entries WHERE id='$READER_ENTRY'")"
case "$READER_ROW" in psql-unavailable) skip "the partner's row check";; "1|REVEALED") pass "…in the database too: her row is untouched";; *) fail "partner's row" "expected 1|REVEALED, got '$READER_ROW'";; esac
expect "the one who withdrew reads their own tombstone" 200 '"status":"DELETED"' -- "$API/bonds/$TAKEN_BOND/today" -H "Authorization: Bearer $TAKER_ACCESS"
[ "$(today_field myEntry text)|$(today_field myEntry status)" = "null|DELETED" ] && pass "…as myEntry, with no text" || fail "own tombstone" "${LAST_BODY:0:300}"

# A leave keeps the entries unless it is asked to take them. Asked, it erases as a block does.
expect "a leave that takes the entries back is 204" 204 "" -- -X POST "$API/bonds/$LEAVE_BOND/leave" -H "Authorization: Bearer $TAKER_ACCESS" -d '{"withdrawEntries": true}'
await_erased "$LEFT_ENTRY"
erased_check "the poller erases the entry of the member who left"
expect "the one who stayed sees the tombstone" 200 '"status":"DELETED"' -- "$API/bonds/$LEAVE_BOND/today" -H "Authorization: Bearer $OTHER_ACCESS"
[ "$(today_field partnerEntry text)" = "null" ] && [ "$(today_field myEntry text)" = "$STAYED_TEXT" ] && pass "…beside their own entry, whole" || fail "leave tombstone" "${LAST_BODY:0:300}"

# The bond ended keeping its words: ten seconds of the poller, and at least two
# erasures elsewhere, have passed over it. The loop only waits out what is left
# of the ten; nothing is published for this bond, so there is no event to wait on.
while [ "$(( $(date +%s) - KEPT_SINCE ))" -lt 10 ]; do sleep 1; done
expect "ten seconds on, the kept words are still read by the other member" 200 "\"text\":\"$KEPT_TEXT\"" -- "$API/bonds/$KEPT_BOND/today" -H "Authorization: Bearer $OTHER_ACCESS"
[ "$(today_field partnerEntry text)|$(today_field partnerEntry status)" = "$KEPT_TEXT|REVEALED" ] && pass "…as the partner's entry, REVEALED" || fail "kept entry" "${LAST_BODY:0:300}"
keep_body today-partner-revealed
KEPT_ROW="$(sql "SELECT (text = '$KEPT_TEXT')::int || '|' || status FROM entries WHERE id='$KEPT_ENTRY'")"
case "$KEPT_ROW" in psql-unavailable) skip "the kept row check";; "1|REVEALED") pass "…and the row still holds them";; *) fail "kept row" "expected 1|REVEALED, got '$KEPT_ROW'";; esac

# An ended bond takes no new words, and an author may still take their own
# back (ADR-0032, question 1): were that refused, a tombstone appearing after
# the end could only mean a withdrawal.
expect "PATCH on an ended bond is 409 BOND_ARCHIVED" 409 '"code":"BOND_ARCHIVED"' -- -X PATCH "$API/entries/$OTHER_ENTRY" -H "Authorization: Bearer $OTHER_ACCESS" -d '{"text":"too late to change"}'
expect "DELETE of one's own entry on an ended bond is 204" 204 "" -- -X DELETE "$API/entries/$KEPT_ENTRY" -H "Authorization: Bearer $KEEPER_ACCESS"
expect "…and the partner sees the tombstone" 200 '"status":"DELETED"' -- "$API/bonds/$KEPT_BOND/today" -H "Authorization: Bearer $OTHER_ACCESS"
[ "$(today_field partnerEntry text)" = "null" ] && [[ "$LAST_BODY" != *"$KEPT_TEXT"* ]] && pass "…with the words gone" || fail "delete on an ended bond" "${LAST_BODY:0:300}"
expect "…while the partner cannot delete it for them" 404 '"code":"NOT_FOUND"' -- -X DELETE "$API/entries/$OTHER_ENTRY" -H "Authorization: Bearer $KEEPER_ACCESS"

# The outbox, after a whole run: every ending above and in the sections before
# it published or did not, and nothing published is still owed or has failed.
OWED="$(sql "SELECT count(*) FILTER (WHERE processed_at IS NULL) || '|' || count(*) FILTER (WHERE last_error IS NOT NULL) || '|' || (count(*) FILTER (WHERE processed_at IS NOT NULL) > 0)::int FROM outbox_deliveries WHERE consumer_id = 'gratitude.withdrawal'")"
case "$OWED" in psql-unavailable) skip "the delivery check";; "0|0|1") pass "every withdrawal was delivered: none owed, none ever failed";; *) fail "outbox deliveries" "expected owed|failed|any-delivered = 0|0|1, got '$OWED'";; esac
WITHDRAWN_LOG="$(tail -n "+$((LOG_LINES_BEFORE_WITHDRAWAL + 1))" "$MOYI_LOG")"
# The poller says nothing at INFO when it delivers: the line would sit two seconds after
# "A member ended bond …" and say that ending took its entries back (ADR-0035 decision 11).
# That it was the poller is what the erasures above showed; nothing else runs here.
if grep -qE "outbox: delivered" <<<"$WITHDRAWN_LOG"; then fail "outbox poller" "the poller announced a delivery in the log"; else pass "…and the poller did not announce one in the log"; fi
if grep -qE "Outbox delivery failed|outbox: .*(could not|did not finish|still running|more is waiting)" <<<"$WITHDRAWN_LOG"; then fail "outbox poller" "the poller or the dispatcher logged a failure"; else pass "…and neither it nor the dispatcher logged a failure"; fi
LEAKED=""
for words in "$TAKEN_TEXT" "$READER_TEXT" "$KEPT_TEXT" "$OTHER_TEXT" "$LEFT_TEXT" "$STAYED_TEXT"; do grep -qF "$words" "$MOYI_LOG" && LEAKED="$LEAKED [$words]"; done
[ -z "$LEAKED" ] && pass "no entry of this section is anywhere in the log" || fail "text in log" "found:$LEAKED"
if grep -qiE '\bblock' <<<"$WITHDRAWN_LOG"; then fail "block in log" "the log says block"; else pass "…and the log does not say which ending was a block"; fi

echo; echo "the archive — days, one day, a bookmark and a conditional read (FR-090, FR-093, ADR-0036)"
flush_buckets
# What this section is for: the four routes of slice C5b answering from the jar,
# with the real converter, the real security headers and the real poller. Every
# day here is TODAY. A Bond-day is a calendar day long and the close job's short
# schedule cannot end one, so nothing in this run pages past a first page, reads
# a closed or a solo day, or follows a nextCursor that is not null: past days,
# paging and the byte budget are the integration tests' to show, not this
# script's. If Lagos midnight passes while this section runs, its date checks
# fail; that is the run's timing and not a finding.

# at <dotted.path> — a value out of LAST_BODY as JSON with its keys sorted
# ("true", "null", "\"REVEALED\"", a whole object), or "absent" for a key or an
# index that is not there. An empty path is the whole body. raw is the same,
# with a string printed bare.
at() {
  python3 -c '
import json, sys
try:
    node = json.loads(sys.stdin.read())
    for part in filter(None, sys.argv[1].split(".")):
        node = node[int(part)] if isinstance(node, list) else node[part]
    print(node if sys.argv[2] == "raw" and isinstance(node, str) else json.dumps(node, sort_keys=True, ensure_ascii=False))
except (KeyError, IndexError, TypeError, ValueError):
    print("absent")' "$1" "${2:-json}" <<<"$LAST_BODY"
}
raw() { at "$1" raw; }
# is <label> <got> <expected>
is() { if [ "$2" = "$3" ]; then pass "$1"; else fail "$1" "expected '${3:0:200}', got '${2:0:200}'"; fi; }
# repeats <value> — whether LAST_BODY carries it as a word of its own anywhere
# but `instance`, which is the path the caller typed. "50" does not repeat "0".
repeats() {
  python3 -c '
import re, sys
body = re.sub(r"\"instance\":\"[^\"]*\"", "", sys.stdin.read())
sys.exit(0 if re.search(r"(?<![0-9A-Za-z])" + re.escape(sys.argv[1]) + r"(?![0-9A-Za-z])", body) else 1)' "$1" <<<"$LAST_BODY"
}
no_instance() { printf '%s' "$LAST_BODY" | sed 's/"instance":"[^"]*"/"instance":"-"/'; }
# no_times <label> — LAST_BODY carries no key that says when an entry was erased
# or last changed. A withdrawal stamps every entry it erases with one instant,
# so either key on a tombstone would tell a withdrawal from a deletion by hand.
no_times() { if [[ "$LAST_BODY" == *'"deletedAt"'* || "$LAST_BODY" == *'"updatedAt"'* ]]; then fail "$1" "${LAST_BODY:0:300}"; else pass "$1"; fi; }
tag() { etag_of || true; }

# The two members go by different names, so a day that named the caller instead of the partner would fail.
verified_account "writer" "203.0.113.90" en "Writer"; ARC_W="$ACCOUNT_ACCESS"
verified_account "marker" "203.0.113.91" en "Marker"; ARC_M="$ACCOUNT_ACCESS"
verified_account "outsider" "203.0.113.92"; ARC_O="$ACCOUNT_ACCESS"
ARC_W_TEXT="thank you for the tea left by the door"
ARC_M_TEXT="thank you for reading to me last night"
ARC_SOLO_TEXT="thank you for the walk before work"
revealed_bond "an archive of one day" "$ARC_W" "$ARC_M" "$ARC_W_TEXT" "$ARC_M_TEXT"
ARC_BOND="$RB_BOND"; ARC_W_ENTRY="$RB_A_ENTRY"; ARC_M_ENTRY="$RB_B_ENTRY"
expect "today, for the date the archive should carry" 200 '"bondDay"' -- "$API/bonds/$ARC_BOND/today" -H "Authorization: Bearer $ARC_W"
ARC_DATE="$(raw bondDay.date)"
# The partner's name is the archive's (ADR-0036 question 5), not today's: the ruling names the archive.
is "…today carries no partner's name" "$(at partnerDisplayName)" "absent"

# The feed: one day, newest first, each member's own entry as myEntry.
expect "the writer's GET /days is 200" 200 '"items":[' -- "$API/bonds/$ARC_BOND/days" -H "Authorization: Bearer $ARC_W"
is "…it lists today, $ARC_DATE, and no other day" "$(raw items.0.date)|$(at items.1)" "$ARC_DATE|absent"
is "…REVEALED" "$(raw items.0.status)" "REVEALED"
is "…the writer's own entry is myEntry, with its words" "$(raw items.0.myEntry.id)|$(raw items.0.myEntry.text)" "$ARC_W_ENTRY|$ARC_W_TEXT"
is "…the partner's is partnerEntry, with its words" "$(raw items.0.partnerEntry.id)|$(raw items.0.partnerEntry.text)" "$ARC_M_ENTRY|$ARC_M_TEXT"
is "…neither is marked, and there is no further page" "$(at items.0.myEntry.favourited)|$(at items.0.partnerEntry.favourited)|$(at nextCursor)" "false|false|null"
no_times "…and no entry says when it was erased or last changed"
# What the other member goes by, which GET /bonds/{bondId} already tells both (ADR-0036 question 5).
is "…and the day names the partner, not the writer" "$(raw items.0.partnerDisplayName)" "Marker"
ARC_W_ITEM="$(at items.0)"
expect "the marker's GET /days is 200" 200 '"items":[' -- "$API/bonds/$ARC_BOND/days" -H "Authorization: Bearer $ARC_M"
is "…the same day, REVEALED, and no other" "$(raw items.0.date)|$(raw items.0.status)|$(at items.1)|$(at nextCursor)" "$ARC_DATE|REVEALED|absent|null"
is "…with the two entries the other way round" "$(raw items.0.myEntry.id)|$(raw items.0.myEntry.text)|$(raw items.0.partnerEntry.id)|$(raw items.0.partnerEntry.text)" "$ARC_M_ENTRY|$ARC_M_TEXT|$ARC_W_ENTRY|$ARC_W_TEXT"
is "…and names the writer, not the marker" "$(raw items.0.partnerDisplayName)" "Writer"
ARC_M_ITEM="$(at items.0)"

# One day is the feed's item, not a second rendering of it.
expect "the writer's GET /days/{today} is 200" 200 "\"date\":\"$ARC_DATE\"" -- "$API/bonds/$ARC_BOND/days/$ARC_DATE" -H "Authorization: Bearer $ARC_W"
is "…and is the feed's first item, value for value" "$(at "")" "$ARC_W_ITEM"
expect "the marker's GET /days/{today} is 200" 200 "\"date\":\"$ARC_DATE\"" -- "$API/bonds/$ARC_BOND/days/$ARC_DATE" -H "Authorization: Bearer $ARC_M"
is "…and is the marker's first item, value for value" "$(at "")" "$ARC_M_ITEM"

# A parameter the route cannot read is a 422 that names the parameter and does
# not hand the value back: what was typed into a query string is the caller's,
# and an error body is copied into bug reports.
for bad in limit=0 limit=51 limit=007 cursor=junk until=2026-02-30 favourites=maybe; do
  expect "GET /days?$bad is 422 VALIDATION_FAILED" 422 '"code":"VALIDATION_FAILED"' -- "$API/bonds/$ARC_BOND/days?$bad" -H "Authorization: Bearer $ARC_W"
  is "…on ${bad%%=*}" "$(raw errors.0.field)" "${bad%%=*}"
  if repeats "${bad#*=}"; then fail "422 repeats the value" "'${bad#*=}' is in the body: ${LAST_BODY:0:300}"; else pass "…and the body does not repeat '${bad#*=}'"; fi
done

# One 404 for a date that is not in the caller's archive, whatever the reason.
expect "GET /days/1999-01-01 is 404 DAY_NOT_FOUND" 404 '"code":"DAY_NOT_FOUND"' -- "$API/bonds/$ARC_BOND/days/1999-01-01" -H "Authorization: Bearer $ARC_W"
ARC_NO_DAY="$(no_instance)"
expect "GET /days/not-a-date is 404 DAY_NOT_FOUND" 404 '"code":"DAY_NOT_FOUND"' -- "$API/bonds/$ARC_BOND/days/not-a-date" -H "Authorization: Bearer $ARC_W"
is "…the same body, apart from the path that was typed" "$(no_instance)" "$ARC_NO_DAY"

# Somebody who is not in the bond gets the bond's own 404, before any parameter is read.
expect "a stranger's GET /days is the bond's 404" 404 '"code":"NOT_FOUND"' -- "$API/bonds/$ARC_BOND/days" -H "Authorization: Bearer $ARC_O"
ARC_NO_BOND="$(no_instance)"
expect "a stranger's GET /days/{today} is the same 404" 404 '"code":"NOT_FOUND"' -- "$API/bonds/$ARC_BOND/days/$ARC_DATE" -H "Authorization: Bearer $ARC_O"
is "…the same body, apart from the path" "$(no_instance)" "$ARC_NO_BOND"
expect "a stranger's unreadable cursor is that 404 too, not a 422" 404 '"code":"NOT_FOUND"' -- "$API/bonds/$ARC_BOND/days?cursor=junk" -H "Authorization: Bearer $ARC_O"
[[ "$LAST_BODY" != *"$ARC_W_TEXT"* && "$LAST_BODY" != *"$ARC_M_TEXT"* ]] && pass "…and nothing of the day is in it" || fail "stranger's 404" "${LAST_BODY:0:300}"

# A second bond, in which only one of the two has written today. The day is in
# the writer's archive and not in the other member's, and the other member
# cannot tell that from a date on which nothing happened at all.
expect "a second bond is 201" 201 '"status":"PENDING_MEMBER"' -- -X POST "$API/bonds" -H "Authorization: Bearer $ARC_W" -d "$(bond_body "Us")"
ARC_BOND2="$(printf '%s' "$LAST_BODY" | jget id)"
ARC_CODE2="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['code'])" <<<"$LAST_BODY")"
expect "…joined" 200 '"status":"ACTIVE"' -- -X POST "$API/invites/$ARC_CODE2/accept" -H "Authorization: Bearer $ARC_O"
expect "only one of them writes today" 201 '"status":"SUBMITTED"' -- -X POST "$API/bonds/$ARC_BOND2/entries" -H "Authorization: Bearer $ARC_W" -H "Idempotency-Key: $(new_key)" -d "{\"text\":\"$ARC_SOLO_TEXT\"}"
ARC_SOLO_ENTRY="$(printf '%s' "$LAST_BODY" | jget id)"
expect "the one who wrote has today in their archive" 200 '"items":[' -- "$API/bonds/$ARC_BOND2/days" -H "Authorization: Bearer $ARC_W"
is "…with their own entry and no partner entry" "$(raw items.0.date)|$(raw items.0.myEntry.id)|$(raw items.0.myEntry.text)|$(at items.0.partnerEntry)|$(at items.1)" "$ARC_DATE|$ARC_SOLO_ENTRY|$ARC_SOLO_TEXT|null|absent"
expect "…and reads it as one day" 200 "\"text\":\"$ARC_SOLO_TEXT\"" -- "$API/bonds/$ARC_BOND2/days/$ARC_DATE" -H "Authorization: Bearer $ARC_W"
expect "the one who has not written has an empty archive" 200 '"items":[]' -- "$API/bonds/$ARC_BOND2/days" -H "Authorization: Bearer $ARC_O"
is "…exactly empty: no day, no cursor" "$(at "")" '{"items": [], "nextCursor": null}'
expect "…and today is 404 DAY_NOT_FOUND for them" 404 '"code":"DAY_NOT_FOUND"' -- "$API/bonds/$ARC_BOND2/days/$ARC_DATE" -H "Authorization: Bearer $ARC_O"
ARC_HIDDEN_DAY="$(no_instance)"
expect "…as a date nobody wrote on is" 404 '"code":"DAY_NOT_FOUND"' -- "$API/bonds/$ARC_BOND2/days/1999-01-01" -H "Authorization: Bearer $ARC_O"
is "…the same body, so the route does not say whether the partner wrote" "$(no_instance)" "$ARC_HIDDEN_DAY"

# A third bond, for what a partner's entry the caller was never shown is, as
# the jar's own serializer writes it: an author and a status, and no other key
# (BR-8), while it is live; and nothing at all once it is erased, the null of
# an entry never written (the owner's ruling of 2026-10-21, ADR-0032 q2). The
# archive lists such a day only for a member with an entry of their own on it,
# so one member writes and deletes, which leaves their tombstone, and then the
# other writes alone.
flush_buckets
ARC_GONE_TEXT="thank you for waiting up for me"
ARC_LOCKED_TEXT="thank you for the lift to the station"
expect "a third bond is 201" 201 '"status":"PENDING_MEMBER"' -- -X POST "$API/bonds" -H "Authorization: Bearer $ARC_M" -d "$(bond_body "Us")"
ARC_BOND3="$(printf '%s' "$LAST_BODY" | jget id)"
ARC_CODE3="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['code'])" <<<"$LAST_BODY")"
expect "…joined" 200 '"status":"ACTIVE"' -- -X POST "$API/invites/$ARC_CODE3/accept" -H "Authorization: Bearer $ARC_O"
expect "one member writes today" 201 '"status":"SUBMITTED"' -- -X POST "$API/bonds/$ARC_BOND3/entries" -H "Authorization: Bearer $ARC_O" -H "Idempotency-Key: $(new_key)" -d "{\"text\":\"$ARC_GONE_TEXT\"}"
ARC_GONE_ENTRY="$(printf '%s' "$LAST_BODY" | jget id)"
expect "…and deletes it" 204 "" -- -X DELETE "$API/entries/$ARC_GONE_ENTRY" -H "Authorization: Bearer $ARC_O"
expect "then the other writes, alone" 201 '"status":"SUBMITTED"' -- -X POST "$API/bonds/$ARC_BOND3/entries" -H "Authorization: Bearer $ARC_M" -H "Idempotency-Key: $(new_key)" -d "{\"text\":\"$ARC_LOCKED_TEXT\"}"
ARC_LOCKED_ENTRY="$(printf '%s' "$LAST_BODY" | jget id)"; ARC_LOCKED_AUTHOR="$(printf '%s' "$LAST_BODY" | jget authorMemberId)"
expect "the one who deleted still has the day, through their own tombstone" 200 '"status":"DELETED"' -- "$API/bonds/$ARC_BOND3/days" -H "Authorization: Bearer $ARC_O"
is "…their own entry the wide tombstone" "$(raw items.0.myEntry.id)|$(at items.0.myEntry.text)|$(raw items.0.myEntry.status)" "$ARC_GONE_ENTRY|null|DELETED"
is "…and the partner's entry exactly an author and LOCKED, no other key" "$(at items.0.partnerEntry)" "{\"authorMemberId\": \"$ARC_LOCKED_AUTHOR\", \"status\": \"LOCKED\"}"
[[ "$LAST_BODY" != *"$ARC_LOCKED_TEXT"* && "$LAST_BODY" != *"$ARC_LOCKED_ENTRY"* ]] && pass "…neither its words nor its id anywhere in the feed" || fail "locked entry read" "${LAST_BODY:0:300}"
no_times "…and no key says when anything was erased or changed, on the tombstone or the locked entry"
ARC_LOCKED_ITEM="$(at items.0)"
expect "…and the day alone" 200 '"status":"LOCKED"' -- "$API/bonds/$ARC_BOND3/days/$ARC_DATE" -H "Authorization: Bearer $ARC_O"
is "…is that same item" "$(at "")" "$ARC_LOCKED_ITEM"
no_times "…with no such key either"
expect "the other deletes theirs too, before anybody read it" 204 "" -- -X DELETE "$API/entries/$ARC_LOCKED_ENTRY" -H "Authorization: Bearer $ARC_M"
expect "the first member's archive still lists the day, through their own tombstone" 200 '"status":"DELETED"' -- "$API/bonds/$ARC_BOND3/days" -H "Authorization: Bearer $ARC_O"
is "…and the partner's entry is not there: null, as for a partner who never wrote" "$(raw items.0.myEntry.id)|$(at items.0.partnerEntry)|$(at items.1)" "$ARC_GONE_ENTRY|null|absent"
[[ "$LAST_BODY" != *"$ARC_LOCKED_ENTRY"* && "$LAST_BODY" != *"$ARC_LOCKED_AUTHOR"* && "$LAST_BODY" != *REMOVED* ]] && pass "…not its id, not its author, and no REMOVED" || fail "unseen erasure shown" "${LAST_BODY:0:300}"
no_times "…and no key says when it was removed"
ARC_REMOVED_ITEM="$(at items.0)"
expect "…and the day alone" 200 '"partnerEntry":null' -- "$API/bonds/$ARC_BOND3/days/$ARC_DATE" -H "Authorization: Bearer $ARC_O"
is "…is that same item" "$(at "")" "$ARC_REMOVED_ITEM"
expect "…and today shows the same nothing" 200 '"partnerEntry":null' -- "$API/bonds/$ARC_BOND3/today" -H "Authorization: Bearer $ARC_O"
is "…beside the same tombstone of their own, on a day that reads OPEN" "$(raw myEntry.id)|$(raw myEntry.status)|$(raw bondDay.status)" "$ARC_GONE_ENTRY|DELETED|OPEN"
[[ "$LAST_BODY" != *"$ARC_LOCKED_ENTRY"* && "$LAST_BODY" != *"$ARC_LOCKED_AUTHOR"* && "$LAST_BODY" != *REMOVED* ]] && pass "…not its id, not its author, and no REMOVED" || fail "unseen erasure shown on today" "${LAST_BODY:0:300}"

# A bookmark is the caller's own. The marker marks the writer's entry; the
# writer, reading the same entry as their own, is told nothing about it.
flush_buckets
expect "PUT /entries/{id}/favourite on the partner's revealed entry is 204" 204 "" -- -X PUT "$API/entries/$ARC_W_ENTRY/favourite" -H "Authorization: Bearer $ARC_M"
[ -z "$LAST_BODY" ] && pass "…with no body" || fail "favourite body" "${LAST_BODY:0:200}"
expect "…and 204 again" 204 "" -- -X PUT "$API/entries/$ARC_W_ENTRY/favourite" -H "Authorization: Bearer $ARC_M"
ARC_MARKS="$(sql "SELECT count(*) FROM entry_favourites WHERE entry_id='$ARC_W_ENTRY'")"
case "$ARC_MARKS" in psql-unavailable) skip "the favourite row count";; 1) pass "…leaving one row, not two";; *) fail "favourite rows" "expected 1, got '$ARC_MARKS'";; esac
# The row is two ids and no time: when a person marked an entry is, to an operator, when they were reading it (V26).
ARC_FAV_COLUMNS="$(sql "SELECT string_agg(column_name, ',' ORDER BY column_name) FROM information_schema.columns WHERE table_schema='public' AND table_name='entry_favourites'")"
case "$ARC_FAV_COLUMNS" in psql-unavailable) skip "the favourite row's columns";; entry_id,member_id) pass "…and that row holds no time (V26)";; *) fail "favourite columns" "expected 'entry_id,member_id', got '$ARC_FAV_COLUMNS'";; esac
expect "the marker's archive" 200 '"items":[' -- "$API/bonds/$ARC_BOND/days" -H "Authorization: Bearer $ARC_M"
is "…says favourited on that entry, and not on their own" "$(raw items.0.partnerEntry.id)|$(at items.0.partnerEntry.favourited)|$(at items.0.myEntry.favourited)" "$ARC_W_ENTRY|true|false"
expect "the writer's archive" 200 '"items":[' -- "$API/bonds/$ARC_BOND/days" -H "Authorization: Bearer $ARC_W"
is "…says false on the same entry: a partner's mark is in no response" "$(raw items.0.myEntry.id)|$(at items.0.myEntry.favourited)|$(at items.0.partnerEntry.favourited)" "$ARC_W_ENTRY|false|false"
is "…and the writer's day is what it was before anybody marked anything" "$(at items.0)" "$ARC_W_ITEM"
expect "GET /days?favourites=true lists the day for the one who marked it" 200 '"favourited":true' -- "$API/bonds/$ARC_BOND/days?favourites=true" -H "Authorization: Bearer $ARC_M"
is "…that day and no other" "$(raw items.0.date)|$(at items.1)" "$ARC_DATE|absent"
expect "…and nothing for the partner" 200 '"items":[]' -- "$API/bonds/$ARC_BOND/days?favourites=true" -H "Authorization: Bearer $ARC_W"
expect "favourites=false is the whole archive" 200 "\"date\":\"$ARC_DATE\"" -- "$API/bonds/$ARC_BOND/days?favourites=false" -H "Authorization: Bearer $ARC_W"
expect "today carries the mark too" 200 '"favourited":true' -- "$API/bonds/$ARC_BOND/today" -H "Authorization: Bearer $ARC_M"
expect "DELETE /entries/{id}/favourite is 204" 204 "" -- -X DELETE "$API/entries/$ARC_W_ENTRY/favourite" -H "Authorization: Bearer $ARC_M"
expect "…and 204 again" 204 "" -- -X DELETE "$API/entries/$ARC_W_ENTRY/favourite" -H "Authorization: Bearer $ARC_M"
expect "the marker's archive, afterwards" 200 '"items":[' -- "$API/bonds/$ARC_BOND/days" -H "Authorization: Bearer $ARC_M"
is "…is the day as it was before the mark" "$(at items.0)" "$ARC_M_ITEM"
expect "…and favourites=true is empty again" 200 '"items":[]' -- "$API/bonds/$ARC_BOND/days?favourites=true" -H "Authorization: Bearer $ARC_M"

# What may not be marked. One's own entry that is still waiting is a 409, which
# tells its author nothing they do not know; everything the caller was never
# shown is one 404, the same as an id that was never issued.
expect "marking one's own unrevealed entry is 409 ENTRY_NOT_REVEALED" 409 '"code":"ENTRY_NOT_REVEALED"' -- -X PUT "$API/entries/$ARC_SOLO_ENTRY/favourite" -H "Authorization: Bearer $ARC_W"
expect "PUT /entries/<an id nobody was issued>/favourite is 404" 404 '"code":"NOT_FOUND"' -- -X PUT "$API/entries/$(new_key)/favourite" -H "Authorization: Bearer $ARC_W"
ARC_NO_ENTRY="$(no_instance)"
expect "the partner marking that unrevealed entry, which they may not read, is 404" 404 '"code":"NOT_FOUND"' -- -X PUT "$API/entries/$ARC_SOLO_ENTRY/favourite" -H "Authorization: Bearer $ARC_O"
is "…the same body as for no entry at all" "$(no_instance)" "$ARC_NO_ENTRY"
expect "a stranger marking a revealed entry of a bond they are not in is 404" 404 '"code":"NOT_FOUND"' -- -X PUT "$API/entries/$ARC_W_ENTRY/favourite" -H "Authorization: Bearer $ARC_O"
is "…the same body again" "$(no_instance)" "$ARC_NO_ENTRY"
expect "DELETE /entries/<an id nobody was issued>/favourite is 404" 404 '"code":"NOT_FOUND"' -- -X DELETE "$API/entries/$(new_key)/favourite" -H "Authorization: Bearer $ARC_W"
is "…the same body as the PUT's" "$(no_instance)" "$ARC_NO_ENTRY"
expect "a stranger unmarking a revealed entry of a bond they are not in is 404" 404 '"code":"NOT_FOUND"' -- -X DELETE "$API/entries/$ARC_W_ENTRY/favourite" -H "Authorization: Bearer $ARC_O"
is "…the one 404: the same body as for no entry at all" "$(no_instance)" "$ARC_NO_ENTRY"
ARC_MARKS="$(sql "SELECT count(*) FROM entry_favourites WHERE entry_id IN ('$ARC_W_ENTRY','$ARC_SOLO_ENTRY')")"
case "$ARC_MARKS" in psql-unavailable) skip "the refused-mark row count";; 0) pass "…and none of those refusals, nor the unmarking, left a row";; *) fail "favourite rows" "expected 0, got '$ARC_MARKS'";; esac

# The conditional read. The tag is of the bytes this caller is sent, so it moves
# when their own mark does and stays when only the partner's does.
flush_buckets
expect "the marker's GET /days, for its headers" 200 '"items":[' -- "$API/bonds/$ARC_BOND/days" -H "Authorization: Bearer $ARC_M"
ARC_FEED_TAG="$(tag)"
[[ "$ARC_FEED_TAG" =~ ^\"[^\"]+\"$ ]] && pass "…a quoted, strong ETag" || fail "feed ETag" "got '$ARC_FEED_TAG'"
header_is "…and it is the caller's alone to keep, revalidated every time" Cache-Control "private, no-cache"
expect "the same request with If-None-Match is 304" 304 "" -- "$API/bonds/$ARC_BOND/days" -H "Authorization: Bearer $ARC_M" -H "If-None-Match: $ARC_FEED_TAG"
[ -z "$LAST_BODY" ] && pass "…with no body" || fail "304 body" "${LAST_BODY:0:200}"
header_is "…the same ETag" ETag "$ARC_FEED_TAG"
header_is "…and the same Cache-Control" Cache-Control "private, no-cache"
expect "the marker's GET /days/{today}, for its headers" 200 "\"date\":\"$ARC_DATE\"" -- "$API/bonds/$ARC_BOND/days/$ARC_DATE" -H "Authorization: Bearer $ARC_M"
ARC_DAY_TAG="$(tag)"
[[ "$ARC_DAY_TAG" =~ ^\"[^\"]+\"$ ]] && pass "…a quoted, strong ETag" || fail "day ETag" "got '$ARC_DAY_TAG'"
header_is "…private, no-cache" Cache-Control "private, no-cache"
expect "the same request with If-None-Match is 304" 304 "" -- "$API/bonds/$ARC_BOND/days/$ARC_DATE" -H "Authorization: Bearer $ARC_M" -H "If-None-Match: $ARC_DAY_TAG"
[ -z "$LAST_BODY" ] && pass "…with no body" || fail "304 body" "${LAST_BODY:0:200}"
header_is "…the same ETag" ETag "$ARC_DAY_TAG"
expect "the writer's GET /days/{today}, the same day read by the other member" 200 "\"date\":\"$ARC_DATE\"" -- "$API/bonds/$ARC_BOND/days/$ARC_DATE" -H "Authorization: Bearer $ARC_W"
ARC_W_DAY_TAG="$(tag)"
[ -n "$ARC_W_DAY_TAG" ] && [ "$ARC_W_DAY_TAG" != "$ARC_DAY_TAG" ] && pass "…has a different ETag: one member's tag is not the other's" || fail "tags of two members" "writer '$ARC_W_DAY_TAG', marker '$ARC_DAY_TAG'"
expect "…and the marker's tag does not make the writer's read a 304" 200 "\"date\":\"$ARC_DATE\"" -- "$API/bonds/$ARC_BOND/days/$ARC_DATE" -H "Authorization: Bearer $ARC_W" -H "If-None-Match: $ARC_DAY_TAG"

expect "the marker marks the writer's entry" 204 "" -- -X PUT "$API/entries/$ARC_W_ENTRY/favourite" -H "Authorization: Bearer $ARC_M"
expect "the old feed tag is no longer current: 200, not 304" 200 '"favourited":true' -- "$API/bonds/$ARC_BOND/days" -H "Authorization: Bearer $ARC_M" -H "If-None-Match: $ARC_FEED_TAG"
ARC_FEED_TAG_MARKED="$(tag)"
[ -n "$ARC_FEED_TAG_MARKED" ] && [ "$ARC_FEED_TAG_MARKED" != "$ARC_FEED_TAG" ] && pass "…and the feed's ETag is a new one" || fail "feed tag after a mark" "before '$ARC_FEED_TAG', after '$ARC_FEED_TAG_MARKED'"
expect "the old day tag is no longer current either" 200 '"favourited":true' -- "$API/bonds/$ARC_BOND/days/$ARC_DATE" -H "Authorization: Bearer $ARC_M" -H "If-None-Match: $ARC_DAY_TAG"
ARC_DAY_TAG_MARKED="$(tag)"
[ -n "$ARC_DAY_TAG_MARKED" ] && [ "$ARC_DAY_TAG_MARKED" != "$ARC_DAY_TAG" ] && pass "…and the day's ETag is a new one" || fail "day tag after a mark" "before '$ARC_DAY_TAG', after '$ARC_DAY_TAG_MARKED'"

# The partner's mark is not in the caller's bytes, so it is not in the caller's tag.
expect "the writer marks the marker's entry" 204 "" -- -X PUT "$API/entries/$ARC_M_ENTRY/favourite" -H "Authorization: Bearer $ARC_W"
expect "the marker's feed is still 304 on the tag from before that" 304 "" -- "$API/bonds/$ARC_BOND/days" -H "Authorization: Bearer $ARC_M" -H "If-None-Match: $ARC_FEED_TAG_MARKED"
header_is "…the same ETag" ETag "$ARC_FEED_TAG_MARKED"
expect "…and so is the marker's day" 304 "" -- "$API/bonds/$ARC_BOND/days/$ARC_DATE" -H "Authorization: Bearer $ARC_M" -H "If-None-Match: $ARC_DAY_TAG_MARKED"
expect "…while the writer's own tag has moved" 200 '"favourited":true' -- "$API/bonds/$ARC_BOND/days/$ARC_DATE" -H "Authorization: Bearer $ARC_W" -H "If-None-Match: $ARC_W_DAY_TAG"
expect "the writer unmarks it" 204 "" -- -X DELETE "$API/entries/$ARC_M_ENTRY/favourite" -H "Authorization: Bearer $ARC_W"
expect "the marker's feed is 304 still" 304 "" -- "$API/bonds/$ARC_BOND/days" -H "Authorization: Bearer $ARC_M" -H "If-None-Match: $ARC_FEED_TAG_MARKED"
expect "…and the marker's day" 304 "" -- "$API/bonds/$ARC_BOND/days/$ARC_DATE" -H "Authorization: Bearer $ARC_M" -H "If-None-Match: $ARC_DAY_TAG_MARKED"
expect "…and the writer's day is 304 again on the tag it began with" 304 "" -- "$API/bonds/$ARC_BOND/days/$ARC_DATE" -H "Authorization: Bearer $ARC_W" -H "If-None-Match: $ARC_W_DAY_TAG"

# The archive is JSON whatever JSON the caller asked for, and the tag is of
# those bytes; a caller who will take no JSON gets no day and no tag.
expect "Accept: application/problem+json on GET /days is still 200" 200 '"items":[' -- "$API/bonds/$ARC_BOND/days" -H "Authorization: Bearer $ARC_M" -H 'Accept: application/problem+json'
header_is "…sent as what it is" Content-Type "application/json"
header_is "…under the tag a plain read has" ETag "$ARC_FEED_TAG_MARKED"
expect "Accept: text/plain on GET /days is 406" 406 '"code":"UNSUPPORTED_MEDIA_TYPE"' -- "$API/bonds/$ARC_BOND/days" -H "Authorization: Bearer $ARC_M" -H 'Accept: text/plain'
[ -z "$(tag)" ] && pass "…with no ETag" || fail "406 ETag" "a 406 carried ETag $(tag)"
[[ "$LAST_BODY" != *"$ARC_W_TEXT"* && "$LAST_BODY" != *"$ARC_M_TEXT"* ]] && pass "…and nothing of the day" || fail "406 body" "${LAST_BODY:0:300}"
expect "Accept: text/plain on GET /days/{today} is 406" 406 '"code":"UNSUPPORTED_MEDIA_TYPE"' -- "$API/bonds/$ARC_BOND/days/$ARC_DATE" -H "Authorization: Bearer $ARC_M" -H 'Accept: text/plain'
[ -z "$(tag)" ] && pass "…with no ETag" || fail "406 ETag" "a 406 carried ETag $(tag)"
ARC_NOT_ACCEPTABLE="$(no_instance)"
# The one place a stranger is not given the bond's 404: the 406 is decided from
# the request's own header, before anybody's membership is looked at. So it
# must be the member's 406 exactly, or it would say who is in the bond.
expect "a stranger's Accept: text/plain on GET /days/{today} is 406 too" 406 '"code":"UNSUPPORTED_MEDIA_TYPE"' -- "$API/bonds/$ARC_BOND/days/$ARC_DATE" -H "Authorization: Bearer $ARC_O" -H 'Accept: text/plain'
is "…the member's 406, body for body" "$(no_instance)" "$ARC_NOT_ACCEPTABLE"
[ -z "$(tag)" ] && pass "…with no ETag" || fail "406 ETag" "a stranger's 406 carried ETag $(tag)"
header_is "…and not to be stored" Cache-Control "no-cache, no-store, max-age=0, must-revalidate"
expect "…and for a bond that does not exist" 406 '"code":"UNSUPPORTED_MEDIA_TYPE"' -- "$API/bonds/$(new_key)/days" -H "Authorization: Bearer $ARC_O" -H 'Accept: text/plain'

# The archive after a withdrawal. The marker's bookmark is on the writer's
# entry (set above); the writer now leaves and takes their entries back. The
# poller erases the entry, and the erasure takes the bookmark with it: a mark
# on words that are gone would be the one thing left that says they were liked.
flush_buckets
expect "before the ending, favourites=true lists the day for the marker" 200 '"favourited":true' -- "$API/bonds/$ARC_BOND/days?favourites=true" -H "Authorization: Bearer $ARC_M"
expect "the writer leaves, taking their entries back: 204" 204 "" -- -X POST "$API/bonds/$ARC_BOND/leave" -H "Authorization: Bearer $ARC_W" -d '{"withdrawEntries": true}'
# At once, whether or not the poller has run yet: the read is gated on the record the ending wrote.
expect "the marker's very next read of the archive carries a tombstone, unmarked" 200 '"status":"DELETED"' -- "$API/bonds/$ARC_BOND/days" -H "Authorization: Bearer $ARC_M"
is "…text null, DELETED, not favourited" "$(raw items.0.partnerEntry.id)|$(at items.0.partnerEntry.text)|$(raw items.0.partnerEntry.status)|$(at items.0.partnerEntry.favourited)" "$ARC_W_ENTRY|null|DELETED|false"
ARC_FEED_TAG_WITHDRAWN="$(tag)"
no_times "…and nothing in it says when"
# Whether the poller had already erased the entry when that tag was taken: the tag is "before the erasure" only if not.
ARC_STILL_WHOLE="$(sql "SELECT text IS NOT NULL FROM entries WHERE id='$ARC_W_ENTRY'")"
expect "…and favourites=true has already stopped listing the day" 200 '"items":[]' -- "$API/bonds/$ARC_BOND/days?favourites=true" -H "Authorization: Bearer $ARC_M"
# A dropped day leaves no trace: no cursor pointing past it, which a deletion by hand could never have produced.
is "…exactly empty: no day, no cursor" "$(at "")" '{"items": [], "nextCursor": null}'
await_erased "$ARC_W_ENTRY"
erased_check "the poller erases the withdrawn entry"
expect "the marker's archive, once it is erased" 200 '"status":"DELETED"' -- "$API/bonds/$ARC_BOND/days" -H "Authorization: Bearer $ARC_M"
is "…the writer's entry is a tombstone: text null, DELETED, not favourited" "$(raw items.0.partnerEntry.id)|$(at items.0.partnerEntry.text)|$(raw items.0.partnerEntry.status)|$(at items.0.partnerEntry.favourited)" "$ARC_W_ENTRY|null|DELETED|false"
is "…and the marker's own entry is whole" "$(raw items.0.myEntry.id)|$(raw items.0.myEntry.text)|$(raw items.0.myEntry.status)" "$ARC_M_ENTRY|$ARC_M_TEXT|REVEALED"
[[ "$LAST_BODY" != *"$ARC_W_TEXT"* ]] && pass "…the withdrawn words are nowhere in the feed" || fail "withdrawn text read" "${LAST_BODY:0:300}"
ARC_M_ITEM_AFTER="$(at items.0)"
[ -n "$(tag)" ] && [ "$(tag)" != "$ARC_FEED_TAG_MARKED" ] && pass "…and the feed's ETag has moved with it" || fail "feed tag after a withdrawal" "still '$ARC_FEED_TAG_MARKED'"
# It moved once, at the ending. The erasure must not move it again on a day
# that had revealed: a second change would tell the reader when the consumer
# ran, and that a consumer ran at all, which a deletion by hand has none of.
case "$ARC_STILL_WHOLE" in
  t) is "…and it is the tag the feed had before the erasure: the erasure itself moved nothing" "$(tag)" "$ARC_FEED_TAG_WITHDRAWN";;
  psql-unavailable) skip "the tag across the erasure (psql unavailable)";;
  *) skip "the tag across the erasure: the poller had erased the entry before the first read (text whole: '$ARC_STILL_WHOLE')";;
esac
no_times "…and nothing in the erased feed says when"
expect "the marker's day view" 200 '"status":"DELETED"' -- "$API/bonds/$ARC_BOND/days/$ARC_DATE" -H "Authorization: Bearer $ARC_M"
is "…is that same item" "$(at "")" "$ARC_M_ITEM_AFTER"
expect "…and the tag from before the withdrawal does not make it a 304" 200 '"status":"DELETED"' -- "$API/bonds/$ARC_BOND/days/$ARC_DATE" -H "Authorization: Bearer $ARC_M" -H "If-None-Match: $ARC_DAY_TAG_MARKED"
expect "favourites=true no longer lists the day for the marker" 200 '"items":[]' -- "$API/bonds/$ARC_BOND/days?favourites=true" -H "Authorization: Bearer $ARC_M"
ARC_MARKS="$(sql "SELECT count(*) FROM entry_favourites WHERE entry_id='$ARC_W_ENTRY'")"
case "$ARC_MARKS" in psql-unavailable) skip "the erased-mark row count";; 0) pass "…and the mark's row is gone with the words";; *) fail "favourite rows" "expected 0, got '$ARC_MARKS'";; esac
expect "the one who withdrew still reads the archive of the ended bond" 200 '"status":"DELETED"' -- "$API/bonds/$ARC_BOND/days" -H "Authorization: Bearer $ARC_W"
is "…their own entry a tombstone: text null, DELETED, not favourited" "$(raw items.0.myEntry.id)|$(at items.0.myEntry.text)|$(raw items.0.myEntry.status)|$(at items.0.myEntry.favourited)" "$ARC_W_ENTRY|null|DELETED|false"
is "…beside the partner's entry, whole" "$(raw items.0.partnerEntry.id)|$(raw items.0.partnerEntry.text)|$(raw items.0.partnerEntry.status)" "$ARC_M_ENTRY|$ARC_M_TEXT|REVEALED"
ARC_W_ITEM_AFTER="$(at items.0)"
expect "…and their day view" 200 '"status":"DELETED"' -- "$API/bonds/$ARC_BOND/days/$ARC_DATE" -H "Authorization: Bearer $ARC_W"
is "…is that same item" "$(at "")" "$ARC_W_ITEM_AFTER"
expect "marking the tombstone is refused: 409 ENTRY_IMMUTABLE" 409 '"code":"ENTRY_IMMUTABLE"' -- -X PUT "$API/entries/$ARC_W_ENTRY/favourite" -H "Authorization: Bearer $ARC_M"
expect "a bookmark may still be kept on the ended bond's surviving entry" 204 "" -- -X PUT "$API/entries/$ARC_M_ENTRY/favourite" -H "Authorization: Bearer $ARC_W"
expect "…and favourites=true lists the day for the one who left" 200 '"favourited":true' -- "$API/bonds/$ARC_BOND/days?favourites=true" -H "Authorization: Bearer $ARC_W"
expect "the second bond, which nobody ended, is as it was" 200 "\"text\":\"$ARC_SOLO_TEXT\"" -- "$API/bonds/$ARC_BOND2/days" -H "Authorization: Bearer $ARC_W"

ARC_LEAKED=""
for words in "$ARC_W_TEXT" "$ARC_M_TEXT" "$ARC_SOLO_TEXT" "$ARC_GONE_TEXT" "$ARC_LOCKED_TEXT"; do grep -qF "$words" "$MOYI_LOG" && ARC_LEAKED="$ARC_LEAKED [$words]"; done
[ -z "$ARC_LEAKED" ] && pass "no entry of this section is anywhere in the log" || fail "text in log" "found:$ARC_LEAKED"

echo; echo "reactions — one acknowledgement on the partner's entry, shown to both (FR-048, ADR-0037)"
flush_buckets
# What this section is for: the two routes of slice C5c answering from the jar,
# and the one thing in this API that a member does to be seen by the other. So
# it reads every reaction from BOTH sides, on all three views that carry an
# entry, and it checks what the author's bytes do when the partner does
# anything else. Every day here is today, as in the archive section.
#
# What it cannot reach: a row that another transaction holds. `409
# REACTION_BUSY`, and the 204 a held row gets on an entry that is already a
# tombstone to the caller, need an erasure kept open on a second connection;
# a script that only sends requests cannot hold one, and a probe that claimed
# to would be reading luck. Those are ReactionLockWaitTest's and
# FavouriteLockWaitTest's alone (ADR-0037, "How this was checked"). What is
# compared here is the twin with nothing held: a withdrawn entry against one
# its author deleted by hand before leaving.

# rx_views <token> <bond> <myEntry|partnerEntry> — the reaction on that entry
# in each of the caller's three views, today, the feed and the day, as JSON
# values with a space between: '"HEART" "HEART" "HEART"', 'null null null',
# and "absent" where a view has no such key. It counts nothing by itself.
rx_views() {
  local token="$1" bond="$2" which="$3" view out=""
  for view in "today|$which.reaction" "days|items.0.$which.reaction" "days/$RX_DATE|$which.reaction"; do
    LAST_BODY="$(curl -s "$API/bonds/$bond/${view%%|*}" -H "Authorization: Bearer $token")"
    out="$out $(at "${view#*|}")"
  done
  printf '%s' "${out# }"
}
# rx_rows <entry id> — "<count>|<types>" of the reactions on one entry, straight from the table.
rx_rows() { sql "SELECT count(*) || '|' || coalesce(string_agg(type, ',' ORDER BY type), '') FROM reactions WHERE entry_id='$1'"; }
# rows_are <label> <entry id> <expected> — skipped when psql cannot be reached, failed when the query does (`sql`).
rows_are() {
  local got; got="$(rx_rows "$2")"
  case "$got" in psql-unavailable) skip "$1 (psql not reachable through docker compose)";; "$3") pass "$1";; *) fail "$1" "expected '$3', got '$got'";; esac
}
# whole_response — the status line, every header but the ones that differ on
# any two requests (the date, the request's id, the limiter's counters), and
# the body with the typed path blanked. Two responses with the same
# whole_response differ in nothing a client could read.
whole_response() {
  printf '%s\n' "$LAST_HEADERS" | grep -viE '^(date|x-request-id|x-ratelimit-[a-z]+|retry-after):' | grep -v '^$' | sort
  no_instance
}
# without_reaction <myEntry|partnerEntry> — LAST_BODY, a day, with that entry's
# reaction put back to null, printed as `at ""` prints a body: what is left is
# everything a reaction did NOT change.
without_reaction() {
  python3 -c '
import json, sys
day = json.loads(sys.stdin.read())
day[sys.argv[1]]["reaction"] = None
print(json.dumps(day, sort_keys=True, ensure_ascii=False))' "$1" <<<"$LAST_BODY"
}

verified_account "author" "203.0.113.100";    RX_A="$ACCOUNT_ACCESS"
verified_account "reactor" "203.0.113.101";   RX_B="$ACCOUNT_ACCESS"
verified_account "third" "203.0.113.102";     RX_C="$ACCOUNT_ACCESS"
verified_account "bystander" "203.0.113.103"; RX_O="$ACCOUNT_ACCESS"
RX_A_TEXT="thank you for the soup when I was ill"
RX_B_TEXT="thank you for fixing the back gate"
RX_SOLO_TEXT="thank you for the note in my coat"
RX_C_TEXT="thank you for the early train ticket"
RX_B2_TEXT="thank you for the spare key under the pot"
RX_HAND_TEXT="thank you for the candle in the window"
RX_O_TEXT="thank you for the long call on sunday"
revealed_bond "a day to react on" "$RX_A" "$RX_B" "$RX_A_TEXT" "$RX_B_TEXT"
RX_BOND="$RB_BOND"; RX_A_ENTRY="$RB_A_ENTRY"; RX_B_ENTRY="$RB_B_ENTRY"
expect "today, for the date" 200 '"bondDay"' -- "$API/bonds/$RX_BOND/today" -H "Authorization: Bearer $RX_A"
RX_DATE="$(raw bondDay.date)"

# Before anybody reacts: the field is there, on both entries, and null.
expect "the author's day before anybody has reacted" 200 "\"date\":\"$RX_DATE\"" -- "$API/bonds/$RX_BOND/days/$RX_DATE" -H "Authorization: Bearer $RX_A"
is "…carries reaction on both entries, null" "$(at myEntry.reaction)|$(at partnerEntry.reaction)" "null|null"
RX_A_DAY="$(at "")"; RX_A_DAY_TAG="$(tag)"
expect "the author's feed" 200 '"items":[' -- "$API/bonds/$RX_BOND/days" -H "Authorization: Bearer $RX_A"
RX_A_FEED_TAG="$(tag)"
expect "the author's today" 200 '"reaction":null' -- "$API/bonds/$RX_BOND/today" -H "Authorization: Bearer $RX_A"
RX_A_TODAY="$(at "")"
expect "the reactor's day" 200 "\"date\":\"$RX_DATE\"" -- "$API/bonds/$RX_BOND/days/$RX_DATE" -H "Authorization: Bearer $RX_B"
RX_B_DAY="$(at "")"

# Set. One row, and the same value for both of them wherever the entry is sent.
expect "PUT /entries/{id}/reaction on the partner's revealed entry is 204" 204 "" -- -X PUT "$API/entries/$RX_A_ENTRY/reaction" -H "Authorization: Bearer $RX_B" -d '{"type":"HEART"}'
[ -z "$LAST_BODY" ] && pass "…with no body" || fail "reaction body" "${LAST_BODY:0:200}"
expect "…and 204 again" 204 "" -- -X PUT "$API/entries/$RX_A_ENTRY/reaction" -H "Authorization: Bearer $RX_B" -d '{"type":"HEART"}'
rows_are "…leaving one row, HEART" "$RX_A_ENTRY" "1|HEART"
is "the one who reacted is shown it on today, the feed and the day" "$(rx_views "$RX_B" "$RX_BOND" partnerEntry)" '"HEART" "HEART" "HEART"'
is "…and so is the author, on their own entry" "$(rx_views "$RX_A" "$RX_BOND" myEntry)" '"HEART" "HEART" "HEART"'
is "…and nobody is shown one on the other entry" "$(rx_views "$RX_A" "$RX_BOND" partnerEntry)|$(rx_views "$RX_B" "$RX_BOND" myEntry)" 'null null null|null null null'
expect "the author's day, with the reaction on it" 200 '"reaction":"HEART"' -- "$API/bonds/$RX_BOND/days/$RX_DATE" -H "Authorization: Bearer $RX_A" -H "If-None-Match: $RX_A_DAY_TAG"
RX_A_DAY_TAG_HEART="$(tag)"
[ -n "$RX_A_DAY_TAG_HEART" ] && [ "$RX_A_DAY_TAG_HEART" != "$RX_A_DAY_TAG" ] && pass "…is a 200 under a new ETag: the author's bytes moved" || fail "author's tag after a reaction" "before '$RX_A_DAY_TAG', after '$RX_A_DAY_TAG_HEART'"
# The whole of what the author learns is the type. No key was added, so there is no time of it and no name.
is "…and differs from the day before in that one value and nothing else" "$(without_reaction myEntry)" "$RX_A_DAY"
if [[ "$LAST_BODY" == *'eactedAt'* || "$LAST_BODY" == *'"reactions"'* ]]; then fail "a time or a list of reactions" "${LAST_BODY:0:300}"; else pass "…no key says when it was left"; fi

# Nothing else the reactor does moves a byte the author is sent: reading,
# keeping a bookmark, and reactions that are refused.
flush_buckets
expect "the reactor bookmarks the author's entry" 204 "" -- -X PUT "$API/entries/$RX_A_ENTRY/favourite" -H "Authorization: Bearer $RX_B"
expect "…sends a reaction that is not one" 422 '"code":"VALIDATION_FAILED"' -- -X PUT "$API/entries/$RX_A_ENTRY/reaction" -H "Authorization: Bearer $RX_B" -d '{"type":"LOVE"}'
expect "…and one with a second key" 400 '"code":"MALFORMED_REQUEST"' -- -X PUT "$API/entries/$RX_A_ENTRY/reaction" -H "Authorization: Bearer $RX_B" -d '{"type":"SMILE","note":"hello"}'
expect "the author's day is 304 on the tag from before all that" 304 "" -- "$API/bonds/$RX_BOND/days/$RX_DATE" -H "Authorization: Bearer $RX_A" -H "If-None-Match: $RX_A_DAY_TAG_HEART"
rows_are "…and the reaction is still the one that was set" "$RX_A_ENTRY" "1|HEART"
expect "the reactor takes the bookmark off" 204 "" -- -X DELETE "$API/entries/$RX_A_ENTRY/favourite" -H "Authorization: Bearer $RX_B"

# The fifth type, ME_TOO (ADR-0037 question 1, V27): taken, and read back by both, like the others.
flush_buckets
expect "PUT {\"type\":\"ME_TOO\"} is 204" 204 "" -- -X PUT "$API/entries/$RX_A_ENTRY/reaction" -H "Authorization: Bearer $RX_B" -d '{"type":"ME_TOO"}'
rows_are "…one row, now ME_TOO" "$RX_A_ENTRY" "1|ME_TOO"
is "…read back by the reactor and the author on today, the feed and the day" "$(rx_views "$RX_B" "$RX_BOND" partnerEntry)|$(rx_views "$RX_A" "$RX_BOND" myEntry)" '"ME_TOO" "ME_TOO" "ME_TOO"|"ME_TOO" "ME_TOO" "ME_TOO"'

# Replace. One per member per entry: a second type is put in place of the first.
for type in SMILE THANK_YOU MOVED; do
  expect "PUT {\"type\":\"$type\"} replaces it: 204" 204 "" -- -X PUT "$API/entries/$RX_A_ENTRY/reaction" -H "Authorization: Bearer $RX_B" -d "{\"type\":\"$type\"}"
  rows_are "…one row still, now $type" "$RX_A_ENTRY" "1|$type"
done
is "both are shown the last of them: the reactor" "$(rx_views "$RX_B" "$RX_BOND" partnerEntry)" '"MOVED" "MOVED" "MOVED"'
is "…and the author" "$(rx_views "$RX_A" "$RX_BOND" myEntry)" '"MOVED" "MOVED" "MOVED"'
expect "…and the author's tag has moved again" 200 '"reaction":"MOVED"' -- "$API/bonds/$RX_BOND/days/$RX_DATE" -H "Authorization: Bearer $RX_A" -H "If-None-Match: $RX_A_DAY_TAG_HEART"

# Remove. Absent is success, and what is left is the day as it was.
flush_buckets
expect "DELETE /entries/{id}/reaction is 204" 204 "" -- -X DELETE "$API/entries/$RX_A_ENTRY/reaction" -H "Authorization: Bearer $RX_B"
[ -z "$LAST_BODY" ] && pass "…with no body" || fail "unreact body" "${LAST_BODY:0:200}"
expect "…and 204 again" 204 "" -- -X DELETE "$API/entries/$RX_A_ENTRY/reaction" -H "Authorization: Bearer $RX_B"
rows_are "…leaving no row" "$RX_A_ENTRY" "0|"
is "neither is shown one any longer" "$(rx_views "$RX_B" "$RX_BOND" partnerEntry)|$(rx_views "$RX_A" "$RX_BOND" myEntry)" 'null null null|null null null'
expect "the author's day is 304 again on the tag it had before anybody reacted" 304 "" -- "$API/bonds/$RX_BOND/days/$RX_DATE" -H "Authorization: Bearer $RX_A" -H "If-None-Match: $RX_A_DAY_TAG"
expect "…and so is the author's feed" 304 "" -- "$API/bonds/$RX_BOND/days" -H "Authorization: Bearer $RX_A" -H "If-None-Match: $RX_A_FEED_TAG"
expect "…and today, which has no tag" 200 '"reaction":null' -- "$API/bonds/$RX_BOND/today" -H "Authorization: Bearer $RX_A"
is "…is what it was, value for value" "$(at "")" "$RX_A_TODAY"
expect "the reactor's day" 200 "\"date\":\"$RX_DATE\"" -- "$API/bonds/$RX_BOND/days/$RX_DATE" -H "Authorization: Bearer $RX_B"
is "…is what it was too" "$(at "")" "$RX_B_DAY"

# Whose entry. One's own takes none, and says so with a code of its own; taking
# back from it is the success that absence always is.
expect "reacting to one's own revealed entry is 409 REACTION_ON_OWN_ENTRY" 409 '"code":"REACTION_ON_OWN_ENTRY"' -- -X PUT "$API/entries/$RX_A_ENTRY/reaction" -H "Authorization: Bearer $RX_A" -d '{"type":"HEART"}'
expect "…and DELETE on one's own entry is 204" 204 "" -- -X DELETE "$API/entries/$RX_A_ENTRY/reaction" -H "Authorization: Bearer $RX_A"
# A second bond in which only one has written: that entry is its author's own
# and unrevealed, and to the other member it is an entry they were never shown.
expect "a second bond is 201" 201 '"status":"PENDING_MEMBER"' -- -X POST "$API/bonds" -H "Authorization: Bearer $RX_A" -d "$(bond_body "Us")"
RX_BOND2="$(printf '%s' "$LAST_BODY" | jget id)"
RX_CODE2="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['code'])" <<<"$LAST_BODY")"
expect "…joined" 200 '"status":"ACTIVE"' -- -X POST "$API/invites/$RX_CODE2/accept" -H "Authorization: Bearer $RX_O"
expect "only one of them writes today" 201 '"status":"SUBMITTED"' -- -X POST "$API/bonds/$RX_BOND2/entries" -H "Authorization: Bearer $RX_A" -H "Idempotency-Key: $(new_key)" -d "{\"text\":\"$RX_SOLO_TEXT\"}"
RX_SOLO_ENTRY="$(printf '%s' "$LAST_BODY" | jget id)"
expect "reacting to one's own unrevealed entry is 409 REACTION_ON_OWN_ENTRY as well" 409 '"code":"REACTION_ON_OWN_ENTRY"' -- -X PUT "$API/entries/$RX_SOLO_ENTRY/reaction" -H "Authorization: Bearer $RX_A" -d '{"type":"HEART"}'

# Everything the caller was never shown is one 404, on both verbs: an id nobody
# was issued, a value that is not an id, the partner's entry that is still
# locked, and a revealed entry of a bond the caller is not in.
flush_buckets
expect "PUT /entries/<an id nobody was issued>/reaction is 404" 404 '"code":"NOT_FOUND"' -- -X PUT "$API/entries/$(new_key)/reaction" -H "Authorization: Bearer $RX_B" -d '{"type":"HEART"}'
RX_NO_ENTRY="$(whole_response)"
expect "PUT /entries/not-an-id/reaction is 404" 404 '"code":"NOT_FOUND"' -- -X PUT "$API/entries/not-an-id/reaction" -H "Authorization: Bearer $RX_B" -d '{"type":"HEART"}'
is "…the same body, apart from the path that was typed" "$(no_instance)" "$(tail -n 1 <<<"$RX_NO_ENTRY")"
expect "the partner reacting to that unrevealed entry, which they may not read, is 404" 404 '"code":"NOT_FOUND"' -- -X PUT "$API/entries/$RX_SOLO_ENTRY/reaction" -H "Authorization: Bearer $RX_O" -d '{"type":"HEART"}'
is "…the same response as for no entry at all" "$(whole_response)" "$RX_NO_ENTRY"
expect "a stranger reacting to a revealed entry of a bond they are not in is 404" 404 '"code":"NOT_FOUND"' -- -X PUT "$API/entries/$RX_A_ENTRY/reaction" -H "Authorization: Bearer $RX_O" -d '{"type":"HEART"}'
is "…the same response again" "$(whole_response)" "$RX_NO_ENTRY"
expect "DELETE /entries/<an id nobody was issued>/reaction is 404" 404 '"code":"NOT_FOUND"' -- -X DELETE "$API/entries/$(new_key)/reaction" -H "Authorization: Bearer $RX_B"
is "…the PUT's response" "$(whole_response)" "$RX_NO_ENTRY"
expect "DELETE …/reaction on the partner's unrevealed entry is 404" 404 '"code":"NOT_FOUND"' -- -X DELETE "$API/entries/$RX_SOLO_ENTRY/reaction" -H "Authorization: Bearer $RX_O"
is "…the same" "$(whole_response)" "$RX_NO_ENTRY"
expect "a stranger's DELETE …/reaction on a revealed entry is 404" 404 '"code":"NOT_FOUND"' -- -X DELETE "$API/entries/$RX_A_ENTRY/reaction" -H "Authorization: Bearer $RX_O"
is "…the one 404" "$(whole_response)" "$RX_NO_ENTRY"

# A body that is not exactly one known type. Judged from the request alone,
# before the entry is looked at, so a stranger is told what a member is.
flush_buckets
for bad in '{"type":"LOVE"}' '{"type":"heart"}' '{"type":" HEART"}' '{"type":3}' '{"type":null}' '{}'; do
  expect "PUT $bad is 422 VALIDATION_FAILED" 422 '"code":"VALIDATION_FAILED"' -- -X PUT "$API/entries/$RX_A_ENTRY/reaction" -H "Authorization: Bearer $RX_B" -d "$bad"
  is "…on type" "$(raw errors.0.field)" "type"
done
expect "an unknown type once more, for its body" 422 '"code":"VALIDATION_FAILED"' -- -X PUT "$API/entries/$RX_A_ENTRY/reaction" -H "Authorization: Bearer $RX_B" -d '{"type":"LOVE"}'
if repeats "LOVE"; then fail "422 repeats the value" "${LAST_BODY:0:300}"; else pass "…which does not repeat what was sent"; fi
RX_BAD_TYPE="$(whole_response)"
expect "the same body from a stranger to the bond is 422 too, not the 404" 422 '"code":"VALIDATION_FAILED"' -- -X PUT "$API/entries/$RX_A_ENTRY/reaction" -H "Authorization: Bearer $RX_O" -d '{"type":"LOVE"}'
is "…the member's response, so it says nothing of who may see the entry" "$(whole_response)" "$RX_BAD_TYPE"
for bad in '{"type":"HEART","note":"x"}' '{"type":"HEART","type":"SMILE"}' '{"kind":"HEART"}' '["HEART"]' '{"type":["HEART"]}' '{"type":"HEART"} trailing'; do
  expect "PUT $bad is 400 MALFORMED_REQUEST" 400 '"code":"MALFORMED_REQUEST"' -- -X PUT "$API/entries/$RX_A_ENTRY/reaction" -H "Authorization: Bearer $RX_B" -d "$bad"
done
expect "a reaction sent as text/plain is 415" 415 "" -- -X PUT "$API/entries/$RX_A_ENTRY/reaction" -H "Authorization: Bearer $RX_B" -H 'Content-Type: text/plain' -d '{"type":"HEART"}'
RX_REFUSED="$(sql "SELECT count(*) FROM reactions WHERE entry_id IN ('$RX_A_ENTRY','$RX_B_ENTRY','$RX_SOLO_ENTRY')")"
case "$RX_REFUSED" in psql-unavailable) skip "the refused-reaction row count";; 0) pass "none of those refusals left a row";; *) fail "reaction rows" "expected 0, got '$RX_REFUSED'";; esac

# Each reacts to the other's entry. Two rows, one an entry, and neither request touches the other's.
flush_buckets
expect "the reactor reacts to the author's entry" 204 "" -- -X PUT "$API/entries/$RX_A_ENTRY/reaction" -H "Authorization: Bearer $RX_B" -d '{"type":"HEART"}'
expect "the author reacts to the reactor's entry" 204 "" -- -X PUT "$API/entries/$RX_B_ENTRY/reaction" -H "Authorization: Bearer $RX_A" -d '{"type":"THANK_YOU"}'
is "the author is shown both: the one on their entry and their own on the partner's" "$(rx_views "$RX_A" "$RX_BOND" myEntry)|$(rx_views "$RX_A" "$RX_BOND" partnerEntry)" '"HEART" "HEART" "HEART"|"THANK_YOU" "THANK_YOU" "THANK_YOU"'
is "…and the reactor the same two, the other way round" "$(rx_views "$RX_B" "$RX_BOND" partnerEntry)|$(rx_views "$RX_B" "$RX_BOND" myEntry)" '"HEART" "HEART" "HEART"|"THANK_YOU" "THANK_YOU" "THANK_YOU"'
rows_are "…one row on the author's entry" "$RX_A_ENTRY" "1|HEART"
rows_are "…and one on the reactor's" "$RX_B_ENTRY" "1|THANK_YOU"

# An ending that withdraws nothing. A reaction is seen by the other person, so
# none may be left once the bond has ended, by either of them: after an ending
# that would be contact. One left before it stays, and cannot be taken back from
# the partner's entry either: a removal the other person sees is contact too
# (ADR-0037 question 11). One's own entry is still 204.
flush_buckets
revealed_bond "a bond to be left with a reaction on it" "$RX_C" "$RX_B" "$RX_C_TEXT" "$RX_B2_TEXT"
RX_BOND3="$RB_BOND"; RX_C_ENTRY="$RB_A_ENTRY"; RX_B2_ENTRY="$RB_B_ENTRY"
expect "a reaction before the ending" 204 "" -- -X PUT "$API/entries/$RX_C_ENTRY/reaction" -H "Authorization: Bearer $RX_B" -d '{"type":"MOVED"}'
expect "the author leaves, keeping their entries: 204" 204 "" -- -X POST "$API/bonds/$RX_BOND3/leave" -H "Authorization: Bearer $RX_C"
expect "PUT …/reaction by the one who stayed is 409 BOND_ARCHIVED" 409 '"code":"BOND_ARCHIVED"' -- -X PUT "$API/entries/$RX_C_ENTRY/reaction" -H "Authorization: Bearer $RX_B" -d '{"type":"HEART"}'
expect "…and by the one who left, on the other's entry" 409 '"code":"BOND_ARCHIVED"' -- -X PUT "$API/entries/$RX_B2_ENTRY/reaction" -H "Authorization: Bearer $RX_C" -d '{"type":"HEART"}'
expect "…while one's own entry is still told as one's own" 409 '"code":"REACTION_ON_OWN_ENTRY"' -- -X PUT "$API/entries/$RX_C_ENTRY/reaction" -H "Authorization: Bearer $RX_C" -d '{"type":"HEART"}'
rows_are "the refused replacement changed nothing: the reaction from before the ending is there" "$RX_C_ENTRY" "1|MOVED"
rows_are "…and nothing was left on the other entry" "$RX_B2_ENTRY" "0|"
is "…and both are still shown it: the one who left" "$(rx_views "$RX_C" "$RX_BOND3" myEntry)" '"MOVED" "MOVED" "MOVED"'
is "…and the one who stayed" "$(rx_views "$RX_B" "$RX_BOND3" partnerEntry)" '"MOVED" "MOVED" "MOVED"'
expect "DELETE …/reaction on the ended bond is 409 BOND_ARCHIVED" 409 '"code":"BOND_ARCHIVED"' -- -X DELETE "$API/entries/$RX_C_ENTRY/reaction" -H "Authorization: Bearer $RX_B"
expect "…and DELETE by the one who left, on the other's entry" 409 '"code":"BOND_ARCHIVED"' -- -X DELETE "$API/entries/$RX_B2_ENTRY/reaction" -H "Authorization: Bearer $RX_C"
expect "…while DELETE on one's own entry is still 204" 204 "" -- -X DELETE "$API/entries/$RX_C_ENTRY/reaction" -H "Authorization: Bearer $RX_C"
rows_are "…and the reaction is still there" "$RX_C_ENTRY" "1|MOVED"
is "…still shown to both of them" "$(rx_views "$RX_C" "$RX_BOND3" myEntry)|$(rx_views "$RX_B" "$RX_BOND3" partnerEntry)" '"MOVED" "MOVED" "MOVED"|"MOVED" "MOVED" "MOVED"'

# The twin, deleted by hand: the author deletes their entry and then leaves.
# What its reactor is answered is kept, to be compared below with what the
# reactor of a WITHDRAWN entry is answered. Nothing holds a row in either.
flush_buckets
revealed_bond "a bond whose author deletes by hand and leaves" "$RX_C" "$RX_O" "$RX_HAND_TEXT" "$RX_O_TEXT"
RX_BOND4="$RB_BOND"; RX_HAND_ENTRY="$RB_A_ENTRY"
expect "a reaction on the entry that will be deleted" 204 "" -- -X PUT "$API/entries/$RX_HAND_ENTRY/reaction" -H "Authorization: Bearer $RX_O" -d '{"type":"HEART"}'
expect "its author deletes it" 204 "" -- -X DELETE "$API/entries/$RX_HAND_ENTRY" -H "Authorization: Bearer $RX_C"
rows_are "…and the reaction went with the words, in that request" "$RX_HAND_ENTRY" "0|"
is "…a tombstone says null to both: the one who reacted" "$(rx_views "$RX_O" "$RX_BOND4" partnerEntry)" 'null null null'
is "…and its author" "$(rx_views "$RX_C" "$RX_BOND4" myEntry)" 'null null null'
expect "then the author leaves" 204 "" -- -X POST "$API/bonds/$RX_BOND4/leave" -H "Authorization: Bearer $RX_C"
expect "PUT …/reaction on the tombstone is 409 ENTRY_IMMUTABLE, though the bond has ended too" 409 '"code":"ENTRY_IMMUTABLE"' -- -X PUT "$API/entries/$RX_HAND_ENTRY/reaction" -H "Authorization: Bearer $RX_O" -d '{"type":"HEART"}'
RX_HAND_PUT="$(whole_response)"
expect "DELETE …/reaction on the tombstone is 204" 204 "" -- -X DELETE "$API/entries/$RX_HAND_ENTRY/reaction" -H "Authorization: Bearer $RX_O"
RX_HAND_DELETE="$(whole_response)"

# The withdrawal. Each has a reaction on the other's entry (set above). The
# author blocks, which takes their entries back: the reaction ON the withdrawn
# entry stops being shown at once and its row goes with the words; the
# reaction the withdrawer LEFT on the partner's entry stays, and can no longer
# be changed or taken back (ADR-0037 decision 9, and question 11 as ruled).
flush_buckets
expect "the reactor's feed before the ending" 200 '"reaction":"HEART"' -- "$API/bonds/$RX_BOND/days" -H "Authorization: Bearer $RX_B"
RX_B_FEED_TAG="$(tag)"
expect "the author blocks, taking the entries: 204" 204 "" -- -X POST "$API/bonds/$RX_BOND/block" -H "Authorization: Bearer $RX_A" -d '{"withdrawEntries": true}'
# At once, whether or not the poller has run: the read is gated on the record the ending wrote.
expect "the reactor's very next read of the feed carries a tombstone" 200 '"status":"DELETED"' -- "$API/bonds/$RX_BOND/days" -H "Authorization: Bearer $RX_B"
is "…text null, DELETED, and no reaction on it" "$(raw items.0.partnerEntry.id)|$(at items.0.partnerEntry.text)|$(raw items.0.partnerEntry.status)|$(at items.0.partnerEntry.reaction)" "$RX_A_ENTRY|null|DELETED|null"
is "…beside the reactor's own entry, whole, with the withdrawer's reaction still on it" "$(raw items.0.myEntry.text)|$(raw items.0.myEntry.reaction)" "$RX_B_TEXT|THANK_YOU"
RX_B_FEED_TAG_WITHDRAWN="$(tag)"
[ -n "$RX_B_FEED_TAG_WITHDRAWN" ] && [ "$RX_B_FEED_TAG_WITHDRAWN" != "$RX_B_FEED_TAG" ] && pass "…under a new ETag: it moved at the ending" || fail "feed tag after a withdrawal" "still '$RX_B_FEED_TAG'"
# One statement, so one snapshot: the words and the reaction's row are both
# there (1|1, the poller has not come) or both gone (0|0). Anything else is an
# erasure that kept a reaction, or a reaction removed before its words.
RX_BEFORE_ERASURE="$(sql "SELECT (text IS NOT NULL)::int || '|' || (SELECT count(*) FROM reactions WHERE entry_id='$RX_A_ENTRY') FROM entries WHERE id='$RX_A_ENTRY'")"
case "$RX_BEFORE_ERASURE" in
  psql-unavailable) skip "the row beside its words (psql unavailable)";;
  "1|1") pass "…while the row is still in the table beside the words: it is the gate that hides it, not the erasure";;
  "0|0") pass "…and the poller has already erased both the words and the row";;
  *) fail "reaction row and its entry's words" "expected 1|1 or 0|0, got '$RX_BEFORE_ERASURE'";;
esac
expect "PUT …/reaction on the withdrawn entry is 409 ENTRY_IMMUTABLE" 409 '"code":"ENTRY_IMMUTABLE"' -- -X PUT "$API/entries/$RX_A_ENTRY/reaction" -H "Authorization: Bearer $RX_B" -d '{"type":"SMILE"}'
is "…the response its twin deleted by hand gave" "$(whole_response)" "$RX_HAND_PUT"
await_erased "$RX_A_ENTRY"
erased_check "the poller erases the withdrawn entry"
rows_are "…and the reaction on it is gone with the words" "$RX_A_ENTRY" "0|"
rows_are "…while the reaction the withdrawer left on the partner's entry is still there" "$RX_B_ENTRY" "1|THANK_YOU"
expect "the reactor's feed, once it is erased" 200 '"status":"DELETED"' -- "$API/bonds/$RX_BOND/days" -H "Authorization: Bearer $RX_B"
is "…the same tombstone, with no reaction" "$(raw items.0.partnerEntry.id)|$(at items.0.partnerEntry.text)|$(raw items.0.partnerEntry.status)|$(at items.0.partnerEntry.reaction)" "$RX_A_ENTRY|null|DELETED|null"
[[ "$LAST_BODY" != *"$RX_A_TEXT"* ]] && pass "…the withdrawn words nowhere in it" || fail "withdrawn text read" "${LAST_BODY:0:300}"
# The tag moved once, at the ending. The erasure removed a row that was already
# shown to nobody, so it must not move the tag a second time.
case "$RX_BEFORE_ERASURE" in
  "1|1") is "…under the tag the feed had before the erasure: removing the row moved nothing" "$(tag)" "$RX_B_FEED_TAG_WITHDRAWN";;
  psql-unavailable) skip "the tag across the erasure (psql unavailable)";;
  *) skip "the tag across the erasure: the poller had erased the entry before the first read ('$RX_BEFORE_ERASURE')";;
esac
expect "PUT …/reaction on the erased entry is still 409 ENTRY_IMMUTABLE" 409 '"code":"ENTRY_IMMUTABLE"' -- -X PUT "$API/entries/$RX_A_ENTRY/reaction" -H "Authorization: Bearer $RX_B" -d '{"type":"SMILE"}'
is "…the twin's response" "$(whole_response)" "$RX_HAND_PUT"
expect "DELETE …/reaction on it is 204" 204 "" -- -X DELETE "$API/entries/$RX_A_ENTRY/reaction" -H "Authorization: Bearer $RX_B"
is "…the twin's response: with nothing held, a withdrawal and a deletion by hand answer alike" "$(whole_response)" "$RX_HAND_DELETE"
is "the one who withdrew is still shown the reaction they left" "$(rx_views "$RX_A" "$RX_BOND" partnerEntry)" '"THANK_YOU" "THANK_YOU" "THANK_YOU"'
is "…and none on their own tombstone" "$(rx_views "$RX_A" "$RX_BOND" myEntry)" 'null null null'
expect "they cannot change it: 409 BOND_ARCHIVED" 409 '"code":"BOND_ARCHIVED"' -- -X PUT "$API/entries/$RX_B_ENTRY/reaction" -H "Authorization: Bearer $RX_A" -d '{"type":"HEART"}'
expect "…nor take it back: 409 BOND_ARCHIVED" 409 '"code":"BOND_ARCHIVED"' -- -X DELETE "$API/entries/$RX_B_ENTRY/reaction" -H "Authorization: Bearer $RX_A"
rows_are "…which leaves it there" "$RX_B_ENTRY" "1|THANK_YOU"
is "…still shown to the partner" "$(rx_views "$RX_B" "$RX_BOND" myEntry)" '"THANK_YOU" "THANK_YOU" "THANK_YOU"'

RX_LEAKED=""
for words in "$RX_A_TEXT" "$RX_B_TEXT" "$RX_SOLO_TEXT" "$RX_C_TEXT" "$RX_B2_TEXT" "$RX_HAND_TEXT" "$RX_O_TEXT"; do grep -qF "$words" "$MOYI_LOG" && RX_LEAKED="$RX_LEAKED [$words]"; done
[ -z "$RX_LEAKED" ] && pass "no entry of this section is anywhere in the log" || fail "text in log" "found:$RX_LEAKED"

echo; echo "prompts — one prompt a day for the bond, in each member's language (FR-047, ADR-0038)"
flush_buckets
# What this section is for: the prompt as the jar serves it. Both members are offered the
# same one, each in their own language; nothing the partner does moves it; an entry
# records which it answered and nobody is sent that back; an id that names no prompt is
# one 422; an ended bond has none. The library is the seed's. A translation is put in for
# this run only, so that a second language has something to show, and taken out after.
verified_account "prompted" "203.0.113.110";          PR_A="$ACCOUNT_ACCESS"
verified_account "lusophone" "203.0.113.111" "pt-PT"; PR_B="$ACCOUNT_ACCESS"
verified_account "outsider" "203.0.113.112";          PR_O="$ACCOUNT_ACCESS"
PR_TRANSLATIONS_IN=1
PR_TRANSLATED="$(sql "WITH added AS (INSERT INTO prompt_texts (prompt_id, locale, text) SELECT id, 'pt-pt', 'PT~ ' || text FROM prompts ON CONFLICT DO NOTHING RETURNING 1) SELECT count(*) FROM added")"
# db_is <label> <got> <expected> — `is` for a value read with `sql`: skipped when psql cannot be reached, failed when the query does.
db_is() { case "$2" in psql-unavailable) skip "$1 (psql not reachable through docker compose)";; *) is "$1" "$2" "$3";; esac; }

expect "prompts: a bond" 201 '"status":"PENDING_MEMBER"' -- -X POST "$API/bonds" -H "Authorization: Bearer $PR_A" -d "$(bond_body "Us")"
PR_BOND="$(printf '%s' "$LAST_BODY" | jget id)"
PR_CODE="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['code'])" <<<"$LAST_BODY")"
expect "prompts: before the second member, there is already a prompt" 200 '"prompt":{' -- "$API/bonds/$PR_BOND/today" -H "Authorization: Bearer $PR_A"
expect "prompts: …joined" 200 '"status":"ACTIVE"' -- -X POST "$API/invites/$PR_CODE/accept" -H "Authorization: Bearer $PR_B"

expect "prompts: today for the first" 200 '"prompt":{' -- "$API/bonds/$PR_BOND/today" -H "Authorization: Bearer $PR_A"
PR_ID="$(raw prompt.id)"; PR_A_PROMPT="$(at prompt)"; PR_A_TEXT="$(raw prompt.text)"
is "prompts: the prompt is an id and a text, nothing else" "$(python3 -c 'import json,sys; print(sorted(json.loads(sys.argv[1])))' "$PR_A_PROMPT")" "['id', 'text']"
# `sql` strips whitespace from what it prints, so the comparison is made without it.
PR_SEED_TEXT="$(sql "SELECT text FROM prompts WHERE id='$PR_ID'")"
db_is "prompts: in en, the seed's own words" "$(printf '%s' "$PR_A_TEXT" | tr -d '[:space:]')" "$PR_SEED_TEXT"
expect "prompts: today for the second" 200 '"prompt":{' -- "$API/bonds/$PR_BOND/today" -H "Authorization: Bearer $PR_B"
is "prompts: the same prompt for both" "$(raw prompt.id)" "$PR_ID"
case "$PR_TRANSLATED" in
  0|psql-unavailable) skip "prompts: the second member reads it in pt-PT (no translation could be put in)" ;;
  psql-error*) fail "prompts: the second member reads it in pt-PT" "the translation could not be put in: $PR_TRANSLATED" ;;
  *) is "prompts: the second member reads it in pt-PT" "$(raw prompt.text)" "PT~ $PR_A_TEXT" ;;
esac

# The partner opens the app, writes, the day reveals: the first member's prompt does not move a byte.
for _ in 1 2 3; do curl -s -o /dev/null "$API/bonds/$PR_BOND/today" -H "Authorization: Bearer $PR_B"; done
expect "prompts: the second writes, with no prompt" 201 '"status":"SUBMITTED"' -- -X POST "$API/bonds/$PR_BOND/entries" -H "Authorization: Bearer $PR_B" -H "Idempotency-Key: $(new_key)" -d '{"text":"thank you for the lamp"}'
expect "prompts: the first's today, after" 200 "" -- "$API/bonds/$PR_BOND/today" -H "Authorization: Bearer $PR_A"
is "prompts: …her prompt is the same bytes" "$(at prompt)" "$PR_A_PROMPT"

# An entry answers the prompt; nobody is sent which.
expect "prompts: the first writes, answering it" 201 '"status":"REVEALED"' -- -X POST "$API/bonds/$PR_BOND/entries" -H "Authorization: Bearer $PR_A" -H "Idempotency-Key: $(new_key)" -d "{\"text\":\"thank you for the long walk\",\"promptId\":\"$PR_ID\"}"
PR_ENTRY="$(printf '%s' "$LAST_BODY" | jget id)"; PR_DATE="$(raw date)"
is "prompts: the 201 has no promptId" "$(at promptId)" "absent"
expect "prompts: the first's today, revealed" 200 '"status":"REVEALED"' -- "$API/bonds/$PR_BOND/today" -H "Authorization: Bearer $PR_A"
is "prompts: …her prompt is still the same bytes" "$(at prompt)" "$PR_A_PROMPT"
db_is "prompts: the entry row records it" "$(sql "SELECT prompt_id FROM entries WHERE id='$PR_ENTRY'")" "$PR_ID"
for view in "days" "days/$PR_DATE"; do
  expect "prompts: the partner's $view" 200 "" -- "$API/bonds/$PR_BOND/$view" -H "Authorization: Bearer $PR_B"
  if repeats "$PR_ID" || [[ "$LAST_BODY" == *promptId* ]]; then fail "prompts: $view names the prompt the entry answered" "${LAST_BODY:0:200}"; else pass "prompts: $view names no prompt"; fi
done

# One 422 for every id that names no prompt, and the guard and the ending first.
PR_ONE="$(curl -s -X POST "$API/bonds/$PR_BOND/entries" -H 'Content-Type: application/json' -H "Authorization: Bearer $PR_A" -H "Idempotency-Key: $(new_key)" -d "{\"text\":\"x\",\"promptId\":\"$(new_key)\"}" | sed -E 's/"instance":"[^"]*"//; s/,"traceId":"[0-9a-f]{32}"//')"
expect "prompts: an id no prompt has is 422 VALIDATION_FAILED" 422 '"code":"VALIDATION_FAILED"' -- -X POST "$API/bonds/$PR_BOND/entries" -H "Authorization: Bearer $PR_A" -H "Idempotency-Key: $(new_key)" -d '{"text":"x","promptId":"not-a-uuid"}'
if [[ "$LAST_BODY" == *'"field":"promptId"'* ]]; then pass "prompts: …on the field promptId"; else fail "prompts: …on the field promptId" "${LAST_BODY:0:200}"; fi
is "prompts: …the same body as for a uuid no prompt has" "$(no_instance | sed 's/"instance":"-"//')" "$PR_ONE"
expect "prompts: a stranger is told the bond's 404 first" 404 '"code":"NOT_FOUND"' -- -X POST "$API/bonds/$PR_BOND/entries" -H "Authorization: Bearer $PR_O" -H "Idempotency-Key: $(new_key)" -d '{"text":"x","promptId":"not-a-uuid"}'

# Deleting the entry takes the prompt it answered.
expect "prompts: the first deletes her entry" 204 "" -- -X DELETE "$API/entries/$PR_ENTRY" -H "Authorization: Bearer $PR_A"
db_is "prompts: …and the row no longer names the prompt" "$(sql "SELECT coalesce(prompt_id::text, 'none') FROM entries WHERE id='$PR_ENTRY'")" "none"

# An ended bond has no prompt, for either member.
expect "prompts: the second leaves" 204 "" -- -X POST "$API/bonds/$PR_BOND/leave" -H "Authorization: Bearer $PR_B"
expect "prompts: no prompt for the one who stayed" 200 '"prompt":null' -- "$API/bonds/$PR_BOND/today" -H "Authorization: Bearer $PR_A"
expect "prompts: …nor for the one who left" 200 '"prompt":null' -- "$API/bonds/$PR_BOND/today" -H "Authorization: Bearer $PR_B"

# Taken out here, so the sections after this one see the seed alone; the EXIT trap
# takes them out again (a no-op by then) and is what does it when a run dies first.
sql "DELETE FROM prompt_texts WHERE text LIKE 'PT~ %'" >/dev/null

echo; echo "search and on this day — the archive found by its words, and earlier years of today (FR-091, FR-092, ADR-0039)"
flush_buckets
# What this section is for: search and on-this-day as the jar serves them. Words fold case and
# accents and are whole; the partner's unrevealed words find nothing; an erased entry's words are
# gone from the row and from search; a q that cannot be searched is one 422 that does not carry
# it, after the bond's 404; neither response may be stored. Last year's day is put in by hand
# (no day ends during a run), and resurfaced for both members until the bond ends, when both are
# answered the same nothing. On 29 February the on-this-day probes skip: a year ago is the 28th.
verified_account "searcher" "203.0.113.120"; SE_A="$ACCOUNT_ACCESS"
verified_account "searched" "203.0.113.121"; SE_B="$ACCOUNT_ACCESS"
verified_account "outsider" "203.0.113.122"; SE_O="$ACCOUNT_ACCESS"
revealed_bond "search" "$SE_A" "$SE_B" "Obrigada pelo Café e pela caminhada" "thank you for the long walk home"
SE_BOND="$RB_BOND"; SE_A_ENTRY="$RB_A_ENTRY"
# search <label> <status> <needle> <q> <token> — q is url-encoded by curl, as a client would send it.
search() { expect "$1" "$2" "$3" -- -G "$API/bonds/$SE_BOND/search" --data-urlencode "q=$4" -H "Authorization: Bearer $5"; }

search "search: cafe finds Café" 200 'Obrigada pelo Café' "cafe" "$SE_B"
search "search: CAFÉ finds it too" 200 'Obrigada pelo Café' "CAFÉ" "$SE_A"
is "search: a page is days and a cursor, and nothing else" "$(python3 -c 'import json,sys; print(sorted(json.loads(sys.argv[1])))' "$LAST_BODY")" "['items', 'nextCursor']"
header_is "search: never stored" "Cache-Control" "no-cache, no-store, max-age=0, must-revalidate"
search "search: words in either order" 200 'thank you for the long walk home' "walk long" "$SE_A"
search "search: caf is not a word of it" 200 '"items":[]' "caf" "$SE_A"
search "search: punctuation alone matches nothing, and is not refused" 200 '"items":[]' "!!!" "$SE_A"

# The partner's unrevealed words find nothing; one's own are found.
expect "search: a second bond" 201 '"status":"PENDING_MEMBER"' -- -X POST "$API/bonds" -H "Authorization: Bearer $SE_A" -d "$(bond_body "Us again")"
SE_BOND2="$(printf '%s' "$LAST_BODY" | jget id)"
SE_CODE="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['code'])" <<<"$LAST_BODY")"
expect "search: …joined" 200 '"status":"ACTIVE"' -- -X POST "$API/invites/$SE_CODE/accept" -H "Authorization: Bearer $SE_B"
expect "search: only she writes" 201 '"status":"SUBMITTED"' -- -X POST "$API/bonds/$SE_BOND2/entries" -H "Authorization: Bearer $SE_A" -H "Idempotency-Key: $(new_key)" -d '{"text":"a secret zebra"}'
expect "search: she finds her own" 200 'a secret zebra' -- -G "$API/bonds/$SE_BOND2/search" --data-urlencode "q=zebra" -H "Authorization: Bearer $SE_A"
expect "search: he does not, before the reveal" 200 '' -- -G "$API/bonds/$SE_BOND2/search" --data-urlencode "q=zebra" -H "Authorization: Bearer $SE_B"
is "search: …the page is empty and the end" "$LAST_BODY" '{"items":[],"nextCursor":null}'

# What cannot be searched: one 422 on q, never carrying q, and the stranger's 404 first.
# The two the state map cites are written out, not through search(): scripts/state-map-check
# finds a probe's label on an `expect` line.
expect "search: blank q is one 422 on q" 422 '"field":"q"' -- -G "$API/bonds/$SE_BOND/search" --data-urlencode "q=   " -H "Authorization: Bearer $SE_A"
SE_LONG="$(python3 -c 'print("zq" * 51)')"
search "search: 102 characters is one 422 on q" 422 '"field":"q"' "$SE_LONG" "$SE_A"
if [[ "$LAST_BODY" == *zqzqzq* ]]; then fail "search: the refusal carries q" "${LAST_BODY:0:200}"; else pass "search: the refusal does not carry q"; fi
expect "search: NUL in q is one 422, not a 500" 422 '"field":"q"' -- "$API/bonds/$SE_BOND/search?q=zq%00zq" -H "Authorization: Bearer $SE_A"
SE_STRANGER="$(curl -s "$API/bonds/$SE_BOND/search" -H "Authorization: Bearer $SE_O" | sed -E 's/"instance":"[^"]*"//; s/,"traceId":"[0-9a-f]{32}"//')"
expect "search: a stranger is told the bond's 404, whatever q is" 404 '"code":"NOT_FOUND"' -- -G "$API/bonds/$SE_BOND/search" --data-urlencode "q=   " -H "Authorization: Bearer $SE_O"
is "search: …the same bytes as with no q at all" "$(no_instance | sed 's/"instance":"-"//')" "$SE_STRANGER"

# Last year's today, put in by hand for both members, revealed.
SE_TODAY="$(sql "SELECT (now() AT TIME ZONE 'Africa/Lagos')::date")"
SE_LAST="$(sql "SELECT ((now() AT TIME ZONE 'Africa/Lagos')::date - interval '1 year')::date")"
expect "on this day: today, for the two member ids" 200 '"myEntry"' -- "$API/bonds/$SE_BOND/today" -H "Authorization: Bearer $SE_A"
SE_A_MEMBER="$(raw myEntry.authorMemberId)"; SE_B_MEMBER="$(raw partnerEntry.authorMemberId)"
case "$SE_LAST" in
  psql-unavailable|psql-error*) SE_LAST_MADE=0 ;;
  *) if [ "${SE_LAST:5}" = "${SE_TODAY:5}" ]; then
       SE_LAST_MADE="$(sql "WITH d AS (INSERT INTO bond_days (id, bond_id, date, status, anchor_timezone, starts_at, ends_at, entry_count, created_at, closed_at, version) VALUES (gen_random_uuid(), '$SE_BOND', '$SE_LAST', 'REVEALED', 'Africa/Lagos', '$SE_LAST'::date::timestamptz, ('$SE_LAST'::date + 1)::timestamptz, 2, now(), ('$SE_LAST'::date + 1)::timestamptz, 0) RETURNING id), e AS (INSERT INTO entries (id, bond_day_id, bond_id, author_member_id, text, text_search, status, created_at, intended_at, updated_at, revealed_at) SELECT gen_random_uuid(), d.id, '$SE_BOND', m, 'a year ago we walked', 'a year ago we walked', 'REVEALED', now(), now(), now(), now() FROM d, unnest(ARRAY['$SE_A_MEMBER', '$SE_B_MEMBER']::uuid[]) m RETURNING 1) SELECT count(*) FROM e")"
     else SE_LAST_MADE=0; fi ;;
esac
if [ "$SE_LAST_MADE" = 2 ]; then
  expect "on this day: last year's day, for her" 200 "\"date\":\"$SE_LAST\"" -- "$API/bonds/$SE_BOND/on-this-day" -H "Authorization: Bearer $SE_A"
  header_is "on this day: never stored" "Cache-Control" "no-cache, no-store, max-age=0, must-revalidate"
  expect "on this day: …and for him" 200 "\"date\":\"$SE_LAST\"" -- "$API/bonds/$SE_BOND/on-this-day" -H "Authorization: Bearer $SE_B"
  search "search: last year's words are found too" 200 "\"date\":\"$SE_LAST\"" "walked" "$SE_A"
else
  skip "on this day: last year's day (it could not be put in, or today is 29 February)"
fi
expect "on this day: a stranger is told the bond's 404" 404 '"code":"NOT_FOUND"' -- "$API/bonds/$SE_BOND/on-this-day" -H "Authorization: Bearer $SE_O"

# An erased entry's words leave the row and the search.
db_is "search: the row holds her words for search" "$(sql "SELECT text_search IS NOT NULL FROM entries WHERE id='$SE_A_ENTRY'")" "t"
expect "search: she deletes her entry" 204 "" -- -X DELETE "$API/entries/$SE_A_ENTRY" -H "Authorization: Bearer $SE_A"
db_is "search: …and the row holds none" "$(sql "SELECT coalesce(text_search, 'none') FROM entries WHERE id='$SE_A_ENTRY'")" "none"
search "search: …and cafe finds nothing" 200 '"items":[]' "cafe" "$SE_B"

# Ended: on-this-day is nothing, the same bytes for both; search still reads.
expect "on this day: he leaves" 204 "" -- -X POST "$API/bonds/$SE_BOND/leave" -H "Authorization: Bearer $SE_B"
expect "on this day: nothing for the one who stayed" 200 "" -- "$API/bonds/$SE_BOND/on-this-day" -H "Authorization: Bearer $SE_A"
is "on this day: …exactly nothing" "$LAST_BODY" '{"items":[]}'
expect "on this day: nothing for the one who left" 200 "" -- "$API/bonds/$SE_BOND/on-this-day" -H "Authorization: Bearer $SE_B"
is "on this day: …the same bytes" "$LAST_BODY" '{"items":[]}'
search "search: the ended bond is still searched, by the one who left" 200 'thank you for the long walk home' "walk" "$SE_B"

echo; echo "semantic search and remember when — keywords alone with no provider, and the setting off and on (FR-204, ADR-0054)"
flush_buckets
# What this section is for: semantic search as the jar serves it with the provider `none`, which
# is what local runs and production name until a key exists. Search is 200 and finds by words
# alone; "Remember when" is 200 from the words' ranking alone, its q read as search's, and the
# stranger's 404 first. PATCH cannot turn it on. Turning it off deletes the bond's vectors through
# the outbox: with no provider nothing writes one, so one is put in by hand first. Back on needs
# both members, a solo bond alone; an ended bond still takes `false` and nothing else (decision 9,
# provisional).
verified_account "meaner" "203.0.113.190"; MS_A="$ACCOUNT_ACCESS"
verified_account "meant" "203.0.113.191"; MS_B="$ACCOUNT_ACCESS"
verified_account "unmeant" "203.0.113.192"; MS_O="$ACCOUNT_ACCESS"
revealed_bond "semantic" "$MS_A" "$MS_B" "we walked the dog along the river" "grateful for soup on a cold evening"
MS_BOND="$RB_BOND"; MS_A_ENTRY="$RB_A_ENTRY"
# ms_bond <token> — the bond's ETag, as that member reads it.
ms_bond() { curl -s -D - -o /dev/null "$API/bonds/$MS_BOND" -H "Authorization: Bearer $1" | tr -d '\r' | grep -i '^etag:' | head -1 | cut -d' ' -f2-; }

expect "semantic: a new bond has it on" 200 '"semanticSearchEnabled":true' -- "$API/bonds/$MS_BOND" -H "Authorization: Bearer $MS_A"
expect "semantic: search with no provider is 200, by words alone" 200 'we walked the dog along the river' -- -G "$API/bonds/$MS_BOND/search" --data-urlencode "q=river" -H "Authorization: Bearer $MS_A"
expect "semantic: …and a word no entry holds finds nothing, however near its meaning" 200 '"items":[]' -- -G "$API/bonds/$MS_BOND/search" --data-urlencode "q=stream" -H "Authorization: Bearer $MS_A"

expect "remember: the words' ranking alone, with no provider" 200 'grateful for soup on a cold evening' -- -G "$API/bonds/$MS_BOND/remember" --data-urlencode "q=cold soup" -H "Authorization: Bearer $MS_A"
is "remember: an answer is days, and nothing else" "$(python3 -c 'import json,sys; print(sorted(json.loads(sys.argv[1])))' "$LAST_BODY")" "['items']"
header_is "remember: never stored" "Cache-Control" "no-cache, no-store, max-age=0, must-revalidate"
expect "remember: a question that shares no word answers no day" 200 '' -- -G "$API/bonds/$MS_BOND/remember" --data-urlencode "q=zebra" -H "Authorization: Bearer $MS_B"
is "remember: …exactly no day" "$LAST_BODY" '{"items":[]}'
# The words' ranking matches as search does: every word of q in one entry. So with no provider, a
# question asked in plain words ("when did we have soup") answers no day, though "soup" alone does
# (seen 2026-11-02; ADR-0054 records it for the owner, beside the relevance floor).
expect "remember: with no provider, a plain question whose words are not all in one entry answers no day" 200 '"items":[]' -- -G "$API/bonds/$MS_BOND/remember" --data-urlencode "q=when did we have soup" -H "Authorization: Bearer $MS_A"
expect "remember: no q is one 422 on q" 422 '"field":"q"' -- "$API/bonds/$MS_BOND/remember" -H "Authorization: Bearer $MS_A"
expect "remember: a limit of 21 is one 422 on limit" 422 '"field":"limit"' -- -G "$API/bonds/$MS_BOND/remember" --data-urlencode "q=soup" --data-urlencode "limit=21" -H "Authorization: Bearer $MS_A"
expect "remember: a stranger is told the bond's 404" 404 '"code":"NOT_FOUND"' -- -G "$API/bonds/$MS_BOND/remember" --data-urlencode "q=soup" -H "Authorization: Bearer $MS_O"

# Off: one member, PATCH false. True is refused on the field.
MS_ETAG="$(ms_bond "$MS_A")"
expect "semantic: PATCH cannot turn it on: 422 on the field" 422 '"field":"semanticSearchEnabled"' -- -X PATCH "$API/bonds/$MS_BOND" -H "Authorization: Bearer $MS_A" -H "If-Match: $MS_ETAG" -d '{"semanticSearchEnabled":true}'
[[ "$LAST_BODY" == *'"code":"ASSERT_FALSE"'* ]] && pass "semantic: …its code ASSERT_FALSE" || fail "semantic: ASSERT_FALSE" "${LAST_BODY:0:200}"
# No provider writes no vector, so one is put in for her entry: zeros, in the OpenAI model's table.
db_is "semantic: a vector put in by hand" "$(sql "WITH v AS (INSERT INTO entry_embeddings_openai_1536 (entry_id, bond_id, embedding, text_hash, created_at) VALUES ('$MS_A_ENTRY', '$MS_BOND', array_fill(0::real, ARRAY[1536])::vector, sha256('x'::bytea), now()) RETURNING 1) SELECT count(*) FROM v")" "1"
db_is "semantic: …the bond has one" "$(sql "SELECT count(*) FROM entry_embeddings_openai_1536 WHERE bond_id = '$MS_BOND'")" "1"
MS_ETAG="$(ms_bond "$MS_B")"
expect "semantic: one member turns it off: 200" 200 '"semanticSearchEnabled":false' -- -X PATCH "$API/bonds/$MS_BOND" -H "Authorization: Bearer $MS_B" -H "If-Match: $MS_ETAG" -d '{"semanticSearchEnabled":false}'
MS_LEFT="$(sql "SELECT count(*) FROM entry_embeddings_openai_1536 WHERE bond_id = '$MS_BOND'")"
for _ in $(seq 1 20); do [ "$MS_LEFT" = 0 ] && break; case "$MS_LEFT" in psql-*) break ;; esac; sleep 1; MS_LEFT="$(sql "SELECT count(*) FROM entry_embeddings_openai_1536 WHERE bond_id = '$MS_BOND'")"; done
db_is "semantic: …and after the outbox pass the bond has no vector" "$MS_LEFT" "0"
expect "semantic: …and she sees it off" 200 '"semanticSearchEnabled":false' -- "$API/bonds/$MS_BOND" -H "Authorization: Bearer $MS_A"
expect "remember: with it off, still the words' ranking" 200 'we walked the dog along the river' -- -G "$API/bonds/$MS_BOND/remember" --data-urlencode "q=the river" -H "Authorization: Bearer $MS_B"

# On again: both members, the deletion request's shape.
expect "semantic: calling a request off with nothing waiting is 404" 404 '"code":"NOT_FOUND"' -- -X DELETE "$API/bonds/$MS_BOND/semantic-search" -H "Authorization: Bearer $MS_A"
expect "semantic: asking to turn it back on is 202, and it stays off" 202 '"semanticSearchEnabled":false' -- -X POST "$API/bonds/$MS_BOND/semantic-search" -H "Authorization: Bearer $MS_A"
[[ "$LAST_BODY" == *'"pendingSemanticSearchRequest":{'* ]] && pass "semantic: …with her request pending" || fail "semantic: pending request" "${LAST_BODY:0:300}"
expect "semantic: asking again is 202, and nothing changes" 202 '"semanticSearchEnabled":false' -- -X POST "$API/bonds/$MS_BOND/semantic-search" -H "Authorization: Bearer $MS_A"
expect "semantic: the other member calls it off: 204" 204 "" -- -X DELETE "$API/bonds/$MS_BOND/semantic-search" -H "Authorization: Bearer $MS_B"
expect "semantic: …and nothing is pending" 200 '"pendingSemanticSearchRequest":null' -- "$API/bonds/$MS_BOND" -H "Authorization: Bearer $MS_A"
expect "semantic: she asks again: 202" 202 '"semanticSearchEnabled":false' -- -X POST "$API/bonds/$MS_BOND/semantic-search" -H "Authorization: Bearer $MS_A"
expect "semantic: the other member's request turns it on: 202" 202 '"semanticSearchEnabled":true' -- -X POST "$API/bonds/$MS_BOND/semantic-search" -H "Authorization: Bearer $MS_B"
expect "semantic: asking while it is on changes nothing: 202" 202 '"pendingSemanticSearchRequest":null' -- -X POST "$API/bonds/$MS_BOND/semantic-search" -H "Authorization: Bearer $MS_A"
expect "semantic: a stranger asking is 404" 404 '"code":"NOT_FOUND"' -- -X POST "$API/bonds/$MS_BOND/semantic-search" -H "Authorization: Bearer $MS_O"
expect "semantic: a stranger calling it off is 404" 404 '"code":"NOT_FOUND"' -- -X DELETE "$API/bonds/$MS_BOND/semantic-search" -H "Authorization: Bearer $MS_O"

# A solo bond: off, then on again by its one member.
expect "semantic: a bond of one" 201 '"semanticSearchEnabled":true' -- -X POST "$API/bonds" -H "Authorization: Bearer $MS_O" -d "$(bond_body "Me")"
MS_SOLO="$(printf '%s' "$LAST_BODY" | jget id)"; MS_SOLO_ETAG="$(etag_of)"
expect "semantic: …turned off by its member" 200 '"semanticSearchEnabled":false' -- -X PATCH "$API/bonds/$MS_SOLO" -H "Authorization: Bearer $MS_O" -H "If-Match: $MS_SOLO_ETAG" -d '{"semanticSearchEnabled":false}'
expect "semantic: a solo bond turns it back on alone: 202" 202 '"semanticSearchEnabled":true' -- -X POST "$API/bonds/$MS_SOLO/semantic-search" -H "Authorization: Bearer $MS_O"

# Ended (decision 9, provisional): only false is taken, and nothing can turn it on.
expect "semantic: he leaves" 204 "" -- -X POST "$API/bonds/$MS_BOND/leave" -H "Authorization: Bearer $MS_B"
MS_ETAG="$(ms_bond "$MS_A")"
expect "semantic: on an ended bond, any other change is 409 BOND_ARCHIVED" 409 '"code":"BOND_ARCHIVED"' -- -X PATCH "$API/bonds/$MS_BOND" -H "Authorization: Bearer $MS_A" -H "If-Match: $MS_ETAG" -d '{"semanticSearchEnabled":false,"strictMode":true}'
expect "semantic: on an ended bond, turning it off is still 200" 200 '"semanticSearchEnabled":false' -- -X PATCH "$API/bonds/$MS_BOND" -H "Authorization: Bearer $MS_A" -H "If-Match: $MS_ETAG" -d '{"semanticSearchEnabled":false}'
expect "semantic: on an ended bond, asking to turn it on is 409 BOND_ARCHIVED" 409 '"code":"BOND_ARCHIVED"' -- -X POST "$API/bonds/$MS_BOND/semantic-search" -H "Authorization: Bearer $MS_A"
expect "semantic: on an ended bond, calling a request off is 404" 404 '"code":"NOT_FOUND"' -- -X DELETE "$API/bonds/$MS_BOND/semantic-search" -H "Authorization: Bearer $MS_B"
expect "remember: the ended bond is still asked, by the one who left" 200 'grateful for soup on a cold evening' -- -G "$API/bonds/$MS_BOND/remember" --data-urlencode "q=soup" -H "Authorization: Bearer $MS_B"

echo; echo "milestones — what the bond has reached of 7, 30, 100 and 365 complete days, and what is next (FR-075, ADR-0041)"
flush_buckets
# What this section is for: milestones as the jar serves them. A bond one day old has reached
# nothing and 7 is next. Six earlier complete days, put in by hand (no day ends during a run),
# make today the seventh: reached the moment both have written, the same bytes for both, with
# nothing in the answer but what was reached and what is next. A stranger gets the bond's 404;
# no cache may keep the answer. Once the bond has ended, what was reached stays and nothing is
# next, the same bytes for both. MilestoneReached is the close job's, and no day ends during a
# run: MilestoneReachedTest holds it.
verified_account "milestoner" "203.0.113.130"; MI_A="$ACCOUNT_ACCESS"
verified_account "milestoned" "203.0.113.131"; MI_B="$ACCOUNT_ACCESS"
verified_account "outsider-m" "203.0.113.132"; MI_O="$ACCOUNT_ACCESS"
revealed_bond "milestones" "$MI_A" "$MI_B" "thank you for the first week" "and thank you for the seventh day"
MI_BOND="$RB_BOND"
expect "milestones: a bond one day old has reached nothing, and seven is next" 200 '"next":{"threshold":7}' -- "$API/bonds/$MI_BOND/milestones" -H "Authorization: Bearer $MI_A"
is "milestones: …exactly that" "$LAST_BODY" '{"achieved":[],"next":{"threshold":7}}'
header_is "milestones: never stored" "Cache-Control" "no-cache, no-store, max-age=0, must-revalidate"
expect "milestones: a stranger is told the bond's 404" 404 '"code":"NOT_FOUND"' -- "$API/bonds/$MI_BOND/milestones" -H "Authorization: Bearer $MI_O"

# Six complete days before today, evaluated, as the job would have left them.
MI_TODAY="$(sql "SELECT (now() AT TIME ZONE 'Africa/Lagos')::date")"
case "$MI_TODAY" in
  psql-unavailable|psql-error*) MI_MADE=0 ;;
  *) MI_MADE="$(sql "WITH d AS (INSERT INTO bond_days (id, bond_id, date, status, anchor_timezone, starts_at, ends_at, entry_count, created_at, closed_at, version, evaluated_at, evaluated_as, evaluated_strict, freeze_applied) SELECT gen_random_uuid(), '$MI_BOND', g::date, 'REVEALED', 'Africa/Lagos', g::date::timestamptz, (g::date + 1)::timestamptz, 2, now(), (g::date + 1)::timestamptz, 0, now(), 'COMPLETE', false, false FROM generate_series('$MI_TODAY'::date - 6, '$MI_TODAY'::date - 1, interval '1 day') AS g RETURNING 1) SELECT count(*) FROM d")" ;;
esac
if [ "$MI_MADE" = 6 ]; then
  MI_SEVEN="{\"achieved\":[{\"threshold\":7,\"achievedOn\":\"$MI_TODAY\"}],\"next\":{\"threshold\":30}}"
  MI_ENDED="{\"achieved\":[{\"threshold\":7,\"achievedOn\":\"$MI_TODAY\"}],\"next\":null}"
  expect "milestones: the seventh complete day is today, reached the moment both wrote" 200 "\"achievedOn\":\"$MI_TODAY\"" -- "$API/bonds/$MI_BOND/milestones" -H "Authorization: Bearer $MI_A"
  is "milestones: …the threshold and its date, and thirty next" "$LAST_BODY" "$MI_SEVEN"
  is "milestones: the answer is what was reached and what is next, and nothing else" "$(python3 -c 'import json,sys; print(sorted(json.loads(sys.argv[1])))' "$LAST_BODY")" "['achieved', 'next']"
  expect "milestones: …the same for him" 200 "" -- "$API/bonds/$MI_BOND/milestones" -H "Authorization: Bearer $MI_B"
  is "milestones: …the same bytes for him" "$LAST_BODY" "$MI_SEVEN"
  expect "milestones: he leaves" 204 "" -- -X POST "$API/bonds/$MI_BOND/leave" -H "Authorization: Bearer $MI_B"
  expect "milestones: nothing next once the bond has ended, for the one who stayed" 200 '"next":null' -- "$API/bonds/$MI_BOND/milestones" -H "Authorization: Bearer $MI_A"
  is "milestones: …and what was reached is kept" "$LAST_BODY" "$MI_ENDED"
  expect "milestones: …and for the one who left" 200 '"next":null' -- "$API/bonds/$MI_BOND/milestones" -H "Authorization: Bearer $MI_B"
  is "milestones: …the same bytes for the one who left" "$LAST_BODY" "$MI_ENDED"
else
  skip "milestones: the seventh day and the ending (the days could not be put in by hand)"
fi

echo; echo "streak — the run that ended, and why a day is a rest day (issue #141, ADR-0034 as amended)"
flush_buckets
# What this section is for: the two fields of issue #141 as the jar serves them, decided by the
# real close job. No day ends during a run, so 21 past days are put in by hand, closed and NOT
# evaluated, and the bond's pairing and zone history are moved back twenty-one days in the same
# statement (so the calendar draws them, and the job's missing-days step finds nothing to add):
# the joining day empty, as the job would write it; fourteen days both wrote bank a freeze; a solo day spends it (FROZEN, REST_DAY); a date a zone
# change stepped over keeps the run (FROZEN, TIMEZONE_SHIFT); an empty day with nothing banked ends
# the run at sixteen; three more days and today make four. The job, on its five-second schedule,
# evaluates them; the read is then checked field by field, the same for both members.
verified_account "streaker" "203.0.113.140"; SR_A="$ACCOUNT_ACCESS"
verified_account "streaked" "203.0.113.141"; SR_B="$ACCOUNT_ACCESS"
revealed_bond "streak" "$SR_A" "$SR_B" "thank you for the long run" "and thank you for starting again"
SR_BOND="$RB_BOND"
SR_TODAY="$(sql "SELECT (now() AT TIME ZONE 'Africa/Lagos')::date")"
case "$SR_TODAY" in
  psql-unavailable|psql-error*) SR_MADE=no ;;
  *) SR_MADE="$(sql "WITH m AS (UPDATE bond_members SET joined_at = joined_at - interval '21 days' WHERE bond_id = '$SR_BOND' RETURNING 1),
    b AS (UPDATE bonds SET created_at = created_at - interval '21 days' WHERE id = '$SR_BOND' RETURNING 1),
    a AS (UPDATE bond_anchor_intervals SET effective_from = effective_from - interval '21 days', first_label = first_label - 21 WHERE bond_id = '$SR_BOND' RETURNING 1),
    d AS (INSERT INTO bond_days (id, bond_id, date, status, anchor_timezone, starts_at, ends_at, entry_count, created_at, closed_at, version)
      SELECT gen_random_uuid(), '$SR_BOND', g.d, s.status, 'Africa/Lagos', g.d::timestamp AT TIME ZONE 'Africa/Lagos',
             (g.d + 1)::timestamp AT TIME ZONE 'Africa/Lagos', s.n, now(), (g.d + 1)::timestamp AT TIME ZONE 'Africa/Lagos', 0
      FROM (SELECT ('$SR_TODAY'::date - k) AS d, k FROM generate_series(1, 21) AS k) g
      CROSS JOIN LATERAL (SELECT CASE g.k WHEN 21 THEN 'EMPTY' WHEN 6 THEN 'SOLO' WHEN 5 THEN 'FROZEN' WHEN 4 THEN 'EMPTY' ELSE 'REVEALED' END AS status,
                                 CASE g.k WHEN 21 THEN 0 WHEN 6 THEN 1 WHEN 5 THEN 0 WHEN 4 THEN 0 ELSE 2 END AS n) s
      RETURNING 1)
    SELECT (SELECT count(*) FROM m) || '/' || (SELECT count(*) FROM b) || '/' || (SELECT count(*) FROM a) || '/' || (SELECT count(*) FROM d)")" ;;
esac
if [ "$SR_MADE" = "2/1/1/21" ]; then
  SR_BROKEN="$(sql "SELECT '$SR_TODAY'::date - 4")"
  SR_REST="$(sql "SELECT '$SR_TODAY'::date - 6")"
  SR_SHIFT="$(sql "SELECT '$SR_TODAY'::date - 5")"
  # The close job runs every five seconds; give it a few runs to evaluate the twenty days.
  for _ in $(seq 1 30); do
    [ "$(sql "SELECT count(*) FROM bond_days WHERE bond_id = '$SR_BOND' AND evaluated_at IS NOT NULL")" = 21 ] && break
    sleep 1
  done
  db_is "streak: the close job evaluated the twenty-one days" "$(sql "SELECT count(*) FROM bond_days WHERE bond_id = '$SR_BOND' AND evaluated_at IS NOT NULL")" "21"
  expect "streak: a run of sixteen ended, and today is the fourth of the next" 200 '"current":4' -- "$API/bonds/$SR_BOND/streak" -H "Authorization: Bearer $SR_A"
  SR_BODY="$LAST_BODY"
  is "streak: …the numbers, the ended run with the day that ended it" \
    "$(python3 -c 'import json,sys; b=json.loads(sys.argv[1]); print(b["current"], b["longest"], b["freezesAvailable"], b["lastRunLength"], b["lastRunBrokenOn"])' "$SR_BODY")" \
    "4 16 0 16 $SR_BROKEN"
  is "streak: …a rest day a freeze paid for, a date a zone change stepped over, and no reason on any other square" \
    "$(python3 -c 'import json,sys; b=json.loads(sys.argv[1]); print(sorted((d["date"], d["status"], d["reason"]) for d in b["days"] if d["reason"] or d["status"] == "FROZEN"), sum(1 for d in b["days"] if "reason" not in d))' "$SR_BODY")" \
    "[('$SR_REST', 'FROZEN', 'REST_DAY'), ('$SR_SHIFT', 'FROZEN', 'TIMEZONE_SHIFT')] 0"
  [[ "$SR_BODY" == *"{\"date\":\"$SR_BROKEN\",\"status\":\"MISSED\",\"reason\":null,\"settlesAt\":null}"* ]] && pass "streak: …the day that ended it is MISSED, with no reason" || fail "streak: broken day" "${SR_BODY:0:250}"
  db_is "streak: …and the job wrote the ended run on the projection, as recalculate would" "$(sql "SELECT last_run_length || '/' || last_run_broken_on FROM streak_states WHERE bond_id = '$SR_BOND'")" "16/$SR_BROKEN"
  expect "streak: the same for the other member" 200 '"lastRunLength":16' -- "$API/bonds/$SR_BOND/streak" -H "Authorization: Bearer $SR_B"
  is "streak: …the same bytes" "$LAST_BODY" "$SR_BODY"
  expect "streak: today's streak object is as it was, without the new fields" 200 '"streak":{"current":4,"longest":16,"freezesAvailable":0,"strictMode":false}' -- "$API/bonds/$SR_BOND/today" -H "Authorization: Bearer $SR_A"
else
  skip "streak: the ended run and the rest-day reasons (the days could not be put in by hand: $SR_MADE)"
fi

echo; echo "late entries — a Bond-day stays open twelve hours past its end (issue #132, BR-3a, ADR-0033 decision 15)"
flush_buckets
# What this section is for: the grace as the jar serves it, with the real close job running on
# its five-second schedule. No day ends during a run, so a fresh bond is moved back three days and
# into a region zone where it is now between 01:00 and 05:59 — yesterday ended hours ago and its
# grace has hours left, whatever the time the run starts. Then: the job writes the two days that
# are past their grace (EMPTY) and does NOT write yesterday, which nobody has opened; an entry
# sent now with an intendedAt before midnight counts for yesterday; one older than the grace lands
# on today; GET /today and GET /streak say when each open day settles; and after more runs of the
# job yesterday is still open, with the late entry on it.
verified_account "late-writer" "203.0.113.150"; LE_A="$ACCOUNT_ACCESS"
verified_account "late-reader" "203.0.113.151"; LE_B="$ACCOUNT_ACCESS"
expect "late entries: a bond" 201 '"status":"PENDING_MEMBER"' -- -X POST "$API/bonds" -H "Authorization: Bearer $LE_A" -d "$(bond_body "Us")"
LE_BOND="$(printf '%s' "$LAST_BODY" | jget id)"
LE_CODE="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['code'])" <<<"$LAST_BODY")"
expect "late entries: …joined" 200 '"status":"ACTIVE"' -- -X POST "$API/invites/$LE_CODE/accept" -H "Authorization: Bearer $LE_B"
LE_ZONE="$(python3 -c '
from datetime import datetime, timezone
from zoneinfo import ZoneInfo, available_timezones
now = datetime.now(timezone.utc)
for z in sorted(available_timezones()):
    if "/" not in z or z.startswith(("Etc/", "SystemV/", "US/", "posix/", "right/")):
        continue
    if 1 <= now.astimezone(ZoneInfo(z)).hour <= 5:
        print(z)
        break' 2>/dev/null || true)"
LE_MADE=no
if [ -n "$LE_ZONE" ]; then
  LE_MADE="$(sql "WITH m AS (UPDATE bond_members SET joined_at = joined_at - interval '3 days' WHERE bond_id = '$LE_BOND' RETURNING 1),
    b AS (UPDATE bonds SET created_at = created_at - interval '3 days', anchor_timezone = '$LE_ZONE' WHERE id = '$LE_BOND' RETURNING 1),
    a AS (UPDATE bond_anchor_intervals SET zone = '$LE_ZONE', effective_from = effective_from - interval '3 days',
            first_label = ((effective_from - interval '3 days') AT TIME ZONE '$LE_ZONE')::date WHERE bond_id = '$LE_BOND' RETURNING 1)
    SELECT (SELECT count(*) FROM m) || '/' || (SELECT count(*) FROM b) || '/' || (SELECT count(*) FROM a)")"
fi
if [ "$LE_MADE" = "2/1/1" ]; then
  LE_TODAY="$(sql "SELECT (now() AT TIME ZONE '$LE_ZONE')::date")"
  LE_YDAY="$(sql "SELECT (now() AT TIME ZONE '$LE_ZONE')::date - 1")"
  LE_OLDER="$(sql "SELECT (now() AT TIME ZONE '$LE_ZONE')::date - 2")"
  utc() { sql "SELECT to_char(($1) AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"')"; }
  LE_BEFORE_MIDNIGHT="$(utc "('$LE_TODAY'::date::timestamp - interval '30 minutes') AT TIME ZONE '$LE_ZONE'")"
  LE_TOO_OLD="$(utc "now() - interval '13 hours'")"
  LE_YDAY_SETTLES="$(utc "('$LE_TODAY'::date::timestamp AT TIME ZONE '$LE_ZONE') + interval '12 hours'")"
  LE_TODAY_SETTLES="$(utc "(('$LE_TODAY'::date + 1)::timestamp AT TIME ZONE '$LE_ZONE') + interval '12 hours'")"
  # The job runs every five seconds: wait for it to write the day past its grace, which is
  # also the run that would have written yesterday closed if the grace were not kept.
  for _ in $(seq 1 30); do
    [ "$(sql "SELECT count(*) FROM bond_days WHERE bond_id = '$LE_BOND' AND date = '$LE_OLDER' AND closed_at IS NOT NULL")" = 1 ] && break
    sleep 1
  done
  db_is "late entries: the job wrote the day before yesterday, past its grace, closed EMPTY ($LE_ZONE)" \
    "$(sql "SELECT status FROM bond_days WHERE bond_id = '$LE_BOND' AND date = '$LE_OLDER' AND closed_at IS NOT NULL")" "EMPTY"
  db_is "late entries: …and did not write yesterday, which nobody opened and whose grace has hours left" \
    "$(sql "SELECT count(*) FROM bond_days WHERE bond_id = '$LE_BOND' AND date = '$LE_YDAY'")" "0"
  expect "late entries: yesterday is OPEN on the calendar, saying when it settles" 200 "{\"date\":\"$LE_YDAY\",\"status\":\"OPEN\",\"reason\":null,\"settlesAt\":\"$LE_YDAY_SETTLES\"}" -- "$API/bonds/$LE_BOND/streak" -H "Authorization: Bearer $LE_B"
  expect "late entries: written before midnight, sent after it, the entry counts for yesterday" 201 "\"date\":\"$LE_YDAY\"" -- \
    -X POST "$API/bonds/$LE_BOND/entries" -H "Authorization: Bearer $LE_A" -H "Idempotency-Key: $(new_key)" \
    -d "{\"text\":\"written last night, sent this morning\",\"intendedAt\":\"$LE_BEFORE_MIDNIGHT\"}"
  is "late entries: …stamped with the instant it was written" "$(raw intendedAt)" "$LE_BEFORE_MIDNIGHT"
  expect "late entries: one written longer ago than the grace lands on today" 201 "\"date\":\"$LE_TODAY\"" -- \
    -X POST "$API/bonds/$LE_BOND/entries" -H "Authorization: Bearer $LE_B" -H "Idempotency-Key: $(new_key)" \
    -d "{\"text\":\"written yesterday afternoon\",\"intendedAt\":\"$LE_TOO_OLD\"}"
  expect "late entries: today says when it settles" 200 "\"bondDay\":{\"date\":\"$LE_TODAY\",\"status\":\"PARTIAL\",\"settlesAt\":\"$LE_TODAY_SETTLES\"}" -- "$API/bonds/$LE_BOND/today" -H "Authorization: Bearer $LE_A"
  # Two more runs of the job (ten seconds), then yesterday: still open, with the late entry on it.
  # As epoch seconds: `sql` strips whitespace, which a timestamp's own space would not survive.
  LE_RUNS_FROM="$(sql "SELECT extract(epoch FROM locked_at)::bigint FROM shedlock WHERE name = 'close-days'")"
  for _ in $(seq 1 30); do
    [ "$(sql "SELECT count(*) FROM shedlock WHERE name = 'close-days' AND extract(epoch FROM locked_at) > $LE_RUNS_FROM + 4")" = 1 ] && break
    sleep 1
  done
  db_is "late entries: the job has run since" "$(sql "SELECT count(*) FROM shedlock WHERE name = 'close-days' AND extract(epoch FROM locked_at) > $LE_RUNS_FROM + 4")" "1"
  db_is "late entries: …and left yesterday open inside its grace, PARTIAL with the late entry" \
    "$(sql "SELECT status || '/' || entry_count || '/' || (closed_at IS NULL) FROM bond_days WHERE bond_id = '$LE_BOND' AND date = '$LE_YDAY'")" "PARTIAL/1/true"
  # The archive lists an unsettled day only for a member who wrote on it (ADR-0036): to the partner it is not there yet.
  expect "late entries: …so the partner cannot read the lone entry yet: the day is not in their archive" 404 '"title":"Day not found"' -- "$API/bonds/$LE_BOND/days/$LE_YDAY" -H "Authorization: Bearer $LE_B"
  [[ "$LAST_BODY" == *"written last night"* ]] && fail "late entries: lone entry" "readable to the partner inside the grace: ${LAST_BODY:0:200}" || pass "late entries: …its words are not in the partner's view of the day"
else
  skip "late entries: the grace on the wire (the bond could not be moved by hand: ${LE_ZONE:-no zone at 01-05h}, $LE_MADE)"
fi

echo; echo "push tokens (Phase 4 N1, ADR-0044)"
# The token goes to the device of the caller's session and is kept by the
# notification module. Nothing requests a push before slice N3, and no route
# may trigger one, so this proves the route and the table, not a send.
flush_buckets
PUSH_TOKEN="smoke-push-token-$RANDOM$RANDOM"
expect "push: sign in on a phone" 200 '"refreshToken"' -- -X POST "$API/auth/login" -d "$(login_body "$EMAIL" "$NEW_PASSWORD")"
PUSH_ACCESS="$(printf '%s' "$LAST_BODY" | jget accessToken)"; PUSH_REFRESH="$(printf '%s' "$LAST_BODY" | jget refreshToken)"
expect "push: PUT a token for the current device is 204" 204 "" -- -X PUT "$API/me/devices/current/push-token" -H "Authorization: Bearer $PUSH_ACCESS" -d "{\"token\":\"$PUSH_TOKEN\"}"
db_is "push: …and it is kept for that session's device, as ANDROID" "$(sql "SELECT p.platform FROM push_tokens p JOIN refresh_tokens t ON t.device_id = p.device_id JOIN users u ON u.id = t.user_id WHERE u.email='$EMAIL' AND p.token='$PUSH_TOKEN' AND t.revoked_at IS NULL AND t.rotated_at IS NULL")" "ANDROID"
expect "push: PUT the same token again is 204, one row" 204 "" -- -X PUT "$API/me/devices/current/push-token" -H "Authorization: Bearer $PUSH_ACCESS" -d "{\"token\":\"$PUSH_TOKEN\"}"
db_is "push: …still one row" "$(sql "SELECT count(*) FROM push_tokens WHERE token='$PUSH_TOKEN'")" "1"
expect "push: a token with a space in it is 422 on token" 422 '"field":"token"' -- -X PUT "$API/me/devices/current/push-token" -H "Authorization: Bearer $PUSH_ACCESS" -d '{"token":"two words"}'
expect "push: DELETE the token is 204" 204 "" -- -X DELETE "$API/me/devices/current/push-token" -H "Authorization: Bearer $PUSH_ACCESS"
db_is "push: …and it is gone" "$(sql "SELECT count(*) FROM push_tokens WHERE token='$PUSH_TOKEN'")" "0"
expect "push: DELETE again is still 204" 204 "" -- -X DELETE "$API/me/devices/current/push-token" -H "Authorization: Bearer $PUSH_ACCESS"
expect "push: PUT it back" 204 "" -- -X PUT "$API/me/devices/current/push-token" -H "Authorization: Bearer $PUSH_ACCESS" -d "{\"token\":\"$PUSH_TOKEN\"}"
expect "push: logout is 204" 204 "" -- -X POST "$API/auth/logout" -d "{\"refreshToken\":\"$PUSH_REFRESH\"}"
db_is "push: …and the logout forgot the device's token" "$(sql "SELECT count(*) FROM push_tokens WHERE token='$PUSH_TOKEN'")" "0"
expect "push: PUT with the ended session's access token is 404 NOT_FOUND" 404 '"code":"NOT_FOUND"' -- -X PUT "$API/me/devices/current/push-token" -H "Authorization: Bearer $PUSH_ACCESS" -d "{\"token\":\"$PUSH_TOKEN\"}"
expect "push: DELETE with it is 404 NOT_FOUND too" 404 '"code":"NOT_FOUND"' -- -X DELETE "$API/me/devices/current/push-token" -H "Authorization: Bearer $PUSH_ACCESS"
expect "push: sign in without describing a device" 200 '"refreshToken"' -- -X POST "$API/auth/login" -d "{\"email\":\"$EMAIL\",\"password\":\"$NEW_PASSWORD\"}"
BARE_ACCESS="$(printf '%s' "$LAST_BODY" | jget accessToken)"; BARE_REFRESH="$(printf '%s' "$LAST_BODY" | jget refreshToken)"
expect "push: a session with no device has nowhere to keep a token: 404 NOT_FOUND" 404 '"code":"NOT_FOUND"' -- -X PUT "$API/me/devices/current/push-token" -H "Authorization: Bearer $BARE_ACCESS" -d "{\"token\":\"$PUSH_TOKEN\"}"
expect "push: …and DELETE from it is 404 NOT_FOUND" 404 '"code":"NOT_FOUND"' -- -X DELETE "$API/me/devices/current/push-token" -H "Authorization: Bearer $BARE_ACCESS"
expect "push: logout of the bare session" 204 "" -- -X POST "$API/auth/logout" -d "{\"refreshToken\":\"$BARE_REFRESH\"}"
expect "push: PUT without a bearer is 401" 401 '"code":"UNAUTHENTICATED"' -- -X PUT "$API/me/devices/current/push-token" -d "{\"token\":\"$PUSH_TOKEN\"}"
expect "push: DELETE without a bearer is 401" 401 '"code":"UNAUTHENTICATED"' -- -X DELETE "$API/me/devices/current/push-token"
# The local profile's push provider is the logging one; it and everything else must never print a token.
if grep -q "$PUSH_TOKEN" "$MOYI_LOG"; then fail "push: the token is in no log line" "found in $MOYI_LOG"; else pass "push: the token is in no log line"; fi
flush_buckets

echo; echo "push preferences, quiet hours and the daily reminder (Phase 4 N2, ADR-0045)"
# The toggles as a resource of their own, beside the settings it must survive;
# then the daily reminder on the wire. The run starts the reminder job and the
# release of held pushes every five seconds instead of every five minutes and
# every minute. The reminder job asks about the five minutes that ended on the
# last boundary, so both members' reminders are set to that boundary in a zone
# at UTC with no DST (Africa/Abidjan): the one who wrote today is not reminded,
# the one who did not is, and inside her quiet hours that push is held. Her
# night is then made to have passed, by hand on the push's own row, and the
# release hands it back and the logging provider takes it.
flush_buckets
verified_account "toggler" "203.0.113.140"; NB_A="$ACCOUNT_ACCESS"
verified_account "toggled" "203.0.113.141"; NB_B="$ACCOUNT_ACCESS"
verified_account "outsider-n" "203.0.113.142"; NB_O="$ACCOUNT_ACCESS"
expect "notifications: a bond for two is 201" 201 '"status":"PENDING_MEMBER"' -- -X POST "$API/bonds" -H "Authorization: Bearer $NB_A" -d "$(bond_body "Us")"
NB_BOND="$(printf '%s' "$LAST_BODY" | jget id)"
NB_MADE="$(date -u +%s)"
NB_CODE="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['code'])" <<<"$LAST_BODY")"
expect "notifications: readable while the bond waits for its second member" 200 '"reminder":true' -- "$API/bonds/$NB_BOND/members/me/notifications" -H "Authorization: Bearer $NB_A"
expect "notifications: …and settable while it waits" 200 '"proposal":true' -- -X PUT "$API/bonds/$NB_BOND/members/me/notifications" -H "Authorization: Bearer $NB_A" -d '{"categories":{"proposal":true}}'
expect "notifications: the other member joins it" 200 '"status":"ACTIVE"' -- -X POST "$API/invites/$NB_CODE/accept" -H "Authorization: Bearer $NB_B"
NB_ALL_ON='{"categories":{"reminder":true,"partnerJoined":true,"reveal":true,"milestone":true,"onThisDay":false,"proposal":true}}'
expect "notifications: a member who never chose has every category on but on-this-day" 200 '"onThisDay":false' -- "$API/bonds/$NB_BOND/members/me/notifications" -H "Authorization: Bearer $NB_A"
is "notifications: …exactly that" "$LAST_BODY" "$NB_ALL_ON"
expect "notifications: a PUT sets what it is sent" 200 '"onThisDay":true' -- -X PUT "$API/bonds/$NB_BOND/members/me/notifications" -H "Authorization: Bearer $NB_A" -d '{"categories":{"onThisDay":true,"milestone":false}}'
expect "notifications: …and a later one keeps what it is not sent" 200 '"milestone":false' -- -X PUT "$API/bonds/$NB_BOND/members/me/notifications" -H "Authorization: Bearer $NB_A" -d '{"categories":{"partnerJoined":false}}'
is "notifications: …all three as set" "$LAST_BODY" '{"categories":{"reminder":true,"partnerJoined":false,"reveal":true,"milestone":false,"onThisDay":true,"proposal":true}}'
expect "notifications: the settings PUT, which clears what it is not sent, is 200" 200 '"reminderTimeLocal":"21:00"' -- -X PUT "$API/bonds/$NB_BOND/members/me/settings" -H "Authorization: Bearer $NB_A" -d '{"reminderTimeLocal":"21:00"}'
expect "notifications: …and leaves the toggles alone" 200 '"onThisDay":true' -- "$API/bonds/$NB_BOND/members/me/notifications" -H "Authorization: Bearer $NB_A"
expect "notifications: the other member's are their own, still the defaults" 200 '"onThisDay":false' -- "$API/bonds/$NB_BOND/members/me/notifications" -H "Authorization: Bearer $NB_B"
is "notifications: …exactly" "$LAST_BODY" "$NB_ALL_ON"
expect "notifications: a PUT without categories is 422 on categories" 422 '"field":"categories"' -- -X PUT "$API/bonds/$NB_BOND/members/me/notifications" -H "Authorization: Bearer $NB_A" -d '{}'
expect "notifications: a toggle that is not a yes or no is 400" 400 '"code":"MALFORMED_REQUEST"' -- -X PUT "$API/bonds/$NB_BOND/members/me/notifications" -H "Authorization: Bearer $NB_A" -d '{"categories":{"reveal":"sometimes"}}'
expect "notifications: a stranger reading them is told the bond's 404" 404 '"code":"NOT_FOUND"' -- "$API/bonds/$NB_BOND/members/me/notifications" -H "Authorization: Bearer $NB_O"
expect "notifications: …and writing them too" 404 '"code":"NOT_FOUND"' -- -X PUT "$API/bonds/$NB_BOND/members/me/notifications" -H "Authorization: Bearer $NB_O" -d '{"categories":{"onThisDay":true}}'
expect "notifications: without a bearer is 401" 401 '"code":"UNAUTHENTICATED"' -- "$API/bonds/$NB_BOND/members/me/notifications"
# Back to the defaults for the reminder below: only the reminder toggle matters to it.
expect "notifications: reminders stay on" 200 '"reminder":true' -- -X PUT "$API/bonds/$NB_BOND/members/me/notifications" -H "Authorization: Bearer $NB_A" -d '{"categories":{"reminder":true}}'

# The daily reminder. Its boundary must come after the bond was made (a reminder that fell before
# its bond began is no reminder: ADR-0045), and a window more than four minutes old would close
# before the job ran again. Up to five minutes' wait, the one long pause of this script.
while [ $(( $(date -u +%s) - $(date -u +%s) % 300 )) -le "$NB_MADE" ]; do sleep 5; done
[ "$(( $(date -u +%s) % 300 ))" -lt 240 ] || sleep $(( 300 - $(date -u +%s) % 300 + 1 ))
NB_AT="$(python3 -c 'import time; t=int(time.time()); t-=t%300; print(time.strftime("%H:%M", time.gmtime(t)))')"
NB_QUIET_FROM="$(python3 -c 'import time; print(time.strftime("%H:%M", time.gmtime(time.time()-3600)))')"
NB_QUIET_TO="$(python3 -c 'import time; print(time.strftime("%H:%M", time.gmtime(time.time()+3600)))')"
NB_TOKEN="smoke-push-token-n2-$RANDOM$RANDOM"
# He writes first: set before, his reminder could fall between the setting and the entry.
expect "reminder: he writes today" 201 '"status":"SUBMITTED"' -- -X POST "$API/bonds/$NB_BOND/entries" -H "Authorization: Bearer $NB_B" -H "Idempotency-Key: $(python3 -c 'import uuid; print(uuid.uuid4())')" -d '{"text":"thank you for the reminder I did not need"}'
expect "reminder: she registers her phone" 204 "" -- -X PUT "$API/me/devices/current/push-token" -H "Authorization: Bearer $NB_A" -d "{\"token\":\"$NB_TOKEN\"}"
expect "reminder: hers at the window's end, with quiet hours around now" 200 "\"quietHoursStart\":\"$NB_QUIET_FROM\"" -- -X PUT "$API/bonds/$NB_BOND/members/me/settings" -H "Authorization: Bearer $NB_A" -d "{\"reminderTimeLocal\":\"$NB_AT\",\"reminderTimezone\":\"Africa/Abidjan\",\"quietHoursStart\":\"$NB_QUIET_FROM\",\"quietHoursEnd\":\"$NB_QUIET_TO\"}"
expect "reminder: his at the same time, no quiet hours" 200 "\"reminderTimeLocal\":\"$NB_AT\"" -- -X PUT "$API/bonds/$NB_BOND/members/me/settings" -H "Authorization: Bearer $NB_B" -d "{\"reminderTimeLocal\":\"$NB_AT\",\"reminderTimezone\":\"Africa/Abidjan\"}"
NB_A_ROW=""
# Up to forty seconds: the reminder job is timed every five here, but its ShedLock holds for
# at least thirty (lockAtLeastFor), so a setting saved just after a run waits for the next
# one up to thirty seconds later. Twenty was too short once (the N3 merge's first run).
for _ in $(seq 1 80); do
  NB_A_ROW="$(sql "SELECT n.status FROM notifications n JOIN bond_members m ON m.user_id = n.user_id AND m.bond_id = n.bond_id WHERE n.bond_id = '$NB_BOND' AND n.category = 'REMINDER' AND m.role = 'OWNER'")"
  [ "$NB_A_ROW" = "HELD" ] || [ "$NB_A_ROW" = "psql-unavailable" ] && break
  sleep 0.5
done
db_is "reminder: she had not written, so hers was asked for, and her quiet hours hold it" "$NB_A_ROW" "HELD"
db_is "reminder: …held until her quiet hours end" "$(sql "SELECT to_char(not_before AT TIME ZONE 'UTC', 'HH24:MI') FROM notifications WHERE bond_id = '$NB_BOND' AND category = 'REMINDER'")" "$NB_QUIET_TO"
db_is "reminder: he wrote, so none was asked for him" "$(sql "SELECT count(*) FROM notifications n JOIN bond_members m ON m.user_id = n.user_id AND m.bond_id = n.bond_id WHERE n.bond_id = '$NB_BOND' AND m.role = 'MEMBER'")" "0"
db_is "reminder: its key is the member and the bond-day" "$(sql "SELECT dedupe_key LIKE 'reminder:%:' || to_char(now() AT TIME ZONE 'Africa/Lagos', 'YYYY-MM-DD') FROM notifications WHERE bond_id = '$NB_BOND' AND category = 'REMINDER'")" "t"
sleep 6
db_is "reminder: the job ran again on the same window and asked for nothing twice" "$(sql "SELECT count(*) FROM notifications WHERE bond_id = '$NB_BOND' AND category = 'REMINDER'")" "1"
# Her night passes, by hand: the hold ends now, and the window it was read under is gone.
sql "UPDATE notifications SET not_before = now() - interval '1 second', quiet_hours_start = NULL, quiet_hours_end = NULL WHERE bond_id = '$NB_BOND' AND status = 'HELD'" >/dev/null
NB_SENT=""
for _ in $(seq 1 40); do
  NB_SENT="$(sql "SELECT status || '|' || coalesce(provider_message_id, '') || '|' || reserved FROM notifications WHERE bond_id = '$NB_BOND' AND category = 'REMINDER'")"
  [[ "$NB_SENT" == SENT* ]] || [ "$NB_SENT" = "psql-unavailable" ] && break
  sleep 0.5
done
db_is "reminder: released when her hours end, and taken by the logging provider, out of the remainder" "$NB_SENT" "SENT|not-sent:log|false"
if grep -q "$NB_TOKEN" "$MOYI_LOG"; then fail "reminder: the token is in no log line" "found in $MOYI_LOG"; else pass "reminder: the token is in no log line"; fi
if grep -qE '"quietHours|quiet_hours' "$MOYI_LOG"; then fail "reminder: her quiet hours are in no log line" "found in $MOYI_LOG"; else pass "reminder: her quiet hours are in no log line"; fi
flush_buckets

echo; echo "the pushes a reveal and a join make (Phase 4 N3, ADR-0046)"
# The same two members. He wrote today above and she has not; both phones are registered, and
# her quiet hours are cleared, so her entry, the second, reveals the day and the outbox tells
# each of them once, through the logging provider. When he joined the bond she was asked one
# push, under the join's key, and him none. How hers settled depends on a race this script runs
# on purpose: she turned partner-joined pushes off within a second or two of his joining, and the
# consumer reads her settings when it runs (ADR-0045 decision 2), so it is SUPPRESSED if her
# PUT came first and FAILED_PERMANENT (no phone yet) if it did not. Never SENT.
NJ_ROW="$(sql "SELECT (n.status IN ('SUPPRESSED', 'FAILED_PERMANENT')) || '|' || (n.dedupe_key = 'joined:' || n.bond_id || ':' || n.user_id) FROM notifications n JOIN bond_members m ON m.user_id = n.user_id AND m.bond_id = n.bond_id WHERE n.bond_id = '$NB_BOND' AND n.category = 'PARTNER_JOINED' AND m.role = 'OWNER'")"
db_is "joined: the member who was there was asked one push, under the join's key, and it was not sent" "$NJ_ROW" "true|true"
db_is "joined: …and the one who joined was asked none" "$(sql "SELECT count(*) FROM notifications WHERE bond_id = '$NB_BOND' AND category = 'PARTNER_JOINED'")" "1"
NR_TOKEN="smoke-push-token-n3-$RANDOM$RANDOM"
expect "reveal push: he registers his phone" 204 "" -- -X PUT "$API/me/devices/current/push-token" -H "Authorization: Bearer $NB_B" -d "{\"token\":\"$NR_TOKEN\"}"
expect "reveal push: her quiet hours are cleared" 200 '"quietHoursStart":null' -- -X PUT "$API/bonds/$NB_BOND/members/me/settings" -H "Authorization: Bearer $NB_A" -d "{\"reminderTimeLocal\":\"$NB_AT\",\"reminderTimezone\":\"Africa/Abidjan\"}"
NR_WORDS="the second entry of the reveal push $RANDOM"
expect "reveal push: she writes the second entry, and the day is open" 201 "" -- -X POST "$API/bonds/$NB_BOND/entries" -H "Authorization: Bearer $NB_A" -H "Idempotency-Key: $(python3 -c 'import uuid; print(uuid.uuid4())')" -d "{\"text\":\"$NR_WORDS\"}"
NR_SENT=""
for _ in $(seq 1 40); do
  NR_SENT="$(sql "SELECT count(DISTINCT user_id) || '|' || count(*) FROM notifications WHERE bond_id = '$NB_BOND' AND category = 'REVEAL' AND status = 'SENT'")"
  [ "$NR_SENT" = "2|2" ] || [ "$NR_SENT" = "psql-unavailable" ] && break
  sleep 0.5
done
db_is "reveal push: one push SENT to each member, and no more" "$NR_SENT" "2|2"
db_is "reveal push: …each under its reveal key" "$(sql "SELECT count(*) FROM notifications n JOIN bond_days d ON n.dedupe_key = 'reveal:' || d.id || ':' || n.user_id WHERE n.bond_id = '$NB_BOND' AND n.category = 'REVEAL'")" "2"
# Issue #146: the row keeps the revealed day's own date, which the consumer sends as `data.date`.
# The log provider's line leaves the payload out, so the ledger is where a run can see it.
db_is "reveal push: …each naming the day it revealed (bond_date, sent as data.date)" "$(sql "SELECT count(*) FROM notifications n JOIN bond_days d ON n.dedupe_key = 'reveal:' || d.id || ':' || n.user_id WHERE n.bond_id = '$NB_BOND' AND n.category = 'REVEAL' AND n.bond_date = d.date")" "2"
# The ledger's SENT is committed by the delivery that logged the send, so the lines are written by
# now; but a log appender may hand them to the file a moment later. Polled, never loosened: two
# lines exactly. On a failure, what the ledger and the log said (the smoke run of D4 once saw 0).
NR_LOGGED=0
for _ in $(seq 1 20); do
  NR_LOGGED="$(grep -c "Push NOT sent (provider=log): category=REVEAL bond=$NB_BOND" "$MOYI_LOG" || true)"
  [ "$NR_LOGGED" = "2" ] && break
  sleep 0.5
done
if [ "$NR_LOGGED" = "2" ]; then pass "reveal push: two sends logged by the logging provider"
else
  fail "reveal push: two sends logged by the logging provider" "found $NR_LOGGED; the ledger said $NR_SENT; lines: $(grep "Push NOT sent (provider=log)" "$MOYI_LOG" | grep -o "category=[A-Z_]* bond=[0-9a-f-]*" | tail -6 | tr '\n' ' ')"
fi
db_is "reveal push: no solo, milestone or streak push came of it" "$(sql "SELECT count(*) FROM notifications WHERE bond_id = '$NB_BOND' AND category NOT IN ('REMINDER', 'PARTNER_JOINED', 'REVEAL')")" "0"
if grep -q "$NR_WORDS" "$MOYI_LOG"; then fail "reveal push: her words are in no log line" "found in $MOYI_LOG"; else pass "reveal push: her words are in no log line"; fi
if grep -q "$NR_TOKEN" "$MOYI_LOG"; then fail "reveal push: his token is in no log line" "found in $MOYI_LOG"; else pass "reveal push: his token is in no log line"; fi
flush_buckets

echo; echo "media — an upload straight to the storage, and the four /media routes (Phase 4 D1, ADR-0047)"
# The bytes go to MinIO (standing in for R2) on the URL the intent hands back,
# never through this server (FR-044). The local profile points the app at
# docker-compose.yml's MinIO and its moyi-media bucket; with --attach, start the
# server against a MinIO that has that bucket. The file is a few bytes that
# start like an Ogg voice note. Since D3 the pipeline takes voice notes up too
# (it fails these bytes, which are no Ogg), so every probe here that reads the
# asset after its completion holds whatever the pipeline has made of it by then:
# the object is looked for in MinIO before the completion, and after the delete
# under every key the asset may have. The photos and voice notes sections below
# have the pipeline.
verified_account "uploader" "203.0.113.120"; MEDIA_ACCESS="$ACCOUNT_ACCESS"
verified_account "onlooker" "203.0.113.121"; ONLOOKER_ACCESS="$ACCOUNT_ACCESS"
MEDIA_FILE="$(mktemp)"
printf 'OggS fake smoke voice note %s' "$RANDOM" > "$MEDIA_FILE"
MEDIA_SIZE="$(wc -c < "$MEDIA_FILE" | tr -d '[:space:]')"
MEDIA_SUM="$(openssl dgst -sha256 -binary "$MEDIA_FILE" | base64)"
media_intent() { printf '{"kind":"%s","contentType":"%s","sizeBytes":%s,"checksum":"%s"}' "$1" "$2" "$3" "$MEDIA_SUM"; }
expect "media: an intent for a voice note is 201 with an upload URL" 201 '"uploadUrl"' -- -X POST "$API/media/upload-intent" -H "Authorization: Bearer $MEDIA_ACCESS" -d "$(media_intent VOICE audio/ogg "$MEDIA_SIZE")"
MEDIA_ID="$(printf '%s' "$LAST_BODY" | jget mediaId)"
MEDIA_URL="$(printf '%s' "$LAST_BODY" | jget uploadUrl)"
MEDIA_CHECKSUM_HEADER="$(printf '%s' "$LAST_BODY" | python3 -c "import json,sys; print(json.load(sys.stdin)['requiredHeaders']['x-amz-checksum-sha256'])")"
db_is "media: …the asset waits for its upload" "$(sql "SELECT status FROM media_assets WHERE id='$MEDIA_ID'")" "PENDING_UPLOAD"
db_is "media: …under a key that names nobody" "$(sql "SELECT (storage_key ~ '^in/[0-9a-f-]{36}$' AND storage_key NOT LIKE '%$MEDIA_ID%') FROM media_assets WHERE id='$MEDIA_ID'")" "t"
expect "media: the owner reads it while it waits, PENDING_UPLOAD" 200 '"status":"PENDING_UPLOAD"' -- "$API/media/$MEDIA_ID" -H "Authorization: Bearer $MEDIA_ACCESS"
expect "media: completing before the PUT is 409 MEDIA_NOT_UPLOADED" 409 '"code":"MEDIA_NOT_UPLOADED"' -- -X POST "$API/media/$MEDIA_ID/complete" -H "Authorization: Bearer $MEDIA_ACCESS" -H "Idempotency-Key: $(uuidgen)"
# The upload, as a phone makes it: straight to the storage, with the signed headers.
PUT_FORGED="$(mktemp)"; cp "$MEDIA_FILE" "$PUT_FORGED"; printf 'X' >> "$PUT_FORGED"
PUT_STATUS="$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$MEDIA_URL" -H 'Content-Type: audio/ogg' -H "x-amz-checksum-sha256: $MEDIA_CHECKSUM_HEADER" --data-binary "@$PUT_FORGED")"
case "$PUT_STATUS" in 400|403) pass "media: the storage refuses a body other than the one declared ($PUT_STATUS)";; *) fail "media: forged body" "expected 400 or 403 from the storage, got $PUT_STATUS";; esac
PUT_STATUS="$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$MEDIA_URL" -H 'Content-Type: audio/ogg' -H "x-amz-checksum-sha256: $MEDIA_CHECKSUM_HEADER" --data-binary "@$MEDIA_FILE")"
[ "$PUT_STATUS" = 200 ] && pass "media: the storage takes the declared bytes (200)" || fail "media: PUT" "expected 200 from the storage, got $PUT_STATUS"
MEDIA_OBJECT="$(sql "SELECT storage_key FROM media_assets WHERE id='$MEDIA_ID'")"
minio_has() { docker compose exec -T minio sh -c "mc alias set smoke http://localhost:9000 \"\$MINIO_ROOT_USER\" \"\$MINIO_ROOT_PASSWORD\" >/dev/null && mc stat smoke/moyi-media/$1" >/dev/null 2>&1; }
if [ "$PSQL_OK" = 1 ] && docker compose exec -T minio true >/dev/null 2>&1; then MINIO_OK=1; else MINIO_OK=0; fi
if [ "$MINIO_OK" = 0 ]; then skip "media: the object is in MinIO before the delete (MinIO or psql not reachable through docker compose)"
elif minio_has "$MEDIA_OBJECT"; then pass "media: the object is in MinIO before the delete"; else fail "media: object before delete" "MinIO has nothing under the asset's key"; fi
# No public bucket (NFR-047): while the object exists, its address without a
# signature is refused. 403 only: a 200 is a public bucket, a 404 a wrong key.
UNSIGNED_STATUS="$(curl -s -o /dev/null -w '%{http_code}' "${MEDIA_URL%%\?*}")"
[ "$UNSIGNED_STATUS" = 403 ] && pass "media: an unsigned GET of the stored object is refused (403)" || fail "media: public bucket" "an unsigned GET of a stored object answered $UNSIGNED_STATUS"
MEDIA_KEY="$(uuidgen)"
expect "media: completing after the PUT is 200 UPLOADED" 200 '"status":"UPLOADED"' -- -X POST "$API/media/$MEDIA_ID/complete" -H "Authorization: Bearer $MEDIA_ACCESS" -H "Idempotency-Key: $MEDIA_KEY"
db_is "media: …and one MediaUploaded event was recorded" "$(sql "SELECT count(*) FROM outbox_events WHERE event_type='MediaUploaded' AND aggregate_id='$MEDIA_ID'")" "1"
# The replay answers with the asset as it is now, and since D3 a voice note is processed: by the
# time the key is sent again, media.process may already have taken it up (PROCESSING) or failed
# these 32 made-up bytes (FAILED). Seen in D4's smoke runs once the outbox poller was no longer
# slowed by a stray process. The replay is the same asset, answered 200 and marked as a replay.
expect "media: the same key again is a replay of the same asset" 200 "\"id\":\"$MEDIA_ID\"" -- -X POST "$API/media/$MEDIA_ID/complete" -H "Authorization: Bearer $MEDIA_ACCESS" -H "Idempotency-Key: $MEDIA_KEY"
header_is "media: …marked as one" Idempotency-Replayed true
expect "media: completion without an Idempotency-Key is 422" 422 '"code":"VALIDATION_FAILED"' -- -X POST "$API/media/$MEDIA_ID/complete" -H "Authorization: Bearer $MEDIA_ACCESS"
expect "media: the owner reads it" 200 '"kind":"VOICE"' -- "$API/media/$MEDIA_ID" -H "Authorization: Bearer $MEDIA_ACCESS"
expect "media: another user's GET is 404 NOT_FOUND" 404 '"code":"NOT_FOUND"' -- "$API/media/$MEDIA_ID" -H "Authorization: Bearer $ONLOOKER_ACCESS"
STRANGER_MEDIA="$LAST_BODY"
expect "media: an id that names nothing is 404 NOT_FOUND" 404 '"code":"NOT_FOUND"' -- "$API/media/$(uuidgen | tr 'A-Z' 'a-z')" -H "Authorization: Bearer $MEDIA_ACCESS"
[ "$(printf '%s' "$STRANGER_MEDIA" | sed 's/"instance":"[^"]*"//')" = "$(printf '%s' "$LAST_BODY" | sed 's/"instance":"[^"]*"//')" ] \
  && pass "media: …the same answer as another user's id" || fail "media: one 404" "another user's id and an unknown id are answered differently"
expect "media: another user's completion is 404 NOT_FOUND" 404 '"code":"NOT_FOUND"' -- -X POST "$API/media/$MEDIA_ID/complete" -H "Authorization: Bearer $ONLOOKER_ACCESS" -H "Idempotency-Key: $(uuidgen)"
expect "media: another user's DELETE is 404 NOT_FOUND" 404 '"code":"NOT_FOUND"' -- -X DELETE "$API/media/$MEDIA_ID" -H "Authorization: Bearer $ONLOOKER_ACCESS"
expect "media: a GIF is 415 MEDIA_TYPE_UNSUPPORTED" 415 '"code":"MEDIA_TYPE_UNSUPPORTED"' -- -X POST "$API/media/upload-intent" -H "Authorization: Bearer $MEDIA_ACCESS" -d "$(media_intent IMAGE image/gif 100)"
expect "media: a photo over 10 MB is 413 MEDIA_QUOTA_EXCEEDED" 413 '"code":"MEDIA_QUOTA_EXCEEDED"' -- -X POST "$API/media/upload-intent" -H "Authorization: Bearer $MEDIA_ACCESS" -d "$(media_intent IMAGE image/png 10485761)"
expect "media: an intent with no checksum is 422 on checksum" 422 '"field":"checksum"' -- -X POST "$API/media/upload-intent" -H "Authorization: Bearer $MEDIA_ACCESS" -d '{"kind":"VOICE","contentType":"audio/ogg","sizeBytes":100}'
# Media requires a verified email (the owner's ruling of 2026-10-29, ADR-0049): an
# account registered and signed in but never verified is refused the two routes
# that make media, with bond's FR-002 answer, and not the two that read and delete.
MEDIA_UNVERIFIED="media-unverified-$(date +%s)-$RANDOM@example.com"
expect "media: register an account that will not verify" 201 "" -- -X POST "$API/auth/register" -H 'X-Forwarded-For: 203.0.113.122' -d "$(register_body "$MEDIA_UNVERIFIED")"
expect "media: …it signs in, unverified" 200 '"emailVerified":false' -- -X POST "$API/auth/login" -d "$(login_body "$MEDIA_UNVERIFIED" "$PASSWORD")"
UNVERIFIED_ACCESS="$(printf '%s' "$LAST_BODY" | jget accessToken)"
expect "media: an unverified account's intent is 403 EMAIL_NOT_VERIFIED" 403 '"code":"EMAIL_NOT_VERIFIED"' -- -X POST "$API/media/upload-intent" -H "Authorization: Bearer $UNVERIFIED_ACCESS" -d "$(media_intent VOICE audio/ogg "$MEDIA_SIZE")"
db_is "media: …and no asset is recorded for it" "$(sql "SELECT count(*) FROM media_assets a JOIN users u ON u.id = a.owner_user_id WHERE u.email = '$MEDIA_UNVERIFIED'")" "0"
expect "media: an unverified account's completion is 403 EMAIL_NOT_VERIFIED, whatever the id" 403 '"code":"EMAIL_NOT_VERIFIED"' -- -X POST "$API/media/$(uuidgen | tr 'A-Z' 'a-z')/complete" -H "Authorization: Bearer $UNVERIFIED_ACCESS" -H "Idempotency-Key: $(uuidgen)"
expect "media: an unverified account's read is not refused for it: an unknown id is 404" 404 '"code":"NOT_FOUND"' -- "$API/media/$(uuidgen | tr 'A-Z' 'a-z')" -H "Authorization: Bearer $UNVERIFIED_ACCESS"
expect "media: …nor its delete: 404" 404 '"code":"NOT_FOUND"' -- -X DELETE "$API/media/$(uuidgen | tr 'A-Z' 'a-z')" -H "Authorization: Bearer $UNVERIFIED_ACCESS"
expect "media: the owner deletes it, 204" 204 "" -- -X DELETE "$API/media/$MEDIA_ID" -H "Authorization: Bearer $MEDIA_ACCESS"
if [ "$MINIO_OK" = 0 ]; then skip "media: …and the object is gone from MinIO"
elif minio_has "$MEDIA_OBJECT" || minio_has "v/${MEDIA_OBJECT#in/}/voice.ogg" || minio_has "v/${MEDIA_OBJECT#in/}/voice.m4a"; then fail "media: object after delete" "MinIO still holds an object of the deleted asset"; else pass "media: …and the object is gone from MinIO"; fi
db_is "media: …the row says DELETED" "$(sql "SELECT status FROM media_assets WHERE id='$MEDIA_ID'")" "DELETED"
expect "media: a deleted asset is 404 to its owner" 404 '"code":"NOT_FOUND"' -- "$API/media/$MEDIA_ID" -H "Authorization: Bearer $MEDIA_ACCESS"
if grep -q "X-Amz-Signature=$(printf '%s' "$MEDIA_URL" | sed -E 's/.*X-Amz-Signature=([0-9a-f]+).*/\1/')" "$MOYI_LOG"; then
  fail "media: the upload URL is in no log line" "its signature is in $MOYI_LOG"
else pass "media: the upload URL is in no log line"; fi
rm -f "$MEDIA_FILE" "$PUT_FORGED"
flush_buckets

echo; echo "photos — processed by libvips, attached to an entry, shown to each member (Phase 4 D2, ADR-0048)"
# The booted jar's outbox poller runs media.process (the local profile turns on
# NATIVE, so libvips' command-line tools and ffmpeg must be on PATH; with
# --attach, start the server with them). The photo is made here: a JPEG from libvips, with an EXIF
# block written in by hand that says where it was taken (51° 30′ 12.34″ N, made
# up). What comes back at the URL must carry none of it.
PHOTO_DIR="$(mktemp -d)"
vips black "$PHOTO_DIR/plain.jpg" 64 48 --bands 3
python3 - "$PHOTO_DIR/plain.jpg" "$PHOTO_DIR/photo.jpg" <<'PY'
import struct, sys
jpeg = open(sys.argv[1], "rb").read()
tiff = b"MM" + struct.pack(">HI", 42, 8)
tiff += struct.pack(">H", 1) + struct.pack(">HHII", 0x8825, 4, 1, 26) + struct.pack(">I", 0)
tiff += struct.pack(">H", 2)
tiff += struct.pack(">HHI", 1, 2, 2) + b"N\x00\x00\x00"
tiff += struct.pack(">HHII", 2, 5, 3, 56) + struct.pack(">I", 0)
tiff += struct.pack(">6I", 51, 1, 30, 1, 1234, 100)
body = b"Exif\x00\x00" + tiff
open(sys.argv[2], "wb").write(jpeg[:2] + b"\xff\xe1" + struct.pack(">H", len(body) + 2) + body + jpeg[2:])
PY
vips black "$PHOTO_DIR/plain.png" 32 32 --bands 3
LATITUDE_HEX="$(python3 -c 'import struct; print(struct.pack(">6I", 51, 1, 30, 1, 1234, 100).hex())')"
# has_bytes <file> <hex> — whether the file holds those bytes anywhere.
has_bytes() { python3 -c "import sys; sys.exit(0 if bytes.fromhex(sys.argv[2]) in open(sys.argv[1], 'rb').read() else 1)" "$1" "$2"; }
# upload_photo <access> <file> <declared type> [kind] — intent, PUT straight to MinIO, completion; leaves the id in
# PHOTO_ID. The kind is IMAGE unless named (the voice notes section names VOICE).
upload_photo() {
  local access="$1" file="$2" type="$3" kind="${4:-IMAGE}" size sum url header intent
  intent="$(mktemp)"
  size="$(wc -c < "$file" | tr -d '[:space:]')"; sum="$(openssl dgst -sha256 -binary "$file" | base64)"
  curl -s -X POST "$API/media/upload-intent" -H "Authorization: Bearer $access" -H 'Content-Type: application/json' \
    -d "{\"kind\":\"$kind\",\"contentType\":\"$type\",\"sizeBytes\":$size,\"checksum\":\"$sum\"}" > "$intent"
  PHOTO_ID="$(jget mediaId < "$intent")"; url="$(jget uploadUrl < "$intent")"
  header="$(python3 -c "import json,sys; print(json.load(sys.stdin)['requiredHeaders']['x-amz-checksum-sha256'])" < "$intent")"
  rm -f "$intent"
  curl -s -o /dev/null -X PUT "$url" -H "Content-Type: $type" -H "x-amz-checksum-sha256: $header" --data-binary "@$file"
  curl -s -o /dev/null -X POST "$API/media/$PHOTO_ID/complete" -H "Authorization: Bearer $access" -H "Idempotency-Key: $(uuidgen)"
}
# await_verdict <id> — until moderation has given it a verdict (at most 20 s); prints it, or none (D4).
await_verdict() {
  local verdict=""
  for _ in $(seq 1 40); do
    verdict="$(sql "SELECT coalesce(moderation_verdict, 'none') FROM media_assets WHERE id='$1'")"
    [ "$verdict" = "none" ] || break
    sleep 0.5
  done
  printf '%s' "$verdict"
}
# await_photo <access> <id> — until its status is no longer UPLOADED or PROCESSING (at most 40 s); prints it.
await_photo() {
  local status=""
  for _ in $(seq 1 80); do
    status="$(curl -s "$API/media/$2" -H "Authorization: Bearer $1" | jget status)"
    case "$status" in UPLOADED|PROCESSING) sleep 0.5 ;; *) break ;; esac
  done
  printf '%s' "$status"
}
verified_account "photographer" "203.0.113.150"; PH_A="$ACCOUNT_ACCESS"
verified_account "viewer" "203.0.113.151"; PH_B="$ACCOUNT_ACCESS"
verified_account "outsider-p" "203.0.113.152"; PH_O="$ACCOUNT_ACCESS"

has_bytes "$PHOTO_DIR/photo.jpg" "$LATITUDE_HEX" && pass "photos: the JPEG uploaded carries a GPS latitude in its EXIF" || fail "photos: fixture" "the made-up latitude is not in the JPEG"
upload_photo "$PH_A" "$PHOTO_DIR/photo.jpg" image/jpeg; PH_PHOTO="$PHOTO_ID"
PH_UPLOAD_KEY="$(sql "SELECT storage_key FROM media_assets WHERE id='$PH_PHOTO'")"
is "photos: an uploaded photo becomes READY" "$(await_photo "$PH_A" "$PH_PHOTO")" "READY"
expect "photos: …and its owner is given a URL and its size" 200 '"width":64' -- "$API/media/$PH_PHOTO" -H "Authorization: Bearer $PH_A"
PH_URL="$(printf '%s' "$LAST_BODY" | jget url)"
expect "photos: …the same URL on a second read in the same hour" 200 "\"url\":\"$PH_URL\"" -- "$API/media/$PH_PHOTO" -H "Authorization: Bearer $PH_A"
curl -s -o "$PHOTO_DIR/served.webp" "$PH_URL"
[ "$(head -c 4 "$PHOTO_DIR/served.webp")" = "RIFF" ] && [ "$(dd if="$PHOTO_DIR/served.webp" bs=1 skip=8 count=4 2>/dev/null)" = "WEBP" ] \
  && pass "photos: the image at its URL is a WebP" || fail "photos: served format" "the bytes at the URL are not a WebP"
if has_bytes "$PHOTO_DIR/served.webp" "$LATITUDE_HEX" || has_bytes "$PHOTO_DIR/served.webp" "$(printf 'Exif' | xxd -p)"; then
  fail "photos: …with no EXIF and no GPS position in it" "the served WebP still holds EXIF or the latitude"
else pass "photos: …with no EXIF and no GPS position in it"; fi
if [ "$MINIO_OK" = 0 ]; then skip "photos: the upload itself is gone from MinIO once the photo is ready"
elif minio_has "$PH_UPLOAD_KEY"; then fail "photos: the upload itself is gone from MinIO once the photo is ready" "MinIO still holds the original bytes"
else pass "photos: the upload itself is gone from MinIO once the photo is ready"; fi
db_is "photos: …and two variants are recorded" "$(sql "SELECT jsonb_array_length(variants) FROM media_assets WHERE id='$PH_PHOTO'")" "2"

upload_photo "$PH_A" "$PHOTO_DIR/plain.png" image/jpeg; PH_FAKED="$PHOTO_ID"
is "photos: a PNG declared as a JPEG becomes FAILED" "$(await_photo "$PH_A" "$PH_FAKED")" "FAILED"
expect "photos: …which its owner reads as FAILED, with no URL" 200 '"status":"FAILED","contentType":"image/jpeg","sizeBytes":' -- "$API/media/$PH_FAKED" -H "Authorization: Bearer $PH_A"
case "$LAST_BODY" in *'"url":null'*) pass "photos: …the URL is null";; *) fail "photos: …the URL is null" "${LAST_BODY:0:200}";; esac
if grep -q "Media $PH_FAKED failed: NOT_THE_DECLARED_FORMAT" "$MOYI_LOG"; then pass "photos: …and the log says why, by id and rule"; else fail "photos: failure logged" "no line names $PH_FAKED and NOT_THE_DECLARED_FORMAT"; fi

expect "photos: a bond for the photographer and the viewer is 201" 201 '"status":"PENDING_MEMBER"' -- -X POST "$API/bonds" -H "Authorization: Bearer $PH_A" -d "$(bond_body "Us")"
PH_BOND="$(printf '%s' "$LAST_BODY" | jget id)"
PH_CODE="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['code'])" <<<"$LAST_BODY")"
expect "photos: …which the viewer joins" 200 '"status":"ACTIVE"' -- -X POST "$API/invites/$PH_CODE/accept" -H "Authorization: Bearer $PH_B"
expect "photos: another user's photo on an entry is 422 on imageMediaId" 422 '"field":"imageMediaId"' -- -X POST "$API/bonds/$PH_BOND/entries" -H "Authorization: Bearer $PH_B" -H "Idempotency-Key: $(uuidgen)" -d "{\"text\":\"not mine to show\",\"imageMediaId\":\"$PH_PHOTO\"}"
expect "photos: a failed photo on an entry is 422 on imageMediaId" 422 '"field":"imageMediaId"' -- -X POST "$API/bonds/$PH_BOND/entries" -H "Authorization: Bearer $PH_A" -H "Idempotency-Key: $(uuidgen)" -d "{\"text\":\"a broken one\",\"imageMediaId\":\"$PH_FAKED\"}"
expect "photos: an entry with the author's ready photo is 201, and the author sees it READY" 201 '"status":"READY"' -- -X POST "$API/bonds/$PH_BOND/entries" -H "Authorization: Bearer $PH_A" -H "Idempotency-Key: $(uuidgen)" -d "{\"text\":\"look at the light today\",\"imageMediaId\":\"$PH_PHOTO\"}"
db_is "photos: …the photo is attached to that entry" "$(sql "SELECT (m.attached_entry_id = e.id) FROM media_assets m JOIN entries e ON e.image_media_id = m.id WHERE m.id='$PH_PHOTO'")" "t"
expect "photos: the partner's locked entry says nothing of the photo" 200 '"status":"LOCKED"' -- "$API/bonds/$PH_BOND/today" -H "Authorization: Bearer $PH_B"
if printf '%s' "$LAST_BODY" | grep -q -e "$PH_PHOTO" -e '"image"' -e 'X-Amz'; then fail "photos: …no id, no image field, no URL" "the locked day carries something of the photo"; else pass "photos: …no id, no image field, no URL"; fi
expect "photos: deleting a photo on an entry is 409 MEDIA_ATTACHED" 409 '"code":"MEDIA_ATTACHED"' -- -X DELETE "$API/media/$PH_PHOTO" -H "Authorization: Bearer $PH_A"
expect "photos: its owner still reads it, READY" 200 '"status":"READY"' -- "$API/media/$PH_PHOTO" -H "Authorization: Bearer $PH_A"
expect "photos: the viewer writes, and the day reveals" 201 '"status":"REVEALED"' -- -X POST "$API/bonds/$PH_BOND/entries" -H "Authorization: Bearer $PH_B" -H "Idempotency-Key: $(uuidgen)" -d '{"text":"and mine, no photo"}'
# Until moderation passes it the viewer is shown it PROCESSING (FR-101); the local profile's stand-in
# moderator passes this black square within seconds of its being made (D4, ADR-0051), so by now it has.
is "photos: the photo has passed moderation (D4, the stand-in)" "$(await_verdict "$PH_PHOTO")" "PASSED"
expect "photos: once revealed, the viewer sees the passed photo READY with its URL, not blurred" 200 '"status":"READY","url":"http' -- "$API/bonds/$PH_BOND/today" -H "Authorization: Bearer $PH_B"
case "$LAST_BODY" in *'"sensitive":false'*) pass "photos: …sensitive false";; *) fail "photos: …sensitive false" "${LAST_BODY:0:300}";; esac
expect "photos: the photographer sees it READY on the revealed day" 200 '"status":"READY","url":"http' -- "$API/bonds/$PH_BOND/today" -H "Authorization: Bearer $PH_A"
expect "photos: an outsider reaches neither the photo nor the day" 404 '"code":"NOT_FOUND"' -- "$API/media/$PH_PHOTO" -H "Authorization: Bearer $PH_O"
if grep -q "$(printf '%s' "$PH_URL" | sed -E 's/.*X-Amz-Signature=([0-9a-f]+).*/\1/')" "$MOYI_LOG"; then
  fail "photos: the photo's URL is in no log line" "its signature is in $MOYI_LOG"
else pass "photos: the photo's URL is in no log line"; fi

echo; echo "erasure — an erased entry's photo leaves the storage with it (Phase 4 D5, ADR-0050)"
# The photographer deletes the entry the photo is on. media is told in the same
# transaction (the photo is DELETED at once), and the booted jar's outbox poller
# runs media.purge, which deletes the objects within seconds: the URL handed out
# above, a bearer credential for up to 25 hours, then reaches nothing.
PH_ENTRY="$(sql "SELECT id FROM entries WHERE image_media_id='$PH_PHOTO'")"
PH_VARIANT_KEY="v/${PH_UPLOAD_KEY#in/}/full.webp"
is "erasure: the URL handed out reaches the photo before the erasure" "$(curl -s -o /dev/null -w '%{http_code}' "$PH_URL")" "200"
expect "erasure: the author deletes the entry with the photo, 204" 204 "" -- -X DELETE "$API/entries/$PH_ENTRY" -H "Authorization: Bearer $PH_A"
expect "erasure: the owner's GET of the erased entry's photo is 404" 404 '"code":"NOT_FOUND"' -- "$API/media/$PH_PHOTO" -H "Authorization: Bearer $PH_A"
db_is "erasure: …the photo is DELETED and attached to nothing" "$(sql "SELECT status || '|' || (attached_entry_id IS NULL) FROM media_assets WHERE id='$PH_PHOTO'")" "DELETED|true"
db_is "erasure: …and one MediaReleased was recorded for it" "$(sql "SELECT count(*) FROM outbox_events WHERE event_type='MediaReleased' AND aggregate_id='$PH_PHOTO'")" "1"
PURGED="f"
for _ in $(seq 1 40); do
  PURGED="$(sql "SELECT purged_at IS NOT NULL FROM media_assets WHERE id='$PH_PHOTO'")"
  [ "$PURGED" = "t" ] && break
  sleep 0.5
done
db_is "erasure: media.purge deletes its objects within seconds" "$PURGED" "t"
is "erasure: …and the URL handed out before reaches nothing" "$(curl -s -o /dev/null -w '%{http_code}' "$PH_URL")" "404"
if [ "$MINIO_OK" = 0 ]; then skip "erasure: …nothing of the photo is left in MinIO"
elif minio_has "$PH_VARIANT_KEY"; then fail "erasure: …nothing of the photo is left in MinIO" "MinIO still holds its full variant"
else pass "erasure: …nothing of the photo is left in MinIO"; fi
rm -rf "$PHOTO_DIR"
flush_buckets

echo; echo "voice notes — transcoded by ffmpeg, attached to an entry, played to each member (Phase 4 D3, ADR-0049)"
# The booted jar's outbox poller runs media.process for voice notes too (the
# local profile's NATIVE needs ffmpeg with libopus on PATH as well as libvips;
# with --attach, start the server with both). The recordings are made here: an
# M4A from ffmpeg carrying a made-up title, and WAVs of a tone written by hand.
# What comes back at the URL must be Ogg Opus with none of the title. With the
# photo, the first half of M4 (15:110): an entry with a photo and a voice note.
VOICE_DIR="$(mktemp -d)"
VOICE_TAG="FAKE-SMOKE-TITLE-QZX"
ffmpeg -hide_banner -v error -y -f lavfi -i "sine=frequency=440:sample_rate=16000:duration=2" -ac 1 -c:a aac \
  -metadata "title=$VOICE_TAG" -f ipod "$VOICE_DIR/note.m4a"
# wav_tone <file> <seconds> — a 440 Hz tone, 16-bit mono PCM at 8 kHz, in a RIFF WAVE.
wav_tone() {
  python3 - "$1" "$2" <<'PY'
import math, struct, sys
rate, seconds = 8000, float(sys.argv[2])
samples = int(rate * seconds)
pcm = b"".join(struct.pack("<h", int(8000 * math.sin(2 * math.pi * 440 * i / rate))) for i in range(samples))
header = b"RIFF" + struct.pack("<I", 36 + len(pcm)) + b"WAVEfmt " + struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * 2, 2, 16)
open(sys.argv[1], "wb").write(header + b"data" + struct.pack("<I", len(pcm)) + pcm)
PY
}
wav_tone "$VOICE_DIR/short.wav" 2
wav_tone "$VOICE_DIR/long.wav" 62
vips black "$VOICE_DIR/photo.jpg" 64 48 --bands 3
verified_account "speaker" "203.0.113.160"; VO_A="$ACCOUNT_ACCESS"
verified_account "listener" "203.0.113.161"; VO_B="$ACCOUNT_ACCESS"
verified_account "outsider-v" "203.0.113.162"; VO_O="$ACCOUNT_ACCESS"

has_bytes "$VOICE_DIR/note.m4a" "$(printf '%s' "$VOICE_TAG" | xxd -p | tr -d '\n')" && pass "voice: the M4A uploaded carries a title" || fail "voice: fixture" "the made-up title is not in the M4A"
upload_photo "$VO_A" "$VOICE_DIR/note.m4a" audio/x-m4a VOICE; VO_NOTE="$PHOTO_ID"
VO_UPLOAD_KEY="$(sql "SELECT storage_key FROM media_assets WHERE id='$VO_NOTE'")"
is "voice: an uploaded voice note becomes READY" "$(await_photo "$VO_A" "$VO_NOTE")" "READY"
expect "voice: …and its owner is given a URL and its length" 200 '"durationMs":' -- "$API/media/$VO_NOTE" -H "Authorization: Bearer $VO_A"
VO_URL="$(printf '%s' "$LAST_BODY" | jget url)"
VO_MS="$(printf '%s' "$LAST_BODY" | jget durationMs)"
[ "$VO_MS" -ge 1900 ] && [ "$VO_MS" -le 2200 ] && pass "voice: …two seconds long ($VO_MS ms)" || fail "voice: length" "durationMs is $VO_MS"
VO_TYPE="$(curl -s -o "$VOICE_DIR/served.ogg" -w '%{content_type}' "$VO_URL")"
[ "$VO_TYPE" = "audio/ogg" ] && pass "voice: the file at its URL is served as audio/ogg" || fail "voice: served type" "got $VO_TYPE"
[ "$(head -c 4 "$VOICE_DIR/served.ogg")" = "OggS" ] && has_bytes "$VOICE_DIR/served.ogg" "$(printf 'OpusHead' | xxd -p)" \
  && pass "voice: …and is Ogg Opus" || fail "voice: served format" "the bytes at the URL are not Ogg Opus"
VO_PROBE="$(ffprobe -v error -show_entries stream=codec_name,channels -of csv=p=0 "$VOICE_DIR/served.ogg")"
is "voice: …one channel of Opus, by ffprobe" "$VO_PROBE" "opus,1"
if has_bytes "$VOICE_DIR/served.ogg" "$(printf '%s' "$VOICE_TAG" | xxd -p | tr -d '\n')"; then
  fail "voice: …with nothing of the upload's title in it" "the served file still holds the title"
else pass "voice: …with nothing of the upload's title in it"; fi
if [ "$MINIO_OK" = 0 ]; then skip "voice: the upload itself is gone from MinIO once the voice note is ready"
elif minio_has "$VO_UPLOAD_KEY"; then fail "voice: the upload itself is gone from MinIO once the voice note is ready" "MinIO still holds the original bytes"
else pass "voice: the upload itself is gone from MinIO once the voice note is ready"; fi

upload_photo "$VO_A" "$VOICE_DIR/long.wav" audio/wav VOICE; VO_LONG="$PHOTO_ID"
is "voice: a voice note of 62 seconds becomes FAILED" "$(await_photo "$VO_A" "$VO_LONG")" "FAILED"
if grep -q "Media $VO_LONG failed: TOO_LONG" "$MOYI_LOG"; then pass "voice: …and the log says why, by id and rule"; else fail "voice: too long logged" "no line names $VO_LONG and TOO_LONG"; fi
upload_photo "$VO_A" "$VOICE_DIR/short.wav" audio/mp4 VOICE; VO_FAKED="$PHOTO_ID"
is "voice: a WAV declared as an M4A becomes FAILED" "$(await_photo "$VO_A" "$VO_FAKED")" "FAILED"
if grep -q "Media $VO_FAKED failed: NOT_THE_DECLARED_FORMAT" "$MOYI_LOG"; then pass "voice: …NOT_THE_DECLARED_FORMAT in the log"; else fail "voice: mislabelled logged" "no line names $VO_FAKED"; fi

upload_photo "$VO_A" "$VOICE_DIR/photo.jpg" image/jpeg; VO_PHOTO="$PHOTO_ID"
is "voice: a photo to go with it becomes READY" "$(await_photo "$VO_A" "$VO_PHOTO")" "READY"
expect "voice: a bond for the speaker and the listener is 201" 201 '"status":"PENDING_MEMBER"' -- -X POST "$API/bonds" -H "Authorization: Bearer $VO_A" -d "$(bond_body "Us")"
VO_BOND="$(printf '%s' "$LAST_BODY" | jget id)"
VO_CODE="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['code'])" <<<"$LAST_BODY")"
expect "voice: …which the listener joins" 200 '"status":"ACTIVE"' -- -X POST "$API/invites/$VO_CODE/accept" -H "Authorization: Bearer $VO_B"
expect "voice: another user's voice note on an entry is 422 on voiceMediaId" 422 '"field":"voiceMediaId"' -- -X POST "$API/bonds/$VO_BOND/entries" -H "Authorization: Bearer $VO_B" -H "Idempotency-Key: $(uuidgen)" -d "{\"text\":\"not mine to play\",\"voiceMediaId\":\"$VO_NOTE\"}"
expect "voice: a photo named as a voice note is 422 on voiceMediaId" 422 '"field":"voiceMediaId"' -- -X POST "$API/bonds/$VO_BOND/entries" -H "Authorization: Bearer $VO_A" -H "Idempotency-Key: $(uuidgen)" -d "{\"text\":\"wrong kind\",\"voiceMediaId\":\"$VO_PHOTO\"}"
expect "voice: M4 - an entry with a photo and a voice note is 201, both READY to the author" 201 '"durationMs":' -- -X POST "$API/bonds/$VO_BOND/entries" -H "Authorization: Bearer $VO_A" -H "Idempotency-Key: $(uuidgen)" -d "{\"text\":\"hear the rain\",\"imageMediaId\":\"$VO_PHOTO\",\"voiceMediaId\":\"$VO_NOTE\"}"
VO_ENTRY="$(printf '%s' "$LAST_BODY")"
# Issue #165 (ADR-0061): the waveform media.process measured from the Opus it kept, 64 levels of
# 0-255, the loudest 255; a steady tone, so every slice away from the ends is loud.
is "voice: …with its waveform, 64 levels of 0-255, the loudest 255 (ADR-0061)" "$(python3 -c "import json,sys; w=json.load(sys.stdin)['voice']['waveform']; print(len(w), all(type(x) is int and 0 <= x <= 255 for x in w), max(w), min(w[4:60]) >= 200)" <<<"$VO_ENTRY")" "64 True 255 True"
db_is "voice: …kept on the asset as 64 bytes" "$(sql "SELECT octet_length(waveform) FROM media_assets WHERE id='$VO_NOTE'")" "64"
VO_LEVELS="$(python3 -c "import json,sys; print(json.load(sys.stdin)['voice']['waveform'])" <<<"$VO_ENTRY")"
is "voice: …the photo READY to its author" "$(python3 -c "import json,sys; print(json.load(sys.stdin)['image']['status'])" <<<"$VO_ENTRY")" "READY"
is "voice: …the voice note READY to its author" "$(python3 -c "import json,sys; print(json.load(sys.stdin)['voice']['status'])" <<<"$VO_ENTRY")" "READY"
db_is "voice: …the voice note is attached to that entry" "$(sql "SELECT (m.attached_entry_id = e.id) FROM media_assets m JOIN entries e ON e.voice_media_id = m.id WHERE m.id='$VO_NOTE'")" "t"
db_is "voice: …and its length is the asset's, not the entry's (spec D-10)" "$(sql "SELECT voice_duration_ms IS NULL FROM entries WHERE voice_media_id='$VO_NOTE'")" "t"
expect "voice: the listener's locked entry says nothing of the voice note" 200 '"status":"LOCKED"' -- "$API/bonds/$VO_BOND/today" -H "Authorization: Bearer $VO_B"
if printf '%s' "$LAST_BODY" | grep -q -e "$VO_NOTE" -e '"voice"' -e 'durationMs' -e 'waveform' -e 'X-Amz'; then fail "voice: …no id, no voice field, no length, no waveform, no URL" "the locked day carries something of the voice note"; else pass "voice: …no id, no voice field, no length, no waveform, no URL"; fi
expect "voice: deleting a voice note on an entry is 409 MEDIA_ATTACHED" 409 '"code":"MEDIA_ATTACHED"' -- -X DELETE "$API/media/$VO_NOTE" -H "Authorization: Bearer $VO_A"
# Issue #164 (ADR-0048 decision 7): PATCH takes a photo or a voice note off with a flag, and lets it
# go as a replacement does, to the reaper of unattached media; a flag with the id it would replace is
# 422. Then both go back on, since the reveal below needs them: a removed asset is its owner's again.
VO_ENTRY_ID="$(python3 -c "import json,sys; print(json.load(sys.stdin)['id'])" <<<"$VO_ENTRY")"
expect "entry media: removeImage with an imageMediaId is 422 on removeImageWithoutImageMediaId" 422 '"field":"removeImageWithoutImageMediaId"' -- -X PATCH "$API/entries/$VO_ENTRY_ID" -H "Authorization: Bearer $VO_A" -d "{\"text\":\"hear the rain\",\"removeImage\":true,\"imageMediaId\":\"$VO_PHOTO\"}"
expect "entry media: removeVoice with a voiceMediaId is 422 on removeVoiceWithoutVoiceMediaId" 422 '"field":"removeVoiceWithoutVoiceMediaId"' -- -X PATCH "$API/entries/$VO_ENTRY_ID" -H "Authorization: Bearer $VO_A" -d "{\"text\":\"hear the rain\",\"removeVoice\":true,\"voiceMediaId\":\"$VO_NOTE\"}"
db_is "entry media: …and after both refusals the entry still has its photo and its voice note" "$(sql "SELECT image_media_id='$VO_PHOTO' AND voice_media_id='$VO_NOTE' FROM entries WHERE id='$VO_ENTRY_ID'")" "t"
expect "entry media: PATCH removeImage takes the photo off" 200 '"image":null' -- -X PATCH "$API/entries/$VO_ENTRY_ID" -H "Authorization: Bearer $VO_A" -d '{"text":"hear the rain","removeImage":true}'
is "entry media: …and leaves the voice note" "$(python3 -c "import json,sys; print(json.load(sys.stdin)['voice']['id'])" <<<"$LAST_BODY")" "$VO_NOTE"
db_is "entry media: …the photo is let go to the reaper, as a replaced one is: on no entry, detached now" "$(sql "SELECT attached_entry_id IS NULL AND detached_at IS NOT NULL AND status='READY' FROM media_assets WHERE id='$VO_PHOTO'")" "t"
expect "entry media: PATCH removeVoice takes the voice note off" 200 '"voice":null' -- -X PATCH "$API/entries/$VO_ENTRY_ID" -H "Authorization: Bearer $VO_A" -d '{"text":"hear the rain","removeVoice":true}'
db_is "entry media: …the voice note is let go the same way" "$(sql "SELECT attached_entry_id IS NULL AND detached_at IS NOT NULL AND status='READY' FROM media_assets WHERE id='$VO_NOTE'")" "t"
expect "entry media: removing both again, when neither is there, is 200 and the words stay" 200 '"text":"hear the rain"' -- -X PATCH "$API/entries/$VO_ENTRY_ID" -H "Authorization: Bearer $VO_A" -d '{"text":"hear the rain","removeImage":true,"removeVoice":true}'
expect "entry media: …and both go back on, the owner's again" 200 '"durationMs":' -- -X PATCH "$API/entries/$VO_ENTRY_ID" -H "Authorization: Bearer $VO_A" -d "{\"text\":\"hear the rain\",\"imageMediaId\":\"$VO_PHOTO\",\"voiceMediaId\":\"$VO_NOTE\"}"
db_is "entry media: …attached to the entry again" "$(sql "SELECT count(*) FROM media_assets WHERE attached_entry_id='$VO_ENTRY_ID'")" "2"
expect "voice: the listener writes, and the day reveals" 201 '"status":"REVEALED"' -- -X POST "$API/bonds/$VO_BOND/entries" -H "Authorization: Bearer $VO_B" -H "Idempotency-Key: $(uuidgen)" -d '{"text":"and mine, in words"}'
# The listener hears it only once it has passed moderation (FR-061, ADR-0049 decision 7); the local
# profile's stand-in passes every voice note, and this photo, within seconds (D4, ADR-0051).
is "voice: the voice note has passed moderation (D4, the stand-in)" "$(await_verdict "$VO_NOTE")" "PASSED"
is "voice: …and the photo too" "$(await_verdict "$VO_PHOTO")" "PASSED"
expect "voice: once revealed and passed, the listener is played the voice note" 200 '"partnerEntry"' -- "$API/bonds/$VO_BOND/today" -H "Authorization: Bearer $VO_B"
VO_PARTNER="$(python3 -c "import json,sys; print(json.dumps(json.load(sys.stdin)['partnerEntry']))" <<<"$LAST_BODY")"
is "voice: …READY to the listener, with a URL and its length" "$(python3 -c "import json,sys; v=json.load(sys.stdin)['voice']; print(v['status'], v['url'] is not None, v['durationMs'] is not None)" <<<"$VO_PARTNER")" "READY True True"
is "voice: …and the speaker's waveform, the same 64 levels (ADR-0061)" "$(python3 -c "import json,sys; print(json.load(sys.stdin)['voice']['waveform'])" <<<"$VO_PARTNER")" "$VO_LEVELS"
is "voice: …and the photo READY to the listener (FR-101)" "$(python3 -c "import json,sys; print(json.load(sys.stdin)['image']['status'])" <<<"$VO_PARTNER")" "READY"
# Issue #152, ADR-0060: the same note as AAC-LC in MP4 (.m4a), which iOS's AVPlayer can stream: given
# to the author with the Opus, and to the listener under the same rule; it downloads, and is an MP4
# (an `ftyp` box first) with its `moov` index before the audio, one channel of AAC.
VO_AAC_URL="$(python3 -c "import json,sys; print(json.load(sys.stdin)['voice'].get('urlAac') or '')" <<<"$VO_ENTRY")"
[ -n "$VO_AAC_URL" ] && pass "voice: the author is given urlAac with the Opus's url" || fail "voice: urlAac" "the author's voice note has no urlAac"
is "voice: …and the listener too, once passed" "$(python3 -c "import json,sys; print(json.load(sys.stdin)['voice'].get('urlAac') is not None)" <<<"$VO_PARTNER")" "True"
VO_AAC_TYPE="$(curl -s -o "$VOICE_DIR/served.m4a" -w '%{http_code} %{content_type}' "$VO_AAC_URL")"
is "voice: …urlAac downloads, served as audio/mp4" "$VO_AAC_TYPE" "200 audio/mp4"
is "voice: …and starts with an MP4 ftyp box" "$(dd if="$VOICE_DIR/served.m4a" bs=1 skip=4 count=4 2>/dev/null)" "ftyp"
is "voice: …its moov index before its audio (+faststart), so it streams" \
  "$(python3 -c "import sys; b=open(sys.argv[1],'rb').read(); print(0 <= b.find(b'moov') < b.find(b'mdat'))" "$VOICE_DIR/served.m4a")" "True"
is "voice: …one channel of AAC-LC, by ffprobe" "$(ffprobe -v error -show_entries stream=codec_name,profile,channels -of csv=p=0 "$VOICE_DIR/served.m4a")" "aac,LC,1"
if has_bytes "$VOICE_DIR/served.m4a" "$(printf '%s' "$VOICE_TAG" | xxd -p | tr -d '\n')"; then
  fail "voice: …with nothing of the upload's title in it either" "the m4a still holds the title"
else pass "voice: …with nothing of the upload's title in it either"; fi
if grep -q "$(printf '%s' "$VO_AAC_URL" | sed -E 's/.*X-Amz-Signature=([0-9a-f]+).*/\1/')" "$MOYI_LOG"; then
  fail "voice: urlAac is in no log line" "its signature is in $MOYI_LOG"
else pass "voice: urlAac is in no log line"; fi
expect "voice: the speaker still plays it READY on the revealed day" 200 '"durationMs":' -- "$API/bonds/$VO_BOND/today" -H "Authorization: Bearer $VO_A"
expect "voice: an outsider reaches neither the voice note nor the day" 404 '"code":"NOT_FOUND"' -- "$API/media/$VO_NOTE" -H "Authorization: Bearer $VO_O"
if grep -q "$(printf '%s' "$VO_URL" | sed -E 's/.*X-Amz-Signature=([0-9a-f]+).*/\1/')" "$MOYI_LOG"; then
  fail "voice: the voice note's URL is in no log line" "its signature is in $MOYI_LOG"
else pass "voice: the voice note's URL is in no log line"; fi
# A deleted voice note keeps no waveform (ADR-0061 decision 8): its owner deletes one never attached.
upload_photo "$VO_A" "$VOICE_DIR/short.wav" audio/wav VOICE; VO_LOOSE="$PHOTO_ID"
is "voice: a WAV voice note to delete becomes READY" "$(await_photo "$VO_A" "$VO_LOOSE")" "READY"
db_is "voice: …with a waveform of 64 bytes" "$(sql "SELECT octet_length(waveform) FROM media_assets WHERE id='$VO_LOOSE'")" "64"
expect "voice: …which its owner deletes, 204" 204 '' -- -X DELETE "$API/media/$VO_LOOSE" -H "Authorization: Bearer $VO_A"
db_is "voice: …and the deleted row keeps no waveform" "$(sql "SELECT status || '|' || (waveform IS NULL) FROM media_assets WHERE id='$VO_LOOSE'")" "DELETED|true"
rm -rf "$VOICE_DIR"
flush_buckets

echo; echo "moderation — each verdict, what each member is shown, and the operator's queue (Phase 4 D4, ADR-0051)"
# The local profile names the stand-in moderators, which decide by colour so
# that every verdict is reached without AWS: a photo almost all red is held
# back (extreme violence), almost all magenta passes blurred (adult nudity),
# almost all blue is "known" and blocked; every voice note passes. The booted
# jar's outbox poller runs media.moderate-image and -voice once media.process
# has made each one READY. The operator's queue is scripts/moyi media
# quarantine, reaching this run's database through docker compose.
MOD_DIR="$(mktemp -d)"
vips black "$MOD_DIR/black.v" 64 48 --bands 3
vips linear "$MOD_DIR/black.v" "$MOD_DIR/red.jpg" "1 1 1" "220 20 20" --uchar
vips linear "$MOD_DIR/black.v" "$MOD_DIR/magenta.jpg" "1 1 1" "220 20 220" --uchar
vips linear "$MOD_DIR/black.v" "$MOD_DIR/blue.jpg" "1 1 1" "20 20 220" --uchar
python3 - "$MOD_DIR/note.wav" <<'PY'
import math, struct, sys
rate, samples = 8000, 16000
pcm = b"".join(struct.pack("<h", int(8000 * math.sin(2 * math.pi * 440 * i / rate))) for i in range(samples))
header = b"RIFF" + struct.pack("<I", 36 + len(pcm)) + b"WAVEfmt " + struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * 2, 2, 16)
open(sys.argv[1], "wb").write(header + b"data" + struct.pack("<I", len(pcm)) + pcm)
PY
MO_START="$(sql "SELECT extract(epoch FROM now())")"
verified_account "moderated" "203.0.113.170"; MO_A="$ACCOUNT_ACCESS"
verified_account "partner-m" "203.0.113.171"; MO_B="$ACCOUNT_ACCESS"
# quarantine <args…> — the operator's command, as the operator runs it, against this run's database.
quarantine() {
  MOYI_OPERATOR=smoke-operator MOYI_OPS_PSQL="docker compose exec -T postgres psql -U moyi -d $MOYI_DB" \
    scripts/moyi media quarantine "$@"
}

upload_photo "$MO_A" "$MOD_DIR/red.jpg" image/jpeg; MO_RED="$PHOTO_ID"
upload_photo "$MO_A" "$MOD_DIR/magenta.jpg" image/jpeg; MO_NUDE="$PHOTO_ID"
upload_photo "$MO_A" "$MOD_DIR/blue.jpg" image/jpeg; MO_KNOWN="$PHOTO_ID"
upload_photo "$MO_A" "$MOD_DIR/note.wav" audio/wav VOICE; MO_VOICE="$PHOTO_ID"
for id in "$MO_RED" "$MO_NUDE" "$MO_KNOWN" "$MO_VOICE"; do await_photo "$MO_A" "$id" >/dev/null; done
is "moderation: extreme violence is QUARANTINED" "$(await_verdict "$MO_RED")" "QUARANTINED"
is "moderation: adult nudity PASSES" "$(await_verdict "$MO_NUDE")" "PASSED"
is "moderation: a known image is BLOCKED" "$(await_verdict "$MO_KNOWN")" "BLOCKED"
is "moderation: a voice note PASSES (the stand-in passes every one)" "$(await_verdict "$MO_VOICE")" "PASSED"
expect "moderation: the author reads the quarantined photo QUARANTINED, still with its URL (Q10)" 200 '"status":"QUARANTINED"' -- "$API/media/$MO_RED" -H "Authorization: Bearer $MO_A"
case "$LAST_BODY" in *'"url":"http'*) pass "moderation: …the URL is there";; *) fail "moderation: …the URL is there" "${LAST_BODY:0:200}";; esac
expect "moderation: the author reads the blocked photo QUARANTINED" 200 '"status":"QUARANTINED"' -- "$API/media/$MO_KNOWN" -H "Authorization: Bearer $MO_A"
case "$LAST_BODY" in *'"url":null'*) pass "moderation: …with no URL, not even its author's (Q16)";; *) fail "moderation: …with no URL" "${LAST_BODY:0:200}";; esac
db_is "moderation: …and it is held as evidence" "$(sql "SELECT evidence_held FROM media_assets WHERE id='$MO_KNOWN'")" "t"
expect "moderation: the nude photo is READY and sensitive to its author" 200 '"sensitive":true' -- "$API/media/$MO_NUDE" -H "Authorization: Bearer $MO_A"
db_is "moderation: every verdict is in the audit log, by the moderator" \
  "$(sql "SELECT count(*) FROM media_moderation_log WHERE actor = 'moderation:stand-in' AND media_id IN ('$MO_RED','$MO_NUDE','$MO_KNOWN')")" "3"

expect "moderation: a bond for the two is 201" 201 '"status":"PENDING_MEMBER"' -- -X POST "$API/bonds" -H "Authorization: Bearer $MO_A" -d "$(bond_body "Us")"
MO_BOND="$(printf '%s' "$LAST_BODY" | jget id)"
MO_CODE="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['code'])" <<<"$LAST_BODY")"
expect "moderation: …which the partner joins" 200 '"status":"ACTIVE"' -- -X POST "$API/invites/$MO_CODE/accept" -H "Authorization: Bearer $MO_B"
# #149: a photo held back before its entry is sent is attached all the same, as it would be had
# moderation run a moment later (ADR-0051 decision 8 as amended): NOT_SHARED to its author from
# the entry's first answer, PROCESSING to the partner. In a bond of its own, so this day's entry
# in MO_BOND stays free for the photo and voice note below.
verified_account "partner-h" "203.0.113.172"; MO_C="$ACCOUNT_ACCESS"
expect "moderation: #149 - a second bond is 201" 201 '"status":"PENDING_MEMBER"' -- -X POST "$API/bonds" -H "Authorization: Bearer $MO_A" -d "$(bond_body "Held")"
MO_HBOND="$(printf '%s' "$LAST_BODY" | jget id)"
MO_HCODE="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['code'])" <<<"$LAST_BODY")"
expect "moderation: #149 - …which its partner joins" 200 '"status":"ACTIVE"' -- -X POST "$API/invites/$MO_HCODE/accept" -H "Authorization: Bearer $MO_C"
expect "moderation: #149 - a photo held back before the entry is sent is attached: 201" 201 '"status":"NOT_SHARED"' -- -X POST "$API/bonds/$MO_HBOND/entries" -H "Authorization: Bearer $MO_A" -H "Idempotency-Key: $(uuidgen)" -d "{\"text\":\"this one too\",\"imageMediaId\":\"$MO_RED\"}"
is "moderation: #149 - …NOT_SHARED to its author, with its URL (Q10)" "$(python3 -c "import json,sys; i=json.load(sys.stdin)['image']; print(i['id'], i['status'], i['url'] is not None, i['sensitive'])" <<<"$LAST_BODY")" "$MO_RED NOT_SHARED True False"
MO_HENTRY="$(printf '%s' "$LAST_BODY" | jget id)"
db_is "moderation: #149 - …attached, and still held back with its verdict" "$(sql "SELECT (attached_entry_id = '$MO_HENTRY') || '|' || status || '|' || moderation_verdict FROM media_assets WHERE id='$MO_RED'")" "true|QUARANTINED|QUARANTINED"
expect "moderation: #149 - the partner writes, and the day reveals" 201 '"status":"REVEALED"' -- -X POST "$API/bonds/$MO_HBOND/entries" -H "Authorization: Bearer $MO_C" -H "Idempotency-Key: $(uuidgen)" -d '{"text":"and mine"}'
expect "moderation: #149 - the partner reads the day" 200 '"partnerEntry"' -- "$API/bonds/$MO_HBOND/today" -H "Authorization: Bearer $MO_C"
is "moderation: #149 - …and is shown the held-back photo PROCESSING, with nothing of it" "$(python3 -c "import json,sys; i=json.load(sys.stdin)['partnerEntry']['image']; print(i['status'], i['url'], i['width'], i['sensitive'])" <<<"$LAST_BODY")" "PROCESSING None None False"
case "$LAST_BODY" in *NOT_SHARED*|*X-Amz*) fail "moderation: #149 - …never NOT_SHARED, no signed URL" "${LAST_BODY:0:300}";; *) pass "moderation: #149 - …never NOT_SHARED, no signed URL";; esac
expect "moderation: #149 - its author reads it NOT_SHARED on the day too" 200 '"status":"NOT_SHARED"' -- "$API/bonds/$MO_HBOND/today" -H "Authorization: Bearer $MO_A"
expect "moderation: an entry with the nude photo and the voice note is 201" 201 '"sensitive":true' -- -X POST "$API/bonds/$MO_BOND/entries" -H "Authorization: Bearer $MO_A" -H "Idempotency-Key: $(uuidgen)" -d "{\"text\":\"for you\",\"imageMediaId\":\"$MO_NUDE\",\"voiceMediaId\":\"$MO_VOICE\"}"
expect "moderation: the partner writes, and the day reveals" 201 '"status":"REVEALED"' -- -X POST "$API/bonds/$MO_BOND/entries" -H "Authorization: Bearer $MO_B" -H "Idempotency-Key: $(uuidgen)" -d '{"text":"and mine"}'
expect "moderation: the partner is shown the passed photo" 200 '"partnerEntry"' -- "$API/bonds/$MO_BOND/today" -H "Authorization: Bearer $MO_B"
MO_PARTNER="$(python3 -c "import json,sys; print(json.dumps(json.load(sys.stdin)['partnerEntry']))" <<<"$LAST_BODY")"
is "moderation: …READY with a URL, and sensitive, to blur it (Q16)" "$(python3 -c "import json,sys; i=json.load(sys.stdin)['image']; print(i['status'], i['url'] is not None, i['sensitive'])" <<<"$MO_PARTNER")" "READY True True"
is "moderation: …and the passed voice note READY, with its length" "$(python3 -c "import json,sys; v=json.load(sys.stdin)['voice']; print(v['status'], v['durationMs'] is not None, v['sensitive'])" <<<"$MO_PARTNER")" "READY True False"

if [ "$PSQL_OK" = 1 ]; then
  MO_LIST="$(quarantine list 2>&1)"
  case "$MO_LIST" in *"$MO_RED"*EXTREME_VIOLENCE*) pass "moderation: the operator's list shows the quarantined photo and why";; *) fail "moderation: quarantine list" "${MO_LIST:0:300}";; esac
  is "moderation: the list's header names its eight columns, verdict first" \
    "$(printf '%s\n' "$MO_LIST" | head -1)" "$(printf 'verdict\tmediaId\tkind\tstatus\treason\tattached\tmoderatedAt\treports')"
  if printf '%s\n' "$MO_LIST" | grep "$MO_KNOWN" | grep -q "^BLOCKED.*KNOWN_IMAGE.*DO NOT VIEW — runbook §2$"; then
    pass "moderation: …and the blocked one, marked DO NOT VIEW"
  else fail "moderation: quarantine list, blocked" "${MO_LIST:0:400}"; fi
  case "$MO_LIST" in *"$MO_NUDE"*) fail "moderation: …and nothing that passed" "the nude photo is listed";; *) pass "moderation: …and nothing that passed";; esac
  if MO_OUT="$(quarantine release "$MO_KNOWN" "smoke: trying" 2>&1)"; then fail "moderation: releasing a blocked asset is refused" "it was released: $MO_OUT"
  else case "$MO_OUT" in *"never released"*) pass "moderation: releasing a blocked asset is refused, never released (Q16)";; *) fail "moderation: release refused" "$MO_OUT";; esac; fi
  db_is "moderation: …it is still blocked" "$(sql "SELECT status || '|' || moderation_verdict FROM media_assets WHERE id='$MO_KNOWN'")" "QUARANTINED|BLOCKED"
  MO_OUT="$(quarantine release "$MO_RED" "smoke: a made-up red square, not violence" 2>&1)" \
    && pass "moderation: the operator releases the quarantined photo" || fail "moderation: release" "$MO_OUT"
  expect "moderation: …which its author now reads READY" 200 '"status":"READY"' -- "$API/media/$MO_RED" -H "Authorization: Bearer $MO_A"
  expect "moderation: #149 - …and the partner it was on an entry for is now shown it READY" 200 '"partnerEntry"' -- "$API/bonds/$MO_HBOND/today" -H "Authorization: Bearer $MO_C"
  is "moderation: #149 - …READY, with its URL" "$(python3 -c "import json,sys; i=json.load(sys.stdin)['partnerEntry']['image']; print(i['status'], i['url'] is not None)" <<<"$LAST_BODY")" "READY True"
  MO_KNOWN_KEY="$(sql "SELECT storage_key FROM media_assets WHERE id='$MO_KNOWN'")"
  MO_OUT="$(quarantine delete "$MO_KNOWN" "smoke: reported, ref FAKE-SMOKE-0001" 2>&1)" \
    && pass "moderation: the operator deletes the blocked asset after the report" || fail "moderation: delete" "$MO_OUT"
  MO_PURGED="f"
  for _ in $(seq 1 40); do
    MO_PURGED="$(sql "SELECT purged_at IS NOT NULL FROM media_assets WHERE id='$MO_KNOWN'")"
    [ "$MO_PURGED" = "t" ] && break
    sleep 0.5
  done
  db_is "moderation: …media.purge deletes its objects within seconds" "$MO_PURGED" "t"
  if [ "$MINIO_OK" = 0 ]; then skip "moderation: …nothing of it is left in MinIO"
  elif minio_has "v/${MO_KNOWN_KEY#in/}/display.webp"; then fail "moderation: …nothing of it is left in MinIO" "MinIO still holds its display variant"
  else pass "moderation: …nothing of it is left in MinIO"; fi
  db_is "moderation: every look and decision is in the audit log, by the operator" \
    "$(sql "SELECT string_agg(action, ',' ORDER BY at, id) FROM media_moderation_log WHERE actor = 'smoke-operator' AND at >= to_timestamp($MO_START)")" "LISTED,RELEASE_REFUSED,RELEASED,DELETED"
else
  skip "moderation: the operator's queue (psql not reachable through docker compose)"
fi
expect "moderation: gratitude_media_quarantined_total is scraped" 200 'gratitude_media_quarantined_total{kind="IMAGE",verdict="QUARANTINED"' -- "$MGMT_BASE/actuator/prometheus"
if grep -q "Media $MO_RED moderated: QUARANTINED (EXTREME_VIOLENCE)" "$MOYI_LOG"; then pass "moderation: the log names the verdict and the reason, by id"; else fail "moderation: verdict logged" "no line names $MO_RED"; fi
if grep -q "${MO_KNOWN_KEY:-in/none}" "$MOYI_LOG"; then fail "moderation: no object key in any log line" "the blocked asset's key is in $MOYI_LOG"; else pass "moderation: no object key in any log line"; fi
rm -rf "$MOD_DIR"
flush_buckets

echo; echo "reports — a member reports the partner's entry to a person, and its media is held back from them (FR-100, ADR-0053)"
# The author uploads a black square (the stand-in passes it) and a voice note, puts both on an entry, and the
# reporter, once the day has revealed, reports it. What each of the two is then shown is ADR-0051's view of
# anything held back; the operator's queue is scripts/moyi reports, against this run's database.
RP_DIR="$(mktemp -d)"
vips black "$RP_DIR/black.jpg" 64 48 --bands 3
python3 - "$RP_DIR/note.wav" <<'PY'
import math, struct, sys
rate, samples = 8000, 16000
pcm = b"".join(struct.pack("<h", int(8000 * math.sin(2 * math.pi * 330 * i / rate))) for i in range(samples))
header = b"RIFF" + struct.pack("<I", 36 + len(pcm)) + b"WAVEfmt " + struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * 2, 2, 16)
open(sys.argv[1], "wb").write(header + b"data" + struct.pack("<I", len(pcm)) + pcm)
PY
# The reporter's words: plainly a test's, and looked for in the log at the end.
RP_DETAIL="smoke-fake-report-detail-$RANDOM"
# reports <args…> — the operator's command, as the operator runs it, against this run's database.
reports() {
  MOYI_OPERATOR=smoke-operator MOYI_OPS_PSQL="docker compose exec -T postgres psql -U moyi -d $MOYI_DB" \
    scripts/moyi reports "$@"
}
# rp_shown <access> <myEntry|partnerEntry> — the entry's photo and voice note as that member is shown them today:
# "<image status> <image url present> <voice status> <voice url present>".
rp_shown() {
  curl -s "$API/bonds/$RP_BOND/today" -H "Authorization: Bearer $1" | python3 -c "
import json, sys
e = json.load(sys.stdin)['$2']
print(e['image']['status'], e['image']['url'] is not None, e['voice']['status'], e['voice']['url'] is not None)"
}
verified_account "reported" "203.0.113.180"; RP_A="$ACCOUNT_ACCESS"
verified_account "reporter" "203.0.113.181"; RP_B="$ACCOUNT_ACCESS"
verified_account "outsider-r" "203.0.113.182"; RP_O="$ACCOUNT_ACCESS"
upload_photo "$RP_A" "$RP_DIR/black.jpg" image/jpeg; RP_PHOTO="$PHOTO_ID"
upload_photo "$RP_A" "$RP_DIR/note.wav" audio/wav VOICE; RP_VOICE="$PHOTO_ID"
for id in "$RP_PHOTO" "$RP_VOICE"; do await_photo "$RP_A" "$id" >/dev/null; done
is "reports: the photo has passed moderation (the stand-in)" "$(await_verdict "$RP_PHOTO")" "PASSED"
is "reports: …and the voice note" "$(await_verdict "$RP_VOICE")" "PASSED"
expect "reports: a bond for the two is 201" 201 '"status":"PENDING_MEMBER"' -- -X POST "$API/bonds" -H "Authorization: Bearer $RP_A" -d "$(bond_body "Us")"
RP_BOND="$(printf '%s' "$LAST_BODY" | jget id)"
RP_CODE="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['code'])" <<<"$LAST_BODY")"
expect "reports: …which the reporter joins" 200 '"status":"ACTIVE"' -- -X POST "$API/invites/$RP_CODE/accept" -H "Authorization: Bearer $RP_B"
expect "reports: the author writes with the photo and the voice note" 201 '"status":"SUBMITTED"' -- -X POST "$API/bonds/$RP_BOND/entries" -H "Authorization: Bearer $RP_A" -H "Idempotency-Key: $(new_key)" -d "{\"text\":\"for you\",\"imageMediaId\":\"$RP_PHOTO\",\"voiceMediaId\":\"$RP_VOICE\"}"
RP_ENTRY="$(printf '%s' "$LAST_BODY" | jget id)"
expect "POST /entries/<an id nobody was issued>/report is 404" 404 '"code":"NOT_FOUND"' -- -X POST "$API/entries/$(new_key)/report" -H "Authorization: Bearer $RP_B" -H "Idempotency-Key: $(new_key)" -d '{"reason":"OTHER"}'
RP_UNKNOWN="$(no_instance)"
expect "reporting the partner's locked entry is 404, as an unknown id is" 404 '"code":"NOT_FOUND"' -- -X POST "$API/entries/$RP_ENTRY/report" -H "Authorization: Bearer $RP_B" -H "Idempotency-Key: $(new_key)" -d '{"reason":"OTHER"}'
is "reports: …the same bytes as the unknown id's" "$(no_instance)" "$RP_UNKNOWN"
expect "reports: the reporter writes, and the day reveals" 201 '"status":"REVEALED"' -- -X POST "$API/bonds/$RP_BOND/entries" -H "Authorization: Bearer $RP_B" -H "Idempotency-Key: $(new_key)" -d '{"text":"and mine"}'
RP_OWN="$(printf '%s' "$LAST_BODY" | jget id)"
is "before the report, the reporter is shown the photo and the voice note READY" "$(rp_shown "$RP_B" partnerEntry)" "READY True READY True"
expect "reporting one's own entry is 409 REPORT_ON_OWN_ENTRY" 409 '"code":"REPORT_ON_OWN_ENTRY"' -- -X POST "$API/entries/$RP_OWN/report" -H "Authorization: Bearer $RP_B" -H "Idempotency-Key: $(new_key)" -d '{"reason":"OTHER"}'
expect "an outsider reporting the entry is 404" 404 '"code":"NOT_FOUND"' -- -X POST "$API/entries/$RP_ENTRY/report" -H "Authorization: Bearer $RP_O" -H "Idempotency-Key: $(new_key)" -d '{"reason":"OTHER"}'
expect "a report with an unknown reason is 422 on reason" 422 '"field":"reason"' -- -X POST "$API/entries/$RP_ENTRY/report" -H "Authorization: Bearer $RP_B" -H "Idempotency-Key: $(new_key)" -d '{"reason":"SPAM"}'
expect "a report with a detail over 500 characters is 422 on detail" 422 '"field":"detail"' -- -X POST "$API/entries/$RP_ENTRY/report" -H "Authorization: Bearer $RP_B" -H "Idempotency-Key: $(new_key)" -d "{\"reason\":\"OTHER\",\"detail\":\"$(printf 'x%.0s' $(seq 1 501))\"}"
expect "a report with no Idempotency-Key is 422 VALIDATION_FAILED" 422 '"code":"VALIDATION_FAILED"' -- -X POST "$API/entries/$RP_ENTRY/report" -H "Authorization: Bearer $RP_B" -d '{"reason":"OTHER"}'
is "reports: …and nothing of those was filed or held" "$(rp_shown "$RP_B" partnerEntry)" "READY True READY True"
RP_KEY="$(new_key)"
expect "the reporter reports the partner's revealed entry: 201" 201 '"reason":"NON_CONSENSUAL"' -- -X POST "$API/entries/$RP_ENTRY/report" -H "Authorization: Bearer $RP_B" -H "Idempotency-Key: $RP_KEY" -d "{\"reason\":\"NON_CONSENSUAL\",\"detail\":\"$RP_DETAIL\"}"
RP_REPORT="$(printf '%s' "$LAST_BODY" | jget id)"
RP_FIRST="$LAST_BODY"
expect "the same Idempotency-Key again is the same report, replayed" 201 "\"id\":\"$RP_REPORT\"" -- -X POST "$API/entries/$RP_ENTRY/report" -H "Authorization: Bearer $RP_B" -H "Idempotency-Key: $RP_KEY" -d "{\"reason\":\"NON_CONSENSUAL\",\"detail\":\"$RP_DETAIL\"}"
header_is "reports: …marked as a replay" "Idempotency-Replayed" "true"
is "reports: …byte for byte the first answer" "$LAST_BODY" "$RP_FIRST"
db_is "reports: …and one report is filed, open, holding the photo and the voice note" \
  "$(sql "SELECT count(*) || '|' || min(status) || '|' || (SELECT count(*) FROM media_report_holds WHERE report_id = '$RP_REPORT') FROM entry_reports WHERE entry_id = '$RP_ENTRY'")" "1|OPEN|2"
is "after the report, the reporter is shown the photo and the voice note PROCESSING, with no URL" "$(rp_shown "$RP_B" partnerEntry)" "PROCESSING False PROCESSING False"
is "after the report, the author is shown both NOT_SHARED, still with their URLs" "$(rp_shown "$RP_A" myEntry)" "NOT_SHARED True NOT_SHARED True"
RP_AUTHOR_VIEW="$(curl -s "$API/bonds/$RP_BOND/today" -H "Authorization: Bearer $RP_A")$(curl -s "$API/bonds/$RP_BOND/days" -H "Authorization: Bearer $RP_A")$(curl -s "$API/media/$RP_PHOTO" -H "Authorization: Bearer $RP_A")"
case "$RP_AUTHOR_VIEW" in *"$RP_REPORT"*|*eport*|*NON_CONSENSUAL*) fail "reports: nothing the author reads names the report" "${RP_AUTHOR_VIEW:0:300}";; *) pass "reports: nothing the author reads names the report";; esac
expect "reports: the reporter still reads the entry's words" 200 '"text":"for you"' -- "$API/bonds/$RP_BOND/today" -H "Authorization: Bearer $RP_B"

if [ "$PSQL_OK" = 1 ]; then
  RP_LIST="$(reports list 2>&1)"
  case "$RP_LIST" in *"$RP_REPORT"$'\t'NON_CONSENSUAL$'\t'ENTRY$'\t'"$RP_ENTRY"$'\t-\t'2$'\t't*) pass "reports: the operator's list shows the report, what it holds and that it has a detail";; *) fail "reports: list" "${RP_LIST:0:400}";; esac
  case "$RP_LIST" in *"$RP_DETAIL"*) fail "reports: …and never the detail" "the detail is in the list";; *) pass "reports: …and never the detail";; esac
  RP_SHOW="$(reports show "$RP_REPORT" 2>&1)"
  case "$RP_SHOW" in *"\"$RP_DETAIL\""*"$RP_PHOTO"*) pass "reports: show gives the operator the detail and what is held";; *) fail "reports: show" "${RP_SHOW:0:400}";; esac
  RP_OUT="$(reports dismiss "$RP_REPORT" "smoke: a made-up black square" 2>&1)" \
    && pass "the operator dismisses the report" || fail "the operator dismisses the report" "$RP_OUT"
  is "after the dismissal, the reporter is shown the photo and the voice note READY again" "$(rp_shown "$RP_B" partnerEntry)" "READY True READY True"
  is "reports: …and the author READY" "$(rp_shown "$RP_A" myEntry)" "READY True READY True"
  db_is "reports: …the detail is gone with the decision" "$(sql "SELECT status || '|' || (detail IS NULL) FROM entry_reports WHERE id = '$RP_REPORT'")" "DISMISSED|true"
  db_is "reports: …and the moderation verdict is the one the photo had" "$(sql "SELECT moderation_verdict || '|' || sensitive FROM media_assets WHERE id = '$RP_PHOTO'")" "PASSED|false"

  expect "a report of sexual content involving a minor is 201" 201 '"reason":"MINOR_SEXUAL_CONTENT"' -- -X POST "$API/entries/$RP_ENTRY/report" -H "Authorization: Bearer $RP_B" -H "Idempotency-Key: $(new_key)" -d '{"reason":"MINOR_SEXUAL_CONTENT"}'
  RP_MINOR="$(printf '%s' "$LAST_BODY" | jget id)"
  db_is "reports: …it holds the photo back and does not block it: a person decides (Q16)" \
    "$(sql "SELECT status || '|' || moderation_verdict || '|' || evidence_held FROM media_assets WHERE id = '$RP_PHOTO'")" "QUARANTINED|QUARANTINED|false"
  RP_LIST="$(reports list 2>&1)"
  if printf '%s\n' "$RP_LIST" | sed -n 2p | grep -q "^$RP_MINOR"$'\t'"MINOR_SEXUAL_CONTENT.*DO NOT VIEW — runbook §2$"; then
    pass "reports: …and it is first in the operator's list, marked DO NOT VIEW"
  else fail "reports: priority list" "${RP_LIST:0:400}"; fi
  if RP_OUT="$(reports uphold "$RP_MINOR" delete "smoke: trying" 2>&1)"; then fail "upholding a minor report by delete is refused" "it was upheld: $RP_OUT"
  else case "$RP_OUT" in *"never upheld by delete"*) pass "upholding a minor report by delete is refused";; *) fail "upholding a minor report by delete is refused" "$RP_OUT";; esac; fi
  RP_OUT="$(reports uphold "$RP_MINOR" block "smoke: FAKE-SMOKE-0002" 2>&1)" \
    && pass "the operator upholds it by blocking" || fail "the operator upholds it by blocking" "$RP_OUT"
  db_is "reports: …the photo is BLOCKED and held as evidence" \
    "$(sql "SELECT status || '|' || moderation_verdict || '|' || evidence_held FROM media_assets WHERE id = '$RP_PHOTO'")" "QUARANTINED|BLOCKED|true"
  is "reports: …the author is shown both NOT_SHARED, with no URL: both are blocked" "$(rp_shown "$RP_A" myEntry)" "NOT_SHARED False NOT_SHARED False"
  # Not left for the next run's media check: both are let go as the runbook's §2 lets them go, after "the report".
  for id in "$RP_PHOTO" "$RP_VOICE"; do quarantine delete "$id" "smoke: reported, ref FAKE-SMOKE-0002" >/dev/null 2>&1 || true; done
  db_is "reports: every look and decision is in the report log, by the operator" \
    "$(sql "SELECT string_agg(action, ',' ORDER BY at, id) FROM entry_report_log WHERE actor = 'smoke-operator' AND (report_id IN ('$RP_REPORT', '$RP_MINOR') OR report_id IS NULL) AND at >= to_timestamp($MO_START)")" "LISTED,READ,DISMISSED,LISTED,UPHELD"

  # ADR-0053 decision 8a: the author erases a reported entry; to everyone it is gone, but its photo's
  # objects stay until the report is decided, and then the purge runs. A pair of its own: the first
  # pair has written today.
  verified_account "erasing" "203.0.113.183"; RE_A="$ACCOUNT_ACCESS"
  verified_account "erased-reporter" "203.0.113.184"; RE_B="$ACCOUNT_ACCESS"
  upload_photo "$RE_A" "$RP_DIR/black.jpg" image/jpeg; RE_PHOTO="$PHOTO_ID"
  await_photo "$RE_A" "$RE_PHOTO" >/dev/null
  is "reports: the second pair's photo has passed moderation" "$(await_verdict "$RE_PHOTO")" "PASSED"
  RE_KEY="$(sql "SELECT storage_key FROM media_assets WHERE id='$RE_PHOTO'")"
  expect "reports: a bond for the second pair" 201 '"status":"PENDING_MEMBER"' -- -X POST "$API/bonds" -H "Authorization: Bearer $RE_A" -d "$(bond_body "Us")"
  RE_BOND="$(printf '%s' "$LAST_BODY" | jget id)"
  RE_CODE="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['code'])" <<<"$LAST_BODY")"
  expect "reports: …joined" 200 '"status":"ACTIVE"' -- -X POST "$API/invites/$RE_CODE/accept" -H "Authorization: Bearer $RE_B"
  expect "reports: the author writes with the photo" 201 '"status":"SUBMITTED"' -- -X POST "$API/bonds/$RE_BOND/entries" -H "Authorization: Bearer $RE_A" -H "Idempotency-Key: $(new_key)" -d "{\"text\":\"a photo\",\"imageMediaId\":\"$RE_PHOTO\"}"
  RE_ENTRY="$(printf '%s' "$LAST_BODY" | jget id)"
  expect "reports: the reporter writes, and the day reveals" 201 '"status":"REVEALED"' -- -X POST "$API/bonds/$RE_BOND/entries" -H "Authorization: Bearer $RE_B" -H "Idempotency-Key: $(new_key)" -d '{"text":"and mine"}'
  expect "reports: the reporter reports it" 201 '"reason":"NON_CONSENSUAL"' -- -X POST "$API/entries/$RE_ENTRY/report" -H "Authorization: Bearer $RE_B" -H "Idempotency-Key: $(new_key)" -d '{"reason":"NON_CONSENSUAL"}'
  RE_REPORT="$(printf '%s' "$LAST_BODY" | jget id)"
  expect "the author deletes the reported entry: 204" 204 '' -- -X DELETE "$API/entries/$RE_ENTRY" -H "Authorization: Bearer $RE_A"
  sleep 3 # media.purge's poller has had its chance; what is checked is that it did not purge
  db_is "reports: …the photo is deleted to everyone, still held, and not purged" \
    "$(sql "SELECT status || '|' || (purged_at IS NULL) || '|' || (SELECT count(*) FROM media_report_holds WHERE media_id = '$RE_PHOTO') FROM media_assets WHERE id = '$RE_PHOTO'")" "DELETED|true|1"
  if [ "$MINIO_OK" = 0 ]; then skip "reports: …its objects are kept in MinIO while the report is open"
  elif minio_has "v/${RE_KEY#in/}/display.webp"; then pass "reports: …its objects are kept in MinIO while the report is open"
  else fail "reports: …its objects are kept in MinIO while the report is open" "the display variant is gone"; fi
  RP_OUT="$(reports dismiss "$RE_REPORT" "smoke: dismissed, so the purge runs" 2>&1)" \
    && pass "the operator dismisses the report on the erased entry" || fail "the operator dismisses the report on the erased entry" "$RP_OUT"
  RE_PURGED="f"
  for _ in $(seq 1 40); do
    RE_PURGED="$(sql "SELECT purged_at IS NOT NULL FROM media_assets WHERE id='$RE_PHOTO'")"
    [ "$RE_PURGED" = "t" ] && break
    sleep 0.5
  done
  db_is "reports: …and media.purge then deletes its objects within seconds" "$RE_PURGED" "t"
  if [ "$MINIO_OK" = 0 ]; then skip "reports: …nothing of it is left in MinIO"
  elif minio_has "v/${RE_KEY#in/}/display.webp"; then fail "reports: …nothing of it is left in MinIO" "MinIO still holds its display variant"
  else pass "reports: …nothing of it is left in MinIO"; fi
else
  skip "reports: the operator's queue (psql not reachable through docker compose)"
fi

flush_buckets
for _ in $(seq 1 10); do
  curl -s -o /dev/null -X POST "$API/entries/$(new_key)/report" -H "Authorization: Bearer $RP_O" -H "Idempotency-Key: $(new_key)" -H 'Content-Type: application/json' -d '{"reason":"OTHER"}'
done
expect "the eleventh report in a day is 429 RATE_LIMITED" 429 '"code":"RATE_LIMITED"' -- -X POST "$API/entries/$RP_ENTRY/report" -H "Authorization: Bearer $RP_O" -H "Idempotency-Key: $(new_key)" -d '{"reason":"OTHER"}'
header_is "reports: …ten a day" "X-RateLimit-Limit" "10"
if grep -q "$RP_DETAIL" "$MOYI_LOG"; then fail "the report's detail is in no log line" "it is in $MOYI_LOG"; else pass "the report's detail is in no log line"; fi
rm -rf "$RP_DIR"
flush_buckets

echo; echo "member reports — a member reports the other member of the bond to a person (FR-100's member half, ADR-0053 amended for #127)"
# The reported member's own reads are taken before and after, bytes and ETag: nothing they can see may change
# (doc 26 §2.2). The report is then the operator's, in the same queue as an entry report.
verified_account "mr-reported" "203.0.113.185"; MR_A="$ACCOUNT_ACCESS"
verified_account "mr-reporter" "203.0.113.186"; MR_B="$ACCOUNT_ACCESS"
verified_account "mr-stranger" "203.0.113.187"; MR_O="$ACCOUNT_ACCESS"
expect "member reports: a bond for the two" 201 '"status":"PENDING_MEMBER"' -- -X POST "$API/bonds" -H "Authorization: Bearer $MR_A" -d "$(bond_body "Us")"
MR_BOND="$(printf '%s' "$LAST_BODY" | jget id)"
MR_CODE="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['code'])" <<<"$LAST_BODY")"
expect "reporting the member of a bond with no partner yet is 404" 404 '"code":"NOT_FOUND"' -- -X POST "$API/bonds/$MR_BOND/members/other/report" -H "Authorization: Bearer $MR_A" -H "Idempotency-Key: $(new_key)" -d '{"reason":"OTHER"}'
MR_ALONE="$(no_instance | sed "s/$MR_BOND/-/g")"
expect "member reports: …joined" 200 '"status":"ACTIVE"' -- -X POST "$API/invites/$MR_CODE/accept" -H "Authorization: Bearer $MR_B"
expect "a stranger reporting a member of the bond is 404" 404 '"code":"NOT_FOUND"' -- -X POST "$API/bonds/$MR_BOND/members/other/report" -H "Authorization: Bearer $MR_O" -H "Idempotency-Key: $(new_key)" -d '{"reason":"OTHER"}'
is "member reports: …the same bytes as the bond with nobody to report" "$(no_instance | sed "s/$MR_BOND/-/g")" "$MR_ALONE"
expect "member reports: the reported member writes" 201 '"status":"SUBMITTED"' -- -X POST "$API/bonds/$MR_BOND/entries" -H "Authorization: Bearer $MR_A" -H "Idempotency-Key: $(new_key)" -d '{"text":"for you"}'
expect "member reports: the reporter writes, and the day reveals" 201 '"status":"REVEALED"' -- -X POST "$API/bonds/$MR_BOND/entries" -H "Authorization: Bearer $MR_B" -H "Idempotency-Key: $(new_key)" -d '{"text":"and mine"}'
expect "member reports: the reported member reads the bond, before" 200 "\"id\":\"$MR_BOND\"" -- "$API/bonds/$MR_BOND" -H "Authorization: Bearer $MR_A"
MR_BOND_BEFORE="$LAST_BODY"; MR_BOND_TAG_BEFORE="$(tag)"
expect "member reports: the reported member reads today, before" 200 '"myEntry"' -- "$API/bonds/$MR_BOND/today" -H "Authorization: Bearer $MR_A"
MR_TODAY_BEFORE="$LAST_BODY"; MR_TODAY_TAG_BEFORE="$(tag)"
expect "a report with an unknown reason is 422 on the member route too" 422 '"field":"reason"' -- -X POST "$API/bonds/$MR_BOND/members/other/report" -H "Authorization: Bearer $MR_B" -H "Idempotency-Key: $(new_key)" -d '{"reason":"SPAM"}'
MR_KEY="$(new_key)"
expect "the reporter reports the other member: 201" 201 '"reason":"HARASSMENT"' -- -X POST "$API/bonds/$MR_BOND/members/other/report" -H "Authorization: Bearer $MR_B" -H "Idempotency-Key: $MR_KEY" -d '{"reason":"HARASSMENT","detail":"smoke-fake-member-report-detail"}'
MR_FIRST="$LAST_BODY"; MR_REPORT="$(printf '%s' "$LAST_BODY" | jget id)"
is "member reports: …the answer is the id and the reason, nothing else" "$(python3 -c 'import json,sys; print(sorted(json.loads(sys.argv[1])))' "$MR_FIRST")" "['id', 'reason']"
expect "the same key again is the same member report, replayed" 201 "\"id\":\"$MR_REPORT\"" -- -X POST "$API/bonds/$MR_BOND/members/other/report" -H "Authorization: Bearer $MR_B" -H "Idempotency-Key: $MR_KEY" -d '{"reason":"HARASSMENT","detail":"smoke-fake-member-report-detail"}'
header_is "member reports: …marked as a replay" "Idempotency-Replayed" "true"
db_is "member reports: …one report, of the member, open, holding nothing" \
  "$(sql "SELECT count(*) || '|' || min(target) || '|' || bool_and(entry_id IS NULL) || '|' || min(status) || '|' || (SELECT count(*) FROM media_report_holds WHERE report_id = '$MR_REPORT') FROM entry_reports WHERE bond_id = '$MR_BOND'")" "1|MEMBER|true|OPEN|0"
expect "member reports: the reported member reads the bond, after" 200 "\"id\":\"$MR_BOND\"" -- "$API/bonds/$MR_BOND" -H "Authorization: Bearer $MR_A"
is "member reports: …the reported member's GET /bonds/{id} is byte for byte what it was" "$LAST_BODY" "$MR_BOND_BEFORE"
is "member reports: …and its ETag" "$(tag)" "$MR_BOND_TAG_BEFORE"
case "$MR_BOND_TAG_BEFORE" in '"'*'"') pass "member reports: …which is a real ETag ($MR_BOND_TAG_BEFORE)";; *) fail "member reports: the bond's ETag" "got '$MR_BOND_TAG_BEFORE'";; esac
expect "member reports: the reported member reads today, after" 200 '"myEntry"' -- "$API/bonds/$MR_BOND/today" -H "Authorization: Bearer $MR_A"
is "member reports: …the reported member's GET /today is byte for byte what it was" "$LAST_BODY" "$MR_TODAY_BEFORE"
is "member reports: …and its ETag" "$(tag)" "$MR_TODAY_TAG_BEFORE"
expect "member reports: the reporter blocks the reported member" 204 '' -- -X POST "$API/bonds/$MR_BOND/block" -H "Authorization: Bearer $MR_B" -d '{"withdrawEntries":false}'
expect "a member report after a block is 201" 201 '"reason":"VIOLENCE_OR_SELF_HARM"' -- -X POST "$API/bonds/$MR_BOND/members/other/report" -H "Authorization: Bearer $MR_B" -H "Idempotency-Key: $(new_key)" -d '{"reason":"VIOLENCE_OR_SELF_HARM"}'
MR_AFTER_BLOCK="$(printf '%s' "$LAST_BODY" | jget id)"
if docker compose exec -T postgres true >/dev/null 2>&1; then
  MR_LIST="$(reports list 2>&1)"
  case "$MR_LIST" in *"$MR_REPORT"$'\t'HARASSMENT$'\t'MEMBER$'\t-\t'*$'\t'0$'\t't*) pass "member reports: the operator's list shows the member report, naming no entry and holding nothing";; *) fail "member reports: list" "${MR_LIST:0:400}";; esac
  MR_OUT="$(reports dismiss "$MR_REPORT" "smoke: dismissed" 2>&1)" && MR_OUT2="$(reports dismiss "$MR_AFTER_BLOCK" "smoke: dismissed" 2>&1)" \
    && pass "the operator dismisses the member reports" || fail "the operator dismisses the member reports" "$MR_OUT ${MR_OUT2:-}"
else
  skip "member reports: the operator's queue (psql not reachable through docker compose)"
fi
if grep -q "smoke-fake-member-report-detail" "$MOYI_LOG"; then fail "the member report's detail is in no log line" "it is in $MOYI_LOG"; else pass "the member report's detail is in no log line"; fi
flush_buckets

echo; echo "support — a message to a person, acknowledged without its words (FR-102, ADR-0057)"
support() {
  MOYI_OPERATOR=smoke-operator MOYI_OPS_PSQL="docker compose exec -T postgres psql -U moyi -d $MOYI_DB" \
    scripts/moyi support "$@"
}
verified_account "support" "203.0.113.190"; SU_A="$ACCOUNT_ACCESS"
flush_buckets
SU_SUBJECT="Support~subject~$RANDOM$RANDOM"
SU_BODY="Support~body~$RANDOM$RANDOM we cannot see today"
SU_MESSAGE="{\"subject\":\"$SU_SUBJECT\",\"body\":\"$SU_BODY\"}"
expect "a support message without a token is 401 UNAUTHENTICATED" 401 '"code":"UNAUTHENTICATED"' -- -X POST "$API/support/messages" -d "$SU_MESSAGE"
expect "a support message with no subject is 422 on subject" 422 '"field":"subject"' -- -X POST "$API/support/messages" -H "Authorization: Bearer $SU_A" -d "{\"body\":\"$SU_BODY\"}"
expect "a support message with a subject over 120 characters is 422 on subject" 422 '"field":"subject"' -- -X POST "$API/support/messages" -H "Authorization: Bearer $SU_A" -d "{\"subject\":\"$(printf 's%.0s' $(seq 1 121))\",\"body\":\"$SU_BODY\"}"
expect "a support message with a body over 5000 characters is 422 on body" 422 '"field":"body"' -- -X POST "$API/support/messages" -H "Authorization: Bearer $SU_A" -d "{\"subject\":\"$SU_SUBJECT\",\"body\":\"$(python3 -c 'print("b" * 5001)')\"}"
if [[ "$LAST_BODY" == *"$SU_SUBJECT"* || "$LAST_BODY" == *"bbbbbbbbbb"* ]]; then fail "support: …and the refusal repeats nothing sent" "${LAST_BODY:0:200}"; else pass "support: …and the refusal repeats nothing sent"; fi
SU_KEY="$(new_key)"
expect "a support message is 201 with its id and time" 201 '"createdAt"' -- -X POST "$API/support/messages" -H "Authorization: Bearer $SU_A" -H "Idempotency-Key: $SU_KEY" -d "$SU_MESSAGE"
SU_ID="$(printf '%s' "$LAST_BODY" | jget id)"
SU_FIRST="$LAST_BODY"
if [[ "$LAST_BODY" == *"$SU_BODY"* || "$LAST_BODY" == *"$SU_SUBJECT"* ]]; then fail "support: …which repeats nothing of the message" "${LAST_BODY:0:200}"; else pass "support: …which repeats nothing of the message"; fi
expect "a support message replayed under its key is the same ticket" 201 "\"id\":\"$SU_ID\"" -- -X POST "$API/support/messages" -H "Authorization: Bearer $SU_A" -H "Idempotency-Key: $SU_KEY" -d "$SU_MESSAGE"
header_is "support: …and says it was replayed" Idempotency-Replayed true
is "support: …the same body, byte for byte" "$LAST_BODY" "$SU_FIRST"
expect "the sixth support message in a day is 429 RATE_LIMITED" 429 '"code":"RATE_LIMITED"' -- -X POST "$API/support/messages" -H "Authorization: Bearer $SU_A" -d "$SU_MESSAGE"
header_is "support: …five a day" X-RateLimit-Limit 5
SU_RETRY="$(printf '%s\n' "$LAST_HEADERS" | grep -i '^retry-after:' | head -1 | cut -d' ' -f2- || true)"
if [[ "$SU_RETRY" =~ ^[0-9]+$ ]] && [ "$SU_RETRY" -gt 0 ]; then pass "the sixth support message carries Retry-After ($SU_RETRY s)"; else fail "the sixth support message carries Retry-After" "got '${SU_RETRY:-<absent>}'"; fi
db_is "support: one ticket was filed, open, for the replay and the 201 together" "$(sql "SELECT count(*) || '|' || min(status) FROM support_tickets WHERE user_id = (SELECT user_id FROM support_tickets WHERE id = '$SU_ID')")" "1|OPEN"
SU_ACK=""
for _ in $(seq 1 40); do
  SU_ACK="$(grep -c "Support ticket $SU_ID acknowledgement: ACCEPTED" "$MOYI_LOG" || true)"
  [ "$SU_ACK" -gt 0 ] && break
  sleep 0.25
done
is "support: the acknowledgement was sent once (provider=log)" "$SU_ACK" "1"
if grep -q "Your reference is $SU_ID" "$MOYI_LOG"; then pass "the support acknowledgement gives the ticket's id as its reference"; else fail "the support acknowledgement gives the ticket's id as its reference" "no 'Your reference is $SU_ID' in $MOYI_LOG"; fi
if grep -qF "$SU_BODY" "$MOYI_LOG" || grep -qF "$SU_SUBJECT" "$MOYI_LOG"; then fail "the support acknowledgement does not repeat the message" "the subject or body is in $MOYI_LOG"; else pass "the support acknowledgement does not repeat the message, nor does any log line"; fi
if [ "$PSQL_OK" = 1 ]; then
  SU_OUT="$(support list 2>&1)" || true
  if [[ "$SU_OUT" == *"$SU_ID"* && "$SU_OUT" != *"$SU_BODY"* ]]; then pass "support: the operator's list names the ticket, not its words"; else fail "support: the operator's list names the ticket, not its words" "${SU_OUT:0:300}"; fi
  SU_OUT="$(support show "$SU_ID" 2>&1)" || true
  if [[ "$SU_OUT" == *"$SU_BODY"* && "$SU_OUT" == *"reply to:   end-support-"* ]]; then pass "support: show gives the words and the address to answer at"; else fail "support: show gives the words and the address to answer at" "${SU_OUT:0:300}"; fi
  SU_OUT="$(support close "$SU_ID" "smoke: answered by email" 2>&1)" && pass "support: the operator closes it" || fail "support: the operator closes it" "$SU_OUT"
  db_is "support: …its words are deleted, and every look is logged" \
    "$(sql "SELECT (subject IS NULL AND body IS NULL) || '|' || status || '|' || (SELECT string_agg(action, ',' ORDER BY at) FROM support_ticket_log WHERE ticket_id = '$SU_ID') FROM support_tickets WHERE id = '$SU_ID'")" "true|CLOSED|READ,CLOSED"
else
  skip "support: the operator's queue (psql not reachable through docker compose)"
fi
flush_buckets

echo; echo "support — the error reference sent with a message (#168, ADR-0057 decision 11)"
# Optional, 1 to 64 visible ASCII characters (0x21 to 0x7E): stored with the ticket, shown to the
# operator, never in the acknowledgement email nor any log line, and deleted with the words on close.
SR_REFERENCE="smoke-ref-$RANDOM$RANDOM-4bf92f3577b34da6"
SR_MESSAGE="{\"subject\":\"$SU_SUBJECT\",\"body\":\"$SU_BODY\",\"reference\":\"$SR_REFERENCE\"}"
expect "a support reference with a space is 422 on reference" 422 '"field":"reference"' -- -X POST "$API/support/messages" -H "Authorization: Bearer $SU_A" -d "{\"subject\":\"$SU_SUBJECT\",\"body\":\"$SU_BODY\",\"reference\":\"$SR_REFERENCE x\"}"
if [[ "$LAST_BODY" == *"$SR_REFERENCE"* ]]; then fail "support: …and the refusal repeats no reference" "${LAST_BODY:0:200}"; else pass "support: …and the refusal repeats no reference"; fi
expect "a support reference of 65 characters is 422 on reference" 422 '"field":"reference"' -- -X POST "$API/support/messages" -H "Authorization: Bearer $SU_A" -d "{\"subject\":\"$SU_SUBJECT\",\"body\":\"$SU_BODY\",\"reference\":\"$(printf 'r%.0s' $(seq 1 65))\"}"
expect "an empty support reference is 422 on reference" 422 '"field":"reference"' -- -X POST "$API/support/messages" -H "Authorization: Bearer $SU_A" -d "{\"subject\":\"$SU_SUBJECT\",\"body\":\"$SU_BODY\",\"reference\":\"\"}"
expect "a support reference past ASCII is 422 on reference" 422 '"field":"reference"' -- -X POST "$API/support/messages" -H "Authorization: Bearer $SU_A" -d "{\"subject\":\"$SU_SUBJECT\",\"body\":\"$SU_BODY\",\"reference\":\"caf\u00e9\"}"
flush_buckets
expect "a support message with a reference is 201 with its id and time" 201 '"createdAt"' -- -X POST "$API/support/messages" -H "Authorization: Bearer $SU_A" -d "$SR_MESSAGE"
SR_ID="$(printf '%s' "$LAST_BODY" | jget id)"
if [[ "$LAST_BODY" == *"$SR_REFERENCE"* ]]; then fail "support: …which does not repeat the reference" "${LAST_BODY:0:200}"; else pass "support: …which does not repeat the reference"; fi
SR_ACK=""
for _ in $(seq 1 40); do
  SR_ACK="$(grep -c "Support ticket $SR_ID acknowledgement: ACCEPTED" "$MOYI_LOG" || true)"
  [ "$SR_ACK" -gt 0 ] && break
  sleep 0.25
done
is "support: the referenced message was acknowledged (provider=log)" "$SR_ACK" "1"
if grep -qF "$SR_REFERENCE" "$MOYI_LOG"; then fail "the support reference is in no email and no log line" "it is in $MOYI_LOG"; else pass "the support reference is in no email and no log line"; fi
if [ "$PSQL_OK" = 1 ]; then
  db_is "support: the reference is stored with the ticket" "$(sql "SELECT reference FROM support_tickets WHERE id = '$SR_ID'")" "$SR_REFERENCE"
  SR_OUT="$(support show "$SR_ID" 2>&1)" || true
  if [[ "$SR_OUT" == *"reference:  $SR_REFERENCE"* ]]; then pass "support: show gives the operator the reference"; else fail "support: show gives the operator the reference" "${SR_OUT:0:400}"; fi
  SR_OUT="$(support close "$SR_ID" "smoke: found the trace" 2>&1)" && pass "support: the operator closes the referenced one" || fail "support: the operator closes the referenced one" "$SR_OUT"
  db_is "support: …its reference is deleted with its words" \
    "$(sql "SELECT (subject IS NULL AND body IS NULL AND reference IS NULL) || '|' || status FROM support_tickets WHERE id = '$SR_ID'")" "true|CLOSED"
else
  skip "support: the reference in the operator's queue (psql not reachable through docker compose)"
fi
flush_buckets

echo; echo "data export — asked for, built by the job, downloaded, expired by a withdrawal, limited (FR-009, FR-094, ADR-0059)"
# The job runs on a five-second schedule here (the boot above; with --attach, start the server with
# --moyi.scheduling.data-export.cron='*/5 * * * * *'). The first archive is built while the bond is
# whole; then the partner blocks and withdraws, and that archive, which holds the partner's words, must
# stop being downloadable (ADR-0059 decision 12); the next one shows them as a blank placeholder.
verified_account "exporter" "203.0.113.210"; EX_A="$ACCOUNT_ACCESS"
verified_account "exporters-partner" "203.0.113.211"; EX_B="$ACCOUNT_ACCESS"
flush_buckets
EX_WORDS="Exporter~words~$RANDOM$RANDOM"
EX_PARTNER_WORDS="Withdrawn~words~$RANDOM$RANDOM"
revealed_bond "data export" "$EX_A" "$EX_B" "$EX_WORDS" "$EX_PARTNER_WORDS"
EX_BOND="$RB_BOND"; EX_B_ENTRY="$RB_B_ENTRY"
expect "data export without a token is 401 UNAUTHENTICATED" 401 '"code":"UNAUTHENTICATED"' -- -X POST "$API/me/export"
expect "the latest export with none asked for is 404" 404 '"code":"NOT_FOUND"' -- "$API/me/export" -H "Authorization: Bearer $EX_A"
expect "data export request is 202 PREPARING" 202 '"status":"PREPARING"' -- -X POST "$API/me/export" -H "Authorization: Bearer $EX_A"
EX_ID="$(printf '%s' "$LAST_BODY" | jget id)"
expect "asking again while it is prepared is the same export" 202 "\"id\":\"$EX_ID\"" -- -X POST "$API/me/export" -H "Authorization: Bearer $EX_A"
expect "the latest export is the one asked for" 200 "\"id\":\"$EX_ID\"" -- "$API/me/export" -H "Authorization: Bearer $EX_A"
expect "someone else's export is 404" 404 '"code":"NOT_FOUND"' -- "$API/me/export/$EX_ID" -H "Authorization: Bearer $EX_B"
EX_THEIRS="$(printf '%s' "$LAST_BODY" | sed "s#$EX_ID#ID#")"
EX_NOBODY_ID="$(new_key)"
expect "…and one that does not exist is 404" 404 '"code":"NOT_FOUND"' -- "$API/me/export/$EX_NOBODY_ID" -H "Authorization: Bearer $EX_B"
is "…the same bytes but for the path" "$(printf '%s' "$LAST_BODY" | sed "s#$EX_NOBODY_ID#ID#")" "$EX_THEIRS"
expect "the export answers its state while the job builds it" 200 "\"id\":\"$EX_ID\"" -- "$API/me/export/$EX_ID" -H "Authorization: Bearer $EX_A"
EX_MAILS="$(grep -c "Your Moyi archive is ready" "$MOYI_LOG" || true)"
# await_export <id> <status>: up to 60 seconds, the job running every five here.
await_export() {
  for _ in $(seq 1 60); do
    [[ "$(curl -s "$API/me/export/$1" -H "Authorization: Bearer $EX_A")" == *"\"status\":\"$2\""* ]] && break; sleep 1
  done
}
# url_of <READY body>: its downloadUrl. fetch_archive <url> <dir>: the zip unzipped into <dir>/x; the HTTP status.
url_of() { python3 -c "import json,sys; print(json.loads(sys.argv[1])['downloadUrl'])" "$1" 2>/dev/null || echo none; }
fetch_archive() {
  local code; code="$(curl -s -o "$2/archive.zip" -w '%{http_code}' "$1" || true)"
  [ "$code" = 200 ] && unzip -qo "$2/archive.zip" -d "$2/x" 2>/dev/null
  printf '%s' "$code"
}
await_export "$EX_ID" READY
expect "the job makes the export READY" 200 '"status":"READY"' -- "$API/me/export/$EX_ID" -H "Authorization: Bearer $EX_A"
EX_NOW="$LAST_BODY"
is "…its URL good for exactly 24 hours" "$(python3 -c "
import json, sys
from datetime import datetime
b = json.loads(sys.argv[1]); p = lambda k: datetime.fromisoformat(b[k].replace('Z', '+00:00'))
print(int((p('expiresAt') - p('readyAt')).total_seconds()), 'X-Amz-Expires=86400' in b['downloadUrl'])" "$EX_NOW" 2>&1)" "86400 True"
EX_DIR="$(mktemp -d)"
EX_FIRST_URL="$(url_of "$EX_NOW")"
is "the READY export's URL downloads a zip" "$(fetch_archive "$EX_FIRST_URL" "$EX_DIR")" "200"
is "…of the JSON and the README" "$(cd "$EX_DIR/x" 2>/dev/null && ls | sort | tr '\n' ' ')" "README.txt moyi-export.json "
if grep -q "$EX_WORDS" "$EX_DIR/x/moyi-export.json" && grep -q "$EX_PARTNER_WORDS" "$EX_DIR/x/moyi-export.json"; then pass "…holding both people's revealed words, the bond being whole"; else fail "…holding both people's revealed words, the bond being whole" "not both in the archive"; fi
rm -rf "$EX_DIR"
for _ in $(seq 1 20); do [ "$(grep -c "Your Moyi archive is ready" "$MOYI_LOG" || true)" -gt "$EX_MAILS" ] && break; sleep 0.25; done
is "the person is emailed that it is ready" "$(( $(grep -c "Your Moyi archive is ready" "$MOYI_LOG" || true) - EX_MAILS ))" "1"
expect "data export: the partner blocks and withdraws their words" 204 "" -- -X POST "$API/bonds/$EX_BOND/block" -H "Authorization: Bearer $EX_B" -d '{"withdrawEntries": true}'
await_export "$EX_ID" EXPIRED
expect "the withdrawal expires the READY export that holds the words" 200 '"status":"EXPIRED"' -- "$API/me/export/$EX_ID" -H "Authorization: Bearer $EX_A"
is "…and its URL no longer downloads it" "$(curl -s -o /dev/null -w '%{http_code}' "$EX_FIRST_URL")" "404"
expect "asking after it expired is a new export, on the blocked bond (BR-9)" 202 '"status":"PREPARING"' -- -X POST "$API/me/export" -H "Authorization: Bearer $EX_A"
EX_ID2="$(printf '%s' "$LAST_BODY" | jget id)"
[ "$EX_ID2" != "$EX_ID" ] && pass "…with a new id" || fail "…with a new id" "${LAST_BODY:0:200}"
await_export "$EX_ID2" READY
expect "the new export is READY" 200 '"status":"READY"' -- "$API/me/export/$EX_ID2" -H "Authorization: Bearer $EX_A"
EX_DIR="$(mktemp -d)"
is "…its URL downloads a zip" "$(fetch_archive "$(url_of "$LAST_BODY")" "$EX_DIR")" "200"
is "…holding the person's own words, and the partner's withdrawn entry as a blank placeholder" "$(python3 -c "
import json, sys
a = json.load(open(sys.argv[1]))
entries = [e for b in a['bonds'] for d in b['days'] for e in d['entries']]
mine = [e['text'] for e in entries if e['author'] == 'ME']
theirs = [(e['id'], e['status'], e['text']) for e in entries if e['author'] == 'PARTNER']
print(mine == [sys.argv[2]], theirs == [(sys.argv[3], 'DELETED', None)], a['bonds'][0]['bond']['status'], a['adminAccess']['logKept'])" "$EX_DIR/x/moyi-export.json" "$EX_WORDS" "$EX_B_ENTRY" 2>&1)" "True True ARCHIVED True"
if grep -q "$EX_PARTNER_WORDS" "$EX_DIR/x/moyi-export.json"; then fail "…and not a word the partner withdrew" "found in the archive"; else pass "…and not a word the partner withdrew"; fi
rm -rf "$EX_DIR"
if grep -q "X-Amz-Signature" "$MOYI_LOG"; then fail "no signed URL reaches a log, the email's included" "one is in $MOYI_LOG"; else pass "no signed URL reaches a log, the email's included"; fi
expect "asking after it is READY is a new export, the third of the day" 202 '"status":"PREPARING"' -- -X POST "$API/me/export" -H "Authorization: Bearer $EX_A"
EX_ID3="$(printf '%s' "$LAST_BODY" | jget id)"
expect "a repeat while it is prepared spends none of the three" 202 "\"id\":\"$EX_ID3\"" -- -X POST "$API/me/export" -H "Authorization: Bearer $EX_A"
if [ "$PSQL_OK" = 1 ]; then
  db_is "…the third set aside by hand, so a fourth would be new" \
    "$(sql "WITH f AS (UPDATE data_exports SET status = 'FAILED', ended_at = now(), claimed_until = NULL WHERE id = '$EX_ID3' AND status = 'PREPARING' RETURNING 1) SELECT count(*) FROM f")" "1"
  expect "the fourth new export in a day is 429" 429 '"code":"RATE_LIMITED"' -- -X POST "$API/me/export" -H "Authorization: Bearer $EX_A"
else
  skip "data export: the fourth new export in a day (psql not reachable through docker compose)"
fi
if grep -q "$EX_WORDS" "$MOYI_LOG"; then fail "the exported words are in no log line" "they are in $MOYI_LOG"; else pass "the exported words are in no log line"; fi
flush_buckets

echo; echo "account deletion — asked for, called off, asked again, carried out (FR-008, ADR-0058)"
# The job runs on a five-second schedule here (the boot above; with --attach, start the server with
# --moyi.scheduling.account-deletion.cron='*/5 * * * * *'), and the window is moved back by SQL,
# because fourteen days cannot be waited out. Everything before the move is the API alone.
verified_account "deleter" "203.0.113.200"; DL_A="$ACCOUNT_ACCESS"; DL_EMAIL="$ACCOUNT_EMAIL"
verified_account "deleters-partner" "203.0.113.201"; DL_B="$ACCOUNT_ACCESS"
flush_buckets
DL_WORDS="Deleter~words~$RANDOM$RANDOM"
revealed_bond "account deletion" "$DL_A" "$DL_B" "$DL_WORDS" "Partner~words~$RANDOM"
DL_BOND="$RB_BOND"; DL_A_ENTRY="$RB_A_ENTRY"; DL_B_ENTRY="$RB_B_ENTRY"
expect "account deletion without a token is 401 UNAUTHENTICATED" 401 '"code":"UNAUTHENTICATED"' -- -X POST "$API/me/deletion-request" -d "{\"password\":\"$PASSWORD\"}"
expect "account deletion with no password is 422 on password" 422 '"field":"password"' -- -X POST "$API/me/deletion-request" -H "Authorization: Bearer $DL_A" -d '{}'
expect "account deletion with a blank password is 422 on password" 422 '"field":"password"' -- -X POST "$API/me/deletion-request" -H "Authorization: Bearer $DL_A" -d '{"password":"  "}'
expect "account deletion with a wrong password is 403 INVALID_CREDENTIALS" 403 '"code":"INVALID_CREDENTIALS"' -- -X POST "$API/me/deletion-request" -H "Authorization: Bearer $DL_A" -d '{"password":"not the password"}'
header_is "…without WWW-Authenticate: the session is fine" "WWW-Authenticate" ""
expect "GET /me says no deletion is pending" 200 '"deletionRequest":null' -- "$API/me" -H "Authorization: Bearer $DL_A"
expect "cancelling with nothing pending is 404 NOT_FOUND" 404 '"code":"NOT_FOUND"' -- -X DELETE "$API/me/deletion-request" -H "Authorization: Bearer $DL_A"
DL_NOTHING="$LAST_BODY"
DL_MAILS="$(grep -c "Your Moyi account will be deleted" "$MOYI_LOG" || true)"
expect "account deletion with the password is 202 with its dates" 202 '"cancellableUntil":"' -- -X POST "$API/me/deletion-request" -H "Authorization: Bearer $DL_A" -d "{\"password\":\"$PASSWORD\"}"
DL_FIRST="$LAST_BODY"
is "…cancellable for 14 days, done within 30" "$(python3 -c "
import json, sys
from datetime import datetime
b = json.loads(sys.argv[1]); p = lambda k: datetime.fromisoformat(b[k].replace('Z', '+00:00'))
print((p('cancellableUntil') - p('requestedAt')).days, (p('deleteBy') - p('requestedAt')).days)" "$DL_FIRST")" "14 30"
expect "asking again while it is pending is 202" 202 '"requestedAt":"' -- -X POST "$API/me/deletion-request" -H "Authorization: Bearer $DL_A" -d "{\"password\":\"$PASSWORD\"}"
is "…with the same dates, byte for byte" "$LAST_BODY" "$DL_FIRST"
expect "GET /me shows the pending deletion" 200 '"deletionRequest":{"' -- "$API/me" -H "Authorization: Bearer $DL_A"
is "…with the same dates as the 202" "$(python3 -c "import json,sys; print(json.dumps(json.loads(sys.argv[1])['deletionRequest'], sort_keys=True))" "$LAST_BODY")" "$(python3 -c "import json,sys; print(json.dumps(json.loads(sys.argv[1]), sort_keys=True))" "$DL_FIRST")"
for _ in $(seq 1 40); do [ "$(grep -c "Your Moyi account will be deleted" "$MOYI_LOG" || true)" -gt "$DL_MAILS" ] && break; sleep 0.25; done
is "the person is emailed once, with how to cancel, for the two requests" "$(( $(grep -c "Your Moyi account will be deleted" "$MOYI_LOG" || true) - DL_MAILS ))" "1"
if grep -q "cancel it under \"Delete my account\"" "$MOYI_LOG"; then pass "…and the email says where to cancel"; else fail "…and the email says where to cancel" "no such line in $MOYI_LOG"; fi
expect "while it is pending the person still signs in" 200 '"accessToken"' -- -X POST "$API/auth/login" -d "$(login_body "$DL_EMAIL" "$PASSWORD")"
expect "cancelling inside the window is 204" 204 "" -- -X DELETE "$API/me/deletion-request" -H "Authorization: Bearer $DL_A"
expect "cancelling again is 404 NOT_FOUND" 404 '"code":"NOT_FOUND"' -- -X DELETE "$API/me/deletion-request" -H "Authorization: Bearer $DL_A"
is "…the same bytes as cancelling with nothing ever asked" "$LAST_BODY" "$DL_NOTHING"
expect "GET /me is clear again" 200 '"deletionRequest":null' -- "$API/me" -H "Authorization: Bearer $DL_A"
flush_buckets
expect "asked for again: 202" 202 '"cancellableUntil":"' -- -X POST "$API/me/deletion-request" -H "Authorization: Bearer $DL_A" -d "{\"password\":\"$PASSWORD\"}"
expect "…and an export asked for before it is carried out" 202 '"status":"PREPARING"' -- -X POST "$API/me/export" -H "Authorization: Bearer $DL_A"
if [ "$PSQL_OK" = 1 ]; then
  DL_USER="$(sql "SELECT id FROM users WHERE email = '$DL_EMAIL'")"
  db_is "…its window is moved back fifteen days, as fourteen days on would leave it" \
    "$(sql "WITH moved AS (UPDATE deletion_requests SET requested_at = requested_at - interval '15 days', execute_after = execute_after - interval '15 days' WHERE user_id = '$DL_USER' AND cancelled_at IS NULL AND executed_at IS NULL RETURNING 1) SELECT count(*) FROM moved")" "1"
  # Up to 45 seconds: the job runs every five here, but ShedLock holds its lock at least 30 after each run.
  for _ in $(seq 1 45); do [ "$(sql "SELECT status FROM users WHERE id = '$DL_USER'")" = "DELETED" ] && break; sleep 1; done
  db_is "the job carries it out: the account is DELETED, its address and name gone, its password and sessions deleted" \
    "$(sql "SELECT status || '|' || (email = 'deleted-' || id || '@deleted.invalid') || '|' || (display_name <> 'Smoke') || '|' || (SELECT count(*) FROM credentials WHERE user_id = u.id) || '|' || (SELECT count(*) FROM refresh_tokens WHERE user_id = u.id) FROM users u WHERE id = '$DL_USER'")" "DELETED|true|true|0|0"
  expect "the deleted account's access token is refused: 401" 401 '"code":"UNAUTHENTICATED"' -- "$API/me" -H "Authorization: Bearer $DL_A"
  expect "the deleted account cannot sign in: 401 INVALID_CREDENTIALS" 401 '"code":"INVALID_CREDENTIALS"' -- -X POST "$API/auth/login" -d "$(login_body "$DL_EMAIL" "$PASSWORD")"
  for _ in $(seq 1 15); do [ "$(sql "SELECT status FROM bonds WHERE id = '$DL_BOND'")" = "ARCHIVED" ] && [ "$(sql "SELECT author_deleted_account FROM entries WHERE id = '$DL_A_ENTRY'")" = "t" ] && break; sleep 1; done
  expect "the partner sees the bond ended, as a leave ends it" 200 '"status":"ARCHIVED"' -- "$API/bonds/$DL_BOND" -H "Authorization: Bearer $DL_B"
  db_is "the deleted person's entry is a flagged tombstone (BR-10), and the partner's is untouched" \
    "$(sql "SELECT string_agg((text IS NULL) || ':' || status || ':' || author_deleted_account, ',' ORDER BY (id = '$DL_A_ENTRY') DESC) FROM entries WHERE id IN ('$DL_A_ENTRY', '$DL_B_ENTRY')")" "true:DELETED:true,false:REVEALED:false"
  db_is "…and the day they both wrote keeps its status, so the partner's streak stands" \
    "$(sql "SELECT d.status FROM bond_days d JOIN entries e ON e.bond_day_id = d.id WHERE e.id = '$DL_B_ENTRY'")" "REVEALED"
  for _ in $(seq 1 15); do [ "$(sql "SELECT count(*) FROM data_exports WHERE user_id = '$DL_USER'")" = "0" ] && break; sleep 1; done
  db_is "…and every export of the deleted account is gone, its archive with it (ADR-0059)" \
    "$(sql "SELECT count(*) FROM data_exports WHERE user_id = '$DL_USER'")" "0"
else
  skip "account deletion: carried out by the job (psql not reachable through docker compose)"
fi
if grep -q "$DL_WORDS" "$MOYI_LOG"; then fail "the deleted person's words are in no log line" "they are in $MOYI_LOG"; else pass "the deleted person's words are in no log line"; fi
flush_buckets

echo; echo "admin console — invite, enrol, two factors, lookup, and an audit log nothing can alter (FR-110, FR-111, FR-116, ADR-0055)"
# The console is on in the local profile for loopback only. A server started
# without it answers /admin/login as any unknown path (401, no token), and the
# section says so and stops. The "off" half of the contract (404 with a bearer)
# is the integration tests': this run has it on.
ADM="$BASE/admin"
ADM_DIR="$(mktemp -d)"
adm_code() { # <jar> <curl args…>: status in ADM_CODE, body in ADM_BODY, headers in ADM_HEADERS
  local jar="$1"; shift
  ADM_CODE="$(curl -s -o "$ADM_DIR/body" -D "$ADM_DIR/hdrs" -w '%{http_code}' -c "$jar" -b "$jar" "$@")"
  ADM_BODY="$(cat "$ADM_DIR/body")"; ADM_HEADERS="$(tr -d '\r' < "$ADM_DIR/hdrs")"
}
adm_csrf() { printf '%s' "$ADM_BODY" | python3 -c 'import re,sys; m=re.search(r"name=\"_csrf\"[^>]*value=\"([^\"]+)\"|value=\"([^\"]+)\"[^>]*name=\"_csrf\"", sys.stdin.read()); print((m.group(1) or m.group(2)) if m else "")'; }
adm_secret() { printf '%s' "$ADM_BODY" | python3 -c 'import re,sys; m=re.search(r"id=\"secret\"[^>]*>([A-Z2-7]+)<", sys.stdin.read()); print(m.group(1) if m else "")'; }
adm_location() { printf '%s\n' "$ADM_HEADERS" | grep -i '^location:' | head -1 | cut -d' ' -f2- || true; }
adm_is() { # <label> <want-status> [<want-location-substring>]
  if [ "$ADM_CODE" != "$2" ]; then fail "$1" "expected HTTP $2, got $ADM_CODE: ${ADM_BODY:0:160}"
  elif [ -n "${3:-}" ] && [[ "$(adm_location)" != *"$3"* ]]; then fail "$1" "expected Location to contain '$3', got '$(adm_location)'"
  else pass "$1 ($ADM_CODE)"; fi
}
adm_code "$ADM_DIR/probe" "$ADM/login"
if ! command -v oathtool >/dev/null; then
  skip "admin: the whole section (oathtool not installed: brew install oath-toolkit)"
elif [ "$PSQL_OK" != 1 ]; then
  skip "admin: the whole section (psql not reachable through docker compose)"
elif [ "$ADM_CODE" != 200 ]; then
  skip "admin: the whole section (this server has the console off: /admin/login answered $ADM_CODE)"
else
  pass "the console is on for this machine: /admin/login is 200"
  # A refused address: loopback is a trusted proxy, so X-Forwarded-For names the caller.
  expect "admin: a refused address gets the API's 401, with no token" 401 '"status":401' -- "$ADM/login" -H 'X-Forwarded-For: 203.0.113.9'
  ADM_REFUSED="$(no_instance)"
  expect "admin: …and the same from a refused address that brings a bearer token" 401 '"status":401' -- "$ADM/login" -H 'X-Forwarded-For: 203.0.113.9' -H 'Authorization: Bearer not-a-token'
  is "admin: …byte for byte the same problem (but its instance)" "$(no_instance)" "$ADM_REFUSED"
  adm_code "$ADM_DIR/refused" "$ADM/login" -H 'X-Forwarded-For: 203.0.113.9'
  if printf '%s\n' "$ADM_HEADERS" | grep -qi '^set-cookie:'; then fail "admin: a refused address is given no session" "a cookie was set"; else pass "admin: a refused address is given no session"; fi

  AD_EMAIL="admin-smoke-$RANDOM$RANDOM@example.com"
  AD_PASSWORD="smoke admin passphrase $RANDOM-$RANDOM-x7Q"
  AD_TOKEN="$(sql "SELECT admin_invite('$AD_EMAIL', 'SUPPORT')")"
  case "$AD_TOKEN" in psql-error*|"") fail "admin: admin_invite returns a token" "$AD_TOKEN"; AD_TOKEN="" ;; *) pass "admin: admin_invite returns a one-time token (${#AD_TOKEN} characters)" ;; esac
  # The token is base64url of 32 random bytes and the column holds SHA-256 of those bytes, so the
  # check decodes it first: digest() of the 43 characters themselves would never match.
  db_is "admin: …stored as its hash only, the SHA-256 of the token" "$(sql "SELECT i.token_hash = digest(decode(translate('$AD_TOKEN', '-_', '+/') || '=', 'base64'), 'sha256') FROM admin_invitations i JOIN admin_accounts a ON a.id = i.admin_id WHERE a.email = '$AD_EMAIL' AND i.consumed_at IS NULL")" "t"

  adm_code "$ADM_DIR/enrol" "$ADM/enrol?token=not-a-real-token-at-all-0000000000000000000"
  adm_is "admin: an invalid enrolment link is one page" 404
  adm_code "$ADM_DIR/enrol" "$ADM/enrol?token=$AD_TOKEN"
  adm_is "admin: the invitation opens the enrolment page" 200
  AD_SECRET="$(adm_secret)"; AD_CSRF="$(adm_csrf)"
  if [ -n "$AD_SECRET" ] && [ -n "$AD_CSRF" ]; then pass "admin: …with a secret to scan and a CSRF token"; else fail "admin: the enrolment page has a secret and a CSRF token" "secret='${AD_SECRET:+present}' csrf='${AD_CSRF:+present}'"; fi
  adm_code "$ADM_DIR/enrol" -X POST "$ADM/enrol" --data-urlencode "token=$AD_TOKEN" --data-urlencode "password=$AD_PASSWORD" --data-urlencode "confirm=$AD_PASSWORD" --data-urlencode "code=000000" --data-urlencode "_csrf=$AD_CSRF"
  adm_is "admin: a wrong code enrols nothing" 422
  db_is "admin: …and the account is still INVITED" "$(sql "SELECT status FROM admin_accounts WHERE email='$AD_EMAIL'")" "INVITED"
  adm_code "$ADM_DIR/enrol" "$ADM/enrol?token=$AD_TOKEN"; AD_SECRET="$(adm_secret)"; AD_CSRF="$(adm_csrf)"
  adm_code "$ADM_DIR/enrol" -X POST "$ADM/enrol" --data-urlencode "token=$AD_TOKEN" --data-urlencode "password=$AD_PASSWORD" --data-urlencode "confirm=$AD_PASSWORD" --data-urlencode "code=$(oathtool --totp -b "$AD_SECRET")" --data-urlencode "_csrf=$AD_CSRF"
  adm_is "admin: the right code enrols, and sends the operator to sign in" 302 "/admin/login"
  db_is "admin: …the account is ACTIVE" "$(sql "SELECT status FROM admin_accounts WHERE email='$AD_EMAIL'")" "ACTIVE"
  db_is "admin: …its TOTP secret is stored sealed, not as the base32 shown" "$(sql "SELECT (totp_secret_enc LIKE '%$AD_SECRET%') FROM admin_accounts WHERE email='$AD_EMAIL'")" "f"
  adm_code "$ADM_DIR/enrol2" "$ADM/enrol?token=$AD_TOKEN"
  adm_is "admin: …and the link cannot be used again" 404

  # The enrolment spent the current step; the next step's code is inside the ±1 tolerance.
  ad_next_code() { oathtool --totp -b -N "now + 30 seconds" "$AD_SECRET"; }
  ad_password_step() { # <jar>
    adm_code "$1" "$ADM/login"; AD_CSRF="$(adm_csrf)"
    adm_code "$1" -X POST "$ADM/login" --data-urlencode "email=$AD_EMAIL" --data-urlencode "password=$AD_PASSWORD" --data-urlencode "_csrf=$AD_CSRF"
  }
  ad_code_step() { # <jar> <code>
    adm_code "$1" "$ADM/login/totp"; AD_CSRF="$(adm_csrf)"
    adm_code "$1" -X POST "$ADM/login/totp" --data-urlencode "code=$2" --data-urlencode "_csrf=$AD_CSRF"
  }

  adm_code "$ADM_DIR/anon" "$ADM/users?q=x"
  adm_is "admin: no session at all is sent to sign in" 302 "/admin/login"
  adm_code "$ADM_DIR/post-nocsrf" -X POST "$ADM/login" --data-urlencode "email=$AD_EMAIL" --data-urlencode "password=$AD_PASSWORD"
  adm_is "admin: a sign-in POST with no CSRF token is refused" 403

  ad_password_step "$ADM_DIR/one"
  adm_is "admin: the right password alone moves to the code page" 302 "/admin/login/totp"
  adm_code "$ADM_DIR/one" "$ADM/users?q=$EMAIL"
  adm_is "admin: …and one factor reaches nothing else (sent to the code page)" 302 "/admin/login/totp"
  ad_code_step "$ADM_DIR/one" "000000"
  adm_is "admin: a wrong code is refused" 401
  AD_GOOD="$(ad_next_code)"
  ad_code_step "$ADM_DIR/one" "$AD_GOOD"
  adm_is "admin: the next step's code signs in" 302 "/admin"

  ad_password_step "$ADM_DIR/replay"
  ad_code_step "$ADM_DIR/replay" "$AD_GOOD"
  adm_is "admin: the same code a second time is refused (replay)" 401

  adm_code "$ADM_DIR/one" "$ADM/users?q=$EMAIL"
  adm_is "admin: both factors reach the lookup form (a GET looks nothing up)" 200
  # A2 §9.3: a lookup is a POST that says why (the note a SAFETY or LEGAL lookup needs never goes in a URL).
  AD_CSRF="$(adm_csrf)"
  adm_code "$ADM_DIR/one" -X POST "$ADM/users" --data-urlencode "q=$EMAIL" --data-urlencode "purpose=OPERATIONS" --data-urlencode "_csrf=$AD_CSRF"
  adm_is "admin: both factors reach the user lookup" 200
  if [[ "$ADM_BODY" == *"$EMAIL"* ]]; then pass "admin: …and it shows the user by their exact email"; else fail "admin: the lookup shows the user" "${ADM_BODY:0:200}"; fi
  for h in "Content-Security-Policy: default-src 'none'" "Cache-Control: no-store" "Referrer-Policy: no-referrer"; do
    if printf '%s\n' "$ADM_HEADERS" | grep -qi "^$h"; then pass "admin: …with the header $h"; else fail "admin: the lookup page's headers" "missing: $h"; fi
  done
  AD_CSRF="$(adm_csrf)"
  adm_code "$ADM_DIR/one" -X POST "$ADM/users" --data-urlencode "q=00000000-0000-7000-8000-000000000000" --data-urlencode "purpose=OPERATIONS" --data-urlencode "_csrf=$AD_CSRF"
  adm_is "admin: a lookup that finds nothing is a page, not an error" 200
  AD_CSRF="$(adm_csrf)"
  adm_code "$ADM_DIR/one" -X POST "$ADM/users" --data-urlencode "q=$EMAIL" --data-urlencode "purpose=SAFETY" --data-urlencode "_csrf=$AD_CSRF"
  adm_is "admin: a SAFETY lookup with no note is refused, and the form is shown again" 400
  adm_code "$ADM_DIR/one" -X POST "$ADM/logout"
  adm_is "admin: sign-out with no CSRF token is refused" 403
  adm_code "$ADM_DIR/one" "$ADM"; AD_CSRF="$(adm_csrf)" # the sign-out button is on the home page
  adm_code "$ADM_DIR/one" -X POST "$ADM/logout" --data-urlencode "_csrf=$AD_CSRF"
  adm_is "admin: sign-out with it works" 302 "/admin/login"
  adm_code "$ADM_DIR/one" "$ADM/users?q=$EMAIL"
  adm_is "admin: …and the old session reaches nothing" 302 "/admin/login"

  AD_ID="$(sql "SELECT id FROM admin_accounts WHERE email='$AD_EMAIL'")"
  db_is "admin: audit has the invitation, the enrolment, the sign-in, the lookup and the sign-out" \
    "$(sql "SELECT string_agg(DISTINCT action, ',' ORDER BY action) FROM audit_log WHERE (actor_id='$AD_ID' OR target_id='$AD_ID')")" \
    "ADMIN_ENROLLED,ADMIN_INVITED,ADMIN_SIGN_IN,ADMIN_SIGN_OUT,USER_LOOKED_UP"
  db_is "admin: …the refused attempts are recorded as REFUSED (a wrong code, a replay)" "$(sql "SELECT count(*) >= 2 FROM audit_log WHERE actor_id='$AD_ID' AND action='ADMIN_SIGN_IN' AND outcome='REFUSED'")" "t"
  db_is "admin: …a lookup row names the user found" "$(sql "SELECT count(*) >= 1 FROM audit_log WHERE actor_id='$AD_ID' AND action='USER_LOOKED_UP' AND outcome='SUCCEEDED' AND target_type IS NOT NULL")" "t"
  db_is "admin: …and no audit row holds the operator's or the user's email" "$(sql "SELECT count(*) FROM audit_log WHERE metadata::text ILIKE '%$AD_EMAIL%' OR metadata::text ILIKE '%$EMAIL%'")" "0"
  AD_UPD="$(sql "UPDATE audit_log SET outcome = 'FAILED' WHERE actor_id = '$AD_ID'")"
  case "$AD_UPD" in psql-error*append-only*) pass "admin: UPDATE audit_log raises: append-only";; *) fail "admin: UPDATE audit_log must raise" "$AD_UPD";; esac
  AD_DEL="$(sql "DELETE FROM audit_log WHERE actor_id = '$AD_ID'")"
  case "$AD_DEL" in psql-error*append-only*) pass "admin: DELETE audit_log raises: append-only";; *) fail "admin: DELETE audit_log must raise" "$AD_DEL";; esac
  if grep -qF "$AD_TOKEN" "$MOYI_LOG" || grep -qF "$AD_SECRET" "$MOYI_LOG" || grep -qF "$AD_PASSWORD" "$MOYI_LOG"; then fail "admin: the token, the secret and the password are in no log line" "found in $MOYI_LOG"; else pass "admin: the token, the secret and the password are in no log line"; fi

  # The lockout: five failures lock the account, and the right password then gets the same page.
  for _ in 1 2 3 4 5; do
    adm_code "$ADM_DIR/lock" "$ADM/login"; AD_CSRF="$(adm_csrf)"
    adm_code "$ADM_DIR/lock" -X POST "$ADM/login" --data-urlencode "email=$AD_EMAIL" --data-urlencode "password=wrong password number $RANDOM" --data-urlencode "_csrf=$AD_CSRF"
  done
  ad_password_step "$ADM_DIR/locked"
  adm_is "admin: after five failures the right password is refused as a wrong one" 401
  db_is "admin: …the account is locked in the database" "$(sql "SELECT locked_until > now() FROM admin_accounts WHERE email='$AD_EMAIL'")" "t"

  sql "SELECT admin_disable('$AD_EMAIL')" >/dev/null || true
  db_is "admin: admin_disable disables the account" "$(sql "SELECT status FROM admin_accounts WHERE email='$AD_EMAIL'")" "DISABLED"
fi
rm -rf "$ADM_DIR"

echo; echo "database state"
ROW="$(docker compose exec -T postgres psql -U moyi -d "$MOYI_DB" -Atc "SELECT u.status, (u.email_verified_at IS NOT NULL), count(t.id), count(t.consumed_at) FROM users u LEFT JOIN verification_tokens t ON t.user_id=u.id WHERE u.email='$EMAIL' GROUP BY 1,2" 2>/dev/null || echo "psql-unavailable")"
# Two tokens by now — the verification link and the reset link — both consumed.
if [ "$ROW" = "ACTIVE|t|2|2" ]; then pass "user ACTIVE, verified, two tokens (verification + reset), both consumed"; elif [ "$ROW" = "psql-unavailable" ]; then skip "database check (psql not reachable through docker compose)"; else fail "database row" "expected ACTIVE|t|2|2, got '$ROW'"; fi
SESSIONS="$(docker compose exec -T postgres psql -U moyi -d "$MOYI_DB" -Atc "SELECT count(*) || '|' || count(*) FILTER (WHERE revoked_at IS NULL) FROM refresh_tokens t JOIN users u ON u.id=t.user_id WHERE u.email='$EMAIL'" 2>/dev/null || echo "psql-unavailable")"
# Every family started here was ended: two by reuse detection and logout,
# the phone's and the watch's by DELETE /auth/sessions, the rest by
# logout-all and the reset. Two live tokens remain for THIS account — the
# login after the reset and the one the bond section signed in with; the
# joiner is a different account and is not counted here.
case "$SESSIONS" in psql-unavailable) skip "session check";; *"|2") pass "refresh tokens stored as hashes; exactly two live sessions remain ($SESSIONS)";; *) fail "sessions" "expected exactly two live refresh tokens, got '$SESSIONS'";; esac

# The close job (slice C3), on the wire. The run above started it on a
# five-second schedule instead of its quarter-hour, so by now it has run many
# times, against real rows, through the real scheduler, lock and closer. It
# cannot be shown closing a day — no day in this run has ended — so what is
# checked is that it ran, holds its lock by name, and failed on nothing.
echo; echo "scheduling — the close job runs, under its lock, without error (spec §6.4, C3)"
CLOSE_LOCK="$(docker compose exec -T postgres psql -U moyi -d "$MOYI_DB" -Atc "SELECT count(*) FROM shedlock WHERE name='close-days'" 2>/dev/null | tr -d '[:space:]' || echo psql-unavailable)"
case "$CLOSE_LOCK" in psql-unavailable) skip "lock check";; 1) pass "the close job has taken its ShedLock row";; *) fail "close job" "expected one shedlock row named close-days, got '$CLOSE_LOCK'";; esac
if grep -qE "close: day .* failed|close: the missing days of bond|streak: bond .* could not be evaluated|FAILED [1-9]|Unexpected error occurred in scheduled task" "$MOYI_LOG"; then fail "close job" "the close job logged a failure"; else pass "…and has logged no failure"; fi
# A day stays open for its twelve-hour late-entry grace (issue #132, ADR-0033 decision 15), so
# what must be closed is every day whose grace has run out, a minute ago.
UNCLOSED_PAST="$(docker compose exec -T postgres psql -U moyi -d "$MOYI_DB" -Atc "SELECT count(*) FROM bond_days WHERE closed_at IS NULL AND ends_at < now() - interval '12 hours 1 minute'" 2>/dev/null | tr -d '[:space:]' || echo psql-unavailable)"
case "$UNCLOSED_PAST" in psql-unavailable) skip "unclosed-day check";; 0) pass "…and no day past its grace is still unclosed";; *) fail "close job" "$UNCLOSED_PAST days past their grace are still unclosed";; esac

echo; printf '%d passed, %d failed, %d skipped\n' "$PASS" "$FAIL" "$SKIP"
# The exit status is the failure count, as the README says (capped at what a
# shell can carry), so a caller can tell one failure from several.
exit $(( FAIL > 255 ? 255 : FAIL ))
