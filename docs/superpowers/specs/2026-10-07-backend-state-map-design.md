# The backend state map — design

**Date:** 2026-10-07
**Status:** awaiting the owner's review
**Describes:** `main @ 174b474` (34 endpoints, 34 error codes)

## 1. Purpose

Daniel asked for a visual map of the backend: every action, every possible
next action, and every error with the reason for it.

The map is for **learning the system as a state machine**. It is organised
by state, not by screen and not by endpoint. A per-endpoint drill-down is
included. A screen-by-screen map for the KMP client is a different
document and is out of scope.

Success means:

- For any state, a reader can see what is allowed there, what is refused
  there and why, and what can happen without them acting.
- For any endpoint, a reader can see every response it can return, the
  cause of each, and how to trigger it.
- No endpoint, documented status or error code is missing, and a script
  proves it.
- Every fact says where it came from.

## 2. What this is called, and what it is not

In established practice this artefact is a **state machine model** with a
**state transition table**, plus a small set of **scenarios**. It is not a
"user journey map" in the UX sense (stages, touchpoints, feelings); doc 02
already holds the journeys J1–J5, and this map traces them rather than
replacing them.

| Part of the map | Established practice it follows |
|---|---|
| Three diagrams, one per lifecycle | Statechart orthogonal regions: independent machines side by side, which avoids one diagram with every combination of states |
| Insets (session, invite, proposal, entry) | Composite states and substates |
| "Requires" tags on a transition | Guard conditions |
| The grid of every action against every state | State transition table, including invalid transitions |
| Journeys J1–J5 traced across the diagrams | Runtime scenarios (arc42 section 6: a representative few, not all) |
| Sequence diagram per journey | The standard notation for several parties interacting in order, which a state diagram shows poorly |
| One data table that generates every view | Diagrams as code, required by doc 18 §7 |
| Endpoint cards | API reference with an error catalogue |

Two parts are this project's own convention and are labelled as such on
the page:

- **Refusals drawn as red self-loops.** Standard UML does not draw an
  event that a state does not handle. The state transition table is the
  conventional home for refusals. The loops are therefore off by default
  and switched on with a toggle; the grid is the complete record.
- **The clickable page.** Teams normally stop at diagrams in the
  repository. The page is an extra layer for learning, generated from the
  same data.

## 3. The three machines

States are read from the code, not invented.

### 3.1 Account (12 endpoints)

- Main states: `anonymous`, `PENDING_VERIFICATION`, `ACTIVE (signed out)`,
  `signed in`.
- Drawn grey, with no endpoint reaching them yet: `SUSPENDED`,
  `PENDING_DELETION`, `DELETED` (`UserStatus`).
- Substates, session: access token valid, expired, refreshed; and the
  reuse trap (`TOKEN_REUSE_DETECTED`), which ends every session.
- Substates, password reset: requested, token used, token expired.

### 3.2 Bond (17 endpoints)

- Main states: `no bond`, `PENDING_MEMBER`, `ACTIVE`, `PENDING_DELETION`,
  `ARCHIVED`, `DELETED` (`BondStatus`).
- Substates, invite: created, accepted, revoked, not usable. Looking an invite
  up is an action that leaves it created.
- Substates, proposal: none, proposed, confirmed, withdrawn. One mechanism
  serves the timezone change and the deletion (`ProposalKind`, ADR-0030).

### 3.3 Day (5 endpoints, and most of the system's own transitions)

- `NOT_OPENED`: the day has no row yet. Nothing is stored for a day until its first
  entry is written, or until the close job records it after it has ended.
- Not closed: `OPEN`, `PARTIAL`, `PENDING_REVEAL`.
- Closed: `REVEALED`, `SOLO`, `EMPTY`, `FROZEN`.
- `SUSPENDED`, drawn apart. It has two meanings (waiting for the second
  member; a called-off deletion countdown) and the map shows both.
- Substates, your entry: none, `SUBMITTED`, `REVEALED`, `DELETED`.
- What you see of your partner's entry: `LOCKED`, the text, `REMOVED` (deleted before
  the reveal), or `DELETED` (deleted after it, with its id and times).

### 3.4 Guards across machines

A transition in one machine may require a state in another. Writing an
entry requires `signed in`, a bond in `PENDING_MEMBER` or `ACTIVE`, and a
day that is not closed. These are guards on the transition, shown as tags.
No arrow crosses from one diagram to another.

## 4. Notation

| Mark | Meaning |
|---|---|
| Solid arrow | the reader's own action succeeding |
| Dashed arrow | the partner's action |
| Dotted arrow with a clock | the system or time: reveal, close job, token expiry, countdown |
| Red self-loop (toggle, off by default) | refused in this state; the reader stays |
| Grey | designed and not built (C5b, C5c, C6, Phase 4) |

Errors are of two kinds:

- **State errors** depend on the current state (`EMAIL_NOT_VERIFIED`,
  `BOND_FULL`, `ENTRY_ALREADY_EXISTS`, `DAY_CLOSED`, `PROPOSAL_PENDING`
  and the like). They appear in the state panel, the grid and the loops.
- **Everywhere errors** can follow any call: 400, 401, 403, 415, 422,
  429, 500. They are explained once in a strip above the diagrams and
  listed on each endpoint card. They are never drawn as arrows.

## 5. Views

All five are drawn from the data in section 6.

1. **Map.** The three diagrams stacked. Clicking a state opens a panel
   with three lists: *you can* (action, resulting state), *refused here*
   (status, code, plain reason, rule or ADR), *happens without you*.
2. **Endpoint card.** Opened by clicking an arrow. Method and path; what
   it needs (auth, headers such as `Idempotency-Key` and `If-Match`, the
   guard in each machine); every response with its cause; a curl line.
3. **Grid.** Actions down the side, states across the top, one grid per
   machine. Each cell is a success with its next state, an error code, or
   "not reachable". An empty cell is a defect in the data.
4. **Journeys.** J1–J5 from doc 02. Choosing one highlights its path
   across the three diagrams as numbered steps and shows the same steps
   as a sequence diagram (you, partner, API, scheduled job).
5. **Search.** An error code or path highlights every place it occurs.

## 6. The data

Six hand-written files under `docs/state-map/data/`:

| File | Holds |
|---|---|
| `model.json` | The machines, regions and states; the events; the errors that apply everywhere; the `pending` list; the stamp |
| `endpoints.json` | One card per endpoint: summary, auth, headers, request errors, a curl line |
| `day.json`, `bond.json`, `account.json` | The rows of each machine |
| `journeys.json` | Each journey as an ordered list of row references |

**The row, field by field** (every key is required; use `""`, `[]` or `null`, never omit):

| Field | Content |
|---|---|
| `id` | unique, kebab-case: `day-open-write` |
| `region` | a region id from `model.json`: `day`, `day.entry`, `bond`, ... |
| `from` | a built state of that region |
| `action` | an endpoint id exactly as the contract spells it (`POST /api/v1/bonds/{bondId}/entries`) or an event id (`event:day-ends`) |
| `actor` | `you`, `partner` or `system`. Your actions are endpoints; the system's are events |
| `when` | the condition that picks this row when a cell has more than one; else `""` |
| `guards` | `[{"region": "bond", "states": ["PENDING_MEMBER", "ACTIVE"]}]`: states required elsewhere |
| `outcome` | `ok`, `refused` or `unreachable` |
| `to` | resulting state; equal to `from` unless the outcome is `ok` |
| `status`, `code` | HTTP status and error code; `null` where there is none |
| `reason` | one plain sentence |
| `rule` | `BR-2`, `FR-062`, `ADR-0031 §4`; `""` if none |
| `evidence` | `smoke` (asserted by `scripts/smoke.sh`), `test` (asserted by an automated test), `hand` (seen with curl, by a person), `never-run` (read from code only) |
| `evidenceRef` | smoke: text of the probe's label. test: `ClassName#text of the test name`. hand: who and when. never-run: `""` |
| `codeRef` | `path/from/repo/root.kt#text found in that file`. Text, not a line number, so it survives edits above it |

### Generated from the data

- `docs/state-map/account.md`, `bond.md`, `day.md`: Mermaid
  `stateDiagram-v2`, which GitHub renders. These satisfy doc 18 §7 and
  are the artefact a reviewer or interviewer sees in the repository.
- `docs/state-map/journeys.md`: Mermaid `sequenceDiagram` per journey.
- The published page.

Generated files are committed. The check in section 7 fails if they are
stale.

## 7. Keeping it true

A script, `scripts/state-map-check`, run in CI:

- Every path and method in `contracts/openapi.json` has at least one row.
- Every status the contract documents for an endpoint has a row or is an
  everywhere error.
- Every value of `ErrorCode` appears in at least one row.
- Every grid cell is filled.
- The generated Mermaid matches the data.

Every row cites the code it was read from (`codeRef`) and says what executed it (`evidence`):
`smoke`, `test`, `hand` or `never-run`. The check confirms each citation still resolves.
A `pending` list in `model.json` names what is not yet mapped; the check fails when an
entry on it has been mapped, and in strict mode when the list is not empty.

The grid is also a test-design artefact: in state transition testing,
covering every cell, valid and invalid, is the strongest coverage level.
Cells marked `never-run` are the smoke script's missing probes.

## 8. Where it lives and how it ships

- Data, generator, check and generated Mermaid: `docs/state-map/` and
  `scripts/`, in `moyi-backend`, through a pull request.
- The page: published as a fourth Moyi page beside the Ledger, Field
  Guide and Smoke Run Sheet, in the Rosewood tokens, stamped with the
  commit it describes. Re-stamped when a slice merges.

Built in this order, each a reviewable step:

1. Data format, generator, check, with the **Day** machine only. Day has
   the fewest endpoints and the most system transitions, so it tests the
   design hardest.
2. Bond machine.
3. Account machine.
4. Journeys J1–J5.
5. The published page.

Steps 1 to 4 are one implementation plan. Step 5 has
its own, written once the data exists.

## 9. Out of scope

- Screen or UI guidance for the client.
- A walkable simulator.
- Database, locking and transaction detail beyond a rule or ADR link.
- Unbuilt endpoints as anything but grey placeholders.

## 10. What was checked, and what was not

Checked against sources on 2026-10-07:

- UML state machines: guards, composite states, orthogonal regions, and
  that an event with no enabled transition is discarded.
- State transition testing: the state table includes invalid transitions,
  and "all transitions" coverage exercises them. Read from secondary
  summaries of ISTQB CTFL v4.0 §4.2.4; the syllabus itself was not
  retrieved.
- Stripe documents PaymentIntents as a lifecycle, one row per state,
  saying what moves it on. This is the closest public precedent for
  explaining an API by its states.
- arc42 section 6 asks for a representative few runtime scenarios and
  accepts sequence diagrams, numbered steps and state machines.
- Diagrams as code is common practice and is this project's own rule
  (doc 18 §7).

Not established: that teams commonly build a clickable page of this kind.
Stately's visualiser for XState is the nearest tool. The page is a
learning aid on top of the standard artefacts, not itself a standard.
