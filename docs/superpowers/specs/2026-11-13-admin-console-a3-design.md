# Admin console A3: suspending an account, and the bond it pauses (ADMIN, FR-113)

**Status:** Draft, 2026-11-13. The owner asked for A3 to be built without stopping for questions,
so every decision the corpus did not already make is a ruling in §3, made by the lead for the owner
and **his to overrule**; each says what it costs if it is wrong. A builder check is owed before the
plan (§14 answers the questions a builder will ask).

**Requirements served:** FR-113's last item ("… and suspend accounts. Suspension semantics are
defined in `04` §8"); FR-116 (every admin write audited); doc 04 §8.1 (binding, below); doc 06
`POST /auth/login` ("suspended account" is one of the identical `401 INVALID_CREDENTIALS`); doc 09
§3 ("admin suspension bumps" `tokens_invalid_before`).

**Corpus this builds on:** the A1 spec §9/§11, ADR-0055 (roles, `audit_log`), the A2 spec §10.2
and ADR-0063 (`ModerationNote`, the decide form), the A5 spec and ADR-0064 (`proveNow`,
`FreshStepUp`, the non-transactional controller, refusals audited), ADR-0058 (decision 2, Owed 3),
ADR-0034 and V19 (`wasPausedAt`: the called-off deletion's `SUSPENDED` days, the precedent reused
here), doc 04 §8.3a, ADR-0020 (no "your account is suspended" screen).

## 1. Why this slice

FR-113 is a MUST and the console cannot suspend; today the only way to stop a member is to erase
them. The schema has carried `users.status = 'SUSPENDED'` since V2, `User.canAuthenticate` already
refuses it and verifying an email already declines to lift it (now `AccountStore.markEmailVerified`, one
conditional statement); nothing sets it, and doc 04 §8.1's
pause (sub-state, read-only partner, `SUSPENDED` days, "told it is paused, nothing about why",
resume on lift) has no code.

## 2. Goals, non-goals, anti-goals

**Goals**
- From the A1 lookup result, an `ADMIN` suspends an account, or lifts a suspension, each with a
  typed justification and a code proved in the same request, each one `audit_log` row.
- The suspended member is signed out everywhere at once and cannot sign in, refresh or reset
  their way back; every way in answers exactly what it answers a wrong password.
- Every bond they are in pauses for their partner: reads, archive, export, erasure, leaving and
  blocking still work; a new entry, an edit and a reaction are `409 BOND_PAUSED`; `GET` of the bond
  and of today say `paused: true` and nothing else.
- No day of the pause can be missed: such days are `SUSPENDED`, no freeze is spent, the run is
  where it was when the bond resumes.

**Non-goals**
- Doc 04 §8.2 (a pending account deletion pauses the bond): ADR-0058 decision 2 stands (ruling 13).
- Telling the suspended member why, by any channel (ruling 12); an appeal route.
- A suspension with an end date, a reason taxonomy, a suspended-members list or queue.
- An operator's `psql`/`moyi` route to suspend (ruling 14).

**Anti-goals (a failure even if it works):** a session or sign-in in flight that survives the
commit; any answer that tells the partner, or an invite prober, *why* a bond is paused; a streak
broken or a freeze spent by a day the partner could not write on; the suspended member's words
reaching the partner by a reveal or unlock that happened only because time passed in the pause.

## 3. The rulings (lead for the owner, 2026-11-13)

Each: the decision; why; what it costs if wrong. ✱ marks a departure from the brief's starting
rulings or from doc 04's wording.

1. **`ADMIN` only.** Doc 09 §4's ladder gives `MODERATOR` content-level acts (reports, held media)
   and `ADMIN` the acts with reach beyond one item; a suspension signs a person out and stops their
   innocent partner's writing. *Cost:* a moderator who upholds a report must ask the admin; with
   one operator today, none.
2. **Two routes, each its own act:** `POST /admin/users/{userId}/suspend` and
   `POST /admin/users/{userId}/unsuspend`, offered on the lookup result. The justification is
   checked (`ModerationNote`) before the code; the code is proved per act by
   `SecondFactor.proveNow` (no window, A5's rule), its `ADMIN_STEP_UP` row committed before the
   act's transaction opens. *Cost:* a code per act, with the 30-second step between two.
3. ✱ **Idempotent, and every attempt that reaches the account is audited.** Suspending a
   suspended account (or lifting an active one) changes nothing and is an `ACCOUNT_SUSPENDED`
   (`ACCOUNT_UNSUSPENDED`) row with outcome `REFUSED`, `metadata.result = ALREADY_SUSPENDED`
   (`NOT_SUSPENDED`). The brief proposed no row; A5 audits every refusal past the role check, and a
   spent code whose `ADMIN_STEP_UP` row stands beside no act would read as a gap in the record.
   *Cost:* one row per mistaken click.
4. **Who can be suspended:** `ACTIVE` and `PENDING_VERIFICATION` (a sign-up being abused before it
   verifies; it is in no bond, so only the sign-in effect applies). `DELETED` is refused,
   `result = GONE`. `PENDING_DELETION` is unused since ADR-0058 decision 2 and is answered as
   `GONE` so the enum is total. An account with a **pending deletion request** (V52, status still
   `ACTIVE`) can be suspended, and the page says when the deletion will run. *Cost:* none known.
5. ✱ **Lifting returns the status the account earned, not a stored one:** `ACTIVE` if
   `email_verified_at` is set, else `PENDING_VERIFICATION`. `AccountStore.markEmailVerified` already records a
   verification on a suspended account without changing its status, so this is exact and no
   "prior status" column exists to drift. *Cost:* none; if a third pre-suspension status is ever
   added, this rule must name it.
6. **The member effect, in one transaction in `identity`:** the sessions advisory lock, then the
   `users` row lock (ADR-0058 decision 6's order); `status = SUSPENDED`; `tokens_invalid_before`
   = the **next whole second** (as erasure: a token's `iat` is whole seconds); every refresh family
   revoked; every push token forgotten (`PushTokens.forgetAllOf`, as `logout-all`); a span opened
   in `account_suspensions` (§7). Lifting: the same two locks; status per ruling 5; the span's
   `lifted_at` set. Lifting restores no session: the member signs in afresh. *Cost:* none.
7. ✱ **Login re-checks the account under the sessions lock, and mints the access token inside that
   transaction** (a change to `LoginUser`, required, not optional). Today the password and status
   are judged before the lock and the access token is minted after the commit, so a sign-in whose
   password check straddles the suspension's commit would create a live refresh family after the
   revocation, and its access token's `iat` could reach `tokens_invalid_before`. The re-read refuses
   with the same `InvalidCredentialsException` when the account can no longer authenticate **or its
   password hash is no longer the one just verified** (the same read; it also closes the same race
   against a password reset, found while reading for this). *Cost:* one indexed read per sign-in.
8. ✱ **"Paused" is derived, never stored on the bond** (doc 04's `SUSPENDED_MEMBER` sub-state, as
   a boolean, not a `BondStatus` value). A bond is **paused** when it is open (`PENDING_MEMBER` or
   `ACTIVE`) and a member who has not left holds an open suspension span. `bond` asks `identity`
   through a new read port (`AccountStanding`, §6), which `bond` may call: it already depends on
   `identity.api` (`UserDirectory`). So suspension is one write and lifting is one write, both in
   `identity`; no bond, day or entry row is touched by either, and no lock of theirs is taken. A new
   `BondStatus` value would break every generated client's exhaustive `when` and would need a
   cross-module write at both edges. *Cost:* one indexed read (an open span by user id, partial
   index) on each membership resolution, bond view and closing view.
9. **The partner's bond is read-only for entries, and only for entries.** Refused with
   `409 BOND_PAUSED`: submitting an entry (offline drafts and late entries included), editing one
   (which is also how media is attached), and leaving a reaction (`PUT`). Still allowed: every read,
   search, archive, export, erasing one's own entry (BR-10a is the author's right), removing one's
   own reaction, bookmarks, reports, member settings, leaving, blocking, bond settings and
   proposals. A partner must never be kept from leaving or reporting; anything else is a write that
   cannot produce a solo day. An idempotent replay of a write accepted before the pause answers as
   it did. *Cost:* a timezone or Strict-mode proposal can sit unanswered until it lapses.
10. **The signal:** `paused: boolean` (required) on `BondResponse` (every bond route that returns
    one, and the list) and on `TodayResponse`. No reason, no date, no member named. The today's
    `prompt` is `null` while paused (a suggestion to write is not shown where writing is refused).
    No push is sent for the pause or its end (doc 04 says "told", which the state does; a push would
    be an event the partner could time against a report). The **daily reminder is not sent** in a
    paused bond (`ReminderRoster`'s own contract is "in a bond that takes entries"); on-this-day
    pushes, a read, still go. *Cost:* a partner who never opens the app is not told; the app's copy
    for the paused state is owed (moyi-app, design-system strings).
11. **Invites:** an invite into a bond whose creator is suspended answers, on resolve and on
    accept, exactly what a revoked invite answers (`INVITE_NOT_USABLE`, same bytes); it is not
    revoked, so it works again once the suspension is lifted, if it has not expired. A suspended
    account cannot accept anything (it cannot sign in). *Cost:* an invitee is told the code does not
    work rather than to wait.
12. **The suspended member is told nothing.** Doc 06's identical `401` stands, ADR-0020's gap is
    unchanged, and no email is sent at suspension or lifting. Whether DSA Art. 17 requires a
    statement of reasons is already among the legal checks the console waits on (ADR-0063 Owed,
    `ops/admin/README.md`: the console is not switched on in production before them); if it does,
    that slice adds an email, not this one. *Cost:* until then, a suspended person cannot tell a
    suspension from a forgotten password.
13. **Doc 04 §8.2 stays unbuilt** (ADR-0058 decision 2 and Owed 3 unchanged). It is now cheap: a
    pending deletion request would be a second source for ruling 8's derivation and §4's day rule. But
    decision 2 keeps that person signed in and writing, and pausing their partner meanwhile is the
    owner's product call. A3 records the cost of building it in ADR-0066 and leaves it owed.
14. **No operator route.** The console is the only way to suspend; there is no SQL function and no
    `moyi` command, because one would restate ruling 6's rule (status, tokens, sessions, push
    tokens, span) in a second place. *Cost:* until the console is switched on in production, a
    suspension there needs a deploy of the console first.
15. **A deletion request already pending is carried out on time, suspended or not**
    (`ExecuteAccountDeletions` does not look at status, and is not changed). The right to erasure
    is the person's, as A5 ruling 4 let erasure beat an open report; media evidence holds are
    unaffected. The span outlives the erasure (dates and an anonymised id, like `blocks`).
    *Cost:* a person suspended for abuse can erase their words (they could before A3, too).

## 4. What "paused" means to a day *(rulings 8, 16, 17 together)*

16. ✱ **A day any part of whose writable window fell inside a suspension span is not one anybody
    missed.** A day's writable window is `[startsAt, endsAt + LateEntryGrace)` (BR-3a's 12 hours,
    the one `LateEntryGrace` bean the close job and `SubmitEntry` use; `startsAt`/`endsAt` are the
    day's bounds after `extendedTo`), and it **overlaps** a span `[suspended_at, lifted_at ?: ∞)`
    of any member of its bond. *Why the grace:* a late entry is refused while the bond is paused,
    so a span that begins in the grace stops the day being finished, as one inside it would
    (controller ruling, 2026-11-13, amending the first text, which judged `[startsAt, endsAt)`).
    Such a day, when it closes:
    - `OPEN` or `PARTIAL` (nobody, or one, wrote) → closes **`SUSPENDED`**, not `EMPTY`/`SOLO`:
      no lone entry is unlocked (FR-063 does not run), no `DayClosed`, and the streak walk skips it;
    - `REVEALED` (both wrote and read each other before the pause) → stays `REVEALED`, `COMPLETE`;
    - `PENDING_REVEAL` → ruling 17.
    A missing day (no row) that overlaps is written `SUSPENDED` by `CreateMissingDays`.

    **Overlap, not the day's end** (the called-off deletion's rule, `wasPausedAt(endsAt)`): that rule
    stops a member buying a free rest day with a three-second request-and-cancel. Nobody but an
    `ADMIN` makes a suspension, and the lifted member is told nothing (ruling 12), so the day the
    suspension is lifted must not be a day they can miss. The brief's "the next day resumes" becomes
    "the day under way at the lift resumes for writing and cannot be missed". *Cost:* the day of a
    suspension and the day of its lift can never break a streak, nor extend it unless both write.
17. **A reveal that falls due inside a span is withheld**: the day closes `PENDING_REVEAL` with
    nothing stamped and counts as a day both wrote on (`COMPLETE`), the treatment ADR-0035 question
    9 gives an ended bond. Judged at `revealFallsDueAt`, never at the run's time. A due instant
    before the suspension reveals as normal. *Cost:* a mistaken suspension costs that couple one
    exchange they will never read together; the alternative delivers a suspended member's words
    the moment time passes. **A reveal caused by the partner's own second entry after the lift is
    allowed** (`SubmitEntry` reveals at once, even if the reveal time fell inside the span): it is
    the partner's act, not time passing, so `SubmitEntry` and the close job do not disagree.

The decision for a day is stable from the end of its grace: spans only ever begin at "now", so no
span can reach back over a writable window that has already ended, and a span still open at that
end overlaps it whatever its later `lifted_at`. Days close only after the 12-hour late-entry grace
(and the job's settle margin), so the close job always reads settled spans.

## 5. The pages and the flow

**On the lookup result** (`users.html`, A1): the `Account` list gains **Status** (`ACTIVE`,
`PENDING_VERIFICATION`, `SUSPENDED` "since <instant>", `DELETED`) for every role, and each bond row
gains "paused" when it is. For an `ADMIN` only (`SecondFactor.isAdmin`, display only): a form
**Suspend this account** (when `ACTIVE`/`PENDING_VERIFICATION`) or **Lift the suspension** (when
`SUSPENDED`), each with a justification (1 to 400, `ModerationNote`'s hint) and a code field; for a
pending deletion request, the line "A deletion this person asked for runs on <deleteBy>, suspended
or not." Others see "Only an ADMIN can suspend an account."

**`POST /admin/users/{userId}/suspend`** (and `/unsuspend`, the same in every step), on a new
`SuspensionController`: class-level `@PreAuthorize("hasRole('ADMIN')")`, **not `@Transactional`**
(A5's reason: the code's own transaction commits first). In order, each before the next:

1. **Role** — Spring's 403 page; nothing read, no row (A5 §5 step 1).
2. **The id** — not a UUID: the admin 404 page, no row (`ModerationPages.uuidOrNull`).
3. **Justification** — `ModerationNote.require`; refused: the form again on `admin/decide`
   (`DecisionForm`, note kept), `422`, no code spent, no row.
4. **Code** — `SecondFactor.proveNow`: blank → the form again asking for it (`200`); refused →
   `401`, `ADMIN_STEP_UP` `REFUSED` (A2); accepted → a `FreshStepUp`, its row committed.
5. **The act** — `SuspensionConsole.suspend(userId, justification, stepUp, adminId, from)`,
   `@PreAuthorize ADMIN`, `@Transactional`: re-validates the note, checks the step-up is this
   admin's, calls `identity`'s port (ruling 6, locks §8), writes the audit row, commits.
6. **Answer** — after the commit: **`303` to `GET /admin/users?q=<userId>`** with a one-line flash
   (the outcome's line below; never the note). The `GET` fills the lookup form and reads nothing
   (A1). A redirect, unlike A5's words page, because there is nothing secret to keep out of a URL,
   and a reload must not re-post the spent code into the five-failure lockout.

| Outcome (`metadata.result`) | When | Audit outcome | Flash |
|---|---|---|---|
| `SUSPENDED` | `ACTIVE`/`PENDING_VERIFICATION` | `SUCCEEDED` | "Suspended. They are signed out everywhere and cannot sign in. Their bonds are paused." (+ the deletion line, when pending) |
| `ALREADY_SUSPENDED` | suspend on `SUSPENDED` | `REFUSED` | "Already suspended. Nothing changed." |
| `LIFTED` | unsuspend on `SUSPENDED` | `SUCCEEDED` | "Suspension lifted. They can sign in again; their bonds take entries again now." |
| `NOT_SUSPENDED` | unsuspend on any other live status | `REFUSED` | "This account is not suspended. Nothing changed." |
| `GONE` | `DELETED` (or `PENDING_DELETION`) | `REFUSED` | "This account was deleted. There is nothing to suspend." |
| `NO_SUCH_ACCOUNT` | no `users` row | `REFUSED`, no target | the admin 404 page, not a redirect |

**The row:** `action = ACCOUNT_SUSPENDED | ACCOUNT_UNSUSPENDED`, `target_type = USER`,
`target_id = <userId>` (none for `NO_SUCH_ACCOUNT`), `justification = <the note>`,
`metadata = {"result": …, "statusBefore": "ACTIVE"|…, "deletionPending": true|false}`, `ip_hash`
as A1. **Metric** `moyi.admin.suspension{action, result}`; **log** one INFO line per act (admin id,
user id, result), never the note.

## 6. Where the code lives

- **`identity.api`**, two ports, both `internal`-implemented in `identity.service`:
  - `AccountSuspension` (writes; **admin only**, ArchitectureTest's `declaredIn` list):
    `suspend(userId): SuspensionResult` and `lift(userId): SuspensionResult`, each
    `@Transactional(propagation = MANDATORY)` (they join the console's transaction, so the act and
    its audit row commit together). `SuspensionResult` is a closed enum of §5's six outcomes plus
    `statusBefore` and `deleteBy: Instant?`.
  - `AccountStanding` (reads; **imported by `bond` only**, a new ArchitectureTest rule):
    `suspendedAmong(userIds): Set<UUID>` (open spans) and `suspensionsOf(userIds):
    List<SuspensionSpan>` (`userId`, `from`, `to?`). No lock, read-only.
- **`identity.service.UserAdminLookupService`**: `UserAdminView` gains `status` (an api enum) and
  `suspendedSince: Instant?`. **`LoginUser`**: ruling 7.
- **`bond`**: `BondAccessAdapter.assemble` sets `BondMembership.paused` (read after the bond lock
  under `lockMembershipOf`); `BondViews` sets `BondResponse.paused`; `BondPasts` adds the members'
  spans to `BondPast`, and `BondClosingView` gains `wasSuspendedDuring(startsAt, endsAt)` (overlap,
  §4) and `wasSuspendedAt(instant)` (for ruling 17); `ResolveInvite`/`AcceptInvite` refuse per
  ruling 11 (`AcceptInvite` under the bond lock it already takes); `ReminderRosterAdapter` drops
  due reminders in paused bonds (one `suspendedAmong` call per run); `BondAdminView` gains `paused`.
- **`gratitude`**: a `BondPausedException` (`409 BOND_PAUSED`), thrown **immediately after**
  each existing `BondArchivedException` check in `SubmitEntry`, `ChangeEntry` (edit only, not the
  erasure) and `ReactToEntry` (`PUT` only); `DailyPrompt` returns null when `paused`;
  `TodayResponse.paused`. `BondDay.close(now, withheld, paused)`: `paused` turns `OPEN`/`PARTIAL`
  into `SUSPENDED`. `CloseDay` passes `view.wasSuspendedDuring(startsAt, endsAt + grace)` (ruling 16) and folds
  `view.wasSuspendedAt(revealFallsDueAt)` into the withholding; `CreateMissingDays` writes
  `SUSPENDED` for an overlapping gap (the same window); `EvaluateStreaks.outcomeOf` maps a `MISSED`
  overlapping day to `SUSPENDED` (belt and braces, as it does for `wasPausedAt`);
  `ReconcileJoiningDay` withholds while `membership.paused` (it only defers: the close then judges
  by the due instant).
- **`admin`**: `SuspensionConsole` (service), `SuspensionController` (web), `users.html`'s
  status and forms, `AuditAction` + V66, `Disclosure`'s explicit case (§10). A new ArchitectureTest
  rule: within `admin`, only `service/SuspensionConsole.kt` imports `AccountSuspension` (A5's
  pattern); `FreshStepUp` is still made only by `proveNow`.

## 7. Data model and migrations

Versions are one sequence across modules; V64 is the last on main (82a8bb9) and on every branch.

- **V65** (`modules/identity/.../V65__identity_account_suspensions.sql`):
  `account_suspensions (id uuid PK, user_id uuid NOT NULL REFERENCES users(id), suspended_at
  timestamptz NOT NULL, lifted_at timestamptz, CHECK (lifted_at IS NULL OR lifted_at >=
  suspended_at))`; `UNIQUE (user_id) WHERE lifted_at IS NULL` (one open span; also the index
  `suspendedAmong` reads); `INDEX (user_id, suspended_at)`. No actor and no reason: those are the
  admin's `audit_log` row, and `identity` does not know admins. Timestamps truncated to
  microseconds (they are compared with day bounds). The invariant "`status = SUSPENDED` iff an open
  span exists" is held by the port's one method and asserted by a test; it cannot be a `CHECK`.
- **V66** (`modules/admin/.../V66__admin_account_suspension.sql`): drop and re-add
  `audit_log_action_check` with `ACCOUNT_SUSPENDED`, `ACCOUNT_UNSUSPENDED` (V64's pattern);
  `AuditActionCheckTest` holds enum and SQL together. A migration test for each, as
  `V61MigrationTest`.

Nothing in `bond` or `gratitude` migrates: `bond_days.status` and `streak_days.evaluated_as` already
admit `SUSPENDED`.

## 8. Concurrency

**Lock order.** Suspend/lift: sessions advisory lock `(1, user)` → `users` row `FOR UPDATE` →
refresh tokens → push tokens → `account_suspensions` → `audit_log` insert. It takes **no** bond, day
or entry lock. Every bond/gratitude path keeps bond → day → entry and reads spans **without** a
lock. No path holds a bond lock and then waits on a `users` row, so no cycle is possible.
`ExecuteAccountDeletions`, login, refresh and reset take the same two identity locks in the same
order.

| Race | What decides it | Result in each order |
|---|---|---|
| Suspension vs the member's sign-in | sessions lock + ruling 7's re-read | login first: its family is revoked and its token's `iat` precedes the bump; suspension first: `401` |
| vs the member's refresh | sessions lock; `issueNext` re-reads; the access token is minted inside that transaction (final review I1, as ruling 7) | refresh first: rotated then revoked, and its access token's `iat` precedes the bump; suspension first: refused |
| vs a reset link or code sent before it | sessions lock; `ApplyPasswordReset` re-reads the status under it | reset first: the password changes, then the suspension; suspension first: refused exactly as a spent link or code, the password unchanged |
| vs a request already authenticated | none | it completes (one request's length); the next is `401` |
| vs the partner's submit/edit/react | none (spans read under the partner's bond lock, statement-level) | read before the commit: the write lands and is ordered before the suspension (a second entry may reveal instantly, as it would a second earlier); after: `409 BOND_PAUSED` |
| vs the close job | spans read under the bond lock; §4's stability | a day closes ≥ 12 h after its end, from settled spans; the same answer either way |
| vs the member's account deletion executing | sessions + user row locks | deletion first: `GONE`; suspension first: the deletion still runs (ruling 15) |
| two admins; suspend vs lift | `users` row lock | the second sees the first's result: `ALREADY_SUSPENDED` / `NOT_SUSPENDED` / the act |
| vs the partner leaving, blocking, ending | none needed | allowed while paused; once ended, `BOND_ARCHIVED` precedes `BOND_PAUSED` and `paused` is false |
| vs an invite accepted into the creator's bond | `AcceptInvite`'s bond lock; spans unlocked | a join just before: the bond activates paused, its days overlap and are `SUSPENDED`; after: `INVITE_NOT_USABLE` |
| Lift vs the partner's submit | none | refused until the commit, accepted after |

## 9. Error codes and the contract

- **`BOND_PAUSED`**, `409`, new in `ErrorCode`: "This bond is paused. Everything in it can still be
  read; new entries wait until it resumes." It names no cause, so a §8.2 pause could later answer
  the same. Precedence on each refused route: `404` (not a member) → `BOND_ARCHIVED` → `BOND_PAUSED`
  → the route's own conflicts.
- `BondResponse.paused` and `TodayResponse.paused`: required booleans, additive.
- The descriptions of `POST …/entries`, `PATCH …/entries/{id}` and `PUT …/reaction` name
  `409 BOND_PAUSED`; `GET /bonds/{id}` and `/today` describe `paused`.
- **A new response enum value is breaking under oasdiff** (`flatten-allof`, `fail-on: ERR`), as
  `REPORT_ON_OWN_ENTRY` was (#118): the PR carries `breaking-api-change` and its title is
  `feat(admin)!: …`. Invite routes are unchanged (same code, same bytes).

## 10. Disclosure in the export

`Disclosure.shows` gains an explicit case: `ACCOUNT_SUSPENDED` and `ACCOUNT_UNSUSPENDED` are
**withheld**, whatever the outcome. A suspension is usually report-driven, and in a bond of two the
reporter is the partner (A2 ruling 1, A5 ruling 2); the release review owed for withheld rows
decides whether a lifted suspension may be shown. The exhaustive `DisclosureTest` grows; a test
proves an export built after a suspension and a lift equals, byte for byte, one built before.

## 11. Tests and mutation targets

- **Console:** `MODERATOR`, `SUPPORT` and a hand-made POST: 403, no row, account untouched. Note
  empty / 401 code points / NUL: 422, **no code spent** (the next `proveNow` with it succeeds), no
  row. Code blank, wrong, replayed, or a fresh `secondFactorAt` without a code: refused. Each of
  §5's outcomes with its row, flash and status. The note in no log line, flash, metric or URL.
- **Member:** after `suspend`, the access token minted before is `401`; refresh `401`; sign-in
  `401` whose body equals a wrong password's, byte for byte (the instance aside); the failed-login
  counter unchanged; reset sends nothing; verify leaves `SUSPENDED`; push tokens gone;
  `tokens_invalid_before` is a whole second after the act. After `lift`: `ACTIVE` (verified) or
  `PENDING_VERIFICATION` (not, and verified-while-suspended → `ACTIVE`); sign-in works; no session
  came back.
- **Partner:** `POST …/entries`, a late entry, `PATCH` (edit), `PUT …/reaction`: `409 BOND_PAUSED`
  on a paused bond, `409 BOND_ARCHIVED` first on an ended one; erasure, unreact, bookmark, report,
  leave, block, archive, search, export: as before; `paused` true then false; `prompt` null; no
  reminder pushed; invite resolve/accept equal to a revoked invite's bytes, and working after lift.
- **Days and streak** (through the close job, not inserted outcomes): an overlapping `OPEN` day →
  `SUSPENDED`; `PARTIAL` with the suspended member's lone entry → `SUSPENDED`, the entry never
  shown to the partner; `REVEALED` → `COMPLETE`; `PENDING_REVEAL` due inside the span → withheld,
  `COMPLETE`, nothing stamped; due before → revealed; missing days → `SUSPENDED`; no `DayClosed`
  for them; a 5-day run with one freeze: pause three days, lift, both write → run 6, freeze kept;
  the lift day with one entry → `SUSPENDED`, not missed; a span beginning inside a day's late-entry
  grace → that day `SUSPENDED` (lone entry locked, not `SOLO`, not `EMPTY`, no freeze spent), and
  one beginning at `endsAt + grace` leaves it as it was.
- **Races** (synchronised on locks, never a sleep): login holding the sessions lock past its
  password check while a suspension waits, and the reverse; partner submit vs an uncommitted
  suspension (write lands) and a committed one (409); suspension vs deletion execution, both orders;
  two admins.
- **Architecture:** the three rules of §6; `AuditActionCheckTest`; V65/V66 migration tests;
  `DisclosureTest`; an invariant test that status and open span agree after every port call.
- **Mutation targets (each must turn a test red):** remove ruling 7's re-read; mint the access
  token after the commit; skip the `tokens_invalid_before` bump; skip `forgetAllOf`; drop the
  `BOND_PAUSED` check in any of the three services; judge overlap by `endsAt` only; let a paused
  `PARTIAL` close `SOLO`; drop the reveal withholding; drop the roster filter; let `Disclosure`
  show the rows; check the code before the note; remove the role check.

## 12. State map, smoke, CLI, operations, documents

- **State map:** `account.json` gains the `SUSPENDED` state (from `SIGNED_IN`, `ACTIVE_SIGNED_OUT`
  and `PENDING_VERIFICATION` by the console; sign-in, refresh, access, reset and verify from it;
  lift back); `bond.json` a paused bond's rows (the three `BOND_PAUSED` writes, the allowed writes,
  `paused` on reads, invites); `day.json` the overlap close and the withheld reveal; `admin.json`
  one row per §5 outcome. Each with its test as `evidenceRef`.
- **Smoke** (`scripts/smoke.sh`, the admin section): invite an `ADMIN`, sign in, look up the smoke's
  member, suspend (a code; then wait for the next TOTP step), and drive the member side through the
  API: the old access token `401`, refresh `401`, sign-in `401` equal to a wrong password's, the
  partner's submit `409 BOND_PAUSED`, `GET` bond and today `paused: true`, the partner's leave not
  run (it would end the bond); lift (a new step), sign-in `200`, the partner's submit `201`;
  `audit_log` holds both rows with `result`, and the export lists neither. Add one probe from a
  refused address to `/admin/users/{id}/suspend`: the API's own 401, as A5 did. Update the header
  comment with the run.
- **`scripts/moyi`:** no command (ruling 14); its `write` prints `409 BOND_PAUSED` as any refusal.
- **`ops/admin/README.md`:** "What exists" gains A3; a section *Suspending an account* (the look-up
  first with purpose `SAFETY` and a note, the form, a code per act, what the member and partner
  see, what to tell nobody, the pending-deletion line, that lifting restores no session); the
  metric; "What this does not do" loses suspension.
- **ADR-0066** (*the admin console A3: suspension, and the paused bond*): rulings 1–17, the
  departures (✱), and amendments to ADR-0058 (spans kept on erasure; §8.2 still owed, now cheap),
  ADR-0055 (`ADMIN`'s second act `MODERATOR` lacks), ADR-0034 (a second kind of pause, by overlap).
- **Corpus amendments owed** (the documents repository): doc 04 §8.1 (sub-state = derived `paused`;
  overlap; withheld reveals), doc 06 (`BOND_PAUSED`, `paused`), doc 07 (`account_suspensions`).

## 13. Owed after A3

- The legal checks the console already waits on, now including whether DSA Art. 17 needs a
  statement of reasons to the suspended member (ruling 12).
- Doc 04 §8.2 (ruling 13): the owner's.
- The release review of withheld audit rows (A2, A5, and now A3).
- moyi-app: the paused state's screen and copy; `BOND_PAUSED` on a queued draft (keep it, say
  why nothing sent); `paused` in the generated client.
- A4 (jobs, flags, support inbox) and the `audit_log` retention job.

## 14. Builder's questions, answered

1. **Why not reuse `bond_write_pauses` (V19)?** It records a stretch the *bond* refused writes,
   written when it closes; a suspension is an account's, open while it lasts, and `identity`
   cannot write `bond`'s tables. The close job asks both through `BondClosingView`, as two
   questions, because they are judged differently (day's end vs overlap, §4).
2. **Does `takesWrites` become false while paused?** No: its readers (the next milestone, push
   audiences) must not stop on a pause. `paused` is separate, checked after it.
3. **Both members suspended, or one in two bonds?** Each bond with a suspended member who has not
   left is paused, derived per bond; it resumes when no such member remains.
4. **Is `paused` an oracle?** For "my bond is paused", by design (doc 04); never for why.
5. **Which transaction does the step-up run in?** Its own, before the act's (A5): a failed act
   never un-spends the code, and an act is two audit rows.
6. **What the member meets after the commit** (the tests of §11 hold each): the old access token
   `401` with `WWW-Authenticate`; refresh `401 REFRESH_TOKEN_INVALID`; sign-in the wrong-password
   `401`, uncounted; reset sends nothing (`RequestPasswordReset` checks `canAuthenticate`); a
   verification link records itself and leaves `SUSPENDED`; no push reaches a phone. Their
   partner's archive still names them (`UserDirectory` answers for a suspended account).
