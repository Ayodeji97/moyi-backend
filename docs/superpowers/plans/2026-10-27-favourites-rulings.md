# Plan — favourites rulings (ADR-0036 q5 name, q7 `created_at`), V26

Branch `feat/favourites-rulings`, cut from `origin/main` b70f8b4. The first of the six
#67 rulings, put first because it carries **V26**: gratitude-3f's Phase 4 migrations
start at V27, and Flyway refuses an older version after a newer one has run, so this
must reach `main` before any of theirs is deployed.

Two owner rulings of 2026-10-21, both "to be built":

- **ADR-0036 q7:** `entry_favourites.created_at` is dropped, by a new migration.
- **ADR-0036 q5 (the name):** the partner's display name is added to the archive
  response; `bond` owes it. (The rest of q5 — the design gaps written into `states.md` —
  is a corpus change in the Gratitude repo, not this PR.)

## Decisions (controller's; record them in ADR-0036's amendment)

1. **V26 drops the column outright**, no expand/contract. `V26__gratitude_favourites_drop_created_at.sql`,
   one `ALTER TABLE entry_favourites DROP COLUMN created_at;` with a header comment in
   V22's style saying why (the owner's ruling: to an operator it is the time a person was
   reading; nothing returns, orders or filters by it).
   **Rollout cost, stated in the ADR and the PR:** an image older than this one runs
   `INSERT … created_at` and fails once V26 has run. That is (a) the seconds during a
   Kamal deploy when the old container still serves while the new one has migrated —
   a `PUT …/favourite` in that window is a 500 — and (b) any rollback to a pre-V26
   image, where every mark is a 500 until rolled forward. Accepted: one couple, a
   deliberate deploy; the alternative is a second migration later in a sequence the
   Phase 4 work is now also taking numbers from.
2. **The name sits on each day, as `partnerDisplayName: String?`**, not on the page.
   `GET /days/{date}` is "byte for byte the feed's element" (decision in ADR-0036); a
   page-level field would break that, and the day route would need its own. It is the
   same value on every day of a bond, which costs a few bytes per item and keeps one shape.
   - `null` only when the bond has never had a second member (the creator's pre-join
     archive). Never null for a bond that had two members, including after the partner
     left or blocked: the archive still shows what they wrote (ADR-0028).
   - An account identity no longer knows becomes `"Former member"` — the same placeholder
     `BondViews` uses, from **one** place (move the constant somewhere both can use, or
     have the adapter reuse `BondViews`' rule; do not write the string twice — CLAUDE.md
     "a rule lives in one place").
   - It is read at request time from `identity.api.UserDirectory`, as `BondViews` does
     (BondViews' KDoc: a changed name changes everywhere at once). So a rename moves the
     archive's `ETag` — correct, since the tag is the body.
3. **`bond` owes it through `BondAccess`**, a new method
   `fun partnerDisplayNameOf(membership: BondMembership): String?`. It takes the
   unforgeable `BondMembership` (ADR-0026), so it cannot be asked for a bond the caller is
   not in, and it runs no second guard. "Partner" = the bond's other member row, whether
   or not they have left. Implemented in `BondAccessAdapter`. **No new import of
   `identity` into `gratitude`**: the name comes through `bond`, as the ruling says.
4. **`GET /today` is unchanged.** The ruling names the archive. `DayResponse` gains the
   field; `TodayResponse` does not. Say so in the ADR amendment as a deliberate edge.
5. **One lookup per request**, not per day: `GetDays.page` / `GetDays.day` (or the
   controller) resolve the name once and every `DayView`/`DayResponse` of the response
   carries it. Prove no N+1 by test or by reading the call count in a test double.

## Tasks

1. **V26 + `Favourites.mark`** — migration; `Favourites.mark` stops writing `created_at`
   (and whatever clock/`now` plumbing existed only for it — keep what the lock timeout or
   anything else still needs). Update KDoc/comments that mention the column (Favourites.kt
   ~line 116, V22 header is merged and stays as is). `FlywayMigrationTest` (or a new test)
   proves the column is gone after migration. Check `docs/state-map/data/` and tests for
   any reference to the column.
2. **`BondAccess.partnerDisplayNameOf`** — interface KDoc in the file's style; adapter
   implementation; the `"Former member"` single source. Tests in `bond`: two-member bond →
   partner's name; partner left/blocked → still their name; creator alone → null; identity
   has no such user → "Former member". The cross-module architecture tests must stay green.
3. **Archive response** — `DayView`/`DayResponse.partnerDisplayName`; one lookup per
   request; `DayResponse`'s KDoc ("Nothing here is about the other person beyond that")
   amended to name the one addition and why it is not a disclosure (the bond's own
   `GET /bonds/{id}` already gives both members their names). Tests in `gratitude`: feed
   and day route both carry it and are still byte-identical for the same day; null for a
   creator alone; present after the partner left; a rename moves the `ETag`. Mutation:
   remove the field mapping, watch the tests go red, restore.
4. **Contract, docs, tools** — regenerate `contracts/openapi.json` (read the diff: one
   added nullable property on the day schema). Not breaking (a new optional field), so no
   label. ADR-0036: an amendment under q5 and q7 saying built, with decisions 1–5 above
   and the rollout cost; bump its **Amended:** line. `docs/state-map` if it lists
   archive fields. `scripts/smoke.sh`: assert `partnerDisplayName` on a day in the
   archive section, and that `entry_favourites` has no `created_at`
   (`information_schema.columns`), using the script's existing `sql` helper style.
   Bruno/CLI only if they print archive fields. A learning-log entry only if something
   surprising happened.

## Verify before "ready"

`./gradlew build --rerun-tasks` (count tests from the XML), then `scripts/smoke.sh`
(5432 may be held by another project: compose Postgres on 55432 and `MOYI_DB`, as before).
Report numbers measured, not remembered.
