# ADR-0036 — The archive and favourites

**Status:** Accepted · **Date:** 2026-10-09 · **Deciders:** Daniel

## Context

Until this slice a member could read one day of a bond: today. Spec §5.2 gave slice C5 four
routes for the rest: `GET /bonds/{bondId}/days` (FR-090, the archive, newest first, by cursor),
`GET /bonds/{bondId}/days/{date}`, and `PUT` and `DELETE /entries/{entryId}/favourite`
(FR-093, a bookmark that is private to the member who made it). Spec §6.6 added two rules:
the partner's favourites appear in no response in any shape, and an `ETag` on the archive
covers the response as rendered for the caller, not a bond-row version.

**C5 is three pull requests** (ADR-0035). C5a, the poller and withdrawal, merged as #58 on
2026-10-07. This is **C5b**, the archive and favourites, stacked on it: the branch was begun
on C5a's branch before #58 merged and rebased onto `main` at `174b474` during its second
task. C5c is reactions. The migration is **V22** (`modules:gratitude`); C5c takes V23 and
C6 moves to V24.

The corpus read made before C5a (`c5-corpus.md`, 22 contradictions and 26 silences) found
five things in what this slice touches that the plan had to settle before any code:

- **Three documents give three sets of archive days** (X1). FR-090 says "all revealed days";
  doc 06 says "revealed and solo days"; the spec says "days with revealed entries, including
  subsequently frozen solo days". `states.md` §9 adds a fourth: after a withdrawal the
  archive draws days whose entries are all tombstones.
- **The default page breaks the response cap** (X11). Twenty days of two entries is more
  than NFR-008's 256 KB, and no document states a maximum `limit`.
- **The month jump has no API** (X13). `states.md` §6 makes it the first way into the
  archive; doc 06 offers `limit`, `cursor` and `favourites`, and calls the cursor opaque.
- **`409 ENTRY_NOT_REVEALED` on a partner's locked entry would confirm an id** the caller
  was never shown (X14). ADR-0032 decision 9 gives that case `404` on every other route by
  entry id.
- **"On delete cascade" can never remove a favourite** (X5). A delete keeps the row as a
  tombstone, so a foreign-key cascade never fires.

ADR-0035's "Owed — C5b" list set six more conditions, the sixth shared with C5c; "Owed"
below says how each was met.

The slice was built in four tasks, each by an implementing agent. Tasks 1 to 3 were each
then read by an independent reviewer who ran the code in a checkout of its own. Task 4 had
no reviewer of its own. The plan
(`docs/superpowers/plans/2026-10-07-archive-and-favourites-c5b.md`) made fourteen decisions.
Sixteen are recorded here. Decisions 1, 5, 6, 9 and 12 contradict the plan; 7, 11 and 14 add
what it did not say. Each says what changed and which review or test caused it.

Three whole-branch reviews then read the code at `9bba582` (the branch head was `c4b3e71`,
one commit of documents later): privacy and the contract, concurrency and cost, and
conformance to the corpus. **Two of them proved a defect in what this record had accepted.**
Decision 7 said an empty page with a cursor "leaks nothing"; it told a withdrawal from a
deletion by hand. Consequences said a mark could wait with no bound and did not mention the
unmark; it was the unmark that waited, and a few of them stalled every user. Decisions 7 and
12 are rewritten below and say what was proved and what was done. "How this was checked"
has what each review ran.

Four terms. A **favourite**, or **mark**, is a row of `entry_favourites`: one member's
bookmark on one entry. The **gate** is `Entry.canBeReadBy`, the one function that says what a
reader may see of an entry; its answers are `FULL`, `TOMBSTONE` (erased, and the reader could
read it before), `LOCKED`, `TOMBSTONE_UNSEEN` (erased, never shown to this reader) and
`NOT_A_MEMBER`. The **marker** is ADR-0035's: the bond's record that a member withdrew their
entries. A **wide tombstone** is the full entry shape with `text: null`; the two narrow shapes
are `{authorMemberId, status: LOCKED}` and `{authorMemberId, status: REMOVED}`.

## Decision

**1. The archive lists a day when it holds an entry the caller wrote, or one that has been
revealed. An erased row counts.** That is the whole rule, and it is asked of the entry rows,
never of the day (`ArchiveDays`, the `SEEN` fragment:
`author_member_id = ? OR revealed_at IS NOT NULL`). It is one rule for the four sets the
corpus gives. Case by case:

| Day | Listed for |
|---|---|
| Both wrote and the day revealed, open or closed | both |
| Revealed, then one or both entries erased | both, with wide tombstones |
| `SOLO`, closed while the bond was whole: the lone entry was revealed at the close | both |
| `SOLO` on a bond that ended before the day did: never revealed (ADR-0033 decision 9) | its author only |
| `SUSPENDED` from before the second member joined | the creator only; with both entries, each sees their own and the other's stays `LOCKED` |
| Holding only the partner's unrevealed entry (`PARTIAL`, or today) | not the caller |
| Listed for the caller through their own entry, where the partner wrote and erased theirs before any reveal | the caller, with the partner's entry as `{authorMemberId, status: REMOVED}`, **for good** |
| `PARTIAL` or `PENDING_REVEAL` | whoever wrote on it; the partner's entry is `LOCKED` |
| Closed `EMPTY` after its author deleted their only entry | **the author only, as `status: EMPTY` with their own tombstone** |
| No entry row at all, whatever the status | nobody |
| Today | the same rule: listed once the caller has written, or once it reveals |

- **The emptied day is listed, and the plan said it would not be.** The plan's decision 1
  ended "`EMPTY` days … are absent". The implementer of Task 2 found, by reading the plan's
  own SQL against its prose, that a day which closed `EMPTY` after its author deleted their
  only entry still holds that author's tombstone row, so the rule lists it for them and the
  gate answers `TOMBSTONE`. The filter and the gate agree; the prose did not. Built as the
  rule says, and tested (`ArchiveGateTest`, "EMPTY after its only entry was deleted"). A
  client that reads `EMPTY` as "nothing to draw" hides the tombstone; the contract's
  descriptions of the feed and of the one day both say so. Question 2.
- **"They wrote something and took it back before you saw it" is permanent here.** A day
  the caller is listed on through their own entry, on which the partner wrote and erased
  before any reveal, shows the caller `partnerEntry: {authorMemberId, status: REMOVED}`.
  BR-8 allows exactly that shape and `GET /today` has shown it since C5a, for that day. The
  archive shows it on every walk, for as long as the bond's days exist. It follows from the
  rule and the gate (`TOMBSTONE_UNSEEN`), and `ArchiveGateTest`'s matrix pins the shape for
  every such cell. Hiding it would be a rule of its own: render an entry never shown as
  absent once its day has closed. Noted by the whole-branch privacy review; the smoke run
  now reads the shape from the jar. Question 6.
- **It reads no column of the day.** A `SOLO` or `REVEALED` status is not evidence that an
  entry was revealed (ADR-0033 decision 9), and `bond_days.revealed_at` is unset on a `SOLO`
  day whose lone entry was revealed.
- *Rejected:* listing by status (`REVEALED`, `SOLO`), which is doc 06's wording. Run as a
  mutation it failed 16 tests, among them the solo day that never revealed and both
  pre-join days.
- *Rejected:* listing every day with a row. A day holding only the partner's unrevealed
  entry would then say that they wrote, on a route that is not `today`.

**2. The SQL is a filter; the gate decides what is shown.** The query picks days. Every
entry of a picked day is then loaded and rendered through `Entry.readBy`, exactly as
`GET /today` renders it, and nothing else chooses a shape: `GetDays` only ever holds the
gate's answers. The filter's predicate is the gate's "ever readable" clause asked of a row,
so the two agree row by row.

`ArchiveGateTest` holds them together. Its cells are day status (eight) × the first
member's entry (none, live and unrevealed, revealed, erased before the reveal, erased after)
× the second member's entry (the same five): 200 days, each read by both members. Twelve
tests reach a cell the way the application does, through requests, the clock and the close
job's step, and check from the rows that the cell was reached. One test puts all 200 down by
hand, because most cannot be reached (no request leaves a `FROZEN` day holding an entry, or
a `REVEALED` day holding an unrevealed one); they are there because neither the query nor
the gate reads the day, and the way to hold that is to vary the day under them. For each
cell and each reader the test asserts whether the day is listed, the exact JSON of each
entry, that no word of an entry the reader may not read in full is anywhere in the
response, and the cross-check: the day is listed if and only if the gate answers `FULL` or
`TOMBSTONE` for at least one of its entries. Each member is listed 176 of the 200. A second
hand-built test runs the matrix again after a real block with no dispatcher run: every
listed entry of the withdrawn author changes shape, and not one day enters or leaves the
list.

**One cell is not in the matrix and no request can reach it:** two rows by one author on
one day, where the erased row was revealed and the live one is not. The filter would list
the day for the partner on the strength of the erased row, and `onEachSideOf` (which row of
a day is "mine": a live row before an erased one, then the newest) would render the live
one, `LOCKED`, and hide the tombstone. `BondDay` refuses a write on a settled day, so an
entry cannot be written again after its day revealed. The matrix has one row per author.
Found by the reviewer of Task 2, by reading; not pinned.

**3. A day is `{date, status, myEntry, partnerEntry}`**: `GET /today`'s shape without the
streak. `myEntry` is the wide shape or absent; `partnerEntry` is the wide shape or one of
the two narrow ones. **`status` is the day's own**, `SOLO`, `EMPTY`, `FROZEN` and
`SUSPENDED` included. That is the owner's ruling of 2026-10-06: an archive day carries its
status, as the calendar now draws a solo day (ADR-0034, Rulings, 2). It was given in
conversation and recorded at the time only in ADR-0035's Owed list, so nothing in this
repository but that line vouches for it; this is the first place it is recorded as a
decision. Nothing is decided from the status. It is there to be shown.

No day and no entry carries `deletedAt` or `updatedAt`, on any shape. A withdrawal stamps
every entry it erases with one instant, and the archive is the first route to return many
tombstones at once (ADR-0035, Owed). `DaysFeedTest` asserts the absence on every shape.

**4. `EntryResponse` gains `favourited`, the caller's own mark, on every route that returns
one.** `GET /today`, both archive routes, and the replies and replays of `POST /entries` and
`PATCH /entries/{entryId}`. It is `true` only on a `FULL` reading. `EntryResponse.of`
forces it `false` on a tombstone whatever its caller passed, because a mark can outlive the
gate's answer: a withdrawal hides an entry before anything is erased, and the mark goes
with the erasure. The two narrow shapes have no such field. The marks are asked once per
response, by the caller's member id, only for entries the gate answered in full
(`Favourites.markedBy`); there is no query that returns anybody else's.

Two guards say the same thing here (the ids asked about are `FULL` only; the renderer
forces `false`), so each one's removal is invisible over HTTP while the other stands. One is
held by a unit test of the renderer; the other is stated equivalent (How this was checked).

**5. Keyset on the date, newest first.** `(bond_id, date)` is unique, so the date is a total
order within a bond and the cursor needs nothing else. A day added or erased between two
pages is neither skipped nor repeated.

- **`limit`** is 1 to 50, default 20. It has one spelling: a decimal number with no sign,
  space or leading zero (`[1-9][0-9]{0,8}`). `007`, `+7`, `5.0` and a full-width digit are
  refused. A generated client sends nothing else, and a padded number read here as seven is
  another cache key for the same page somewhere else. `07` was accepted, and asserted
  accepted, until the reviewer of Task 2 noted it.
- **The cursor** is the base64url, unpadded, of `v1:` and the date of the last day the
  page took: eighteen characters. It means "days strictly before this one". It is read
  strictly (decoded, checked, encoded again and compared, because the decoder forgives
  padding and trailing bits) and refused unread when longer than 32 characters.
- **A cursor from another bond's feed is accepted. The plan said `422`.** A cursor is only
  a date. It names no bond and no caller, there is nothing in it the client does not know,
  and a client that makes one up has only chosen where to start reading, which `until`
  lets it do openly. What the caller may read is decided by the guard and the gate on every
  request. Binding a cursor to a bond would mean signing it, to protect nothing. The plan's
  "a tampered or foreign cursor is `422`" is true of a tampered one. The reviewer of Task 2
  recorded the difference; nothing was changed.
- **`until=YYYY-MM-DD`** starts a page at that date or the nearest earlier listed day, and
  holds with a cursor across the pages that follow. It is the month jump `states.md` §6
  draws and doc 06 gives no way to make (X13). A date has one spelling too: four digits,
  two, two, and a day that exists (`strictIsoDate`, shared with the cursor and with decision
  8). Question 4.
- **`favourites`** is `true` or `false`, strictly.
- **Every parameter that cannot be read is the standing `422 VALIDATION_FAILED`**, with an
  `errors` entry naming the parameter and never its value, and all of them reported at
  once. A parameter sent twice is refused the same way (run by the reviewer).
- **The parameters are parsed after the membership guard.** They arrive as text and are
  read in `DaysQuery`. Bound by Spring as an `Int` or a `LocalDate`, a value that does not
  convert is refused before the controller's method runs: before the guard, so a stranger
  would be told about their `limit` on a bond they may not know exists, and as Spring's
  `400`, whose detail quotes what was sent. Read after the guard, a stranger's bad cursor
  gets the bond's `404`. The reviewer of Task 2 sent 17 odd cursors, dates and limits as a
  stranger, one of them a 100 KB cursor: one byte-identical `404`.

**6. A page is also bounded by bytes.** Days are taken in order while the entry text the
caller will be sent stays within **192 KiB (196,608 octets)**. `nextCursor` then comes
earlier than `limit` would have put it.

- **Text is counted as JSON carries it, not as it arrived** (`GetDays.octetsAsJson`: the
  UTF-8 length, five more for each control character, one more for each quote and
  backslash). The limit is on the response, and an entry is bounded by octets as sent to
  us. The plan counted raw octets and said "twenty days of two full entries would be 327,680
  bytes", which assumes an entry is at most 8,192 octets on the wire. The implementer of
  Task 2 saw that JSON escapes a control character as six octets and counted escaped
  octets. The reviewer then found the real worst case by running it: `a` followed by 8,191
  U+001F is accepted as an entry, because the 500-character limit is counted on the text
  trimmed and U+001F trims as space. It is 8,192 octets in and **49,147 on the wire**. A
  day of two is about 98 KB, and so is `GET /today`. The bound held, and only because it
  counts escaped octets; the arithmetic written beside it (in the plan, in a KDoc, in the
  Task 2 report: "a day is at most about 21 KB") was wrong by a factor of five.
- **The worst page is about 230 KB against 262,144.** Two of the largest days fit the
  budget (196,588 octets) and a third does not. The rest of a 50-day page (ids, dates and
  field names of a hundred wide entries) is about 32.5 KB. `DaysFeedTest` builds that page
  and measures it: 229,063 octets, with the test clock's whole-second timestamps. An
  instant in production carries microseconds, seven octets more in each of two fields of a
  hundred entries: about 1.4 KB more than was measured (the conformance review's
  arithmetic; not built). The margin is about 31 KB, and a new field on an entry spends it
  at a hundred entries a page.
- The count is an upper bound on what the serializer writes, never under: the reviewer
  compared it with the application's mapper over every scalar of the Basic Multilingual
  Plane.
- **The first day shown is always taken**, so a page is never empty for its size. No day
  can reach the real bound alone, so the rule could not be shown to be needed: the
  mutation that removed it survived. `GetDays.page` takes the bound as a parameter with the
  real value as its default, and a test passes 1. That is a test hook in a production
  signature, noted by the reviewer and kept.
- A tombstone and a locked entry send no text and count for nothing.
- *Rejected:* a maximum `limit` below 16, which gives up page size for every ordinary
  archive to bound a pathological one; and shortening text in the list on the server, which
  `states.md` §6 puts on the client and which would make the list and the day view two
  renderings.

**7. `favourites=true` lists the days holding an entry the caller has marked and can read
in full, and a day the gate drops leaves no trace in the page.** The query adds a second
`EXISTS` over `entry_favourites` for this member, joined to a revealed, unerased entry of
the day. That is still only a filter. A marked entry whose author has withdrawn, and which
nothing has erased yet, is a whole row to the query and a tombstone saying
`favourited: false` to the caller. So `GetDays` filters **again after the gate** and drops a
day on which nothing marked can be read in full now.

- **As first built, a dropped day still moved the cursor, and that told a withdrawal from
  a deletion by hand.** The cursor was the last day taken from the archive, kept or
  dropped, so a page could be empty, or shorter than `limit`, and still carry a
  `nextCursor`. This record said of it: "That leaks nothing. The dropped day held the
  caller's own mark, on an entry the plain feed already shows them as a tombstone." It was
  accepted on that argument and it was false.
- **What was proved** (the whole-branch privacy review, by running). Two bonds with one
  history: four revealed days, Bea's bookmark on Ada's entry on two of them and on her own
  on one, and nothing written on the day of the ending. In the first Ada blocked, taking
  her entries back, and the dispatcher had not run. In the second Ada deleted her four
  entries by hand and then left. Twelve of Bea's views were compared with ids normalised.
  Ten were identical: `GET /today`, the plain feed at three page sizes, five day views,
  and `favourites=true` at the default `limit`. `favourites=true` at `limit=1` and at
  `limit=2` were not. At `limit=1` the first bond answered `{"items":[],"nextCursor":"…"}`,
  then a day with a cursor, then `{"items":[],"nextCursor":null}`; the second answered
  that day with `nextCursor: null`.
  A deletion by hand removes the bookmark with the words (decision 13), so it can never
  produce an empty page with a cursor. For as long as the consumer had not run, one old
  bookmark told Bea that Ada had taken everything back in one act, which ADR-0028 decision
  8 says she must not be able to learn. It is not ADR-0035 question 4's window either:
  that one lasts a day and needs a lone or pending entry on the ending day; this needed
  only that Bea had ever bookmarked an entry of Ada's, and lasted as long as the poller
  was stopped or a delivery was backing off. After the dispatcher ran the two bonds were
  identical in all twelve views, in four scenarios.
- **What was done: the request reads on until the page is decided** (`GetDays.page`,
  `a461ea8`). It reads candidates in windows, the first of `limit + 1` and then 51 at a
  time, each through the gate. The page is the days that are shown. `nextCursor` is the
  last day shown when a further day that would be shown has been met, and `null` when the
  candidates have run out. So the page and its cursor are those of an archive in which the
  dropped days were never candidates, which is the hand-deleted twin's archive.
  `WithdrawalTwinArchiveTest` holds it: a withdrawn bond and its twin, the plain feed and
  `favourites=true` at `limit` 1, 2, 3 and the default, page by page with ids normalised
  and cursors as sent; and the withdrawn bond before and after the dispatcher erases, byte
  for byte and tag for tag. Two bonds do not share an id, so their bytes and tags cannot
  be compared directly; the second comparison is where they can be.
- **The brief for the fix said the cursor should be "derived exactly as now", and it could
  not be.** As it was, the cursor was the last candidate taken, shown or not. The twin's
  cursor is its last shown day, and it is `null` when no shown day follows. So the request
  has to look ahead to the next day that would be **shown**, not the next candidate, and
  give the last shown day as the cursor. Run as mutations: with the cursor taken from the
  last day examined, the look-ahead day is skipped by the next page (ten tests fail).
- **Who has withdrawn is asked once per window, after that window's entries are loaded**
  (decision 15). Not once after the last window: whether another window is needed depends
  on what the gate dropped from this one, so each window needs the answer before the next
  is fetched. Each entry is then judged by an answer taken after its own row was read,
  which is the whole of ADR-0035 decision 12's guarantee. Two windows of one request can
  hold two answers if an ending commits between them: the later days are tombstones and
  the earlier are not, each true when it was read, as two requests a moment apart would
  have been. Asking again at the end and judging every entry a second time was not done:
  it could shorten the page after the reading had stopped.
- **Amended 2026-10-10, after PR #60 review: exclude known withdrawn authors before
  `LIMIT`.** The earlier refill fix still exposed withdrawal once twenty windows were
  exhausted: 972 bookmarked days at `limit=1` produced two empty pages before erasure
  and one afterward, with different tags. `GetDays` now asks `BondAccess` for the current
  withdrawn authors before **each** candidate query, including look-ahead, and passes
  that set as SQL parameters. `ArchiveDays` excludes those authors only from the
  favourites predicate; the ordinary archive still includes their tombstones. No query
  reaches into another module's tables, and this filter grants no permission.
- **The gate still reads the marker last.** A withdrawal can commit between the candidate
  query and entry loading. The final gate drops it, and the next window's fresh exclusion
  skips the rest of that author's history. There are at most two such additions: a bond
  has two members and withdrawal markers are insert-only. A settled withdrawal therefore
  cannot consume the refill bound, and racing withdrawals cannot exhaust its twenty
  windows either. The bound remains for concurrent deletions/unmarks invalidating
  candidates, not for a backlog of withdrawn entries. No unbounded refill was introduced.
- **Cost:** favourites requests add a membership read before each candidate query. All
  entry disclosure still uses a separate reader resolved after loading the entries.
  The day still carries both entries: the filter selects days, not entries.

**8. `GET /bonds/{bondId}/days/{date}` is one `404 DAY_NOT_FOUND` for every way a date is
not in the caller's archive.** No row for the date, a day nobody wrote on, a date before
the bond or not yet come, a day holding only an entry the partner wrote and the caller has
not been shown, and a value that is not a date: one exception class with no argument, so
one body, for every value that reaches the handler. The day holding only the partner's
entry is why the others may not differ from it: an answer of its own would say that the
partner has written. (A value the firewall or the container refuses first never reaches
the handler: the review of Task 1 saw a bare `400` for a percent-encoded `<` in
`{entryId}`. Not run for the date segment.)

- It is the feed's own rule asked of one date (`ArchiveDays.on` runs the same statement
  with the date pinned; the `SEEN` fragment is written once) and the feed's own rendering
  (`GetDays.day` uses the functions `page` uses). `DayViewTest` asserts a day is, byte for
  byte, the element the feed gives.
- **The date is taken as text and read strictly**, for the guard's reason and one more:
  bound as a `LocalDate`, a value that is not one would be Spring's `400`, a second answer
  that quotes the input. `2026-2-3`, `2026-02-30` and `yesterday` are the `404`.
- **The contract says where today is** (`a5cf2f3`). The operation's description tells a
  client that today is `404` here until the caller has written or the day has revealed,
  and to read today from `GET /today`. Before the conformance review said so, only the feed
  had a description.
- A stranger gets the bond's `404`, before the date is looked at. The date is read before
  the joining day is reconciled, so a request that names no day writes nothing.
- **Today, for a member who has not written while the partner has, is `404` here and a
  day on `GET /today`.** That is the rule: `today` is the one route that says "they have
  written".

**9. Both archive routes are conditional reads, and the tag is a keyed digest.** Each
response is serialised once into a `Representation` that holds the bytes; the `ETag` is
made from exactly those bytes and a converter writes them and nothing else. So there is one
serialisation, and the tag cannot be a digest of one attached to another.

- **The tag is the lower-case hexadecimal HMAC-SHA256, under the personal-data secret, of
  the label `moyi-etag-v1\n` followed by the body. The plan specified the plain SHA-256.**
  A response header reaches proxies and access logs that the body never does. Every field
  of a day but the entry's words is known to the other member, so a bare hash seen in a log
  confirms a guess at a short entry: hash "thank you" in its place and compare. It is the
  hazard ADR-0031 decision 8 closed for `request_hash`, met again in a header. The
  reviewer of Task 2 named it in a note for Task 3, and the coordinator ruled for the keyed
  form while Task 3 was being built. The arrangement is the request
  fingerprint's: a port in `common:web` (`RepresentationDigest`), the bean in
  `common:security` (`HmacRepresentationDigest`), no new secret. The label keeps a tag from
  ever being the fingerprint or the address hash of the same bytes.
- **Strong**, because it is true: under one secret, two responses with one tag are the
  same octets. It moves when the caller marks or unmarks, when an entry is erased or
  withdrawn, and when a day reveals. It differs between the two members for the same day.
  It does **not** move when the partner marks anything, because nothing of theirs is in
  the body (`DaysConditionalTest`).
- **For a withdrawal it moves once, at the ending's commit, and not again at the erasure,
  on every day that had revealed.** A second change would tell the reader when the consumer
  ran, which a deletion by hand has no counterpart to. Run by the whole-branch privacy
  review: the other member's seven tags (the feed, the favourites feed, each day) were
  identical before and after the dispatcher ran; `WithdrawalTwinArchiveTest` now holds
  every page's bytes and tag across the erasure, and the smoke run reads the tag on both
  sides of the poller. **One day is the exception: the day of the ending, when the reader
  is listed on it and it had not settled.** There the day's `status` goes from `PARTIAL` to
  `OPEN` at the erasure, so the reader's feed tag and that day's tag move a second time
  (seen in the probe: the reader's own tombstone beside the withdrawn author's lone
  entry). That is ADR-0035 question 4's window, on the one unsettled day an ended bond
  has; the archive adds no day to it. What the archive adds is a cheap way to watch it:
  `If-None-Match` answers `304` until the instant the consumer runs.
- **What a keyed tag costs.** It validates only while the secret is unchanged. After a
  rotation, or a restart with an `EPHEMERAL` secret, a stored tag matches nothing and the
  client is sent the whole `200` once.
- **`304` has no body** and carries the same `ETag`. The comparison is Spring MVC's, made
  after the handler has returned, so a `304` has passed every check its `200` would have:
  the guard, the read and the rendering all ran, and only the transfer is saved. It is the
  mechanism `GET /bonds/{bondId}` has used since B4. `If-None-Match` is compared weakly,
  so `W/"…"` matches, and over a list of tags. A value that is not a tag matches nothing
  and the whole `200` is sent. **`If-None-Match: *` is not honoured on a read**: the
  framework keeps `*` for writes and answers the `GET` in full. RFC 9110 would allow a
  `304`; what the client gets instead is never wrong.
- **`Cache-Control: private, no-cache`** on the `200` and the `304`: no shared cache may
  store one caller's view, and the caller's own cache must ask before reusing it. Setting
  it is also what stops Spring Security adding its default, which includes `no-store`
  and would forbid the copy the tag exists to revalidate. That writer adds its default
  only where a response carries none of `Cache-Control`, `Pragma` and `Expires` (and never
  on a `304`), so **every other response keeps
  `no-cache, no-store, max-age=0, must-revalidate`**: the reviewer of Task 3 saw it on the
  archive's `404`, `401` and `422`, on `GET /today` and on `GET /bonds/{id}`. No
  `Vary: Authorization`: `private` already keeps the response out of any cache that serves
  two people, and access tokens are short-lived, so a client cache that honoured it would
  discard its copy at most of the times it would have been of use.
- **What that header lets a client's cache do, which `no-store` never did** (the
  whole-branch privacy review; reasoned, not run). For the first time a client's HTTP cache
  may keep entry text on the device. Words an author later withdraws stay in the other
  member's cache for every page that member does not ask for again; the server cannot
  reach them. And two accounts on one device share a cache keyed by URL. Revalidation is
  mandatory and the tag is each caller's own, so a cache that follows the rules never
  serves one member's page to the other; `Vary: Authorization` was rejected above on cost
  alone, without this being weighed. Both are the client's to handle: a cache per account,
  cleared at sign-out. Question 8.
- **`Accept` is settled by the mapping, and the response is its own bytes whatever was
  asked for.** As first built, the converter claimed a `Representation` for
  `application/json` only. The reviewer of Task 3 proved what that left. For
  `Accept: application/problem+json`, alone or listed before `application/json`, Spring
  chose a `+json` type the converter did not claim, and Jackson serialised the holder
  itself: a `200` whose body was `{"entityTag": …, "bytes…": <base64 of the real body>}`,
  under an `ETag` that was not the digest of the bytes sent. No privacy leak, since it was
  the caller's own body, and a broken response all the same (F1). And for
  `Accept: text/plain` the routes answered `406` after the read, carrying the day's `ETag`
  and `Cache-Control: private, no-cache` where a refusal is `no-store` (F2). No test sent
  an `Accept` header. Fixed at `50166ec`, in three parts:
  - **Both mappings declare `produces`: `application/json` and `application/*+json`.** The
    review prescribed `application/json` alone. Run, that made `Accept:
    application/problem+json` a `406`, and it is the type of this API's errors, so a thing
    its clients send. With the wildcard a client that lists it is answered.
  - **The converter claims a `Representation` for every media type and names the bytes
    itself**: `Content-Type: application/json`, whatever was negotiated. Left to the
    framework, `Accept: application/json;charset=ISO-8859-1` had been answered with that
    charset over UTF-8 bytes.
  - **Jackson refuses the holder.** A serializer on `Representation` throws, so a response
    that ever reaches the JSON converter is a `500` somebody sees and not a `200` carrying
    the holder's fields.

  **A request that will take no JSON is now `406` before the handler runs, and so before
  the membership guard.** It carries no `ETag` and the default `no-store`. It is decided
  from the request's own header and is the same whole response, but for the caller's own
  path in `instance`, for a member, a member with a current `If-None-Match`, a stranger,
  an unknown bond and a value that is not a UUID (run by the fixer over twelve askers), so
  it is not an oracle; but on these two
  routes a stranger who sends `Accept: text/plain` gets `406` and not the bond's `404`. On
  `GET /today` and the rest the `406` still comes after the handler. The generator's copy
  of the wildcard is removed for the two operations, so the `200` documents
  `application/json` only. The `406` itself was in the contract for neither operation
  until `a5cf2f3`: it is now documented on these two and on no other, saying that it is
  decided before the membership check and is the same response whoever asks. The
  whole-branch privacy review saw it byte-identical for a stranger, an unknown bond, a
  value that is not a UUID and a member, and the smoke run now compares a stranger's with
  a member's.

**10. A favourite is a row `entry_favourites (entry_id, member_id, created_at)`** (V22,
`V22__gratitude_entry_favourites.sql`), and not a flag on `entries`: an entry is shared, so
any column on it is readable by both people. Keyed this way there is no query that answers
"is this entry a favourite" without naming whose.

- **The primary key is `(entry_id, member_id)`.** There is no `id` column: nothing refers
  to a favourite. It serves the erasure, which starts from the entry.
- **`entry_favourites_by_member_idx (member_id, entry_id)`** serves the two questions that
  start from the member: which of these entries has this member marked, and the favourites
  filter. With 8,000 other members' marks in the table, Postgres drove the filter from a
  bitmap scan of this index over the member's 20 rows (seen in the plan; `ArchiveDaysTest`
  asserts that fewer than 60 rows of the table are read). It is not doc 07's
  `(member_id, created_at DESC)`: the filter returns days in date order, and nothing orders
  by when a mark was made (X12).
- **`entry_id` carries a foreign key to `entries` with `ON DELETE CASCADE`; `member_id`
  carries none.** `entries` is this module's table. `bond_members` is `bond`'s, and no
  reference crosses a module boundary. The cascade is for the day an entry's row goes,
  which today only happens when its bond-day does. It is not what removes a favourite on a
  delete (decision 13).
- `member_id` is the caller's member id in the entry's bond, never a user id: one person in
  two bonds is two members.
- `created_at` is cut to microseconds by the writer and returned by nothing. A second mark
  keeps the first time. **Nothing reads it either**: no query orders or filters by it. To
  an operator the row is a durable record of which words a person kept and the instant
  they did, and for a partner's entry that is an instant at which they were reading it.
  FR-093's "private" cannot be made to hold against an operator (the filter runs in SQL,
  so the row must be readable server-side); what can be done is to keep less. The column
  is kept because the corpus specifies it (doc 04:90, `memberId · entryId · createdAt`).
  V22 is unmerged and may still be edited. Noted by the whole-branch privacy review;
  question 7.
- The feed needed no index. `bond_days_feed_idx (bond_id, date DESC)` has existed since
  V12, and on a 2,000-day bond Postgres chose the unique `(bond_id, date)` backwards
  instead; either serves, with no sort. **A page costs about a page where the listed days
  are dense, and this record first said "however old the bond is".** The statement walks
  `bond_days` backwards and probes `entries` once a day until it has `limit + 1` days the
  caller has something to see on. A day they have nothing on is walked past with one
  probe: a day with no entry row (`EMPTY`, `FROZEN`, a `SUSPENDED` gap; `CreateMissingDays`
  writes those rows) or one holding only the partner's unrevealed entry. A page that
  crosses a year nobody wrote in probes about 365 days, and the last page of any walk
  reads back to the bond's first day. Measured by the whole-branch concurrency review,
  warm, on a laptop: 3,000 days with the caller's entry on the 5 oldest, 12,084 buffers and
  2.4 ms for the first page; 3,000 days with no entry rows at all, 9,084 buffers and
  1.8 ms; 30,000 days with none visible, 120,761 buffers and 27.7 ms. Every page repeats
  it, and cold it is random I/O. The bound is one row per calendar date of the bond's own
  life, never the table. **NFR-033** (cost does not grow with the size of the table) is
  met for the dense feed and for `GET /days/{date}`; for a sparse history and for
  `favourites=true` it holds only in that sense, the bond's own days or the member's own
  marks, and not by the letter. `ArchiveDaysTest` seeds a revealed day for every date and
  so asserts the dense case only. Nothing was changed. The favourites form can read
  further: it walks back until it has found enough marked days.

**11. Who may mark what is the gate's answer for the caller, and one thing more**
(`Entry.canBeFavouritedBy`, which calls `canBeReadBy`; `FavouriteEntry` only translates):

| The gate answers | And the entry | `PUT` | `DELETE` |
|---|---|---|---|
| `FULL` | has `revealedAt` | `204` | `204` |
| `FULL` | has none: the caller's own, waiting | `409 ENTRY_NOT_REVEALED` | `204` |
| `TOMBSTONE` | erased, or its author withdrew | `409 ENTRY_IMMUTABLE` | `204`, and it does delete |
| `LOCKED`, `TOMBSTONE_UNSEEN`, `NOT_A_MEMBER` | | `404` | `404` |
| no such entry, or not a UUID | | `404` | `404` |

Both verbs are repeatable. An unmark with no mark is a success: absent is what was asked
for.

- **This is not `ChangeEntry.authorOf`'s rule, on purpose** (ADR-0032 asked each new route
  by entry id to use it or say why not). `authorOf` answers "may this caller change the
  entry", and only its author may. A favourite changes nothing of the entry. Doc 04 makes
  it a row of the member's own, and either member may mark either entry they can read, the
  partner's above all. What the two rules share is the refusal: everyone the entry was
  never shown to gets the one `EntryNotFoundException`, for every cause.
- **`409 ENTRY_NOT_REVEALED` is reachable only for the caller's own entry** (X14). Doc 06
  and spec §5.2 say "`409 ENTRY_NOT_REVEALED` before reveal" without that limit. A
  partner's unrevealed entry is locked and its id was never given to them; a `409` there
  would confirm that the id names an entry and that it was written. It is the `404`.
- **A tombstone is `409 ENTRY_IMMUTABLE`**, the code a `PATCH` gets on a settled entry
  (S7). One code for "this entry takes no more changes" is worth more to a client than a
  second.
- **Both sentences were reworded**, after the reviewer of Task 1 read them in a response.
  `ENTRY_IMMUTABLE` said "This entry can no longer be edited.", which is about editing; it
  says "This entry can no longer be changed.", true of an edit and a mark alike.
  `ENTRY_NOT_REVEALED` said "This entry can be saved once both of you have written.",
  which is untrue of an own entry on a solo day that closed after the bond ended (it never
  will be revealed) and of a bond still waiting for its second member; it says "This entry
  has not been revealed." and promises nothing about when. No test had asserted either
  sentence; `FavouritesTest` and `EntryChangesTest` now do.
- On `DELETE` a tombstone still deletes. It costs nothing and removes a mark left by the
  one interleaving decision 12 lets through. It deletes only a row it can lock at once, so
  it never waits for whoever else is deleting it (decision 12).
- **The contract now says all of this to a client** (`a5cf2f3`): whose a bookmark is, that
  only an entry the caller is shown as `REVEALED` takes one, and what each `409` means.
  The conformance review found that three of the four operations had no description.

**12. The mark is one statement, and it takes a `FOR SHARE` row lock. The plan said "no
lock is taken".**

```sql
WITH markable AS (
    SELECT e.id FROM entries e
    WHERE e.id = ? AND e.revealed_at IS NOT NULL AND e.deleted_at IS NULL AND e.status <> 'DELETED'
    FOR SHARE
), marked AS (
    INSERT INTO entry_favourites (entry_id, member_id, created_at)
    SELECT id, ?, ? FROM markable
    ON CONFLICT DO NOTHING
)
SELECT count(*) FROM markable
```

It was reached in three steps, each forced by running the one before.

- **With no lock, the first run wrote a bookmark on an erased entry.** The plan's
  statement was the `INSERT … SELECT … WHERE` with no locking clause, on the reasoning
  that an erasure which commits first leaves nothing to insert. The implementer of Task 1
  held an erasure open and started a `PUT`. The insert did wait, in its foreign-key check,
  behind the eraser's row lock. When the erasure committed, the insert **went through**:
  `204`, and a row on an erased entry, inserted after `EraseEntry` had finished removing
  that entry's favourites. The statement's `WHERE` had been evaluated on the row as last
  committed, before the wait. A lock taken at the read is what makes Postgres evaluate the
  `WHERE` again on the row the other transaction left.
- **`FOR KEY SHARE` worked only because the other side locked first.** It was the first
  lock chosen: the one the foreign-key check takes anyway. The reviewer of Task 1 proved
  what it rests on. It waits only for a transaction that holds `FOR UPDATE` or changes the
  row's key. `EraseEntry` does take `FOR UPDATE` first; an eraser written as a plain
  `UPDATE` was not waited for, the mark returned true while the erasure was open, and an
  orphan row was left. `FOR SHARE` conflicts with every update of the row, so the guard no
  longer rests on how the eraser happens to be written. The reviewer's probe is now a
  test: with `FOR KEY SHARE` it alone fails.
- **It answers whether the entry qualified, not whether a row was inserted.** The first
  form counted the insert and, on a conflict, asked in a second statement whether the row
  was there. The caller's own unmark committing between the two made a repeat `PUT` on a
  whole entry answer `409 ENTRY_IMMUTABLE`: a double tap on a toggle. Reasoned by the
  reviewer, then run as a test by the fixer. In one statement a repeat `PUT` is `204`
  whatever a concurrent unmark does.

The lock is held for that statement alone and nothing else is held with it, so it adds no
edge to the application's lock order (bond, day, entry). In the other order the erasure
waits for the statement and removes what it inserted.

**Neither verb waits out a withdrawal** (`fdd365f`). As first built the mark waited for
its lock with no bound, and this record said so of the mark and nothing of the unmark. The
whole-branch concurrency review proved that the unmark was the one that mattered.

- **Who waited on what.** `Favourites.unmark` was a bare `DELETE`. The withdrawal consumer
  erases a member's entries one at a time in one transaction and holds every row it has
  touched, the bookmarks it removed included, until the whole delivery commits. The gate
  lets an unmark through on a tombstone, which is what a withdrawn author's entry is. So
  an unmark of a bookmark the consumer had already removed waited for the rest of the
  delivery, holding a pooled connection. The mark was the safe one there: the gate
  answers a withdrawn entry as a tombstone and refuses before any statement.
- **Measured.** 2,000 withdrawn entries, all bookmarked by the partner. The delivery took
  3,964 ms. Six unmarks, fired 0.4 s into it, each took 3,557 ms. The test's pool is four
  connections; with all four held, a member of **another bond** waited 3,261 ms for
  `GET /today` (43 ms before), and so did the partner's own feed and a `PUT` that the gate
  refuses without touching a row. They waited for a connection, not for a lock. In
  production the pool is Hikari's default of ten and nothing sets a lock or statement
  timeout for a request, so nine unmarks by one member, which the global limit of 120 a
  minute allows, would hold it for the rest of a delivery, and a delivery may run to sixty
  seconds (reasoned by the reviewer from the run, not run at ten). It healed by itself:
  the delivery keeps its one connection, and nothing deadlocked.
- **Amended 2026-10-10: an unmark uses `FOR UPDATE NOWAIT`, not `SKIP LOCKED`.**
  Skipping a held row returned `204` even when its competing erasure subsequently rolled
  back, leaving the bookmark intact. A held row now gives `409 FAVOURITE_BUSY`, a
  retryable response with no database exception text. An absent or successfully removed
  row still gives `204`. A retry after either commit or rollback is covered over HTTP.
  Both variants avoid waiting for a row lock, but only `NOWAIT` distinguishes contention
  from absence ([PostgreSQL 18 locking clauses](https://www.postgresql.org/docs/18/sql-select.html#SQL-FOR-UPDATE-SHARE)).
- **The mark waits two seconds for a lock and then answers `409 ENTRY_IMMUTABLE`.** Its
  statement now runs in a transaction of its own that first sets
  `set_config('lock_timeout', '2000ms', true)`: `ConsumerRegistry`'s idiom, local to the
  transaction, so the pooled connection goes back as it came. Set outside a transaction
  it would end with its own statement and bound nothing. It still takes no bond or day
  lock. A revealed, unerased entry's row is changed by nothing but an erasure, and only a
  withdrawal's holds it for long; so a row held past two seconds is being erased, and
  "can no longer be changed" is the answer waiting would have given, sooner. If that
  erasure rolls back, the member was refused a mark on a whole entry and may ask again.
  Postgres's `lock_not_available` is caught outside the transaction and never logged.
- **How it is held.** `FavouriteLockWaitTest` opens an erasure on the test's own
  connection and leaves it open: the unmark answers `409 FAVOURITE_BUSY` while it is open; the mark is
  refused while it is open; no pooled connection keeps the timeout. The mark's refusal is
  asserted at the service (`EntryImmutableException`, through a store given 200 ms), not
  over HTTP: a bean of its own for the short wait meant one more Spring context, whose
  connections took the module's test database past its limit. Over HTTP the `409` is
  asserted only where the erasure commits (`FavouritesTest`).
- *Not changed:* every other request that takes the bond's lock on that bond still queues
  behind a delivery, as since C5a (reasoned by the reviewer, not run). The pool's size is
  not set, and no request has a statement timeout.

The gate is asked first, because the statement does not know about a withdrawal that has
erased nothing yet: that row is whole. **One interleaving is let through:** a withdrawal
that commits after the gate answered and before the statement runs. The mark is made, by a
request whose answer was decided before the ending. Nothing shows it (every later read is a
tombstone, and a tombstone says `false`), the favourites filter drops its day (decision 7),
and `EraseEntry` removes it when the consumer or the close job reaches the entry.

**13. `EraseEntry` removes every member's favourite on the entry it erases**, in the
erasure's transaction, under the entry's row lock, and only when that call is the one that
erases. A repeated call writes nothing, this included. A favourite is never a reason to
keep anything of an entry its author took back (spec §6.6).

It is done there and not in each caller, so the three things that erase cannot come to
differ in it: the author's `DELETE`, the withdrawal consumer, and the close job's and the
joining-day reconcile's pre-step (ADR-0035 decision 14). And not by the foreign key, whose
cascade never fires because the row is kept (X5). An erasure that rolls back keeps the
mark (run by the reviewer).

**Measured:** the 2,000-entry withdrawal took 3,800 ms before the change and 4,559 ms
after it with 2,000 favourites to remove, on a developer's laptop: about 0.4 ms an entry
more.

**14. A favourite may be made and removed on an ended bond, and by a member who has left.**
`FavouriteEntry` has no `hasLeft` or `isOpen` check, where every other write has one. BR-9
closes an ended bond to writes; it is read here as writes to what the two people share. A
bookmark is the member's own, the other person can see it in no response, and the archive
is what an ended bond is for. Question 1.

**The favourite routes reconcile the joining day first, like every gratitude operation**
(spec §12.4), and so do both archive routes. On a day C1 left `SUSPENDED` with both
entries, the reconcile is the reveal, and the difference between "not yet" and a mark. So a
`PUT` or `DELETE` of a favourite, or a `GET` of the archive, can take the bond's lock and
write, once per bond, when that day is still `SUSPENDED`. The plan called the favourite
routes lock-free and did not mention the reconcile. The implementer of Task 1 added the
call; the reviewer found that removing it failed no test, and two cases in `JoiningDayTest`
now hold it.

**15. All three new read paths go through the gate and read the marker last** (ADR-0035
decision 12): the feed, the day, and the favourite routes' question about one entry. Each
loads its entries first and only then resolves the membership again for the `Reader` the
gate is asked with (`readerNow`). The membership the controller resolved still says whose
archive this is and which member is "me". It does not say who has withdrawn, because the
joining-day reconcile between the two can wait on the bond's lock while an ending commits.
Since `a461ea8` the feed can read several windows of candidates in one request; it asks
after each window's entries (decision 7).

**A page is not one snapshot.** `GetDays.page` and `day` run in one read-only transaction
at `READ COMMITTED`, which is one snapshot for each statement: the days and their status,
then the entries, then the membership and the marker, then the marks. A day's `status`
comes from the first and its entries from the second. The whole-branch concurrency review
ran the partner's submit committing between the two, and both routes answered
`"status":"PARTIAL"` with both entries `REVEALED` and the partner's words in full: a day
that existed at no instant. With the author's own delete in the gap: `PARTIAL` over the
author's tombstone and no partner entry, where the true state was `OPEN`. It is not a
privacy fault, because the reveal or the erasure had really committed and the gate is
asked of the later rows. It does not stick: the next read differs, and the first tag
revalidates to a `200` (run). `GET /today` has read the day and then the entries since C1.
**`REPEATABLE READ` was not used.** It would remove this and is mechanically possible
(these transactions publish nothing, and a read-only one cannot fail serialisation). But
it moves "who has withdrawn" from "as of the last read" to "as of the transaction's first
statement": a block committing during the request would be shown whole, which is what
this decision exists to prevent. So a client must not read "waiting" from `status` when
both entries are present.

**The first tests of this ordered nothing.** The feed's and the day's each stalled the
request with `LOCK TABLE entries` and ended the bond while it waited. But the statement
that finds the days reads `entries` in its filter, so the request stopped there, before
either of the two reads being ordered, and made both after the ending had committed. The
mutation that swaps the two reads inside `GetDays.read` survived both tests, twice: once
in the first Task 3 agent's run and once in the second agent's. `ArchiveReadOrderTest` now
holds the order where it matters. A test-only subclass of the application's `EntryStore`
runs a real `/block` to its commit on another thread as the archive's read of the entries
returns; four tests, the day and the feed for each reader; the mutation fails all four. A
table lock could not do this: the request's own transaction already holds `ACCESS SHARE`
on `entries` from its first statement, so an exclusive lock taken between the two reads
would wait on the request itself.

**16. No `Idempotency-Key` and no bucket of their own.** The favourite routes read no
`Idempotency-Key`: a repeat already changes nothing (S7). None of the four routes carries
`@RateLimited`; they sit under the global per-user limit every authenticated request
passes (S15). A mark is one small statement and an id that was never shown is the same
`404` however many are tried.

## Consequences

- **Migration V22 (`modules:gratitude`).** It has been applied to no shared database.
- **The contract gains four operations and two error codes.** `days` and `day` under
  `/api/v1/bonds/{bondId}/days`, with an optional `If-None-Match`, an `ETag` on the `200`
  and a documented `304`; `favouriteEntry` (`204`, `404`, `409`) and `unfavouriteEntry`
  (`204`, `404`) under `/api/v1/entries/{entryId}/favourite`; the schemas `DayResponse` and
  `DaysResponse`; `EntryResponse.favourited`, required; `ENTRY_NOT_REVEALED` and
  `DAY_NOT_FOUND` in the code enum. **The two codes make this a breaking change for the
  generated client** (ADR-0024's 2026-09-24 amendment), so the pull request carries
  `breaking-api-change`.
- **An existing client sees one new field**, `favourited`, on every entry it already
  receives in the wide shape.
- **A short page is not the last page.** `items` can be shorter than `limit` while
  `nextCursor` is not null, because a page is also bounded by its size (decision 6). A
  client that stops at a short page stops early. A page with a cursor holds at least one
  day, except at decision 7's bound on one request's reading; until `a461ea8` any
  `favourites=true` page could be empty with a cursor, and that was a leak.
- **`GET /today` can be about 98 KB** (decision 6). It always could; nobody had measured
  it. Whether `EntryText` should count untrimmed control characters is not this slice's
  and is not changed.
- **A read can write, once per bond**, on the four new routes as on `GET /today`
  (decision 14).
- **A mark waits at most two seconds for its entry's row, and an unmark never waits**
  (decision 12). Before that was so, the per-task reviewers ran 30 rounds of a partner's
  `PUT`, the author's `DELETE`, a block with withdrawal and the dispatcher started
  together, and 25 rounds of two `PUT`s, an erasure or an unmark and a favourites feed. No
  deadlock, no `5xx` and no mark left on an erased entry in either; the worst round of the
  first was 24 ms (the second was not timed). The whole-branch concurrency review then ran
  40 rounds on one bond with two revealed days: looping `PUT`s by both members on three
  entries, two looping unmarks, both feeds, the day view and `GET /today`, against an
  author's `DELETE`, a block with withdrawal and its dispatcher, a second dispatcher and
  the close job. About 1,150 requests: `PUT` `204` 275 times and `409` 123 times, so the
  races were met; everything else `204` or `200`; no `5xx`; Postgres's deadlock counter
  unmoved; the worst round 142 ms; and after every round no bookmark on any erased entry.
  On a pool of four, so at most four statements at once. **None of these races was run
  again after `fdd365f`** changed both statements; the build's own tests were.
- **A second use of the personal-data secret that a client can see.** The tag is the first
  value made from it that leaves the server in a response. The label keeps it apart from
  the other two uses. Rotating the secret costs each client one full read per resource, in
  addition to what ADR-0031 decision 8 says it costs idempotent replays.
- **The favourites filter reads all of the member's marks in the bond on each page**, and
  the plan the application runs is not the plan the test reads. `ArchiveDaysTest` asks
  Postgres to explain the statement with its values in place, which is a custom plan. The
  driver prepares the statement on its fifth use on a connection, and Postgres then prices
  a generic plan that knows neither the member nor the limit. Measured by the whole-branch
  concurrency review at 498,000 entries, for a member with 6,000 marks on a 3,000-day bond
  at a limit of 21: custom plan, a backward index scan, 256 buffers, 0.06 ms; generic plan,
  driven from all of the member's marks with a top-N sort, 48,103 buffers, 12.8 ms; through
  `ArchiveDays.candidates` itself after twenty warm-up calls, a median of 12.3 ms, so the
  application does get the generic plan. At about 200 and 1,000 marks: 0.4 to 0.7 ms and
  2.6 to 3.1 ms. Cost is linear in the member's marks, on every page. The plain feed keeps
  its index scan in every mode (88 buffers, 0.03 ms). When this was first written it said
  "not measured beyond 20 marks among 8,020". Not changed: 12 ms at a number of bookmarks
  no member has.
- **A feed request is about eleven to fourteen statements in three connection
  check-outs**, and the page's own three are the small part: the membership twice (the
  controller's, then `readerNow`'s; each is the bond and its members, the anchor timeline
  and the withdrawals), the joining day, the candidates, the entries, the marks.
  `GET /days/{date}` is the same with the date pinned. The joining-day check is one read
  and no lock on every archive and favourite request, for good. Counted from the code by
  the concurrency review, not measured. A request that refills (decision 7) adds about
  eight a window.
- **A date with a dot in it gets one more header on its `404`.** `GET …/days/2026.09.15`
  answers the same `404 DAY_NOT_FOUND` body with Spring's
  `Content-Disposition: inline;filename=f.txt`, its guard against a path that looks like a
  file name (`.json` and `.txt` get none). It depends only on what the caller typed: the
  response is the same whether the value resembles the caller's day or nobody's, and a
  stranger gets the bond's `404` with the same header. Found while writing the loose-date
  test; accepted, not suppressed, and pinned by a test in `DayViewTest` since `50166ec`.
- **A `406` on the two archive routes comes before the guard** (decision 9). Everywhere
  else it comes after. The contract documents it on these two, and only there.
- **One more logger is pinned in the shipped `application.yml`**:
  `org.springframework.jdbc.core.StatementCreatorUtils`, at INFO (`b9f1e78`). At TRACE it
  prints each value `JdbcTemplate` binds, and for a bookmark that is the entry's id, the
  member's id and the instant on three adjacent lines: who kept what and when, in a log
  kept longer and read by more people than the table. No entry text goes through it. Seen
  by the whole-branch privacy review with every level raised. `SecretsNeverLoggedTest`
  drove none of the four new routes; it does now, and fails if that logger writes a line
  (225 before the pin). `JdbcTemplate` itself at DEBUG prints a statement's text with `?`
  for each value, which the test asserts of the bookmark's statements, and is not pinned.
  The pin gives way to a level set on that exact logger, as the other pins do.
- **ADR-0032 to ADR-0035 read "Proposed" after they merged; this slice sets them to
  "Accepted".** ADR-0035's smoke run is corrected from 18 to 19 sections. Each of the four
  says so in a dated note; this is the line they point at. This record's own status is set
  in this pull request, as ADR-0026 to ADR-0031 did.
- **The plan test asserts shape, not planner choice, and of the custom plan, on a dense
  bond.** `ArchiveDaysTest` reads
  `EXPLAIN (ANALYZE, FORMAT JSON)`: no sequential scan, and fewer than 63 rows taken from
  `bond_days` and from `entries` for a first, a deep and an `until` page on a 2,000-day
  bond. It does not read the generic plan the application comes to run (above), and it
  never meets a gap in the days (decision 10). Its first form asserted "no `Sort`" and an
  index name, which a Postgres upgrade could turn red for no fault; the reviewer of Task 2
  said so and it was rewritten. NFR-003 (a 20-day page at p95 ≤ 200 ms) and doc 12's
  500k-entry deep paging were not measured.
- **Nothing removes a member's favourites when the member goes.** `member_id` has no
  foreign key, on purpose. Owed, Phase 5.

## Owed

**ADR-0035's "Owed — C5b" is discharged**, item by item:

- A closed `SOLO` day whose entry has no `revealedAt` is private to its author, through
  `Entry.canBeReadBy` and not the day's status: decisions 1 and 2; `ArchiveGateTest`,
  "SOLO on a bond that ended before the day did".
- Favourites are removed in `EraseEntry`: decision 13; `FavouritesTest` for the delete, the
  withdrawal and the closer's pre-step.
- No response carries a tombstone's `deletedAt` or `updatedAt`: decision 3; `DaysFeedTest`,
  "no response carries when an entry was erased or last changed, on any shape".
- The default page and the 256 KB cap: decision 6. The figure in that item, 327,680 bytes,
  understated the problem.
- Whether a day's page may say `SOLO`: it does, decision 3. **Still owed there:** `states.md`
  §6 draws no solo day card (question 5).
- Each new route by entry id uses `ChangeEntry.authorOf`'s rule or states why not, and
  joins `EntryChangesTest`'s route set: decision 11 for favourites; the two routes are in
  the set. **Open for C5c**, reactions.

**C5c, reactions.**

- A route by entry id that is not the author's rule either. Decision 11's table is the
  nearest model: the gate's answer for the caller, one `404` for everything never shown,
  and no `409` that confirms an id.
- **No timestamp.** A reaction's `created_at`, if returned, is the time the partner read
  the entry (X4). `entry_favourites.created_at` is returned by nothing, for the same
  reason.
- `EraseEntry` must remove an entry's reactions as it removes its favourites.
- Whether a reaction is allowed on an ended bond is not decision 14's question: a reaction
  is seen by the other person, and a bookmark is not.
- **The owner's rulings of 2026-10-06 on reactions.** Given in conversation and written
  down here for the first time, so nothing else in this repository vouches for them: one
  reaction per member per entry; on the partner's revealed entry only; visible to the
  author; no timestamp; and a placeholder set, `HEART`, `THANK_YOU`, `SMILE`, `MOVED`.
  They answer X2 (one, where doc 07's key and doc 06's route allow one per type) and part
  of S1, S2 and S3 below. "Placeholder" is the owner's word: the set is not final.
- **The corpus read's reactions items are in no other ADR, and the read (`c5-corpus.md`)
  is not in this repository.** ADR-0035 carries X6 to X10, X15 to X21 and S16 to S26; this
  record carries the rest but these: **X2** (doc 03 says "one of a small fixed set of
  reactions"; doc 07's unique key `(entry_id, member_id, type)` and doc 06's
  `DELETE …/reactions/{type}` permit every type at once), **X3** (reactions have an
  endpoint and no screen, no fixed set and no recorded gap), and **S1 to S5**: the set, its
  wire names and whether it is an enum in the contract; whose entry may be reacted to;
  whether and on which responses reactions are returned, and in what shape; what an
  erasure by delete, by withdrawal and by account deletion does to them, the withdrawing
  member's own reactions on the partner's entries included; and the status codes for a
  repeat, an unknown type, a tombstone and before the reveal. C5c's plan settles each
  before code. Found dropped by the conformance review.

**C6, search.**

- Search lists by the archive's predicate and renders through the gate. A second account
  of which days a member may see would drift from this one. `ArchiveDays.SEEN` is the
  fragment to share.
- `EraseEntry` must clear search data (ADR-0035, Owed, C6: unchanged by this slice).
- A search result is one more response that returns many entries: decision 3's rule on
  `deletedAt` and `updatedAt`, decision 6's on bytes and decision 15's on the marker apply
  to it.

**Phase 5, account deletion.** A member's favourites. Nothing removes a member row today;
when something does, that change removes the member's rows of `entry_favourites`. Until
then a record of what a deleted person kept would outlive them, until the bond's days are
hard-deleted (the foreign key's cascade from `entries` takes them then). This belongs in
Phase 5's deletion checklist and not only here; and a member's export should carry their
own bookmarks and nobody else's.

**`bond`.** The partner's display name is still not obtainable in `gratitude`:
`BondMembership` names nothing about the other member (ADR-0031, Consequences). The
archive's author label needs it (question 5).

**Its own pull request: the bond route's conditional `GET`.** Measured while building
decision 9. `GET /bonds/{bondId}` sends its `ETag` beside Spring Security's `no-store` on
the `200`, so for an HTTP cache its conditional read is of no use; it works for a client
that keeps its own copy. Its `304` carries no `Cache-Control` at all, because the security
writer skips a `304`. And its `304` and `If-None-Match` are not in the contract. Not a
privacy defect: it is stricter than it needs to be, and its tag exists for `If-Match`. By
the reviewer's reasoning, not run, a `406` from it carries the entity's `ETag` by the same
mechanism as F2.

**Not assigned.**

- The unreachable cell of decision 2 has no test.
- `REVALIDATED_OPERATIONS` in the contract's configuration selects `days` and `day` by
  operation id. A later handler named `day` in another controller would get the header
  documentation without earning it.
- The mark's two-second refusal has no test over HTTP, and the races of Consequences were
  not run again after decision 12 changed (How this was checked).
- `ArchiveDaysTest` reads the custom plan on a dense bond only (Consequences). A plan read
  from a prepared statement under `plan_cache_mode = force_generic_plan`, and a bond with
  gaps, would hold the two cases it does not.
- Nothing sets the connection pool's size or a statement timeout for a request (decision
  12).

## Questions that are the owner's

Each is built one way and cheap to turn.

1. **Favourites on an ended bond.** BR-9 says every write on an archived bond is `409`
   except export and deletion; doc 06 and `states.md` §9 repeat it. Built: allowed, for the
   member who left too (decision 14), reading BR-9 as writes to what the two share. Turning
   it is one check in `FavouriteEntry`, and then the bookmark control on an archived bond's
   day view must be absent.
2. **The emptied day is listed for its author** (decision 1). A day that closed `EMPTY`
   because its author deleted their only entry shows that author `status: EMPTY` and their
   own tombstone, and shows the partner nothing. It follows from the rule and from "a
   tombstone the member could once read is still shown". Hiding it would be one predicate
   more, and the first place the list was decided from a day's status.
3. **An empty favourites page with a cursor** (decision 7). **Answered on 2026-10-09, the
   other way.** This question framed it as a matter of round trips; the whole-branch
   privacy review showed it told a withdrawal from a deletion by hand. The request now
   reads on until the page is decided. The remaining withdrawal signal at the bound was
   fixed on 2026-10-10 by excluding known withdrawn authors before each candidate limit
   and retaining the final read gate (decision 7).
4. **`until`** is an API the corpus does not name (decision 5). Doc 06 gives the feed three
   parameters. Built as a fourth, and inclusive, so the client sends the last day of the
   month it jumps to.
5. **Whether a day item carries what the screens need.** `states.md` §6 read against the
   response; this is the Figma-alignment record for the four routes.

   | Screen need (`states.md`) | The response | Verdict |
   |---|---|---|
   | Month-grouped list, newest first (§6) | `date` on each day; one order | served |
   | Day card with both entries side by side (§6) | `myEntry`, `partnerEntry` | served |
   | Two-line truncation in the list, full text in the day view (§6) | full text on both routes | **adaptation**: the client truncates. The list pays for full text, which is what decision 6 bounds |
   | Author label; "From you" on your own tombstone (appendix) | `authorMemberId` on every shape, and which field the entry is in | whose it is: served. **The partner's name: gap** (Owed, `bond`) |
   | Day view (§6) | `GET /days/{date}`, byte for byte the feed's element | served. **Adaptation**: for today, before the caller has written, the client uses `GET /today` (decision 8); the contract says so |
   | Favourite toggle per entry in the day view (§6) | `id`, `favourited`; `PUT` and `DELETE` | served |
   | The toggle changes in place, "no confirmation, no undo toast" (§6) | both verbs are `204` with no body, and repeatable | served |
   | Toggle absent before reveal, and on a tombstone (§6) | no field says "markable" | **adaptation**: the client shows it on an entry whose `status` is `REVEALED`; anything else is a `409`. The contract's description of the `PUT` says so |
   | Day card "holds a favourite" mark (§6) | derivable: either entry's `favourited` | served |
   | Favourites filter, with results and empty (§6) | `favourites=true` | served. The empty state is `items: []` with a null `nextCursor`; a page with a cursor holds a day, short of decision 7's bound |
   | Month jump (§6) | `until` | **adaptation** (question 4). **Gap**: nothing says which months hold days, so a month picker cannot grey out an empty one; and its first month is derivable only from the bond's `createdAt` on `GET /bonds/{id}`, not from the archive |
   | Report this day's entry, reached from the archive's day view (doc 26:102) | the entry's `id`, on an entry read in full or as a wide tombstone | served for the id. **The report route is a later phase's** |
   | "On this day" and "Milestone" rows of §6's state table | none | C6 (spec §11 names on-this-day; milestones are spec §7's) |
   | Empty archive (§6) | `items: []`, `nextCursor: null` | served |
   | Delete your own entry from the day view (§6) | `myEntry.id` | served |
   | A solo day card | `status: SOLO`, one entry, the other absent | **gap in the design**: §6 draws none |
   | A day of tombstones after a withdrawal (§9) | listed for both, wide tombstones | **gap in the design**: §9 says it owes this drawing before C5 |
   | An emptied day; a pre-join day for its creator; a day still waiting; a day on which the partner wrote and erased before any reveal | `status: EMPTY`, `SUSPENDED`, `PARTIAL`, `PENDING_REVEAL` with the caller's own entry; for the last, the partner's entry as an author and `REMOVED` with nothing to draw | **gap in the design**: none is drawn in the archive (the last is also question 6) |
   | Archived bond, read only (§9) | both routes answer for both members, the one who left included | served |
   | No timestamp on an entry card (`tokens.md`) | `createdAt` and `intendedAt` are sent, as since C1 | the client does not draw them |
   | Search chip (§6) | none | C6 |

   The owner's part is the three design gaps and the name. The response would not change
   for the first three. The rows for the report entry point, on-this-day and milestones,
   the toggle's manner and the month picker's range were added after the conformance
   review found them missing.
6. **A partner's entry that was written and erased before you saw it is a permanent
   `REMOVED` in the archive** (decision 1). BR-8 allows the shape, and `today` has shown it
   for the day since C5a. Built: shown for good, on every walk. Hiding it once the day has
   closed would be one more rule, and the first that renders an entry from its day's
   state.
7. **`entry_favourites.created_at`** (decision 10). The corpus specifies the column;
   nothing returns it, orders by it or filters on it; to an operator it is the time a
   person was reading. Built: kept, as specified. Dropping it is an edit to V22 while the
   branch is unmerged, and a new migration after.
8. **What a client may cache** (decision 9). `private, no-cache` lets a client's HTTP cache
   keep entry text, where every other response is `no-store`. Withdrawn words then stay on
   the other member's device until that page is asked for again, and two accounts on one
   device share a URL-keyed cache that only revalidation keeps apart. Built: as it is, with
   no `Vary`. The other ways are `no-store` (the tag then serves only a client that keeps
   its own copy, as the bond route's does) or a rule for the client: one cache per account,
   cleared at sign-out. The KMP client is not written yet.

### The corpus

What the corpus read found that concerns this slice, and what became of each. The corpus
documents are not edited here.

- **X1** — three documents, three sets of archive days. *Resolved, decision 1:* one rule on
  the entry rows.
- **X4** — a visible reaction is the read receipt a visible favourite would be. *Not this
  slice's.* Favourites show nothing to the partner (decisions 4 and 9); Owed, C5c.
- **X5** — "on delete cascade" cannot remove a favourite. *Resolved, decision 13.*
- **X11** — the default page breaks the cap. *Resolved, decision 6*, and the corpus's own
  arithmetic was too kind.
- **X12** — doc 07's favourites index does not serve the filter. *Resolved, decision 10.*
- **X13** — the month jump has no API. *Resolved, decision 5*; question 4.
- **X14** — `409 ENTRY_NOT_REVEALED` confirms an id. *Resolved, decision 11.*
- **X19** — doc 07 §7 and doc 02 J5 say retained read access, unqualified. *Left for the
  corpus*, as ADR-0035 left it. The archive is where the qualification is now visible: a
  withdrawn author's days are tombstones for both.
- **X22** — doc 04 gives `EntryFavourite` no id, doc 07 gives it `id uuid pk`. *Built as
  doc 04 has it* (decision 10).

The silences it named: favourites on an ended bond (S6: decision 14, question 1); a
favourite on a tombstone and the `Idempotency-Key` (S7: decisions 11 and 16); a member's
own entry on a day that never revealed (S8: listed for them, decision 1); the `ETag`'s
details (S9: decision 9); `/days/{date}` for every kind of date (S10: decision 8); the
maximum `limit` and the cursor (S11: decision 5); whether a day carries its status (S12:
decision 3); a day of tombstones in the feed and in `favourites=true` (S13: in the feed,
decision 1; not in the filter, decision 7); the author's display name (S14: Owed); rate
limits (S15: decision 16).

## What the corpus now says that is false

For the owner to carry into the corpus repository, which is not edited here. Line numbers
are the corpus's at `docs/phase-3-daily-loop`, `9be5149`, as `c5-corpus.md` quotes them.

1. **Doc 06:165, the `/days` row** ("`/bonds/{id}/days?limit&cursor&favourites` | Revealed
   and solo days, newest first. `ETag` supported"). The days are those holding an entry the
   caller wrote or one that has been revealed (decision 1). There is a fourth parameter,
   `until`. `limit` is 1 to 50. A page is also bounded by size, and only a null
   `nextCursor` means the end. `favourites=true` is days holding an entry the caller has
   marked **and can still read in full**. The `ETag` is strong, a keyed digest of the body,
   with `Cache-Control: private, no-cache` and a `304`.
2. **Doc 06:166, `/days/{date}`** ("One day, both entries"). It owes `404 DAY_NOT_FOUND`
   for every date not in the caller's archive, and the same `ETag` and `304` as the feed.
3. **Doc 06:158, "`409 ENTRY_NOT_REVEALED` before reveal".** Only for the caller's own
   entry. A partner's unrevealed entry is `404`. The row also owes `409 ENTRY_IMMUTABLE`
   for a tombstone, and that both verbs are allowed on an ended bond. **Doc 06:159** is
   true.
4. **Doc 06:17, the pagination line** (`?limit=20&cursor=<opaque>`). It states no maximum.
   It is 50, and a page may be shorter than `limit` with more to come.
5. **Doc 06:16, "all `POST`/`PUT`/`PATCH` accept `Idempotency-Key: <uuid>`".** The
   favourite `PUT` reads none (decision 16). The header is ignored, not refused; no test
   sends one.
6. **Doc 06:18, "`ETag` on mutable resources — for a Bond it is the row's `version`".**
   True of the bond. The archive's is not a version.
7. **Doc 07:170, `entry_favourites(id uuid pk, …)`.** There is no `id`; the primary key is
   `(entry_id, member_id)`. **Doc 07:172, "index (member_id, created_at desc) for the
   Favourites filter".** The index is `(member_id, entry_id)`. **Doc 07:173, "on delete
   cascade from entries: a favourite never retains content the author removed".** The
   constraint exists and is not what does it: a delete keeps the row, and `EraseEntry`
   removes the favourites. **Doc 07:250-256**, the diagram, omits the table.
8. **Doc 03:104, FR-090, "all revealed days".** Also a member's own entry on a day that
   never revealed, to that member only, and days whose entries are tombstones.
9. **Doc 04:90, `EntryFavourite`, `memberId · entryId · createdAt`.** True as built, and
   the one of the two descriptions to keep. **Doc 04:97-98, "Only a `REVEALED` entry can be
   favourited".** It is the entry's `revealedAt` that is asked, not a status: a lone entry
   revealed at a `SOLO` close can be marked by both. **"Deleting an entry cascades to its
   favourites"**: true in effect, not by a cascade. **Doc 04:44-45**, the aggregate
   diagram, omits `EntryFavourite`.
10. **Doc 04:189 (BR-9), doc 04:56 (I-5), doc 06:117 and `states.md`:858, "every write on
    an archived Bond is `409`".** A favourite is now excepted, with the author's entry
    `DELETE` (ADR-0035). Question 1.
11. **`states.md`:848-849** still owes the drawing of a day of tombstones, and §6 draws no
    solo day, no emptied day and no pre-join day (question 5).
12. **Doc 05:189, "`ETag`/`If-None-Match` on archive reads"**, is now true. Nothing to
    carry.

Added after the conformance review, which found these missing from the list:

13. **Doc 06:168-169, "Every entry in a day payload carries a boolean `favourited`".** Only
    the wide shape does. A locked entry and one erased before it was revealed are
    `{authorMemberId, status}` and carry none (BR-8; decision 4).
14. **Doc 04:178, BR-1, "`E.bondDay.status == REVEALED` OR (`E.bondDay.status == SOLO` AND
    `E.bondDay.closedAt != null`)".** The gate reads the entry's `revealedAt`, never the
    day. A `SOLO` day on a bond that ended before the day did is closed and not readable by
    the partner (ADR-0033 decision 9), and the archive is the first route where the two
    readings give different responses (decision 1).
15. **Doc 06:11, "`application/json; charset=utf-8`".** Both archive routes send
    `Content-Type: application/json` exactly, whatever charset was asked for (decision 9).
16. **Doc 08:15, NFR-003**, is unmeasured. **Doc 08:21, NFR-008**, names "Contract test" as
    its verification; what holds it is one integration test of the feed's worst page.
    **Doc 08:51, NFR-033**, is not met by the letter for `favourites=true` or for a sparse
    history (decision 10). **Doc 12:101**, deep paging over 500k entries, is not built.
17. **Doc 05:60, "Escape hatch | Spring `JdbcClient` | For the archive feed".** Built on
    `JdbcTemplate` (`ArchiveDays`, `Favourites`), as `StreakCalendar` is.
18. **Doc 07 §4, the indexing table (07:265)**, has no row for
    `entry_favourites (member_id, entry_id)`, and credits `bond_days (bond_id, date desc)`
    with the feed, where Postgres was seen to use the unique `(bond_id, date)` backwards.
19. **Doc 06 §2, "Error codes are enumerated and exhaustive".** `DAY_NOT_FOUND` is in no
    corpus document: item 2's row owes it, and the error model owes it too.
20. **`states.md` §6**, the "On this day" and "Milestone" rows, and doc 26:102's report
    entry point: no route serves them (question 5).

## Revisit when

- A field is added to `EntryResponse`: decision 6's margin is about 31 KB and a hundred
  entries a page spend it thirty octets at a time. `DaysFeedTest` measures the worst page
  and fails first.
- The entry limit changes, or `EntryText` starts counting control characters: the worst
  case of decision 6 moves with it.
- A second route by entry id arrives (reactions, C5c): decision 11, or its own reason.
- A read path is added that renders an entry (search, on-this-day, C6): it lists by
  decision 1's predicate, renders through the gate and reads the marker last (decision
  15), or holds the bond's lock.
- An eraser is written that does not go through `EraseEntry`: it must remove favourites
  itself. `Favourites.mark` no longer depends on how it locks (decision 12).
- The personal-data secret is rotated: every stored tag stops matching, once.
- A response filter, an advice or compression is added: decision 9's "the bytes written
  are the bytes digested" has only been true with none in the way.
- An entry may be written again on a settled day: decision 2's unreachable cell becomes
  reachable.
- A member row can be removed: Owed, Phase 5.
- Postgres is upgraded: `ArchiveDaysTest` reads a plan.
- The withdrawal's delivery is split into several transactions, or the connection pool is
  sized: decision 12's two seconds was chosen against a delivery that holds its rows to
  the end and a pool of ten.
- A member can come to hold a thousand bookmarks on one partner's entries: decision 7's
  bound is then within reach.
- The client's HTTP cache is written: question 8.

## How this was checked

"Run" means executed and seen by the party named. Reviewers worked in a checkout of their
own with probes that assert nothing and print what they saw. Two commit ids in the reports
predate the rebase onto `174b474`: `32fa875` is `4826528` and `a15bf57` is `46c130f`.

- **Run by the implementing agents:** each task's tests were seen to fail before the code
  existed, and `./gradlew build` was green at each commit below. Tests in the build's
  result files: 1223 before the slice; 1243 at `4826528`; 1244 at `46c130f`; 1291 at
  `f5a33f1`; 1297 at `a194778`; 1334 at `5e27c3a`; 1339 at `f1737f3`; 1341 at `a20dc2b`;
  1351 at `50166ec`.
  `5ecf293` (the query alone) was committed on its own tests and the module's lint, and
  `44fd6d1` adds tests; neither report gives a whole-build count for them. Modules whose
  inputs had not changed were up to date and not re-executed.
- **Task 1, favourites** (`4826528`, `46c130f`). Run by its author: the no-lock probe that
  wrote the orphan row (decision 12); fourteen mutations. Twelve were caught. Two
  survived and were answered with a test: the reader built before the entry was loaded,
  and `removeAllOf` run on every call whether or not it erased. One guard is stated
  equivalent and was not run as a mutation by the author: `GetToday` asking marks only of
  `FULL` readings, while `EntryResponse.of` forces `false` anyway.
- **The review of Task 1** (at `46c130f`). Proved by running: the author's requests are
  byte-identical, status, headers and body, whether or not the partner has marked both
  entries; decision 11's table, case by case, with every `404` identical but for the
  caller's own path in `instance`; the 30-round race (Consequences); an erasure that rolls
  back keeps the mark; `FOR KEY SHARE` not waiting for a plain `UPDATE` (decision 12); a
  favourite `PUT` reconciling a legacy joining day. Seventeen mutations: fifteen caught,
  one survivor (the reconcile removed from `FavouriteEntry`), and the equivalent one above,
  run this time and green as predicted. Found by reading: the two-statement mark's false
  `409`. Seen in responses: the two sentences, one of them untrue of a bond still waiting
  for its second member (run) and of a solo day that never reveals (reasoned). No
  must-fix. All were fixed at `a194778`, each with a test seen to fail first (seven red).
- **Task 2, the feed** (`5ecf293`, `f5a33f1`, `44fd6d1`). Run by its author: the query's
  plans on a 2,000-day bond beside 20 bonds of 200; `ArchiveGateTest`'s 200 cells, filter
  and gate agreeing in every one; 23 mutations. Twenty-one were caught. Two survived and
  were answered: the service's own check of `limit`, and the first-day rule (decision 6).
  Four of the feed's tests passed before the route existed, because a `404` equalled a
  `404`; three were tightened to require a `200`.
- **The review of Task 2** (at `a194778`). Proved by running: the escape count is an upper
  bound over every BMP scalar; the worst-case entry is accepted and is 49,147 octets out
  (decision 6); 17 odd cursors, dates and limits, where a member got a `200` for the valid
  extremes and one `422` body that echoes nothing for the rest, and a stranger got one
  `404` for all of them; the other member's feed, six ways of asking, is byte-identical
  while the partner writes, erases and rewrites an unrevealed entry today; the 25-round
  race. Fourteen mutations, all caught, six of them by one test each. No must-fix.
  Found: the arithmetic (decision 6), the plan's four sentences the code had left behind
  (decisions 1, 5, 6, 12), the empty page with a cursor, the unreachable cell, leading
  zeros in `limit`, no bound on a cursor's length before decoding, the plan test's
  planner-choice assertions, and the bare hash (decision 9). Fixed at `a20dc2b`, with the
  worst page built and measured.
- **Task 3, the day and conditional reads** (`5e27c3a`, `f1737f3`). **Its author stalled
  before reporting its mutations**, with one still applied to `DaysController.kt` in the
  working tree. A second agent, on 2026-10-09, restored the file from a copy it checked
  against `HEAD`, and re-ran the mutations from the start, not trusting the first run: 31,
  one at a time. Twenty-seven were caught. **Four survived and are now pinned**
  (`f1737f3`): the reader made before the entries inside `GetDays.read`
  (`ArchiveReadOrderTest`, decision 15), and three ways of reading a date loosely, which
  every bad date in the one-`404` test survived because each was a date the caller had
  nothing on (`DayViewTest`, "a date has one spelling", 18 spellings of days that are the
  caller's to read). Seven more mutations on the Task 2 fixes: six caught; one, the
  cursor's length check removed, survives by construction, since the same value is the
  same `422` after decoding, and the KDoc says so.
- **The review of Task 3** (at `5e27c3a`). **One must-fix (F1)**, four should-fix (F2 to
  F5). Proved by running: F1 and F2 (decision 9); the
  default `no-store` on every other response; `HEAD` and `Content-Length` right; a stale
  tag after the partner's erasure answers `200` with the tombstone; two `If-None-Match`
  headers; `0000-01-01`, `0000-00-00` and `9999-12-31` as the date, each the one `404` and
  no `500`. Proved absent: any test with
  a known answer for `PersonalDataHasher.hash` or the request fingerprint, in a commit that
  routed `hash` through a new `mac` (F3). The reviewer computed two vectors with openssl
  outside the JVM and ran them against this commit and its parent: nothing stored is
  orphaned. Proved by mutation: with Spring Security's cache header switched off for every
  route, every test stayed green; nothing in the repository asserted `no-store` (F4).
  Reasoned: why the lock tests order nothing (F5), which `f1737f3` had by then pinned.
- **The last task: the review's F1 to F5** (`50166ec`, 2026-10-09). Run by the fixer,
  tests first and seen red: the holder as a body for `problem+json`, and the `406`
  carrying the day's `ETag`.
  - F1 and F2 (decision 9): `DaysConditionalTest` sends twelve `Accept` values JSON can
    answer to both routes and requires the bytes of a plain read, `Content-Type` exactly
    `application/json`, a tag that is the keyed digest of the bytes received, and a
    working `304`; and five values it cannot, from twelve askers, and requires one `406`
    with no `ETag` and `no-store`. `RepresentationTest` holds the converter and the
    mapper's refusal. `produces` with `application/json` alone was run and seen to refuse
    `problem+json`. Six mutations, all caught; with the converter claiming JSON only the
    answer is now a `500`, which is the serializer's refusal working.
  - F3: `KnownAnswerTest`, three literals (the hash, the request fingerprint, the tag),
    each computed with openssl outside the JVM. The algorithm changed to HmacSHA3-256, and
    a byte added to the MAC's input, each fail it; before, only a test about the tag would
    have.
  - F4: `CacheHeadersTest`, in `app` with the real configuration, asserts the exact
    default (`no-cache, no-store, max-age=0, must-revalidate`, `Pragma`, `Expires`) on a
    login, a write, four reads, and the archive's `404`, `401`, `422` and `406`; and
    exactly `private, no-cache` on the archive's `200`, `304` and stale-tag `200`. The
    reviewer's mutation, the security default switched off, now fails it.
  - F5 needed nothing: the swap inside `GetDays.read` fails `ArchiveReadOrderTest`, four
    of four.
  - `./gradlew build` at `50166ec`: `BUILD SUCCESSFUL`, **1351 tests, 0 failed, 0
    skipped**, counted from the build's result files by the conformance reviewer; the
    fixer's report gave no count. It was the last execution of the tests before the
    whole-branch reviews: `20d729c`, `28218fb` and `9bba582` change only `scripts/`,
    `tools/` and documents, and the build at `9bba582` was 217 tasks up to date with none
    re-executed.
  - **None of `50166ec`, `20d729c`, `28218fb` or `9bba582` was read by an independent
    reviewer before the whole-branch reviews**: the fix of F1 to F4 was run by its author
    only, the smoke section and the tools by theirs.
- **The smoke run** (`20d729c`, `28218fb`; Task 4b's report, and the run's log). One new
  section, after the
  withdrawal section, against the booted jar on a database of its own
  (`MOYI_DB=moyi_c5b_smoke`, dropped afterwards): the `422` of each unreadable parameter
  and that none repeats the value, the two `404`s, a day only one member has written, a
  bookmark set and removed and never shown to the other member, `If-None-Match` and what
  moves a tag, the `Accept` header, and the archive after a withdrawal. **614 passed, 0
  failed, no skips, 20 sections**, at `28218fb`. An earlier run of the whole section on a
  database that had been used before was 611 and 0, before three probes were added. No
  probe failed at any point, and the section's helpers were checked to be able to fail.
  Every day it reads is today: past days and paging stay with the integration tests.
- **The tools** (`9bba582`). `scripts/moyi` gains `days`, `day <date>`, `favourite
  <entryId>` and `unfavourite <entryId>`, run once each against a booted jar on a database
  of its own (`moyi_c5b_cli`, dropped): eight forms refused locally and six by the server
  (`422` four times, `404` twice). The line it prints for a non-null `nextCursor` was not
  reached: that run had one day. `tools/bruno` gains the four requests, written and not
  run: the collection has still never been opened in Bruno.
- **The three whole-branch reviews** (2026-10-09; the code at `9bba582`, the documents at
  `c4b3e71`). Each worked in a checkout of its own. The first two ran probes that assert
  nothing and print what they saw.
  - **Privacy and the contract.** Proved by running: the twin bonds of decision 7, twelve
    views of each over four scenarios for the day of the ending, before and after the
    dispatcher; 73 request shapes, each asked by a stranger of an existing bond or entry,
    of a random UUID and of a value that is not one, every one byte-identical across the
    three (status, every header, the body but `instance`); the reader's tags across the
    erasure (decision 9); and `SecretsNeverLoggedTest` with the four routes added, green
    over 11,166 events at TRACE. Found: the favourites page that told a withdrawal from a
    deletion (F1, decision 7); the guard test that drove none of the new routes (F3); the
    TRACE lines that record who bookmarked what (F4); and four things for the owner
    (questions 6 to 8, and the second tag change, which is ADR-0035 question 4's). Found
    nothing on what a stranger gets, on BR-8's two shapes, on a tombstone's times, or in
    the CLI. Its notes on the contract are answered at `a5cf2f3`.
  - **Concurrency and cost.** Proved by running: 40 rounds and about 1,150 requests with
    no deadlock, no `5xx` and no bookmark left on an erased entry (Consequences); the
    stall behind a withdrawal (decision 12); the page that is not one snapshot (decision
    15); the plans on 24,000 and on 498,000 entries (decision 10, Consequences); and
    320,000 concurrent digests, 16 threads over inputs of one octet to 230 KB, against
    single-threaded answers, none wrong. Reasoned and not run: the table of what each
    transaction locks, in what order; what a failure between two steps leaves; the
    statement count. Found: the unmark's wait (S1, should-fix), three notes (P1, P2, N1),
    and three claims of this record the code did not have: a page's cost, which verb
    waits, and what the plan test reads. All three are corrected here. A fourth, that a
    read writes once per bond, it found to hold, by reading.
  - **Conformance.** Read, with every count taken from a log or a result file: 3
    must-fix, 10 should-fix and 8 notes, and unnumbered notes on the Figma record, the
    corpus list, the tools and the plan. The three must-fix were all in the record and
    none in the code: this ADR's missing test count, its status, and the spec's §6.7
    still saying that favourites do not exist. It also checked every item ADR-0035 owed
    this slice against the code: all six discharged, one with its design gap carried
    (question 5) and one carried on to C5c.
- **The fixes that followed** (2026-10-09, one agent; each test written first and seen to
  fail for its reason, each mutation restored from a copy and compared).
  - `a461ea8`, decision 7. Before the fix `WithdrawalTwinArchiveTest` failed three of
    three. Mutations: no refill, four tests fail; at the bound, the cursor from the last
    day shown, one fails (the favourite beyond the bound is never reached); the cursor from
    the last day examined, ten fail (the look-ahead day is skipped); the marker read
    before the entries, `ArchiveReadOrderTest` fails four of four as before. The
    `DaysFeedTest` case that required an empty page with a cursor, "a marked entry whose
    author has withdrawn…", now requires one page and no cursor.
  - `fdd365f`, decision 12. Before the fix `FavouriteLockWaitTest` failed three of three,
    each at its ten-second bound. Mutations: a plain `DELETE`, the unmark's test fails at
    the bound; no `set_config`, both of the mark's fail at the bound; the setting made
    session-wide, the test that reads every pooled connection fails; the timeout
    rethrown, two fail.
  - `b9f1e78`, the guards. `SecretsNeverLoggedTest` with the routes added and no pin: red,
    225 lines from the logger. With the pin removed again: red. `RepresentationTest`
    holds that the body's holder prints nothing of the body; a `toString` that prints the
    bytes fails it.
  - `a5cf2f3`, the contract: the `406` on the two reads, descriptions on the day and the
    two bookmark operations, `maxLength` on `cursor`. Three tests first, red.
  - **`./gradlew build`**, whole, at each: 1354 tests at `a461ea8`, 1357 at `fdd365f`, 1360
    at `a5cf2f3` and with this record's own changes to comments; 0 failed, 0 skipped,
    counted from the result files. The first whole build of `fdd365f`'s work failed eleven
    tests of `TimezoneMatrixTest` with "too many clients already": the new test class had
    a Spring context of its own. It was rewritten to share one, and the build was run
    again.
  - **The smoke run**, on the jar built from the tree that became `a5cf2f3`, on a database
    of its own (`moyi_c5b_fix`, dropped): **647 passed, 0 failed, no skips, 20
    sections.** The section gained the probes the privacy review
    named: a stranger's `406` is the member's; the exact two keys of a `LOCKED` and of a
    `REMOVED` entry; no `deletedAt` or `updatedAt` in six feed and day bodies; the
    reader's tag before and after the poller erases, taken while the entry was seen still
    whole; a stranger's `DELETE …/favourite`; and the withdrawn favourites page exactly
    empty with no cursor.
  - **The contract check**, oasdiff 1.11.7 (`tufin/oasdiff`), `origin/main` at `174b474`
    against the working copy: `breaking --fail-on ERR` exits 0, "390 changes: 0 error, 390
    warning, 0 info", every one `response-property-enum-value-added`: the two new codes on
    each problem response. `--fail-on WARN` exits 1. The changelog adds 8 infos: four
    endpoints, and `favourited` required on four responses. So CI's action, which fails on
    errors, is not expected to fail; the `breaking-api-change` label is owed by this
    project's own rule for a new code (ADR-0024's amendment), not by the check.
  - **None of these commits, nor this revision of the record, has been read by an
    independent reviewer.** The reviewers' races and the twin probe in its four scenarios
    were not run again after them; the twin's first scenario is now a test.
- **Read, not run:** that every erasure goes through `EraseEntry` (by search: three
  callers); the unreachable cell (decision 2); the lock order of the mark (it holds
  nothing else); rate limiting on the four routes (the test context has the limiter off);
  the bond route's `406`; what a client's HTTP cache does with `private, no-cache`
  (decision 9); the statement count of a request.
- **Not run by anyone:** NFR-003's p95; doc 12's deep paging (the 498,000-entry run above
  measured plans, not a walk); V22 against a shared database; CI's own contract action;
  the mark's two-second refusal over HTTP; a `PUT …/favourite` that carries an
  `Idempotency-Key`; ten connections held by nine unmarks; the collection in Bruno.

### PR #60 fixes, 2026-10-10

The two review reproductions were first run against `36bc05c` and failed for their
reported reasons: different empty-page cursors before and after erasure, and a `204`
unmark where a retryable conflict was required. The regression suite also exercises a
withdrawal committing after the first favourites window loads its entries, an older
bookmark belonging to the reader, and retries after both erasure commit and rollback.
The contract adds `FAVOURITE_BUSY` and documents the DELETE route's `409`; its generated
client impact is covered by PR #60's existing breaking-api-change classification.

Validation of these fixes: `./gradlew build` passed with 1,364 tests, zero failures,
errors or skipped tests. `scripts/smoke.sh --attach` ran against the built jar and
separate PostgreSQL/Valkey containers: 646 passed, zero failed. Its optional tag-across-
erasure check skipped because the poller had already erased the entry before the first
read; the deterministic large-history regression compares the same bytes and tags before
and after explicit dispatch. Reusing the original membership's withdrawal set instead of
refreshing it per window made the new race test fail (two pages instead of one); restored,
the full build passed. A follow-up read of response rendering, replay paths and contract
handling found no additional confirmed defect.
