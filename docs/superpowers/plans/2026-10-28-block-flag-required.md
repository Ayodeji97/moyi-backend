# Plan — `withdrawEntries` required on `POST /block` (ADR-0035 q1 + q11)

Branch `feat/block-flag-required`, cut from `origin/main` 158bbfe. This is the third of the
six #67 rulings. It is a **breaking** contract change and gets the `breaking-api-change`
label. It has no migration.

**The ruling, 2026-10-21, "to be built":** `withdrawEntries` is required on `POST /block`,
and a request without it is `400`. Today a missing flag withdraws:
`request?.withdrawEntries ?: true` in `BondsController.blockBond`.

## Decisions (the controller's; record them in an ADR-0035 amendment under q1 and q11)

1. **"Without it" means each of these, and every one is the same `400`:** no body, `{}`,
   and `{"withdrawEntries": null}`. That is exactly the set `EndBondRequest`'s KDoc calls
   "absent". Use the same `400` an unreadable body already gets (`MALFORMED_REQUEST`, or
   whatever `EndBondRequestReader`'s refusal maps to today; check this by test). Use the
   same problem shape, with nothing quoted back.
2. **The guard runs first.** A non-member, an unknown bond and a malformed id still get the
   one `404` whatever the body says. Check the flag in the handler, after the membership
   is resolved.
   - **One exception to keep:** Spring deserialises a *present but unreadable* body before
     the handler runs. So a non-member who sends `{"withdrawEntry": false}` already gets a
     `400` before the guard. That is today's behaviour and is out of scope. Do not change
     it. State it in the ADR.
   - Write the cross-tenant test for the *absent* case: a non-member with no body gets
     `404`, not `400`.
3. **`/leave` is unchanged.** Its absent flag still keeps the entries. Do not change
   `EndBondRequest`'s shared reader. A rule about what "absent" means on block lives where
   block is handled, as the KDoc already insists.
4. **The contract says it.** In `openapi.json`, `/block`'s request body becomes required,
   with `withdrawEntries` required and non-null. `/leave`'s stays optional.
   - If that needs a block-only request type or an OpenAPI customiser, choose whichever is
     smaller and keeps one reader.
   - Read the generated diff. It must change only `/block`.
5. **Idempotency and repeats are unchanged.** A repeated block with the flag still behaves
   as before, including "repeating with the flag on withdraws what an earlier one kept".

## Tasks

1. **Tests first**, in `bond`'s web tests next to the existing block tests:
   - Each absent form is a `400` with no withdrawal and no ending: the bond is still open,
     no withdrawal is recorded and no outbox event is written.
   - `true` and `false` still behave as before.
   - The non-member absent case is a `404`.
   - Turn the existing test(s) that pin "a bare block withdraws", and list them.
2. **Implement** in `BondsController.blockBond`, and update its KDoc and
   `EndBondRequest`'s KDoc ("on `/block` an absent flag withdraws" is no longer true).
3. **Contract, state map and tools:**
   - Regenerate the contract.
   - Update `docs/state-map/data/` for `/block`'s new `400` row(s) and run
     `scripts/state-map-check --strict`.
   - In `scripts/smoke.sh`: make every block call send the flag explicitly, and add probes
     that a bare block and `{}` are `400` and that the bond is still open afterwards.
   - Check that Bruno's block request and the CLI (`scripts/moyi`, or wherever it lives)
     always send the flag. The ADR says the CLI already refuses a bare block; confirm it.
   - In the ADR-0035 amendment, bump **Amended:** and record decisions 1–5.
   - If doc 06 in the corpus is now wrong about `/block`, note it as owed. The corpus is
     another repository; do not edit it.
4. **Mutation:** put `?: true` back and watch the absent-case tests go red. Then move the
   check before the guard and watch the cross-tenant `404` test go red.

## Verify before "ready"

1. Run `./gradlew build --rerun-tasks` and count the tests from the XML.
2. Run `scripts/smoke.sh --attach` on your own compose project, `-p moyi-0f`:
   - Check that the ports are free first. Last time 55443 for Postgres and 56390 for
     Valkey were free.
   - Use your own `MOYI_DB` and JDK 25.
   - Never touch a container you did not start, and tear down only `moyi-0f`.
3. Run `scripts/state-map-check --strict`.
4. Add a learning-log line only if something surprised you.
