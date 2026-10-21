# ADR-0038 — The prompt library

**Status:** Accepted · **Date:** 2026-10-20 · **Deciders:** Daniel, for the four rulings of
2026-10-15 (Q-P1, Q-P2, Q-P4, Q-P5) only. The eight rulings under "The controller's" were
made by the controller of the C6 session; every other decision here was made by the plan or
by the agents that ran it.

## Context

FR-047 (doc 03, SHOULD): "The system MUST offer an optional writing prompt, drawn from a
curated, locale-aware library, refreshable without deploying. Prompts MUST never be
mandatory." Doc 04's glossary calls a prompt "an optional suggested sentence-starter. Never
required, never inserted into the Entry automatically", and doc 04 records `Entry.promptId`
"so that prompt→engagement can be learned later". The corpus gives it four places:

- doc 06:144, `GET /today` carries `"prompt": {"id", "text"}`;
- doc 06:153, `POST /bonds/{id}/entries` takes `promptId?`;
- doc 06:194, `GET /prompts/daily?bondId=`, "locale-aware, avoids recent repeats";
- doc 06:195, `GET /prompts?limit&cursor`, "browse the library";
- doc 07:194–195, `prompts(id, text, locale, category, active, weight, created_at)` and
  `prompt_impressions(id, bond_id, prompt_id, shown_on date, used boolean)`.

cADR-0013 (the corpus's, not copied into this repository) says the bond's type "selects a
prompt overlay over a shared core library, not three parallel libraries", in `en` at launch
with `pt-PT`, `fr` and `es` prepared (FR-122). Spec §1 says C6 "builds the table, the
selection rule and the endpoints, and ships with a small seed"; the 100-prompt library is
Daniel's editorial work.

**C6 is three pull requests**, as C5 was: **C6a** prompts (this record, V24), **C6b** search
and on-this-day (V25), **C6c** milestones (no migration expected). Spec §10's C6 row held
four features and one pull request for all of them would have been too large to review.
That split is the controller's ruling of 2026-10-15, not the owner's, and is his to turn
(c6-corpus Q-X1); turning it moves only version numbers.

**The corpus read** (`c6-corpus.md` §1, 2026-10-15, in the session's working folder and not
in this repository) found nine contradictions and seven privacy hazards in what C6a touches:

- **P1** one prompt per bond, or one per member; **P2** the overlay needs the bond's type and
  nothing gave it to `gratitude`; **P3** "refreshable without deploying" against a seed;
  **P4** `POST /entries` was specified to take `promptId` and the built route had no such
  field; **P5** `prompt_impressions.used` duplicates `entries.prompt_id`; **P6** "avoids
  recent repeats" now against FR-201's personalised selection in Phase 10; **P7**
  `/prompts/daily?bondId=` duplicates `today.prompt` and puts the bond where the cross-tenant
  suite cannot see it; **P8** `GET /prompts` has no screen; **P9** "locale-aware" against a
  module graph in which `gratitude` could not see a member's locale.
- **H-P1** an impression written when someone opens the app is a presence log (FR-064), and
  a choice that read impressions would let one member's opening move the other's prompt;
  **H-P2** a shared prompt changed on demand leaks activity; **H-P3** the prompt behind a
  locked entry is entry metadata (BR-8); **H-P4** an ended bond, and blocked against read
  only (`states.md` §9); **H-P5** a tombstone's `prompt_id` says what a withdrawn entry was
  answering; **H-P6** `GET /prompts` is global; **H-P7** a prompt chosen in one member's
  locale would tell the other that locale.

The slice was built in four tasks (`docs/superpowers/plans/2026-10-15-prompts-c6a.md`).
Tasks 1 and 2 were built on 2026-10-15; the branch was then rebased onto `main` at `3811f0d`
after #62 merged. Task 3's first implementer was cut off with its work uncommitted, and a
second finished it on 2026-10-20. Task 4 (the smoke section, the tools, the state map and
this record) was built on 2026-10-20. Each of Tasks 1 to 3 had one independent review,
which ran some probes and mutations of its own ("How this was checked").

## Decision

### The owner's rulings

Asked directly by the controller on 2026-10-15, and answered by Daniel:

- **Q-P1. One prompt per bond per day, the same for both members, each in their own
  locale.** Resolves P1, H-P7.
- **Q-P2. No swap.** Dismissing a prompt is the client's, with no server state. Resolves H-P2.
- **Q-P4. A seed in the migration is accepted for v1.** FR-047's "refreshable without
  deploying" is a recorded gap until `modules/admin` exists. Resolves P3, as a gap.
- **Q-P5. `GET /prompts` (browse) is deferred**, owed until a screen needs it. Resolves P8
  and H-P6 by not building the route.

Two questions were not asked; the defaults below are recorded as his to turn:

- **Q-P3. Which prompt a revealed entry answered is not sent**, to anybody. Nothing draws it
  (`tokens.md` §4, `EntryCard`).
- **Q-P6. The seed's wording is placeholder**: 24 prompts, 12 shared and 4 for each overlay,
  `en` only (decision 17). The library is his editorial work.

### The controller's rulings

Made by the controller of the C6 session (controller ruling, 2026-10-15), each the owner's to
turn:

1. **Selection is a pure function of the bond, the bond-local date and the active library
   for the bond's audience**: a per-bond permutation of the eligible prompts, ordered by a
   **keyed** hash of bond id and prompt id, indexed by the date's day number. It reads no
   member's activity, no entry and no impression. "Avoids recent repeats" means no repeat
   within one cycle. (H-P1, H-P3, P6.)
   **The ruling says keyed; the plan built a plain, unkeyed SHA-256 (decision 1)**, and so
   overrode it. The consequence: anyone who knows a bond's id and the seed (which is in this
   public repository) can compute that bond's prompt for any day, past or future. Prompts
   are not secret and a bond's id is not public, so this was judged harmless, but it is the
   plan's decision against the controller's, and the owner's to turn (question 5).
2. **No `prompt_impressions` table.** Recording what was shown when is a presence log
   (FR-064), and ruling 1 needs none. V24 is `prompts` plus per-locale text. Spec §7 and doc 07
   are amended. (H-P1, P5.)
3. **Locale per member**: the exact tag, then its language, then `en`. `locale` is added to
   `identity.api.UserSummary`. (P9.)
4. **Audience**: CORE plus an overlay by bond type (COUPLE, FRIENDS, FAMILY; PARENT_CHILD is
   given FAMILY, per `states.md` §2). `type` is added to `bond.api.BondMembership`. (P2.)
5. **Routes**: `prompt: {id, text}` on `GET /today` only. `GET /prompts/daily` is **not
   built**: it duplicates `today.prompt`, and doc 06's `?bondId=` form would sit outside the
   cross-tenant suite (P7). Owed. `GET /prompts` is deferred (the owner's Q-P5).
6. **No prompt when the bond takes no writes** (ended, blocked or read only, all alike); a
   prompt whether or not the caller has written; never dependent on the partner. (H-P4.)
   The ruling says "absent"; the plan built it as a key with a `null` value (decision 3).
7. **`POST /entries` takes an optional `promptId`.** An id that names no prompt is
   `422 VALIDATION_FAILED`. Not on `PATCH`. Never on a locked entry and not sent on a
   revealed one. `EraseEntry` nulls `prompt_id`. (P4, H-P3, H-P5.)
8. **No `used` flag.** "Used" is `entries.prompt_id`. (P5.)

Three more rulings were made by the same controller while the plan ran, each recorded in
the session's ledger:

- **Task 2's blocker (2026-10-15).** `WithdrawalReadTest` asserted that a leave without a
  withdrawal changes nothing either member reads from `GET /today`, byte for byte; ruling 6
  makes `prompt` go from an object to `null` on that leave. Ruled: narrow the test. It now
  compares both members' bodies with `prompt` removed, and asserts `prompt` is an object
  before and `null` after. The bond's end is already visible to both members (it is the
  leave), so a vanishing prompt tells neither anything new. Cost, if wrong: that test no
  longer guards the prompt's bytes across a leave; `PromptOnTodayTest` holds them instead.
  The test was renamed "…changes no entry either of them reads" on 2026-10-20.
- **Task 3's review, M2 (2026-10-20).** The request's and the draft's `toString` print
  `promptId` only as `(given)` or `null`, never its value, against the brief's literal
  `toString`. The value is unchecked client text of any length until the service parses
  it, and what an erased entry answered goes with its words, on a withdrawal (BR-10a) and on
  the author's own delete (FR-046); a DEBUG line that held the id would outlive the erasure.
- **Task 3's review, M1 (2026-10-20).** A reveal keeps the prompt; asserted in
  `EntryPromptTest` and `SubmitEntryPromptTest`, since the only guard until then was
  incidental.

### What the plan decided

Numbered as the plan numbers them, because code and tests cite them by number.

1. **The order is a plain SHA-256, not a keyed MAC.** A prompt's rank in a bond's cycle is
   the first eight bytes, unsigned, of `SHA-256("moyi.prompt-order.v1" ‖ bondId ‖ promptId)`
   (UUIDs as their sixteen big-endian bytes), ties broken by the prompt id
   (`PromptOfTheDay`). The codebase's one secret, `moyi.security.hashing.secret`, was
   refused: it is ephemeral in `local`, so every bond's prompt would change on every restart
   of a developer's server and of the smoke run's; rotating it is a security act that would
   reshuffle every bond's prompt mid-day; and the order protects nothing, since knowing
   tomorrow's prompt tells nobody anything about anybody. The label makes the hash this use's
   alone. A known-answer test pins it: a change to the hash changes every bond's prompt on
   the day it deploys.
2. **The cycle is indexed by the date's epoch day**, `floorMod(date.toEpochDay(), n)`, not by
   days since `activeSince` (null for a bond still waiting for its second member). For a fixed
   library no prompt repeats within *any* `n` consecutive days. When the library changes, `n`
   changes and the sequence is re-dealt from that day: a recent prompt can come back early,
   and **today's prompt can change mid-day for both members at once**. Accepted: the library
   changes only by deploy, and both members still agree.
3. **`prompt` is sent as `null`, not omitted**, when there is none. Every other optional field
   of the response is a key with a `null` value, and the generated client models a nullable
   field. One `null` covers both causes (the bond takes no writes; the library has nothing for
   it), so nothing tells them apart, and nothing tells a block from a leave.
4. **Eligibility never depends on locale.** Every prompt has an `en` text on its own row
   (`prompts.text`, `NOT NULL`); translations live in `prompt_texts`, which refuses `en`. A
   translation, present or missing, changes only the words, never which prompt.
5. **Locale matching is case-insensitive.** `users.locale` is stored as typed and
   `RegisterRequest` admits `PT-pt`. The candidates are the trimmed, lower-cased tag, its
   language subtag, then `en`, without duplicates (`PromptLocale`). `prompt_texts.locale` is
   lower case, held by a `CHECK`.
6. **The schema drops doc 07's `weight` and `category`.** Nothing weights the choice;
   `category` is `audience` (`CORE`, `COUPLE`, `FRIENDS`, `FAMILY`). Doc 07's `prompts.locale`
   is replaced by `prompt_texts`.
7. **`entries.prompt_id` gains a foreign key to `prompts`** (`entries_prompt_fk`), both this
   module's tables. Prompts are retired (`active = false`), never deleted; the key has no
   `ON DELETE`, and no index, since nothing deletes a prompt or asks which entries answered one.
8. **`promptId` may name any prompt in the table**: active or retired, any audience, today's or
   not. An offline draft (`states.md` §4, "Queued offline") posts the prompt it cached, which
   may since have been retired or may be yesterday's; refusing it would lose a person's words
   over a suggestion.
9. **`promptId` is text on the wire, not a UUID.** A value that is not a UUID names no prompt
   either, and gets the one `422` an unknown id gets: `VALIDATION_FAILED`, field `promptId`,
   code `VALID_PROMPT`, the value never repeated. It is decided after the bond's guard and after
   `BOND_ARCHIVED` (and after the media refusal), so a stranger is told the bond's `404` and an
   ended bond its `409` first. Typed as `UUID?`, a malformed value would be a `400` decided
   before the guard, a second answer for the same mistake. **What a run showed** (Task 3 and
   its review, 2026-10-20):
   - Jackson 3 **coerces a JSON number or boolean to text**, so `"promptId": 5` and
     `"promptId": true` get the same `422` as every other bad value. An object or an array is
     the standing `400 MALFORMED_REQUEST`, with no value echoed. `""` is not coerced to `null`;
     it is the same `422`.
   - An **upper-case UUID is accepted** (`201`): it is canonical apart from case, and
     `PromptId.parse` compares lower case.
   - A **short-spelled UUID** (a real prompt's id with its leading zero dropped, which
     `UUID.fromString` accepts) is a distinct case, and `PromptId.parse` refuses it: it
     admits only the canonical spelling. The `422` test's body for it is load-bearing. It is
     the only guard against a lenient parser: with `PromptId.parse` replaced by a bare
     `UUID.fromString`, that body alone turns the test red, since `1-1-1-1-1` names no prompt
     either way and gets the same `422`.
   - A **refused `422` leaves the `Idempotency-Key` free**: the refusal rolls back, so a
     corrected body under the same key is a `201`, and the bad body after it is
     `IDEMPOTENCY_KEY_REUSED`.
   - The request's and the draft's `toString` print the prompt id only as `(given)` or `null`
     (BR-10a, FR-046; the controller's ruling above).
10. **`promptId` is never sent back**, on any shape and to anybody, its author included. The
    ruling covers the partner's locked and revealed shapes; one rule for every shape is simpler
    to hold, and no screen draws it. `EntryResponse` is built from `DisclosedEntry`, which has
    no `promptId`, so sending it would take two changes, not one.
11. **`PATCH /entries/{id}` ignores a `promptId` key**, as it ignores every key it does not
    know, and changes no `prompt_id`. A test pins that it changes nothing.
12. **Unknown keys on `POST /entries` stay ignored.** Run on 2026-10-15 at `cf8af00`, by the
    plan's author, in a throwaway MockMvc test since deleted: `{"text":"a","promptId":"<random
    uuid>"}` was `201`, and `{"text":"b","surprise":1}` and `{"text":"c","promptId":"not-a-uuid"}`
    both reached the service (they were `409 ENTRY_ALREADY_EXISTS`, not `400`). So until this
    slice a client that sent `promptId` got a silent success, and after it an unknown one is a
    `422`. That is the point of the slice (Consequences). The route's stance on unknown keys in
    general is not this slice's to change.
13. **`BondMembership.takesWrites` is added** (`isOpen && !hasLeft`) and used by the prompt.
    The three inline copies of that predicate (`SubmitEntry`, `ChangeEntry`, `ReactToEntry`)
    are left as they are: state-map `codeRef`s quote those lines verbatim, and moving them is a
    refactor, not this slice. Owed.
14. **`bond.api.BondType` is a new public enum** mirroring the domain's four values, mapped
    exhaustively in `BondAccessAdapter`. PARENT_CHILD to FAMILY is the *prompt's* rule and lives
    in `gratitude` (`DailyPrompt.overlayOf`), not in `bond`.
15. **The prompt is computed from the membership the controller resolved**, before the
    joining-day reconcile, like the rest of `GetToday`. A request that races an ending by
    milliseconds can still show a prompt. Nothing private is in a prompt; accepted.
16. **A bond's type can change** (`PATCH /bonds/{id}`, `type`). When it does, today's overlay
    changes for both members at once. Accepted for decision 2's reason.
17. **The seed is 24 prompts, `en` only**: 12 CORE and 4 for each overlay, placeholder wording
    (Q-P6). A prompt's text is 1 to 200 characters, trimmed, held by `CHECK`s.
18. **An architecture rule pins what `DailyPrompt` may read**, so "reads no entry, no day, no
    mark, no reaction, no streak" is the build's to hold. As planned it was an allowlist of the
    file's imports. Task 2's review found that it cannot see a dependency in the same package
    (`GetStreak`, `DayEntries`, `EntryById` and the rest need no import), and on 2026-10-20 the
    rule gained a second assertion: `DailyPrompt`'s constructor takes exactly `PromptLibrary`
    and `UserDirectory`, and it has no secondary constructor. Task 4's review got past both
    with an `@Autowired` field, its annotation spelt in full so that it needs no import, and
    a third assertion now holds every property `DailyPrompt` declares to one of the two its
    constructor takes. What the rule holds is what `DailyPrompt` is given: its imports, its
    constructor and its fields. It does not see a call to a top-level function or an
    `object` in its own package, which needs none of the three.
19. **`GET /today` gains three reads**: the active library (24 rows today, about 100 planned),
    the chosen prompt's texts, and the caller's own user row. NFR-001 (p95 ≤ 150 ms) is not
    measured here; owed.

## Consequences

- **Migration V24 (`modules:gratitude`), `V24__gratitude_prompts.sql`**: `prompts`,
  `prompt_texts`, `entries_prompt_fk` and the 24-prompt seed. No `prompt_impressions`. It has
  been applied to no shared database: one throwaway database, `moyi_c6a_smoke`, was created for
  the smoke run and the CLI run and dropped (How this was checked).
- **`GET /today` gains `prompt`**, `{id, text}` or `null`, additive. In the contract it is a
  new schema `TodayPromptResponse` and `TodayResponse.prompt` as `oneOf [TodayPromptResponse,
  null]`.
- **`POST /entries` gains `promptId`**, optional, `string` or `null`, documented as a UUID. A
  `promptId` that was silently ignored before this slice (decision 12) is now a `422` when it
  names no prompt. A client that sent one by mistake would start failing; no client is
  generated yet.
- **No new `ErrorCode`**: the refusal is `VALIDATION_FAILED`. The contract change is additive,
  so the pull request does not carry `breaking-api-change`.
- **Three more reads on `GET /today`** (decision 19). Unmeasured.
- **`gratitude` now depends on `identity`'s api** in main code (it was a test dependency), for
  one question: which language does this member read. The README's module diagram shows it.
- **`bond.api.BondMembership` gains `type` and `takesWrites`; `identity.api.UserSummary`
  gains `locale`** (kept out of its `toString`).
- **Both members' `prompt` goes to `null` on a leave or a block**, and nothing else they read
  from `GET /today` changes with it (`WithdrawalReadTest`, as narrowed).
- **An erasure takes the prompt with the words**: deletion, withdrawal, and the edit that
  erases (`Entry.erase` nulls `promptId`; `EntryStore.update` writes it). What an erased entry
  answered is gone from the row.
- **The state map** has the new `422` on `POST /entries`, evidenced by a smoke probe, and
  `GET /today`'s summary names the prompt. No state was added or changed: the prompt is not a
  state, moves none, and is refused nowhere.

## Owed

- **`GET /prompts/daily`** (controller ruling 5). If built, as
  `GET /bonds/{bondId}/prompts/daily`, served by `DailyPrompt`, with a `GratitudeCrossTenantTest`
  fixture.
- **`GET /prompts`** (the owner's Q-P5), when a screen needs it. It must require the bearer
  and echo no bond (H-P6).
- **FR-047's "refreshable without deploying"** (the owner's Q-P4), with `modules/admin`.
- **The 100-prompt library and its `pt-PT`, `fr` and `es` texts** (Q-P6, FR-122). Each is a
  migration until the admin surface exists.
- **Moving the three inline copies of `takesWrites`** (decision 13), with their state-map
  `codeRef`s.
- **NFR-001 measured with the prompt in** (decision 19).
- **The corpus.** Doc 06 and doc 07 amended (below), and ADR-0035 to ADR-0038 copied into
  `Gratitude/documents/adr/` on `docs/phase-3-daily-loop` (c6-corpus X3), as 0031 to 0034
  were.

## Questions that are the owner's

One line each: what was decided, what the alternative was, what turning it would cost.

1. **Q-P3, which prompt a revealed entry answered.** Not sent. The other way is a `promptId`
   on the revealed shape; it costs a contract change and a screen to draw it, and the
   decision 10 guard (`DisclosedEntry`) to undo.
2. **Q-P6, the seed's wording and the split per overlay.** 24 placeholder prompts, 12 and
   4/4/4. Changing it is a migration; changing the ids reshuffles every bond's cycle.
3. **Each of the controller's eight rulings** (2026-10-15). Rulings 2 and 5 are the two that
   deviate from the corpus.
   1. *Ruling 1, a pure function of bond, date and library.* The other way is a choice that
      reads history (FR-201, Phase 10) or impressions; it lets one member's activity move
      the other's prompt (H-P1) and needs today kept out of it (H-P3). Built unkeyed against
      the ruling's "keyed": question 5.
   2. *Ruling 2, no `prompt_impressions`.* The other way is doc 07's table; it is a presence
      log FR-064 forbids, a write on every read of today, and nothing would read it.
   3. *Ruling 3, the member's own locale, then its language, then `en`.* The other way is
      one locale for the bond; it tells one member the other's language (H-P7), or serves a
      language the member never chose.
   4. *Ruling 4, CORE plus an overlay by type, PARENT_CHILD given FAMILY.* The other way is
      a fourth overlay for PARENT_CHILD; it costs a fourth set of prompts to write and
      translate, and a new `audience` value in a migration.
   5. *Ruling 5, `prompt` on `GET /today` only.* The other way is to build `GET
      /prompts/daily`; it is a second route for the same value, and needs a bond-scoped
      path and a cross-tenant fixture where doc 06's `?bondId=` form would have none.
   6. *Ruling 6, no prompt once the bond takes no writes.* The other way is to keep offering
      one on an ended or read-only bond; it is a suggestion to write where no entry can be
      written. (`null` rather than absent is question 7.)
   7. *Ruling 7, an optional `promptId`, `422` when it names no prompt, erased with the
      words.* The other ways are to ignore an unknown id, which hides a client's bug as a
      silent success, or to store it unchecked, which `entries_prompt_fk` would turn into a
      `500`; taking it on `PATCH` would let a locked entry change what it answered.
   8. *Ruling 8, no `used` flag.* The other way is doc 07's `used`; it is a second copy of
      `entries.prompt_id` that can drift, on the table ruling 2 refuses.
4. **The C6 split** (Q-X1): three pull requests. Turning it moves version numbers only.
5. **Decision 1, unkeyed, against controller ruling 1's "keyed hash".** A plain hash: anyone
   who knows a bond's id and the seed can compute its prompt for any day; prompts are not
   secret, so judged harmless. Keying it with the application secret, as the ruling asked,
   would make the order unguessable and make it change on every local boot and every rotation.
6. **Decision 2, a library change can change today's prompt mid-day**, for both at once. The
   other way is to fix the day's prompt the first time it is computed, which needs a write
   on a read, the record ruling 2 refuses.
7. **Decision 3, `null` not omitted.** Omitting the key is what ruling 6's words say; it
   would make the generated client's field optional rather than nullable.
8. **Decision 8, any prompt accepted.** The other way, today's prompt only, would refuse an
   offline draft written yesterday.
9. **Decision 9, one `422` for a malformed id.** The other way is a `UUID` field and a `400`
   before the guard: a second answer for the same mistake, and a stranger's `400` instead of
   the bond's `404`.
10. **Decision 10, not sent to the author either.** The other way is to show the author their
    own prompt on their own entry; it costs the shape split by reader that decision 10 avoids.
11. **Decision 11, `PATCH` ignores `promptId`** rather than refusing it. A refusal would be a
    `400` or `422` on a key the route does not take; nothing else on the route refuses unknown
    keys.
12. **Decisions 15 and 16, the races and type changes.** A request racing an ending can show
    a prompt; a type change moves today's overlay. The other way for 15 is the bond's lock on
    a read.
13. **The `toString` ruling** (the controller's, above). Printing the id would help debugging
    and would keep, in a log, what an erased entry answered.

## What the corpus now says that is false

For the owner to carry into the corpus repository, which is not edited here.

1. **Doc 06:194**, `GET /prompts/daily?bondId=`, "locale-aware, avoids recent repeats". Not
   built. Today's prompt is `prompt` on `GET /bonds/{bondId}/today`; if the route is built it
   will be bond-scoped in its path (controller ruling 5, P7).
2. **Doc 06:195**, `GET /prompts?limit&cursor`. Not built; deferred by the owner (Q-P5).
3. **Doc 07:194–195.** `prompts(id, text, locale, category, active, weight, created_at)`: there
   is no `locale`, `category` or `weight`; there is `audience`, and `prompt_texts(prompt_id,
   locale, text)` holds the translations (decisions 4 and 6). `prompt_impressions` does not
   exist (controller ruling 2), and nor does `used` (ruling 8).
4. **Doc 06:153**, `promptId?` on `POST /entries`: true, and owes the `422 VALIDATION_FAILED`
   on `promptId` for an id that names no prompt, after the bond's `404` and `BOND_ARCHIVED`.
5. **Doc 03, FR-047**, "refreshable without deploying": not true until `modules/admin` (Q-P4).
6. **Spec §7's V24 row**, "`prompts`, `prompt_impressions`", before this slice's amendment.
7. **Spec §11's "search, `/prompts`, `/on-this-day` | Gaps"** for the prompt: `prompt` is
   served by §3 *Today* ("Prompt visible", `states.md`:304) and §4 *Compose* ("Prompt above,
   dismissible", `states.md`:378; `tokens.md`:278's dismissible prompt slot). Amended in place.
8. **Doc 04 BR-10a** names "text and media references nulled" as what a withdrawal erases,
   and **doc 03 FR-046** says an author's delete after the reveal leaves a tombstone without
   saying what it nulls; `prompt_id` is nulled by both (ruling 7, H-P5). BR-10 (account
   deletion) will owe the same when its path is built; none exists yet.

## Revisit when

- The library passes a few hundred prompts: the whole active library is read on every
  `GET /today`.
- Personalised selection (FR-201, Phase 10) is taken up: ruling 1's pure function is what
  keeps the partner out of the choice, and anything that reads history must keep today out
  (H-P3).
- A screen for browsing the library is drawn (Q-P5).
- A member asks why their partner's prompt differs: it cannot, by construction (Q-P1); only
  its words can, by language.
- The library becomes editable at runtime (Q-P4): decision 2's mid-day change then happens
  on an edit, not only on a deploy.
- A field is added to an entry's shapes: decision 10's "never sent back" must hold for it.

## How this was checked

"Run" means executed and seen by the party named. Nothing here was run by the owner. Every
run below is the named agent's report of its own work, except Task 4's, which are this
record's author's.

- **Task 1** (2026-10-15, its implementer; commits `45d272d`, `9f0707c` after the rebase).
  `PromptOfTheDayTest` (8), `PromptLocaleTest` (3) and `PromptLibraryTest` (3) seen to fail to
  compile, then pass; `FlywayMigrationTest` seen red before V24. Mutations, each restored:
  signed ordering of the rank (emulated by flipping the sign bit) → the known-answer test;
  the bond id dropped from the hash → "two bonds are dealt…" and the known answer;
  `toEpochDay() * 7 / 3` → "no prompt comes back within any run of days…" and the known
  answer; the sort dropped → "whatever order the library is read in", "two bonds", the known
  answer; the audience filter made `true` → "only the shared core…", the empty-library,
  shrinking-library and run-of-days tests; `.lowercase()` dropped and `substringBefore('-')`
  dropped → the two locale tests; `WHERE active` dropped → the texts test; the `UNION ALL`
  half dropped → the texts test; `prompts_text_check` without its trim → "every refusal is a
  named constraint". `./gradlew build` green, 1,423 tests. Its review (read and recomputed the
  known-answer hash outside the JVM): Critical 0, Important 0, Minor 5.
- **Task 2** (2026-10-15, its implementer; `f9f9463`, `af18e73` after the rebase). The port
  tests seen to fail to compile, then pass; `PromptOnTodayTest`'s 9 tests seen red (no
  `prompt` key), then green, and re-run four times. Mutations, each restored: PARENT_CHILD
  mapped to FAMILY in `bond` → `BondAccessTest`; `takesWrites` always true → `BondAccessTest`;
  `locale = "en"` in `AccountDirectory` → `AccountDirectoryTest`; `choose` keyed by the
  member's id → "both members are offered the same prompt…" (and six more); `prompt = null`
  at `GetToday`'s day-row site → the same; `candidates("en")` and the raw locale without
  `candidates` → "the same prompt in each member's own language…"; the `takesWrites` check
  dropped → "no prompt once the bond takes no writes…"; `overlayOf` PARENT_CHILD → COUPLE →
  "a parent and child are offered the family overlay…"; the empty-cycle check dropped → "an
  overlay with no prompts…" (`500`); the library read once at construction → "retiring
  today's prompt mid-day…"; the UTC date handed to `DailyPrompt` → both midnight tests
  (Kathmandu +05:45 and the London DST night); an `EntryStore` import → `ArchitectureTest`;
  the contract's `documentPrompts` call dropped → the contract test. One oddity: on its first
  run only, the empty-cycle mutation also turned the parent-and-child test red; not
  reproduced on three more runs or four unmutated ones, cause unknown. The narrowed
  `WithdrawalReadTest` was seen red with a leave that also nulled an entry's text.
  `./gradlew build` green, 1,436 tests. Its review (read, and ran greps; no test or mutation): Critical 0,
  Important 0, Minor 5.
- **Task 3** (2026-10-20, its second implementer; `3001cd2`). Mutations, each restored and
  checked with `cmp`: `promptId = null` dropped from `Entry.erase` → `EntryPromptTest`; the
  `prompt_id` write dropped from `EntryStore.update` → the delete and withdrawal tests; the
  prompt check moved above the bond's lock → the stranger test (`422` for `404`);
  `.takeIf(prompts::exists)` removed → the `422` test (`500` from `entries_prompt_fk`);
  `PromptId.parse` replaced by a bare `UUID.fromString` → the `422` test, **only through the
  short-spelled body; without it the mutation survives** (run twice, by the implementer and
  by the review); `promptId` added to `EntryResponse` → does not compile without threading it
  through `DisclosedEntry`, and with that, red on "nobody is ever sent it back"; `format =
  "uuid"` dropped from the contract → the contract tests. `./gradlew build` green, 1,446 tests.
  Its review ran the probes in decision 9 (an object, an array, `true`, an upper-case UUID,
  and the key freed by a refusal) and the mutation of a reveal that nulls the prompt (red,
  but only incidentally: M1). Critical 0, Important 0, Minor 4. The controller fixed M1 to
  M3 (`0f04535`) and ran two mutations: a reveal that nulls the prompt → `EntryPromptTest`
  and `SubmitEntryPromptTest`; the raw id in `toString` → `EntryRenderingTest`. Build green,
  1,446 tests.
- **Task 4** (2026-10-20, this record's author).
  - **The smoke run**, `MOYI_DB=moyi_c6a_smoke scripts/smoke.sh`, on the jar built from
    `0f04535`: **850 passed, 0 failed, 0 skipped, 22 sections.** The new section's 36 probes
    all passed, among them the `pt-PT` text, which needs the translation the run puts in, and
    the database reads of `entries.prompt_id`. The translation was seen gone afterwards
    (`prompt_texts` empty), and the database was dropped and seen to be gone.
  - **The CLI**, against the same jar on the same database before it was dropped:
    `moyi register` with and without a locale, `moyi today` showing the prompt,
    `moyi write --prompt` storing the prompt's id on the entry (read from the row),
    `moyi write` storing none, and after a leave `moyi write --prompt` dying with "there is no
    prompt today; write without --prompt". **Bruno**: two scripts written, not opened.
  - **The state map**: `scripts/state-map-check --strict` exit 0, "634 rows, 268 never run,
    0 pending, 29 contract gaps. Nothing wrong."; the checker's 154 tests pass. The new row's
    `codeRef` and `evidenceRef` were each misspelt once and `--strict` failed on each.
  - **Task 2's review minors m1 to m4** (`833ba25`). Mutations, each restored: `GetStreak`
    added as a constructor parameter of `DailyPrompt`, with no import → `ArchitectureTest`
    red on the new constructor assertion while the import half passed; `DailyPrompt`
    returning `""` for a user the directory does not know → the old forgotten-user assertion
    stayed green, the new exact-text one went red.
  - **`./gradlew build`** at `833ba25`: green, **1,446 tests, no failures, errors or skips**,
    counted from the result files (`app` 60 and `gratitude` 602 executed; the other modules
    were up to date and their results reused). Task 4 added assertions to two existing tests
    and no test.
- **Argued, not run:** that a concurrent retire does not block an entry's insert (the
  foreign key's `KEY SHARE` lock does not conflict with an `UPDATE … active`; Task 3's
  review); that no account-deletion path exists to erase a prompt id (none exists); that
  production logging does not print request `toString`s at DEBUG.
- **Not run by anyone:** V24 against a shared database; NFR-001 with the prompt in; the
  Bruno collection; CI's contract action; the smoke run on the pull request's final head
  (it ran on `0f04535`; later commits change tests, tools and documents, not the jar's code).
