# Plan: a partner's entry erased before you saw it is not shown (ADR-0032 q2 = ADR-0036 q6)

Branch `feat/unseen-erasure-hidden`, cut from `origin/main` 158bbfe. This is the second
of the six #67 rulings. It has no migration.

**The ruling (2026-10-21, "to be built"):** when a partner's entry was written and then
erased, by a delete or a withdrawal, before the caller was ever shown it, **the partner
sees nothing**, in `GET /today` and in the archive. Today that entry reads as
`Readability.TOMBSTONE_UNSEEN` and is served as `partnerEntry: {authorMemberId, status: REMOVED}`.

## Decisions (controller's; record them in an amendment to ADR-0032 q2 and ADR-0036 q6)

1. **"Nothing" means `partnerEntry: null`, exactly the shape of a day the partner never
   wrote on.** An unseen erasure and "never wrote" must be byte-identical for the caller,
   on every route that renders a day: `/today`, `/days`, `/days/{date}`, search and
   on-this-day.
2. **The rule lives in one place.** Make it a single decision that every day renderer
   inherits, in the service/domain layer (where `DayView`/today's view get their partner
   reading), not repeated per controller. **Do not** remove `Readability.TOMBSTONE_UNSEEN`
   from the domain. It is still the gate's correct answer, and favourites and reactions
   answer it as `NEVER_SHOWN` (404). What changes is only that a day renders it as
   absence. Do not touch the caller's own tombstone or a partner tombstone the caller had
   seen (`TOMBSTONE`). Those are unchanged.
3. **Listing must not betray it.** Check how the archive decides which days to list for a
   member (the SEEN predicate, ADR-0036 decision 1; ADR-0039 for search; on-this-day).
   - **The problem case:** a day listed for the caller *only* because the partner's
     unseen-erased entry exists would now render as a day with nothing on it. That is a
     tell, and it differs from a day the partner never touched.
   - **What the rule should be:** such a day should be listed iff it would be listed had
     the partner never written. If the caller wrote nothing that day, then by whatever
     rule an EMPTY day is listed or not listed today, it is that.
   - **What to test:** twin bonds, one where the partner wrote and erased before reveal,
     one where the partner never wrote, with the same caller actions. They must give
     byte-identical bodies (or the same ETag-free JSON) on all five routes.
   - **If the listing rule cannot be made identical without a schema change or a deeper
     redesign, stop and report.** Do not ship a half-hidden version.
4. **Day `status` and counts.** Confirm (by test, not by reading) that a pre-reveal
   erasure leaves the day's shared `status` and any count (streak, milestones, reaction
   counts, ETag inputs) the same as for a day the partner never wrote on. `BondDay.withoutEntry`
   suggests it does. If anything else differs, that is part of this ruling: report it, and
   fix it if it is within gratitude's read side.
5. **The contract.** If the narrow `REMOVED` partner-entry variant (author + status only)
   can no longer be produced by any route, remove it from the response type and the
   generated contract.
   - This is a breaking change: give the PR the `breaking-api-change` label. There is no
     generated client yet, so it costs nothing now.
   - If some route still serves it, keep it and say which route, and why that route is
     outside the ruling.
6. **Withdrawal is held to the same line** (ADR-0028 decision 8: a withdrawal must be
   indistinguishable from hand deletion). A withdrawn entry that was never revealed to the
   partner is hidden exactly like a deleted one, from the withdrawal's commit, before
   erasure (the reader's `withdrawnAuthors`).

## Tasks

1. Write failing tests first: the twin-bond identity tests for `/today` and the four
   archive routes, covering delete-before-reveal and withdraw-before-reveal (during the
   window before the outbox erases, as the existing withdrawal tests do), plus the
   caller-wrote and caller-did-not-write variants. Turn existing tests that pin the
   `REMOVED` shape for this case; list each one you turn in the report.
2. Implement the one-place rule, plus the listing fix if decision 3 needs one.
3. Contract (decision 5), `docs/state-map/data/` rows for the affected routes and states
   (run `scripts/state-map-check --strict`), smoke probes (a partner deletes before the
   reveal and the caller's `/today` and day show `partnerEntry: null`), Bruno/CLI if they
   print the shape, and the ADR amendments. Bump each ADR's **Amended:** line.
4. Mutations: put the old rendering back and watch the twin tests go red. Break the listing
   fix, if there is one, and watch them go red.

## Verify before "ready"

- Run `./gradlew build --rerun-tasks` and count from the JUnit XML.
- Run `scripts/smoke.sh --attach` on a compose project of your own:
  `docker compose -p moyi-0f`, Postgres on **55433**, its own `MOYI_DB`.
  - **Never stop or recreate a container you did not start**; another session runs its
    own stack on 55434.
  - Clean up only what you started.
- Run `scripts/state-map-check --strict`.

Report measured numbers only.
