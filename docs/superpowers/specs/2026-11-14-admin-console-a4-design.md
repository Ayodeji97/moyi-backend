# Admin console A4: the jobs, the feature flags and the support inbox

**Status:** Draft, 2026-11-14. The owner asked for A4 to be built without stopping for questions,
so every decision the corpus did not already make is a ruling in §3, made by the builder for the
owner and **his to overrule**; each says what it costs if it is wrong.

**Requirements served:** FR-114 ("Admin MUST be able to view scheduled-job status and manually
re-run the reveal and streak jobs for a given date"), FR-115 ("Admin MUST be able to toggle feature
flags without deploying", SHOULD), FR-113's third item ("… view the support inbox"), FR-074's
"`recalculate` admin operation", FR-116 (every admin write audited, with a justification).

**Corpus this builds on:** doc 03 Epic I; doc 05 §2 (`admin/`: "admin API, audit log, feature
flags, report queue"; `scheduling/`: "ShedLock-guarded jobs"); doc 07 §2 (`feature_flags(key pk,
enabled, rollout_percent, payload jsonb, updated_at, updated_by)`, `audit_log` kept 2 years);
doc 09 §4 (`ADMIN` holds "feature flags"); doc 11 §7 (`close-job-did-not-run.md`: "manual re-run
for a date + timezone, verifying idempotency, repairing affected streaks"); ADR-0033 (the close job
is not per zone and keeps no watermark), ADR-0034 decisions 9–10 (`recalculate` replays, announces
nothing, "the route arrives with `modules/admin`"), ADR-0050 (`job_runs`), ADR-0054 (embeddings),
ADR-0055 (the console, roles, `audit_log`), ADR-0057 (support tickets and their three functions),
ADR-0063 (A2: functions stay the rule, the console wraps them; the step-up window), A2 spec §15 and
ADR-0063 Owed 3 (fold the inbox into A4), ADR-0064 and ADR-0066 (A5, A3).

## 1. Why this slice

Three things an operator does today over SSH, or cannot do at all:
- **"Did the close job run?"** The answer is in Prometheus gauges and `shedlock`/`job_runs` rows read
  by `psql`. If the timer stopped, nothing but a redeploy runs the close job, and FR-074's
  `recalculate` exists only as a service with no caller (ADR-0034 decision 10).
- **Switching off a feature without a deploy** (FR-115). Nothing can be switched at runtime: every
  `moyi.*` property is read at boot.
- **The support inbox** (FR-102, FR-113) is three SQL functions run through `scripts/moyi support`,
  with `MOYI_OPERATOR` as a free-text name tied to no second factor.

## 2. Goals, non-goals, anti-goals

**Goals**
- Any signed-in admin sees, for every ShedLock job, when it last started, whether it holds its
  lock now and when it last finished; and, for a date, whether that date's days are settled.
- An `ADMIN` runs the close job now (its reveal, close and streak steps), under the job's own lock,
  and rebuilds one bond's streak from its days (FR-074), each with a justification and an audit row.
- An `ADMIN` switches each of three server-side features off and on, effective on the next run or
  call on every instance, with a justification and an audit row.
- A `SUPPORT` operator (and above) lists the open tickets, reads one (its words, its reference and
  where to answer), and closes it, through the same functions `scripts/moyi support` calls, each
  look and act in `audit_log` and in `support_ticket_log` under `admin:<id>`.

**Non-goals:** a re-run of any job other than the close job; flags a client reads
(`/meta/config`), percentage rollouts and payloads (ruling 9); replying to a ticket from the console
(the operator answers from their own mailbox, as today); the `audit_log` retention job (ruling 15);
the outbox poller's depth on the jobs page (ruling 2).

**Anti-goals (a failure even if it works):** a manual close run overlapping the timer's on one
instance or another; a re-run that announces a streak twice (ADR-0034 decision 10); a flag whose
"off" leaves a path that still does the thing (a query still sent to the embedding provider); a
ticket's words or a justification in a log line, a metric tag, a flash, a URL or an exception
message; anything in this slice changing the JWT API or its contract.

## 3. The rulings (builder for the owner, 2026-11-14)

Each: the decision; why; what it costs if wrong. ✱ marks a departure from the corpus's wording.

**The jobs (FR-114)**

1. **One registry of the jobs, in `scheduling`**, exposed by a new admin-only port
   `scheduling.api.JobConsole`: the eleven ShedLock-guarded jobs (each lock name, what it does in
   one line, the cron it runs on as configured). The page reads `shedlock` (last start, locked
   until) and `job_runs` (last finish) for them. `SUPPORT` and above may view it, and the view writes
   **no audit row**: it names nobody, as the home page's queue counts do not (A2). *Cost:* none.
2. **Every locked job records its finish in `job_runs`** (ADR-0050's table; five of the eleven
   already do: the media, deletion and export jobs). The close, reminder, on-this-day, held-push,
   embedding and idempotency-key jobs gain the one upsert, so "last finished" exists for all. The
   **outbox poller is not listed**: it holds no lock (ADR-0035), runs every two seconds on every
   instance, and its health is its own gauges (`gratitude.outbox.pending`, `…age.seconds.max`).
   *Cost:* one upsert per run (the held-push and embedding jobs run each minute); the page cannot
   show the outbox's state (owed).
3. ✱ **"Re-run the reveal and streak jobs for a given date" is running the close job now.** The
   close job takes no date and keeps no watermark (ADR-0033, `CloseElapsedDays`' KDoc): every day of
   any date that has ended past its grace and is not closed, every reveal fallen due, and every
   settled day not yet counted in its streak (its third step, `EvaluateStreaks`, which *is* the
   streak job) is its work on every run. A run "for a date" would be a second statement of which days
   are due, and the two would drift. What FR-114's date is for, telling whether a date is done, is a
   **date check** on the jobs page: for a date, how many of its days are still open, how many of
   those are past due (the next run closes them), and how many are closed but not yet counted in
   their streak. Read through a new `gratitude.api.DaySettlement` port that uses the close job's own
   threshold (`LateEntryGrace.closesDaysEndedBy(now) − SETTLE_MARGIN`). *Cost:* a day with no row yet
   (nobody wrote, the job has not written it) is not counted by the check; doc 11's runbook wording
   "for a date + timezone" needs amending (§12).
4. **Running the close job now is `ADMIN`'s, with a justification and no code.** It does only what
   the timer does within fifteen minutes, and is idempotent (`DayCloser`'s contract), so a code would
   protect nothing. It runs **under the job's own ShedLock lock** (`close-days`, `lockAtMostFor` 14
   minutes as the timer's, `lockAtLeastFor` zero), through ShedLock's `LockingTaskExecutor`: if the
   timer, or another instance, holds it, the answer is **`BUSY`** and nothing runs. It runs even when
   the timer is switched off (`moyi.scheduling.enabled=false`), which is the case it exists for.
   *Cost:* a run is synchronous in the request; one longer than the proxy's timeout loses its page,
   not its work (the jobs page then shows its finish).
5. ✱ **The run's audit row is written before the run, in its own transaction, while the lock is
   held**: `JOB_RUN`, `SUCCEEDED`, `{"job": "close-days", "result": "STARTED"}`. The run is thousands of
   transactions of its own (one per day), so it cannot share one with its row (A1 §8's rule); writing
   the row first means a run that dies still left its record, and a row that cannot be written stops
   the run. What the run did (created, closed, revealed, evaluated, failed, backlog) is in the flash
   and one INFO log line, not the row. `BUSY` is a `REFUSED` row. *Cost:* the audit log says a run
   was asked for and started, not what it changed.
6. **Rebuilding a bond's streak (FR-074) is `ADMIN`'s, with a justification and no code**, from
   the jobs page by bond id. `RecalculateStreak` already replays the recorded decisions under the
   streak row's lock, writes only `streak_states`, and announces nothing (ADR-0034 decisions 9, 10);
   a new `gratitude.api.StreakRecalculation` port joins the console's transaction (`MANDATORY`), so
   the rebuild and its `STREAK_RECALCULATED` row commit together. Results: `CHANGED` (the stored row
   had drifted, `SUCCEEDED`), `UNCHANGED` (`SUCCEEDED`), `NO_DAYS` (no evaluated day, nothing
   written, `REFUSED`). The row's metadata is the result and the before and after figures (current,
   longest, freezes available, complete days): counts of a couple's run, no date, no member.
   *Cost:* `SUPPORT`, who takes "my streak broke wrongly" (doc 11 §7), must ask the admin; with one
   operator, nobody.

**The feature flags (FR-115)**

7. **Three flags, each a server-side switch that pauses an optional effect**, all on by default:
   - `DAILY_REMINDERS` — the reminder job asks for no reminder while off.
   - `ON_THIS_DAY_PUSHES` — the on-this-day job asks for no push while off.
   - `AI_EMBEDDINGS` — no text is sent to the embedding provider while off: every call through the
     `ai` module's `EmbeddingPort` refuses (`EmbeddingUnavailableException("switched off")`), so a
     search is keywords only (ADR-0054's existing degraded path) and the embedding job skips its run.
     Nothing stored is erased: switching back on resumes where it was (ADR-0054's "turning semantic
     search off" erases; this flag does not, and is not that).
   Each pauses a send to a person or a third party, and each "off" is a state the code already has.
   A flag that switched off writing, signing in or the reveal would be a way to break the product
   from a browser. *Cost:* a reminder or on-this-day push that falls due while its flag is off is
   **not sent later** (their jobs look back thirty minutes, by design: ADR-0045, ADR-0046); a search
   while `AI_EMBEDDINGS` is off counts as `degraded`, not `disabled`, in `gratitude.search.semantic`.
8. **A flag with no row is its default; the table starts empty.** V67 inserts nothing, so this
   slice changes no behaviour until someone switches a flag. Every read goes to Postgres, **no
   cache**: a change is seen by the next run or call on every instance (a job reads its flag at the
   start of its run; the port at each call). *Cost:* one primary-key read per job run and per
   embedding call.
9. ✱ **Doc 07's `rollout_percent` and `payload` are not built, nor is any flag a client reads.**
   A percentage has no use for two people sharing one bond, and a client flag needs `/meta/config`
   in the contract (doc 06; ADR-0056 left it to "a separate route") and the app to read it. The table
   is `feature_flags (key, enabled, updated_at, updated_by)`; adding the columns later is additive.
   *Cost:* a client-side switch still needs an app release.
10. **Switching a flag is `ADMIN`'s alone** (doc 09 §4), the page included, **with a
    justification and no code**: each flag pauses something and switching it back undoes it, so a
    code would protect nothing a session does not already. `FEATURE_FLAG_CHANGED`, target `FLAG`
    and its key, `{"result": "CHANGED", "from": …, "to": …}`; setting a flag to what it already is
    changes nothing and is `REFUSED`, `UNCHANGED` (A3 ruling 3's rule: every attempt is audited).
    Two admins switching one flag are serialised by a transaction-scoped advisory lock on the flag,
    so `from` is always the value the change replaced. *Cost:* a hijacked `ADMIN` session can pause
    reminders without a code; the allowlist and the two-factor sign-in bound it.
11. **Where they live.** The table is `admin`'s (doc 05 §2), written only by `admin`. Reading goes
    through `com.moyi.common.core.FeatureFlags` (interface and the closed `FeatureFlag` enum), so
    `scheduling` and `ai` read a flag without depending on `admin` (which now depends on
    `scheduling`: a cycle otherwise). `admin.infra.FeatureFlagStore` implements it, as a persistence
    bean present with the console off (ADR-0055 ruling D), so the media worker and a console-off
    production read the same table. `FeatureFlagsWiringTest` (in `app`) holds that the one
    `FeatureFlags` in the application is that store. *Cost:* the flag names live in `common:core`,
    which otherwise knows no feature; it is a list of names and defaults, no rule.

**The support inbox**

12. **The console wraps ADR-0057's three functions, unchanged**, through a new admin-only
    `support.api.SupportInbox` port (`support.infra`, `JdbcClient`), as A2 wrapped the report
    functions (ADR-0063 decisions 1–2): the functions stay the one place the rules live, the actor is
    `admin:<accountId>`, each look and act is one `audit_log` row in the same transaction as the
    function's own `support_ticket_log` row. `scripts/moyi support` keeps working, actor aside the
    same effects. *Cost:* none.
13. **`SUPPORT` and above, for all three.** Listing writes `LISTED` (function) and
    `SUPPORT_INBOX_VIEWED` (audit); reading writes `READ` and `SUPPORT_TICKET_READ` (target
    `SUPPORT_TICKET`; an unknown id is `REFUSED`, the admin `404` page, and writes no `READ`, as the
    function writes none); the home page shows the open count with no log row (A2's counts). The
    ticket's page shows its words, its reference, the address to answer at (read from `users` at that
    moment by the function) and whether it was ever confirmed. **Closing empties the words, so it is
    irreversible and behind A2's step-up window** (a code proven in the last five minutes,
    `StepUp.isFresh`), with a note (`ModerationNote`, 1–400, inside the log's 500): `CLOSED` or
    `NOT_OPEN` (already closed, by another operator, `scripts/moyi`, or an account deletion),
    `SUPPORT_TICKET_CLOSED` `SUCCEEDED` or `REFUSED`. *Cost:* the first close in five minutes needs a
    code.
14. **No A4 row is shown in a member's export** (`Disclosure.shows`, an explicit case): the job and
    flag rows name nobody; a streak rebuild is a bond's and changes nothing a member wrote; a ticket
    read is of what the person sent to a person, and the acknowledgement already told them a person
    reads it (ADR-0057 decision 5). *Cost:* a person's export does not say when support read their
    message. Revisit with the release review owed for withheld rows (A2, A3, A5).

**Owed**

15. **The `audit_log` retention job is not built here; it stays owed** (ADR-0055 decision 10). It
    is the one sanctioned delete from a table nothing may alter, so it deserves its own spec and
    review; and nothing is due before **2028-11-11**, two years after the first console row could
    exist (V60 merged then, console still off in production). ADR-0067 records its design sketch
    (a `SECURITY DEFINER` function that sets a transaction-local `moyi.audit_purge` the trigger admits
    only for rows older than two years, run by the scheduling module through an `admin.api` port)
    and the date it is due by.

## 4. The pages and the flow

**Navigation** (`layout.html`): every admin gets **Support inbox** and **Jobs**; an `ADMIN` also
**Feature flags** (display only: the routes decide). The home page adds "Open support tickets" for
every role.

**`GET /admin/jobs`** (`SUPPORT`): a table of the eleven jobs (name, what it does, schedule, last
started, locked until, last finished, "running" when it started after its last finish and still
holds its lock); whether the timer is on on this instance; with `?date=YYYY-MM-DD`, the date check
(ruling 3; a value that is not a date is the page with "Not a date", `400`). For an `ADMIN`, two
forms: **Run the close job now** (justification) and **Rebuild a bond's streak** (bond id,
justification).

**`POST /admin/jobs/close-days/run`** (`ADMIN`, controller not `@Transactional`): role → note
(`ModerationNote`; refused: the form again, `422`) → `JobsConsole.runClose` → `303` to
`/admin/jobs` with one line: "Ran: created N, closed N, revealed N, counted N, failed N." (+ " More
days are waiting: run it again." on a backlog) or "The close job is running now (the timer or another
instance). Nothing was started; look again in a minute."

**`POST /admin/jobs/streak/recalculate`** (`ADMIN`): role → bond id (not a UUID: the form again,
`422`, "Not a bond id") → note → `JobsConsole.recalculate` in one transaction → `303` to
`/admin/jobs` with "Rebuilt: the stored streak had drifted; it is now current N, longest N." /
"Rebuilt: nothing had drifted." / "That bond has no counted day: nothing to rebuild."

**`GET /admin/flags`** (`ADMIN`): each flag, what it pauses, on or off, last changed (when, by which
admin id), and a form to switch it (justification). **`POST /admin/flags/{flag}`** (`ADMIN`,
`enabled=true|false`): role → flag (unknown: the `404` page) → `enabled` (neither: `404`) → note
(`422`) → `FlagConsole.set` → `303` to `/admin/flags`: "Switched off. …" / "Switched on." /
"Already off. Nothing changed."

**`GET /admin/support`** (`SUPPORT`): open tickets oldest first: received, ticket id (link), sender
id (a link to the lookup form, which asks its own purpose), subject and body lengths, age in days.
Never the words. **`GET /admin/support/{ticketId}`**: the case; for an open ticket, the close form
(note, and a code when the window is not fresh). **`POST /admin/support/{ticketId}/close`**
(`SUPPORT`): A2's `Decisions.decideGated` flow (code proved first if typed; refused `401`; stale
window: the form with the code field; note refused `400`) → `303` to the ticket's page: "Closed. Its
words are gone." / "Already closed. Nothing changed."

## 5. Where the code lives

- **`common:core`:** `FeatureFlag` (enum: key, default, one line of what "off" pauses) and
  `FeatureFlags` (`isOn(flag): Boolean`).
- **`scheduling`:** `api/JobConsole.kt` (`JobConsole`, `JobStatus`, `CloseRun`), implemented by
  `service/ScheduledJobsConsole.kt` (the registry, `LockingTaskExecutor` over the existing
  `LockProvider`, `CloseJob.closeOnce`); `infra/JobRuns` gains `all()`; a new `infra/LockRows` reads
  `shedlock`. The six jobs of ruling 2 call `runs.succeeded(LOCK, now)`; `ReminderJob`,
  `OnThisDayJob` and `EmbeddingJob` read their flag first and skip.
- **`gratitude`:** `api/StreakRecalculation.kt` (+ `StreakRebuild`, `StreakFigures`,
  `RebuildResult`), implemented by `service/StreakRecalculationService.kt` (`MANDATORY`, delegating
  to `RecalculateStreak`); `api/DaySettlement.kt` (+ `DateSettlement`), implemented in
  `service/DaySettlementService.kt` over `infra/database/DateSettlementQuery.kt`.
- **`ai`:** `infra/SwitchedEmbeddingPort.kt` wraps whatever `AiConfiguration` chose.
- **`support`:** `api/SupportInbox.kt` (+ `TicketRow`, `TicketCase` with a redacting `toString`,
  `TicketCloseOutcome`), implemented by `infra/database/SupportInboxStore.kt` (`@Component`, not
  `@Repository`: ADR-0063 decision 3).
- **`admin`:** V67; `infra/FeatureFlagStore.kt` (implements `FeatureFlags`; `set` for the console);
  `service/JobsConsole.kt`, `service/FlagConsole.kt`, `service/SupportConsole.kt`;
  `web/JobsController.kt`, `web/FlagsController.kt`, `web/SupportController.kt` (bean name
  `adminSupportController`: `support` has a `SupportController`); templates `jobs.html`,
  `flags.html`, `support.html`, `ticket.html`; `AuditAction` + six values; `Disclosure`'s case.
  `DecisionForm.Wording` gains `JUSTIFICATION` (field `justification`, no code).
- **ArchitectureTest:** `declaredIn` gains `JobConsole`, `JobStatus`, `CloseRun`, `SupportInbox`
  and its types, `StreakRecalculation`, `DaySettlement` and their types; the admin module's allowed
  `gratitude.api` types gain the latter two ports' types. A new rule: only `admin.infra` writes
  `feature_flags` (no other file names the table in an `INSERT`/`UPDATE`).

## 6. Data model: V67 (`modules/admin`)

```sql
CREATE TABLE feature_flags (
    key        text        PRIMARY KEY,
    enabled    boolean     NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by uuid        NOT NULL REFERENCES admin_accounts (id),
    CONSTRAINT feature_flags_key_check CHECK (key IN ('DAILY_REMINDERS', 'ON_THIS_DAY_PUSHES', 'AI_EMBEDDINGS'))
);
-- audit_log_action_check widened by JOB_RUN, STREAK_RECALCULATED, FEATURE_FLAG_CHANGED,
-- SUPPORT_INBOX_VIEWED, SUPPORT_TICKET_READ, SUPPORT_TICKET_CLOSED (V66's pattern).
```
`FeatureFlagCheckTest` holds the `CHECK` to `FeatureFlag.entries`, as `AuditActionCheckTest` holds
the action list. Nothing else migrates: `job_runs`, `shedlock` and the support tables exist.

## 7. Concurrency

| Race | What decides it | Result |
|---|---|---|
| Manual close run vs the timer (any instance) | ShedLock `close-days` row | the second to ask is `BUSY` (manual) or skips (timer); never both |
| Two manual runs | the same row | the second is `BUSY` |
| A run vs a member's write | `CloseDay`'s bond → day lock order, unchanged | as for the timer |
| Rebuild vs the close job's evaluation | `streak_states` row `FOR UPDATE` (ADR-0034 d9) | serialised; the rebuild replays whatever was evaluated before it |
| Two admins switching one flag | `pg_advisory_xact_lock` on the flag | the second sees the first's value: `UNCHANGED` or the reverse change |
| A flag switched during a job's run | none | the run finishes as it began; the next run reads the new value |
| Two operators closing one ticket; a close vs an account deletion | the function's `UPDATE … WHERE status = 'OPEN'` | the second is `NOT_OPEN` |

## 8. Audit rows

| Action | Target | Outcome | Metadata | Justification |
|---|---|---|---|---|
| `JOB_RUN` | none | `SUCCEEDED` (started) / `REFUSED` (busy) | `job`, `result`: `STARTED`/`BUSY` | the note |
| `STREAK_RECALCULATED` | `BOND`, id | `SUCCEEDED` / `REFUSED` (`NO_DAYS`) | `result`, `before`, `after` | the note |
| `FEATURE_FLAG_CHANGED` | `FLAG`, key | `SUCCEEDED` / `REFUSED` (`UNCHANGED`) | `result`, `from`, `to` | the note |
| `SUPPORT_INBOX_VIEWED` | none | `SUCCEEDED` | `{}` | none |
| `SUPPORT_TICKET_READ` | `SUPPORT_TICKET`, id | `SUCCEEDED` / `REFUSED` (`NOT_FOUND`) | `result` when refused | none |
| `SUPPORT_TICKET_CLOSED` | `SUPPORT_TICKET`, id | `SUCCEEDED` / `REFUSED` (`NOT_OPEN`) | `result` | the note |

Never in metadata, a log line or a metric tag: a note, a ticket's words or reference, an address.
Metrics: `moyi.admin.job.run{job,result}`, `moyi.admin.streak.rebuild{result}`,
`moyi.admin.flag.change{flag,result}`, `moyi.admin.support{action,result}`.

## 9. Tests and mutation targets

- **Jobs:** the page lists the eleven with their lock names, a `shedlock` row's start and a
  `job_runs` finish; each of the six newly recording jobs writes its row; a `SUPPORT` session sees no
  run form, and its POST is `403` with no row and no run; a held `close-days` lock (a row with
  `lock_until` in the future) makes the manual run `BUSY`, the closer uncalled, a `REFUSED` row; a run
  writes its row **before** the closer is called (the recording closer reads `audit_log` when it is
  called); an audit write that fails stops the run; the note is refused before anything (`422`, no
  row, closer uncalled); the date check counts a seeded date's open, past-due and uncounted days.
- **Rebuild:** a drifted `streak_states` row comes back to the replay, `CHANGED`, one row with
  before/after; again, `UNCHANGED`; a bond with no evaluated day, `NO_DAYS`, nothing written; no
  outbox event is written by any of them.
- **Flags:** each flag off stops its effect (reminder job asks for nothing; on-this-day asks for
  nothing; the embedding port throws before reaching its delegate, the job skips the sweep) and on
  restores it; no row = default on; `UNCHANGED` is refused and audited; a non-`ADMIN` is `403`; two
  transactions switching one flag, synchronised on `pg_locks`, record `from` correctly;
  `FeatureFlagCheckTest`; the app's `FeatureFlags` is the store.
- **Support:** list (lengths, never words; `LISTED` under `admin:<id>`; one audit row); read (words,
  reference, reply-to; `READ`; audit); unknown id `404`, `REFUSED`, no `READ`; close with a fresh
  window `CLOSED`, words emptied, `CLOSED` log row with the note; stale window asks for a code;
  closed twice `NOT_OPEN`; `MODERATOR` and `SUPPORT` both reach it; words in no log line.
- **Mutation targets (each must turn a test red):** drop the lock around the manual run; write the
  audit row after the run; skip a flag check in either job or in the port; cache a flag; let
  `Disclosure` show an A4 row; let the inbox list carry a word; drop the step-up from the close; drop
  the role check from any POST.

## 10. Operations

- **Smoke** (`scripts/smoke.sh`, a fourth admin section, "admin A4"): an invited `ADMIN` signs in;
  the jobs page lists `close-days` with a last finish; the close job is run from the page (303, its
  line, a `JOB_RUN` row) and a second run while the timer's lock is held is `BUSY`; the date check
  for today answers; a bond's streak is rebuilt (`UNCHANGED`); `DAILY_REMINDERS` is switched off and
  on (two rows, `UNCHANGED` refused), and while it is off a due reminder is not asked for; a ticket
  filed through the API is listed (no words in the list), read (its words), and closed with a code
  (words gone from the row); `SUPPORT` is `403` on the run and on flags; each POST from a refused
  address is the API's own `401`; no note, subject or body in the log.
- **Runbook** (`ops/admin/README.md`): "What exists" gains A4; sections *The jobs page*, *Running
  the close job by hand* (replacing the SSH steps where the console is on), *Feature flags*, *The
  support inbox* (the console replaces `moyi support` once on).
- **ADR-0067**, amending ADR-0034 (the route exists), ADR-0050 (every locked job records its finish),
  ADR-0055 (A4 done; retention owed with a date), ADR-0057 (the console reads tickets through
  `support.api`).

## 11. State map

`admin.json` gains three regions: `admin.flag` (`DEFAULT_ON`, `ON`, `OFF`: switch off, on, the same
value refused), `admin.ticket` (`NO_TICKET`, `OPEN`, `CLOSED`: read, close, close again, a deletion
closing it) and `admin.close-run` (`IDLE`, `LOCK_HELD`: run, busy). Each row cites its test.

## 12. Corpus amendments owed (documents repository)

Doc 07 §2 (`feature_flags` as built, without `rollout_percent` and `payload`); doc 11 §7
(`close-job-did-not-run.md`: the re-run is "run the close job now", not "for a date + timezone");
doc 03 FR-114 (the date is the check, not an argument).
