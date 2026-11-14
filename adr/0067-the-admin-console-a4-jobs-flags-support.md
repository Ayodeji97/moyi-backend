# ADR-0067 — The admin console (A4): the jobs page, three feature flags and the support inbox

**Status:** Accepted, provisional where marked · **Date:** 2026-11-14 · **Deciders:** the owner asked
for A4 to be built without stopping for questions, so every decision the corpus did not already make
is a ruling made by the builder for the owner and **his to overrule**; each says what it costs if
wrong (spec §3). · **Amends:** ADR-0034 (decision 10's `recalculate` route now exists), ADR-0050
(every locked job records its finish in `job_runs`), ADR-0055 (A4 is built; the `audit_log`
retention job stays owed, due by 2028-11-11), ADR-0057 (the console reads and closes tickets through
`support.api`, over the same functions `scripts/moyi support` calls)

## Context

FR-114 (job status and a re-run "for a given date"), FR-115 (feature flags without a deploy), the
support inbox (FR-113's third item, folded into A4 by A2 §15 and ADR-0063 Owed 3) and FR-074's
`recalculate` admin operation had no surface. An operator read `shedlock` and `job_runs` with `psql`,
could not run the close job without a redeploy, could switch nothing at runtime, and answered support
tickets through `scripts/moyi support` under a free-text `MOYI_OPERATOR` tied to no second factor.

The spec (`docs/superpowers/specs/2026-11-14-admin-console-a4-design.md`) holds the detail, the plan
(`docs/superpowers/plans/2026-11-14-admin-console-a4.md`) the build. This record is what was decided,
including what changed while it was built.

## Decision

✱ marks a departure from the corpus's wording.

### The jobs (FR-114, FR-074)

1. **One registry of the jobs, in `scheduling`**, behind an admin-only port
   `scheduling.api.JobConsole`: the **eleven** ShedLock-guarded jobs (lock name, one line of what
   each does, the cron it runs on as configured). The page reads `shedlock` (last start, locked
   until) and `job_runs` (last finish). Every role may view it; the view writes no audit row, since
   it names nobody. `JobConsoleTest` holds the registry to the `@SchedulerLock` names, so a job added
   without a line fails the build. *Cost:* none.
2. **Every locked job records its finish in `job_runs`.** Five already did (media, deletion,
   export); the close, reminder, on-this-day, held-push, embedding and idempotency-key jobs gain the
   one upsert. **The time written is the finish**, read from the clock when the job reports success
   (`JobRuns.succeeded`); before this slice it was the run's start, which the page would have shown
   as "last finished". "Running" is ShedLock's lock held and its `locked_at` after that finish, so a
   lock's `lockAtLeastFor` tail after a finished run is not shown as running. That compares the
   database's clock (`locked_at`, `usingDbTime`) with the application's (the finish): acceptable on one
   host, where Postgres and the JVM read the same kernel clock, and display only; a skew larger than
   a run's length would misreport the tail. A run that throws records no finish, so its tail shows as
   running until the lock lapses. The **outbox poller is not listed**: it holds no lock and its
   health is its own gauges. *Cost:* one upsert per run; the page cannot show the outbox's depth (owed).
3. ✱ **"Re-run the reveal and streak jobs for a given date" is running the close job now.** The
   close job takes no date and keeps no watermark (ADR-0033): every due day, reveal and uncounted
   streak day is its work on every run, so a run "for a date" would be a second statement of which
   days are due. FR-114's date becomes a **date check**: for a date, how many of its days are still
   open, how many of those are past due, and how many are closed but not yet counted. It is read
   through `gratitude.api.DaySettlement` at the close job's own threshold, now written once
   (`CloseElapsedDays.settledBy`). *Cost:* a day with no row is in no count; doc 11 §7 and doc 03
   FR-114 need amending.
4. **Running the close job now is `ADMIN`'s, with a justification and no code.** It does only what
   the timer does within fifteen minutes and is idempotent. It runs under the job's own lock
   (`close-days`, `lockAtMostFor` 14 minutes, `lockAtLeastFor` zero) through ShedLock's
   `LockingTaskExecutor`; when the timer or another instance holds it the answer is `BUSY` and nothing
   runs. It runs with the timer switched off, the case it exists for. *Cost:* the run is synchronous
   in the request; one longer than the proxy's timeout loses its page, not its work. A run by hand
   that outlives `lockAtMostFor` (14 minutes) loses its lock to the timer, and its unlock at the end
   can then release the timer's lock (ShedLock's unlock does not check `locked_by`), letting a third
   run start beside the timer's. Bounded, not prevented: a run stops at a budget of days (`CloseElapsedDays`; a count of
   work, not a time, so not proven inside fourteen minutes), and two runs closing one day meet on its
   bond and day row locks in `CloseDay`, so a day is still closed once. The
   answer while the lock is held says the job "is running now, or ran moments ago": a hand run in
   the timer's thirty-second `lockAtLeastFor` tail is refused as well.
5. ✱ **The run's audit row is written before the run, in its own transaction, while the lock is
   held** (`JobConsole.runCloseNow`'s `beforeRun`): `JOB_RUN`, `SUCCEEDED`,
   `{"job": "close-days", "result": "STARTED"}`. The run is thousands of transactions of its own, so
   it cannot share one with its row; written first, a run that dies has left its record and a row
   that cannot be written stops the run. What it did is in the flash and one INFO line. `BUSY` is a
   `REFUSED` row. *Cost:* the audit log says a run was asked for and started, not what it changed.
6. **Rebuilding a bond's streak (FR-074) is `ADMIN`'s, with a justification and no code**, by bond
   id from the jobs page, through `gratitude.api.StreakRecalculation` (`MANDATORY`, over
   `RecalculateStreak`), so the rebuild and its `STREAK_RECALCULATED` row commit together. `CHANGED`
   and `UNCHANGED` are `SUCCEEDED`; `NO_DAYS` (no evaluated day, or no bond) writes nothing and is
   `REFUSED`. The metadata is the result and the before and after figures (current, longest, freezes
   available, complete days). *Cost:* `SUPPORT`, who takes "my streak broke wrongly", must ask the
   admin.

### The feature flags (FR-115)

7. **Three flags, each pausing an optional send, all on by default:** `DAILY_REMINDERS` (the
   reminder job asks for nothing), `ON_THIS_DAY_PUSHES` (the on-this-day job asks for nothing),
   `AI_EMBEDDINGS` (every call through the `ai` module's `EmbeddingPort` refuses with
   `EmbeddingUnavailableException("switched off")` before anything leaves the process; the search reads
   the flag on the request's thread first and is keywords only, counted `disabled`; the embedding job
   skips its run; nothing stored is erased, unlike ADR-0054's "turning semantic search off"). A flag
   that could switch off writing, sign-in or the reveal would be a way to break the product from a
   browser. **A send that falls due while its flag is off is never sent.** The reminder job looks back
   thirty minutes and the on-this-day job an hour, so each starts its window no earlier than the
   moment its flag was last switched on (`FeatureFlags.onSince`, the row's `updated_at`; no row, no
   bound): otherwise the first run after a pause would send, late and at once, everything that fell
   due in its look-back. *Cost:* those sends are lost; the two reads (on, then since when) are not one
   statement, so a switch made between them is seen by the next run.
8. **A flag with no row is its default; the table starts empty**, so this slice changes nothing
   until a flag is switched. **No cache**: every job run and every embedding call reads the row, so a
   switch is seen next on every instance. *Cost:* one primary-key read per run and per call.
9. ✱ **Doc 07's `rollout_percent` and `payload` are not built, nor is any flag a client reads**
   (that needs `/meta/config` in the contract). Adding them later is additive. *Cost:* a client-side
   switch still needs an app release.
10. **Switching is `ADMIN`'s alone, with a justification and no code** (switching back undoes it).
    `FEATURE_FLAG_CHANGED`, target `FLAG` and its key, `{"result", "from", "to"}`; switching to the
    value it has changes nothing and is `REFUSED`, `UNCHANGED`. Two changes of one flag are serialised
    by `pg_advisory_xact_lock(67, hashtext(key))`, so `from` is always what the change replaced.
    *Cost:* a hijacked `ADMIN` session can pause reminders without a code; the allowlist and the
    two-factor sign-in bound it.
11. **The table is `admin`'s (V67), written only by `admin.infra.FeatureFlagStore`** (an
    `ArchitectureTest` rule, which reads Kotlin sources only: a write from a migration, a PL/pgSQL
    function, a script or a JPA mapping is not seen by it, and none exists); reading goes through `common.core.FeatureFlags` and the closed
    `FeatureFlag` enum, so `scheduling` and `ai` read a flag without depending on `admin`, which now
    depends on `scheduling`. The store is a persistence bean, present with the console off, so the
    media worker and a console-off production read the same table (`FeatureFlagsWiringTest`).
    *Cost:* `common:core` holds three feature names and their defaults (no rule).

### The support inbox (ADR-0057)

12. **The console wraps ADR-0057's three functions, unchanged**, through an admin-only
    `support.api.SupportInbox`; the actor is `admin:<accountId>`, and each look and act is the
    function's `support_ticket_log` row and one `audit_log` row in the same transaction.
    `scripts/moyi support` keeps working. *Cost:* none.
13. **`SUPPORT` and above, for all three.** Listing writes `LISTED` and `SUPPORT_INBOX_VIEWED`;
    reading writes `READ` and `SUPPORT_TICKET_READ` (an unknown id is the `404` page, `REFUSED`, no
    `READ`); the home page shows the open count with no row. **Closing empties the words, so it is
    behind A2's step-up window** (`StepUp.isFresh`), with a note: `CLOSED` or `NOT_OPEN`,
    `SUPPORT_TICKET_CLOSED` `SUCCEEDED` or `REFUSED`. *Cost:* the first close in five minutes needs a
    code.
14. **No A4 row is shown in a member's export** (`Disclosure.shows`, an explicit case). *Cost:* a
    person's export does not say when support read their message. Joins the release review owed for
    withheld rows (A2, A3, A5).

### Owed by decision

15. **The `audit_log` retention job is not built** (ADR-0055 decision 10): the one sanctioned delete
    from a table nothing may alter deserves its own spec and review, and nothing is due before
    **2028-11-11** (two years after V60). The sketch: a `SECURITY DEFINER` function that sets a
    transaction-local `moyi.audit_purge`, which the append-only trigger admits only for rows older
    than two years, run by `scheduling` through an `admin.api` port, with its own `job_runs` row.

### Plan rulings on the spec

16. The close run's audit row reaches the lock through a callback (`beforeRun`), so `scheduling`
    knows nothing of `admin`.
17. A flag-paused job still records its gauge and its `job_runs` finish: a deliberate pause is not a
    stopped job, and the flags page says which are off.

### Rulings while it was built

18. ✱ **Eleven locked jobs, not twelve.** The spec counted twelve; the code has eleven
    `@SchedulerLock`s (the export job holds two). The spec and plan were corrected before any code.
19. **A ShedLock row must never be deleted.** Found by `JobConsoleTest`: ShedLock's storage provider
    remembers the lock names it has inserted and only `UPDATE`s their rows afterwards, so a deleted
    row is a lock that instance can never take again until it restarts, and the job silently stops
    running. A held lock is released by `UPDATE shedlock SET lock_until = timezone('utc', now())`;
    the runbook says so. (Every test here releases, never deletes, but one: `JobConsoleTest`'s "never run" case needs a
    job with no row, and puts the row back in a `finally` while nothing can take that lock.)
20. **The manual run's lock is configured at ShedLock's clock** (`ClockProvider`), as its annotation
    path is, not at the application's `Clock`. The lock's instants are the database's either way
    (`usingDbTime`).
21. **The support close answers as A2's decisions do** (`Decisions.decideGated`, a `302`), not with
    a `303` of its own: the flow is A2's and is not restated. A close of an unknown id closes nothing
    and answers "Already closed" (the function cannot tell unknown from closed).
22. **`AI_EMBEDDINGS` wraps every provider, `NONE` included**, and the port's `space` stays the
    provider's while off: what is stored was made in that space and is searchable again once on.
23. **The navigation's flags link comes from a `@ControllerAdvice`** (`AdminNavigation`'s
    `navAdmin`), so no existing page handler had to gain a model attribute. Display
    only.

## Consequences

**Positive:** FR-114, FR-115 and FR-074's route exist; the close job can be run, and checked, with
the timer off; three sends can be paused in seconds without a deploy; support tickets are read and
closed under a named, two-factor admin with an audit row each.

**Negative:** a paused reminder or push is lost, not deferred. The close run's audit row does not say
what it changed. The jobs page cannot show the outbox poller.

**Neutral:** production is unchanged until the console is switched on, except that every locked job
now writes `job_runs` and V67 creates an empty `feature_flags` (every flag on). No change to the JWT
API or its contract.

## Alternatives considered

- **A per-date close run.** A second statement of which days are due, bound to drift from the job's
  own. Rejected for the date check (decision 3).
- **Flags in `application.yml` with a refresh endpoint.** Per instance, unaudited, and lost on restart.
  Rejected for the table.
- **A cached flag (a few seconds).** Saves one indexed read per run; makes "switched off" mean "off
  soon". Rejected (decision 8).
- **The support inbox in its own console module.** A second login and allowlist for the same
  operator. Rejected; the console wraps the functions as A2 did.

## Owed (the owner decides)

- **The rulings above**, each his to overrule; decisions 3, 7 and 9 change the corpus's wording.
- **Corpus amendments** in the documents repository: doc 07 §2 (`feature_flags` as built), doc 11 §7
  (`close-job-did-not-run.md`: "run the close job now", and never delete a ShedLock row), doc 03
  FR-114 (the date is a check, not an argument).
- **The outbox poller on the jobs page** (decision 2).
- **The `audit_log` retention job**, due by 2028-11-11 (decision 15).
- **A client flag route** (`/meta/config`) if a client-side switch is ever wanted (decision 9).

## Revisit when

A fourth flag is asked for (each must pause an optional send whose "off" the code already has); a
second operator joins (a code for the flags); the close job gains a watermark (then a per-date run
means something).
