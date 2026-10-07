# The backend state map — implementation plan (data, checks and diagrams)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every action the backend accepts, in every state it can be in, recorded as data
that a script proves complete and that generates the state diagrams, grids and journeys in
the repository.

**Architecture:** Hand-written JSON under `docs/state-map/data/` is the only source. A small
Python package, `scripts/statemap/`, loads it, checks it against `contracts/openapi.json`,
`ErrorCode.kt`, the smoke script and the test sources, and generates Mermaid and Markdown
into `docs/state-map/`. A CI workflow runs the checks. The data is filled one machine at a
time, Day first; a `pending` list names what is not yet mapped and must shrink to nothing.

**Tech Stack:** Python 3 (standard library only, 3.10 or later), `unittest`, Mermaid
(`stateDiagram-v2`, `sequenceDiagram`), GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-10-07-backend-state-map-design.md`

**This plan covers spec §8 steps 1 to 4.** Step 5, the published page, gets its own plan
once the data exists, because its layout depends on how many arrows each pair of states
really has.

## Global Constraints

- Describes `main @ 174b474`: 34 endpoints, 34 error codes. Re-count before writing either
  number anywhere.
- States are read from the code, not invented. Where a state in `model.json` and an enum
  disagree, the enum wins; change `model.json` and say so in the pull request.
- Every row says where it came from. A row is written only after opening the code it cites.
  This plan's seed tables are a starting point, not a source.
- Refusals are not drawn on the diagrams. They are in the grid and the tables (spec §2).
- Everywhere errors (400, 401, 403, 405, 415, 422, 429, 500) are never rows.
- Python uses the standard library only. No new dependency enters the repository.
- The repository is public. No token, password, invite code or entry text appears in any
  data file; curl lines use `$API`, `$TOKEN`, `$BOND` and the like.
- Every change goes through a pull request; nothing is committed to `main`. The owner
  merges.
- Commits follow Conventional Commits, subject at most 88 characters.
- `contracts/openapi.json` is generated. When the map and the contract disagree, stop and
  report it; do not edit the contract and do not bend a row to fit.

## Where this plan refines the spec

Task 1 amends the spec to match. The owner approves these by approving this plan.

| Spec | This plan | Why |
|---|---|---|
| One file, `transitions.json` | `data/model.json`, `endpoints.json`, `day.json`, `bond.json`, `account.json`, `journeys.json` | A few hundred rows in one file cannot be reviewed one machine at a time |
| `machine` field | `region` field; a machine has a main region and its insets | The grid is per region, and an inset needs its own |
| `source`: smoke, test, code, never-run | `evidence`: smoke, test, hand, never-run, with `evidenceRef`; and `codeRef` on every row | "Read from code" and "never run" were the same thing; every row cites code, and evidence says what executed it |
| Invite substate "looked up" | dropped | A lookup does not change the invite; it is an action that leaves it `CREATED` |
| Not mentioned | `pending` list in `model.json` | Lets the check pass while one machine is done and two are not, and fails if the list goes stale |
| `scripts/state-map-check` | that, and `scripts/state-map-generate` | Checking must not write files |

## Review Focus

Each line names its test and the task that owns it.

1. **A row cites code that has since changed.** A reader expects the citation to resolve.
   `test_check.py::RefTests` (Task 2): a `codeRef` whose text is no longer in the file fails.
2. **One cell with two outcomes, only one recorded** (for example writing into `PARTIAL`
   when you wrote, and when your partner wrote). `test_model.py::test_two_rows_in_one_cell_need_distinct_when`
   (Task 1).
3. **A label that breaks Mermaid** (`{bondId}`, a colon, a quote).
   `test_mermaid.py::test_clean_removes_what_breaks_mermaid` (Task 3).
4. **The contract and the map disagree in either direction**: a documented status with no
   row, or a row with a status the contract does not document.
   `test_check.py::ContractTests` (Task 2).
5. **A later slice adds an endpoint or an error code.** The check must fail and say what to
   do. `test_check.py::test_an_unmapped_endpoint_is_a_problem` and
   `test_an_unmapped_code_is_a_problem` (Task 2).

## File structure

| File | Responsibility |
|---|---|
| `scripts/statemap/model.py` | Load the data; say whether it is well formed |
| `scripts/statemap/check.py` | Compare the data with the contract, the error codes and the evidence it cites |
| `scripts/statemap/mermaid.py` | Turn the data into Mermaid and Markdown text |
| `scripts/statemap/generate.py` | Decide which files exist; write them; say which are stale |
| `scripts/statemap/cli.py`, `__main__.py` | The two commands |
| `scripts/state-map-check`, `scripts/state-map-generate` | Wrappers a person types |
| `scripts/statemap/tests/` | `fixture.py` and one test file per module |
| `docs/state-map/data/*.json` | The source, hand-written |
| `docs/state-map/{day,bond,account,journeys}.md` | Generated; committed |
| `docs/state-map/README.md` | How to read and how to change the map |
| `.github/workflows/state-map.yml` | Runs the tests and the check |

---

### Task 1: The data format and its loader

**Files:**
- Create: `scripts/statemap/__init__.py` (empty)
- Create: `scripts/statemap/model.py`
- Create: `scripts/statemap/tests/fixture.py`
- Create: `scripts/statemap/tests/test_model.py`
- Create: `docs/state-map/data/model.json`
- Create: `docs/state-map/data/endpoints.json`
- Modify: `docs/superpowers/specs/2026-10-07-backend-state-map-design.md` (§3.2, §6, §7, §8)
- Modify: `.gitignore` (add `__pycache__/`)

**Interfaces:**
- Produces: `statemap.model.Model` (frozen dataclass: `stamp`, `machines`, `events`,
  `everywhere`, `pending`, `endpoints`, `rows`, `journeys`; methods `regions() -> dict`,
  `states(region_id, built_only=False) -> list[str]`, `label(action) -> str`);
  `load(data_dir: Path) -> Model`; `validate(model) -> list[str]`; constants `ACTORS`,
  `OUTCOMES`, `EVIDENCE`, `ROW_FILES`.
- Produces for tests: `fixture.tiny(rows=None, **over) -> Model`, `fixture.row(**over) -> dict`.

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

- [ ] **Step 1: Write the fixture**

**Create `scripts/statemap/tests/fixture.py`:**

```python
"""A two-state lamp: the smallest model every check can be shown against."""
from statemap.model import Model


def row(**over):
    base = {
        "id": "lamp-off-press",
        "region": "lamp",
        "from": "OFF",
        "action": "POST /api/v1/lamp",
        "actor": "you",
        "when": "",
        "guards": [],
        "outcome": "ok",
        "to": "ON",
        "status": 200,
        "code": None,
        "reason": "Pressing turns it on.",
        "rule": "BR-1",
        "evidence": "never-run",
        "evidenceRef": "",
        "codeRef": "src/Lamp.kt#fun press(",
    }
    return {**base, **over}


def refusal(**over):
    return row(
        **{
            "id": "lamp-on-press",
            "from": "ON",
            "outcome": "refused",
            "to": "ON",
            "status": 409,
            "code": "LAMP_ALREADY_ON",
            "reason": "It is already on.",
            **over,
        }
    )


def tiny(rows=None, **over):
    fields = {
        "stamp": "abc1234",
        "machines": [
            {
                "id": "lamp",
                "label": "Lamp",
                "regions": [
                    {
                        "id": "lamp",
                        "label": "The lamp",
                        "states": [
                            {"id": "OFF", "label": "OFF"},
                            {"id": "ON", "label": "ON"},
                            {"id": "BROKEN", "label": "BROKEN", "built": False},
                        ],
                    }
                ],
            }
        ],
        "events": [{"id": "event:timer", "label": "the timer runs out", "actor": "system"}],
        "everywhere": [
            {
                "status": 401,
                "code": "UNAUTHENTICATED",
                "reason": "No valid token.",
                "codeRef": "src/Lamp.kt#fun press(",
            }
        ],
        "pending": {"endpoints": [], "codes": []},
        "endpoints": [
            {
                "id": "POST /api/v1/lamp",
                "summary": "press the switch",
                "auth": "bearer",
                "headers": [],
                "requestErrors": [],
                "curl": 'curl -X POST "$API/lamp" -H "Authorization: Bearer $TOKEN"',
            }
        ],
        "rows": [row(), refusal()] if rows is None else rows,
        "journeys": [],
    }
    return Model(**{**fields, **over})
```

- [ ] **Step 2: Write the failing tests**

**Create `scripts/statemap/tests/test_model.py`:**

```python
import json
import tempfile
import unittest
from pathlib import Path

from fixture import refusal, row, tiny
from statemap.model import load, validate


class ValidateTests(unittest.TestCase):
    def problems(self, model):
        return "\n".join(validate(model))

    def test_the_fixture_is_well_formed(self):
        self.assertEqual(validate(tiny()), [])

    def test_a_missing_key_is_named(self):
        broken = row()
        del broken["rule"]
        self.assertIn("row lamp-off-press: missing rule", self.problems(tiny([broken])))

    def test_only_an_ok_row_may_change_state(self):
        self.assertIn("only an 'ok' row may change state", self.problems(tiny([row(), refusal(to="OFF")])))

    def test_an_unknown_state_is_refused(self):
        self.assertIn("'to' is 'DIM'", self.problems(tiny([row(to="DIM")])))

    def test_a_row_may_not_touch_an_unbuilt_state(self):
        self.assertIn("'to' is 'BROKEN'", self.problems(tiny([row(to="BROKEN")])))

    def test_the_system_acts_through_events_only(self):
        self.assertIn("the system acts through events", self.problems(tiny([row(actor="system")])))

    def test_your_own_action_is_an_endpoint(self):
        mine = row(action="event:timer", status=None)
        self.assertIn("your own actions are endpoints", self.problems(tiny([mine])))

    def test_an_event_has_no_status(self):
        event = row(id="lamp-on-timer", **{"from": "ON"}, to="OFF", action="event:timer", actor="system")
        self.assertIn("an event has no HTTP status", self.problems(tiny([event])))

    def test_a_refusal_needs_a_status_and_a_code(self):
        self.assertIn("a refusal needs", self.problems(tiny([row(), refusal(code=None)])))

    def test_an_unknown_action_is_refused(self):
        self.assertIn("neither an endpoint card nor an event", self.problems(tiny([row(action="GET /api/v1/nope")])))

    def test_executed_evidence_needs_a_reference(self):
        self.assertIn("says what ran it", self.problems(tiny([row(evidence="smoke")])))

    def test_two_rows_in_one_cell_need_distinct_when(self):
        twice = [row(), row(id="lamp-off-press-again")]
        self.assertIn("more than one row and no 'when'", self.problems(tiny(twice)))
        told_apart = [row(when="the bulb is new"), row(id="lamp-off-press-again", when="the bulb is old")]
        self.assertEqual(validate(tiny(told_apart)), [])

    def test_a_guard_names_another_region(self):
        guarded = row(guards=[{"region": "lamp", "states": ["ON"]}])
        self.assertIn("guard names region 'lamp'", self.problems(tiny([guarded])))

    def test_a_curl_line_may_not_carry_a_token(self):
        model = tiny()
        fake_token = "ey" + "JhbGciOi"
        model.endpoints[0]["curl"] = "curl -H 'Authorization: Bearer " + fake_token + "'"
        self.assertIn("looks like a real token", self.problems(model))

    def test_a_journey_step_must_be_a_row(self):
        journey = {"id": "J1", "title": "On", "steps": [{"row": "nope", "note": ""}]}
        self.assertIn("journey J1: step 1 names no row 'nope'", self.problems(tiny(journeys=[journey])))

    def test_two_rows_may_not_share_an_id(self):
        self.assertIn("two rows share the id", self.problems(tiny([row(when="a"), row(when="b")])))


class LoadTests(unittest.TestCase):
    def test_rows_come_from_every_machine_file_that_exists(self):
        model = tiny()
        with tempfile.TemporaryDirectory() as tmp:
            data = Path(tmp)
            base = {k: getattr(model, k) for k in ("stamp", "machines", "events", "everywhere", "pending")}
            (data / "model.json").write_text(json.dumps(base))
            (data / "endpoints.json").write_text(json.dumps(model.endpoints))
            (data / "day.json").write_text(json.dumps(model.rows))
            loaded = load(data)
        self.assertEqual(loaded.rows, model.rows)
        self.assertEqual(loaded.journeys, [])
        self.assertEqual(validate(loaded), [])


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 3: Run the tests and watch them fail**

Run: `touch scripts/statemap/__init__.py && PYTHONPATH=scripts python3 -m unittest discover -s scripts/statemap/tests`
Expected: `ModuleNotFoundError: No module named 'statemap.model'`

- [ ] **Step 4: Write the loader and the validator**

**Create `scripts/statemap/model.py`:**

```python
"""The state map's data: loading it, and saying whether it is well formed.

Every view is generated from this data (spec §6), so a malformed row is
caught here, before any check that reads meaning into it.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path

ACTORS = ("you", "partner", "system")
OUTCOMES = ("ok", "refused", "unreachable")
EVIDENCE = ("smoke", "test", "hand", "never-run")
ROW_FILES = ("day.json", "bond.json", "account.json")
ROW_KEYS = (
    "id", "region", "from", "action", "actor", "when", "guards", "outcome",
    "to", "status", "code", "reason", "rule", "evidence", "evidenceRef", "codeRef",
)
ENDPOINT_KEYS = ("id", "summary", "auth", "headers", "requestErrors", "curl")
REQUEST_ERROR_KEYS = ("status", "code", "reason", "evidence", "evidenceRef", "codeRef")
STATE_ID = re.compile(r"^[A-Za-z][A-Za-z0-9_]*$")
ENDPOINT_ID = re.compile(r"^(GET|POST|PUT|PATCH|DELETE) /")


@dataclass(frozen=True)
class Model:
    stamp: str
    machines: list
    events: list
    everywhere: list
    pending: dict
    endpoints: list
    rows: list
    journeys: list

    def regions(self) -> dict:
        return {r["id"]: {**r, "machine": m["id"]} for m in self.machines for r in m["regions"]}

    def states(self, region_id: str, built_only: bool = False) -> list:
        states = self.regions()[region_id]["states"]
        return [s["id"] for s in states if s.get("built", True) or not built_only]

    def label(self, action: str) -> str:
        for endpoint in self.endpoints:
            if endpoint["id"] == action:
                return endpoint["summary"]
        for event in self.events:
            if event["id"] == action:
                return event["label"]
        return action


def load(data_dir: Path) -> Model:
    def read(name, default=None):
        path = data_dir / name
        if not path.exists():
            if default is None:
                raise FileNotFoundError(path)
            return default
        return json.loads(path.read_text(encoding="utf-8"))

    base = read("model.json")
    rows = [row for name in ROW_FILES for row in read(name, [])]
    return Model(
        stamp=base["stamp"],
        machines=base["machines"],
        events=base["events"],
        everywhere=base["everywhere"],
        pending=base["pending"],
        endpoints=read("endpoints.json", []),
        rows=rows,
        journeys=read("journeys.json", []),
    )


def validate(model: Model) -> list:
    problems = []
    regions = model.regions()
    if len(regions) != sum(len(m["regions"]) for m in model.machines):
        problems.append("two regions share an id")
    for region_id, region in regions.items():
        ids = [s["id"] for s in region["states"]]
        if len(set(ids)) != len(ids):
            problems.append(f"{region_id}: two states share an id")
        problems += [f"{region_id}: state id '{i}' must match {STATE_ID.pattern}" for i in ids if not STATE_ID.match(i)]

    endpoint_ids = [e.get("id") for e in model.endpoints]
    event_ids = [e["id"] for e in model.events]
    row_ids = [r.get("id") for r in model.rows]
    for name, ids in (("endpoints", endpoint_ids), ("events", event_ids), ("rows", row_ids)):
        for repeated in sorted({str(i) for i in ids if ids.count(i) > 1}):
            problems.append(f"two {name} share the id '{repeated}'")

    for endpoint in model.endpoints:
        problems += _endpoint(endpoint)
    for row in model.rows:
        problems += _row(row, model, regions, set(endpoint_ids), set(event_ids))
    problems += _cells(model)
    problems += _journeys(model)
    return problems


def _endpoint(endpoint: dict) -> list:
    name = endpoint.get("id", "<no id>")
    missing = [k for k in ENDPOINT_KEYS if k not in endpoint]
    if missing:
        return [f"endpoint {name}: missing {', '.join(missing)}"]
    problems = []
    if not ENDPOINT_ID.match(name):
        problems.append(f"endpoint {name}: the id is the method, a space, then the path")
    if endpoint["auth"] not in ("none", "bearer"):
        problems.append(f"endpoint {name}: auth is 'none' or 'bearer'")
    if "eyJ" in endpoint["curl"]:
        problems.append(f"endpoint {name}: the curl line looks like a real token; use $TOKEN")
    for error in endpoint["requestErrors"]:
        missing = [k for k in REQUEST_ERROR_KEYS if k not in error]
        if missing:
            problems.append(f"endpoint {name}: a request error is missing {', '.join(missing)}")
        elif error["evidence"] not in EVIDENCE:
            problems.append(f"endpoint {name}: evidence '{error['evidence']}' is not one of {', '.join(EVIDENCE)}")
    return problems


def _row(row: dict, model: Model, regions: dict, endpoint_ids: set, event_ids: set) -> list:
    name = row.get("id", "<no id>")
    missing = [k for k in ROW_KEYS if k not in row]
    if missing:
        return [f"row {name}: missing {', '.join(missing)}"]
    if row["region"] not in regions:
        return [f"row {name}: unknown region '{row['region']}'"]

    problems = []

    def bad(message):
        problems.append(f"row {name}: {message}")

    built = model.states(row["region"], built_only=True)
    for key in ("from", "to"):
        if row[key] not in built:
            bad(f"'{key}' is '{row[key]}', which is not a built state of {row['region']}")
    for key, allowed in (("actor", ACTORS), ("outcome", OUTCOMES), ("evidence", EVIDENCE)):
        if row[key] not in allowed:
            bad(f"{key} '{row[key]}' is not one of {', '.join(allowed)}")

    is_event = row["action"] in event_ids
    if not is_event and row["action"] not in endpoint_ids:
        bad(f"action '{row['action']}' is neither an endpoint card nor an event")
    if row["actor"] == "system" and not is_event:
        bad("the system acts through events, not endpoints")
    if row["actor"] == "you" and is_event:
        bad("your own actions are endpoints, not events")
    if is_event and row["status"] is not None:
        bad("an event has no HTTP status")
    if not row["reason"].strip():
        bad("no reason")

    outcome = row["outcome"]
    if outcome != "ok" and row["to"] != row["from"]:
        bad("only an 'ok' row may change state")
    if outcome == "ok" and row["code"] is not None:
        bad("a success carries no error code")
    if outcome == "ok" and not is_event and not _between(row["status"], 200, 299):
        bad("a successful request needs a 2xx status")
    if outcome == "refused" and not (_between(row["status"], 400, 599) and row["code"]):
        bad("a refusal needs a 4xx or 5xx status and an error code")
    if outcome == "unreachable" and (row["status"] is not None or row["code"] is not None):
        bad("an unreachable cell has no status and no code")
    if outcome != "unreachable":
        if not row["codeRef"].strip():
            bad("no codeRef")
        if row["evidence"] != "never-run" and not row["evidenceRef"].strip():
            bad(f"evidence is '{row['evidence']}' but no evidenceRef says what ran it")

    for guard in row["guards"]:
        region = guard.get("region")
        if region not in regions or region == row["region"]:
            bad(f"guard names region '{region}', which must be another region of the map")
            continue
        unknown = [s for s in guard["states"] if s not in model.states(region)]
        if unknown:
            bad(f"guard names {', '.join(unknown)}, not states of {region}")
    return problems


def _between(value, low, high) -> bool:
    return isinstance(value, int) and low <= value <= high


def _cells(model: Model) -> list:
    cells = {}
    for row in model.rows:
        if row.get("actor") == "you" and all(k in row for k in ROW_KEYS):
            cells.setdefault((row["region"], row["action"], row["from"]), []).append(row)
    problems = []
    for (region, action, state), rows in cells.items():
        conditions = [r["when"].strip() for r in rows]
        if len(rows) > 1 and ("" in conditions or len(set(conditions)) != len(conditions)):
            problems.append(f"{region}: '{action}' in {state} has more than one row and no 'when' to tell them apart")
    return problems


def _journeys(model: Model) -> list:
    rows = {r.get("id"): r for r in model.rows}
    problems = []
    for journey in model.journeys:
        name = journey.get("id", "<no id>")
        if not journey.get("title") or not journey.get("steps"):
            problems.append(f"journey {name}: needs a title and at least one step")
            continue
        for number, step in enumerate(journey["steps"], start=1):
            row = rows.get(step.get("row"))
            if row is None:
                problems.append(f"journey {name}: step {number} names no row '{step.get('row')}'")
            elif row.get("outcome") == "unreachable":
                problems.append(f"journey {name}: step {number} is a cell that cannot be reached")
    return problems
```

- [ ] **Step 5: Run the tests and watch them pass**

Run: `PYTHONPATH=scripts python3 -m unittest discover -s scripts/statemap/tests`
Expected: `Ran 17 tests` and `OK`

- [ ] **Step 6: Prove one test can fail**

In `model.py`, delete the two lines beginning `if outcome != "ok" and row["to"] != row["from"]:`.
Run the tests. Expected: `test_only_an_ok_row_may_change_state` fails. Restore the lines and
run again. Expected: `OK`.

- [ ] **Step 7: Write the real model, with every state and no rows**

Before writing, open each file below and confirm the states against it. Where they differ,
the code wins.

- `modules/identity/src/main/kotlin/com/moyi/identity/domain/User.kt` (`UserStatus`)
- `modules/bond/src/main/kotlin/com/moyi/bond/domain/Bond.kt` (`BondStatus`)
- `modules/bond/src/main/kotlin/com/moyi/bond/domain/Proposal.kt`
- `modules/gratitude/src/main/kotlin/com/moyi/gratitude/domain/BondDay.kt` (`BondDayStatus`)
- `modules/gratitude/src/main/kotlin/com/moyi/gratitude/domain/Entry.kt` (`EntryStatus`)
- `modules/gratitude/src/main/kotlin/com/moyi/gratitude/web/LockedEntryResponse.kt`

For each `everywhere` entry, open the file its `codeRef` names and confirm the status that
code is sent with. Correct the entry if the code says otherwise.

**Create `docs/state-map/data/model.json`:**

```json
{
  "stamp": "174b474",
  "machines": [
    {
      "id": "account",
      "label": "Account",
      "regions": [
        {
          "id": "account",
          "label": "The account",
          "states": [
            {"id": "ANONYMOUS", "label": "anonymous"},
            {"id": "PENDING_VERIFICATION", "label": "PENDING_VERIFICATION"},
            {"id": "ACTIVE_SIGNED_OUT", "label": "ACTIVE, signed out"},
            {"id": "SIGNED_IN", "label": "signed in"},
            {"id": "SUSPENDED", "label": "SUSPENDED", "built": false},
            {"id": "PENDING_DELETION", "label": "PENDING_DELETION", "built": false},
            {"id": "DELETED", "label": "DELETED", "built": false}
          ]
        },
        {
          "id": "account.session",
          "label": "The session",
          "states": [
            {"id": "NO_SESSION", "label": "no session"},
            {"id": "ACCESS_VALID", "label": "access token valid"},
            {"id": "ACCESS_EXPIRED", "label": "access token expired"},
            {"id": "REVOKED", "label": "revoked"}
          ]
        },
        {
          "id": "account.reset",
          "label": "The password reset",
          "states": [
            {"id": "NONE", "label": "none"},
            {"id": "REQUESTED", "label": "requested"},
            {"id": "USED", "label": "token used"},
            {"id": "EXPIRED", "label": "token expired"}
          ]
        }
      ]
    },
    {
      "id": "bond",
      "label": "Bond",
      "regions": [
        {
          "id": "bond",
          "label": "The bond",
          "states": [
            {"id": "NO_BOND", "label": "no bond"},
            {"id": "PENDING_MEMBER", "label": "PENDING_MEMBER"},
            {"id": "ACTIVE", "label": "ACTIVE"},
            {"id": "PENDING_DELETION", "label": "PENDING_DELETION"},
            {"id": "ARCHIVED", "label": "ARCHIVED"},
            {"id": "DELETED", "label": "DELETED"}
          ]
        },
        {
          "id": "bond.invite",
          "label": "The invite",
          "states": [
            {"id": "NONE", "label": "none"},
            {"id": "CREATED", "label": "created"},
            {"id": "ACCEPTED", "label": "accepted"},
            {"id": "REVOKED", "label": "revoked"},
            {"id": "NOT_USABLE", "label": "not usable"}
          ]
        },
        {
          "id": "bond.proposal",
          "label": "The proposal",
          "states": [
            {"id": "NONE", "label": "none"},
            {"id": "PROPOSED", "label": "proposed"},
            {"id": "CONFIRMED", "label": "confirmed"},
            {"id": "WITHDRAWN", "label": "withdrawn"}
          ]
        }
      ]
    },
    {
      "id": "day",
      "label": "Day",
      "regions": [
        {
          "id": "day",
          "label": "The day",
          "states": [
            {"id": "OPEN", "label": "OPEN"},
            {"id": "PARTIAL", "label": "PARTIAL"},
            {"id": "PENDING_REVEAL", "label": "PENDING_REVEAL"},
            {"id": "REVEALED", "label": "REVEALED"},
            {"id": "SOLO", "label": "SOLO"},
            {"id": "EMPTY", "label": "EMPTY"},
            {"id": "FROZEN", "label": "FROZEN"},
            {"id": "SUSPENDED", "label": "SUSPENDED"}
          ]
        },
        {
          "id": "day.entry",
          "label": "Your entry",
          "states": [
            {"id": "NONE", "label": "none"},
            {"id": "SUBMITTED", "label": "SUBMITTED"},
            {"id": "REVEALED", "label": "REVEALED"},
            {"id": "DELETED", "label": "DELETED"}
          ]
        },
        {
          "id": "day.partnerEntry",
          "label": "What you see of your partner's entry",
          "states": [
            {"id": "NONE", "label": "nothing yet"},
            {"id": "LOCKED", "label": "LOCKED"},
            {"id": "VISIBLE", "label": "the text"},
            {"id": "REMOVED", "label": "REMOVED"}
          ]
        }
      ]
    }
  ],
  "events": [],
  "everywhere": [
    {"status": 400, "code": "MALFORMED_REQUEST", "reason": "The body could not be read as what the endpoint expects.", "codeRef": "common/web/src/main/kotlin/com/moyi/common/web/GlobalExceptionHandler.kt#ErrorCode.MALFORMED_REQUEST"},
    {"status": 401, "code": "UNAUTHENTICATED", "reason": "No access token, or one that is expired or not valid.", "codeRef": "common/security/src/main/kotlin/com/moyi/common/security/ProblemAuthenticationHandlers.kt#ErrorCode.UNAUTHENTICATED"},
    {"status": 403, "code": "FORBIDDEN", "reason": "A valid token that lacks the authority this action needs.", "codeRef": "common/security/src/main/kotlin/com/moyi/common/security/ProblemAuthenticationHandlers.kt#ErrorCode.FORBIDDEN"},
    {"status": 405, "code": "METHOD_NOT_ALLOWED", "reason": "The path exists; this verb does not.", "codeRef": "common/web/src/main/kotlin/com/moyi/common/web/GlobalExceptionHandler.kt#ErrorCode.METHOD_NOT_ALLOWED"},
    {"status": 415, "code": "UNSUPPORTED_MEDIA_TYPE", "reason": "A Content-Type this endpoint does not read.", "codeRef": "common/web/src/main/kotlin/com/moyi/common/web/GlobalExceptionHandler.kt#ErrorCode.UNSUPPORTED_MEDIA_TYPE"},
    {"status": 422, "code": "VALIDATION_FAILED", "reason": "The request parsed but a field or a required header is missing or not valid.", "codeRef": "common/web/src/main/kotlin/com/moyi/common/web/GlobalExceptionHandler.kt#ErrorCode.VALIDATION_FAILED"},
    {"status": 429, "code": "RATE_LIMITED", "reason": "A rate-limit bucket is empty; Retry-After says when to try again.", "codeRef": "common/security/src/main/kotlin/com/moyi/common/security/ProblemAuthenticationHandlers.kt#ErrorCode.RATE_LIMITED"},
    {"status": 500, "code": "INTERNAL_ERROR", "reason": "Anything unanticipated. It never carries the underlying message.", "codeRef": "common/web/src/main/kotlin/com/moyi/common/web/GlobalExceptionHandler.kt#ErrorCode.INTERNAL_ERROR"}
  ],
  "pending": {"endpoints": [], "codes": []}
}
```

If a `codeRef` path above is not where the file is, find it with
`grep -rl "ErrorCode.UNAUTHENTICATED" common --include='*.kt'` (quote the glob; zsh expands
it otherwise) and correct the path.

**Create `docs/state-map/data/endpoints.json`:**

```json
[]
```

Fill `pending` from the contract and the enum, so that everything not yet mapped is named:

```bash
python3 - <<'EOF'
import json, re, pathlib
model_path = pathlib.Path("docs/state-map/data/model.json")
model = json.loads(model_path.read_text())
contract = json.loads(pathlib.Path("contracts/openapi.json").read_text())
methods = ("get", "post", "put", "patch", "delete")
model["pending"]["endpoints"] = sorted(
    f"{m.upper()} {p}" for p, ops in contract["paths"].items() for m in ops if m in methods
)
source = pathlib.Path("common/web/src/main/kotlin/com/moyi/common/web/ErrorCode.kt").read_text()
everywhere = {e["code"] for e in model["everywhere"]}
model["pending"]["codes"] = [
    c for c in re.findall(r"^\s+([A-Z][A-Z0-9_]+),?\s*$", source, re.M) if c not in everywhere
]
model_path.write_text(json.dumps(model, indent=2) + "\n")
print(len(model["pending"]["endpoints"]), "endpoints,", len(model["pending"]["codes"]), "codes pending")
EOF
```

Expected: `34 endpoints, 26 codes pending`

- [ ] **Step 8: Load the real model**

Run:

```bash
PYTHONPATH=scripts python3 -c "
from pathlib import Path
from statemap.model import load, validate
m = load(Path('docs/state-map/data'))
print(len(m.regions()), 'regions', validate(m))"
```

Expected: `9 regions []`

- [ ] **Step 9: Amend the spec**

In `docs/superpowers/specs/2026-10-07-backend-state-map-design.md`:

- §3.2: change "Substates, invite: created, looked up, accepted, revoked, not usable." to
  "Substates, invite: created, accepted, revoked, not usable. Looking an invite up is an
  action that leaves it created."
- §6: replace the first sentence and the field table with the file list from this plan's
  "File structure" (the six files under `docs/state-map/data/`) and the field table from
  this task. Keep "Generated from the data" as it is.
- §7: replace the paragraph beginning "Sources are labelled honestly" with: "Every row
  cites the code it was read from (`codeRef`) and says what executed it (`evidence`):
  `smoke`, `test`, `hand` or `never-run`. The check confirms each citation still resolves.
  A `pending` list in `model.json` names what is not yet mapped; the check fails when an
  entry on it has been mapped, and in strict mode when the list is not empty."
- §8: after the build order, add: "Steps 1 to 4 are one implementation plan. Step 5 has
  its own, written once the data exists."

- [ ] **Step 10: Commit**

Python writes bytecode beside the sources. Add this line to `.gitignore`, under the
macOS section, so it is never staged:

```
__pycache__/
```

```bash
git add .gitignore scripts/statemap docs/state-map/data docs/superpowers
git commit -m "docs(state-map): the data format, its loader and every state"
```

---

### Task 2: The checks

**Files:**
- Create: `scripts/statemap/check.py`
- Create: `scripts/statemap/tests/test_check.py`

**Interfaces:**
- Consumes: `Model`, `fixture.tiny`, `fixture.row`, `fixture.refusal`.
- Produces: `operations(openapi: dict) -> dict[str, set[int]]`;
  `error_codes(kotlin_source: str) -> list[str]`;
  `check_contract(model, operations, strict) -> list[str]`;
  `check_error_codes(model, codes, strict) -> list[str]`;
  `check_grid(model, strict) -> list[str]`;
  `check_refs(model, root: Path) -> list[str]`.

- [ ] **Step 1: Write the failing tests**

**Create `scripts/statemap/tests/test_check.py`:**

```python
import tempfile
import unittest
from pathlib import Path

from fixture import refusal, row, tiny
from statemap.check import (
    check_contract,
    check_error_codes,
    check_grid,
    check_refs,
    error_codes,
    operations,
)

LAMP = {"POST /api/v1/lamp": {200, 401, 409}}

KOTLIN = """
enum class ErrorCode {
    /**
     * A KDoc line that Mentions SOMETHING, in capitals.
     */
    UNAUTHENTICATED,

    /** One line. */
    LAMP_ALREADY_ON,
}
"""


class ParsingTests(unittest.TestCase):
    def test_operations_are_method_path_and_statuses(self):
        openapi = {"paths": {"/api/v1/lamp": {"post": {"responses": {"200": {}, "409": {}}}, "parameters": []}}}
        self.assertEqual(operations(openapi), {"POST /api/v1/lamp": {200, 409}})

    def test_error_codes_are_read_past_their_comments(self):
        self.assertEqual(error_codes(KOTLIN), ["UNAUTHENTICATED", "LAMP_ALREADY_ON"])


class ContractTests(unittest.TestCase):
    def problems(self, model, ops=None, strict=False):
        return "\n".join(check_contract(model, LAMP if ops is None else ops, strict))

    def test_a_complete_map_has_no_problems(self):
        self.assertEqual(check_contract(tiny(), LAMP, True), [])

    def test_an_unmapped_endpoint_is_a_problem(self):
        ops = {**LAMP, "GET /api/v1/lamp": {200}}
        self.assertIn("GET /api/v1/lamp is in the contract and has no endpoint card", self.problems(tiny(), ops))

    def test_a_pending_endpoint_is_allowed_until_strict(self):
        ops = {**LAMP, "GET /api/v1/lamp": {200}}
        waiting = tiny(pending={"endpoints": ["GET /api/v1/lamp"], "codes": []})
        self.assertEqual(check_contract(waiting, ops, False), [])
        self.assertIn("still pending", self.problems(waiting, ops, strict=True))

    def test_a_mapped_endpoint_may_not_stay_pending(self):
        stale = tiny(pending={"endpoints": ["POST /api/v1/lamp"], "codes": []})
        self.assertIn("is mapped; take it off the pending list", self.problems(stale))

    def test_a_card_for_an_endpoint_the_contract_lacks(self):
        self.assertIn("is not in the contract", self.problems(tiny(), {}))

    def test_a_documented_status_nothing_explains(self):
        ops = {"POST /api/v1/lamp": {200, 401, 409, 412}}
        self.assertIn("documents 412 and nothing in the map explains it", self.problems(tiny(), ops))

    def test_a_row_status_the_contract_does_not_document(self):
        ops = {"POST /api/v1/lamp": {200, 401}}
        self.assertIn("answers 409, which the contract does not document", self.problems(tiny(), ops))

    def test_a_card_with_no_row_of_your_own(self):
        self.assertIn("has a card and no row", self.problems(tiny(rows=[])))


class ErrorCodeTests(unittest.TestCase):
    CODES = ["UNAUTHENTICATED", "LAMP_ALREADY_ON"]

    def problems(self, model, codes=None, strict=False):
        return "\n".join(check_error_codes(model, self.CODES if codes is None else codes, strict))

    def test_every_code_used(self):
        self.assertEqual(check_error_codes(tiny(), self.CODES, True), [])

    def test_an_unmapped_code_is_a_problem(self):
        self.assertIn("LAMP_FUSED is an ErrorCode and nothing in the map returns it", self.problems(tiny(), self.CODES + ["LAMP_FUSED"]))

    def test_a_pending_code_is_allowed_until_strict(self):
        waiting = tiny(pending={"endpoints": [], "codes": ["LAMP_FUSED"]})
        self.assertEqual(check_error_codes(waiting, self.CODES + ["LAMP_FUSED"], False), [])
        self.assertIn("still pending", self.problems(waiting, self.CODES + ["LAMP_FUSED"], strict=True))

    def test_a_mapped_code_may_not_stay_pending(self):
        stale = tiny(pending={"endpoints": [], "codes": ["LAMP_ALREADY_ON"]})
        self.assertIn("is mapped; take it off the pending list", self.problems(stale))

    def test_a_code_the_enum_lacks(self):
        self.assertIn("LAMP_ALREADY_ON is not an ErrorCode", self.problems(tiny(), ["UNAUTHENTICATED"]))


class GridTests(unittest.TestCase):
    def test_a_full_grid(self):
        self.assertEqual(check_grid(tiny(), True), [])

    def test_an_empty_cell_is_named(self):
        self.assertIn("lamp: 'POST /api/v1/lamp' in ON has no row", "\n".join(check_grid(tiny([row()]), False)))

    def test_an_unbuilt_state_needs_no_cell(self):
        self.assertNotIn("BROKEN", "\n".join(check_grid(tiny(), True)))

    def test_strict_wants_every_built_state_touched(self):
        self.assertIn("lamp: no row enters or leaves OFF", "\n".join(check_grid(tiny([refusal()]), True)))


class RefTests(unittest.TestCase):
    def root(self, tmp):
        root = Path(tmp)
        (root / "src").mkdir()
        (root / "src/Lamp.kt").write_text("class Lamp {\n    fun press() {}\n}\n")
        (root / "scripts").mkdir()
        (root / "scripts/smoke.sh").write_text('expect "pressing an off lamp turns it on" 200 -- "$BASE/lamp"\n')
        tests = root / "modules/lamp/src/test/kotlin"
        tests.mkdir(parents=True)
        (tests / "LampTest.kt").write_text("class LampTest {\n    fun `a lamp that is on refuses`() {}\n}\n")
        return root

    def problems(self, model):
        with tempfile.TemporaryDirectory() as tmp:
            return "\n".join(check_refs(model, self.root(tmp)))

    def test_citations_that_resolve(self):
        rows = [
            row(evidence="smoke", evidenceRef="pressing an off lamp turns it on"),
            refusal(evidence="test", evidenceRef="LampTest#a lamp that is on refuses"),
        ]
        self.assertEqual(self.problems(tiny(rows)), "")

    def test_code_that_has_moved_on(self):
        self.assertIn("'fun toggle(' is not in src/Lamp.kt", self.problems(tiny([row(codeRef="src/Lamp.kt#fun toggle("), refusal()])))

    def test_a_file_that_is_gone(self):
        self.assertIn("src/Gone.kt does not exist", self.problems(tiny([row(codeRef="src/Gone.kt#fun press("), refusal()])))

    def test_a_citation_without_text(self):
        self.assertIn("codeRef is 'path#text", self.problems(tiny([row(codeRef="src/Lamp.kt"), refusal()])))

    def test_a_smoke_probe_that_does_not_exist(self):
        self.assertIn("no probe in scripts/smoke.sh", self.problems(tiny([row(evidence="smoke", evidenceRef="a probe nobody wrote"), refusal()])))

    def test_a_test_that_does_not_exist(self):
        self.assertIn("no test", self.problems(tiny([row(evidence="test", evidenceRef="LampTest#a lamp that flies"), refusal()])))

    def test_the_everywhere_errors_are_checked_too(self):
        model = tiny()
        model.everywhere[0]["codeRef"] = "src/Lamp.kt#fun toggle("
        self.assertIn("everywhere 401 UNAUTHENTICATED", self.problems(model))


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run the tests and watch them fail**

Run: `PYTHONPATH=scripts python3 -m unittest discover -s scripts/statemap/tests`
Expected: `ModuleNotFoundError: No module named 'statemap.check'`

- [ ] **Step 3: Write the checks**

**Create `scripts/statemap/check.py`:**

```python
"""Whether the map is complete and still true (spec §7).

Each check returns a list of sentences, one per problem; an empty list
means the check passed. Nothing here writes a file.
"""
from __future__ import annotations

import re
from pathlib import Path

from statemap.model import Model

METHODS = ("get", "post", "put", "patch", "delete")
SOURCE_ROOTS = ("app", "common", "modules")


def operations(openapi: dict) -> dict:
    found = {}
    for path, methods in openapi["paths"].items():
        for method, operation in methods.items():
            if method in METHODS:
                statuses = {int(s) for s in operation.get("responses", {}) if s.isdigit()}
                found[f"{method.upper()} {path}"] = statuses
    return found


def error_codes(kotlin_source: str) -> list:
    body = kotlin_source.split("enum class ErrorCode", 1)[1]
    return re.findall(r"^\s+([A-Z][A-Z0-9_]+),?\s*$", body, re.M)


def check_contract(model: Model, operations: dict, strict: bool) -> list:
    problems = []
    mapped = {e["id"]: e for e in model.endpoints}
    pending = set(model.pending["endpoints"])
    everywhere = {e["status"] for e in model.everywhere}

    for operation in sorted(operations):
        if operation in mapped and operation in pending:
            problems.append(f"{operation} is mapped; take it off the pending list")
        elif operation not in mapped and operation not in pending:
            problems.append(
                f"{operation} is in the contract and has no endpoint card; "
                "add one with its rows, or name it in pending.endpoints"
            )
    problems += [f"{e} has an endpoint card and is not in the contract" for e in sorted(set(mapped) - set(operations))]
    problems += [f"pending names {e}, which is not in the contract" for e in sorted(pending - set(operations))]
    if strict and pending:
        problems.append(f"{len(pending)} endpoints are still pending")

    for operation, endpoint in sorted(mapped.items()):
        if operation not in operations:
            continue
        rows = [r for r in model.rows if r["action"] == operation and r["actor"] == "you"]
        if not rows:
            problems.append(f"{operation} has a card and no row of your own")
            continue
        from_rows = {r["status"] for r in rows if r["status"] is not None}
        from_card = {e["status"] for e in endpoint["requestErrors"]}
        documented = operations[operation]
        for status in sorted(documented - from_rows - from_card - everywhere):
            problems.append(f"{operation} documents {status} and nothing in the map explains it")
        for status in sorted((from_rows | from_card) - documented):
            problems.append(f"{operation} answers {status}, which the contract does not document")
    return problems


def check_error_codes(model: Model, codes: list, strict: bool) -> list:
    used = {r["code"] for r in model.rows if r["code"]}
    used |= {e["code"] for card in model.endpoints for e in card["requestErrors"]}
    used |= {e["code"] for e in model.everywhere}
    pending = set(model.pending["codes"])

    problems = []
    for code in codes:
        if code in used and code in pending:
            problems.append(f"{code} is mapped; take it off the pending list")
        elif code not in used and code not in pending:
            problems.append(
                f"{code} is an ErrorCode and nothing in the map returns it; "
                "add the row that does, or name it in pending.codes"
            )
    problems += [f"{c} is not an ErrorCode" for c in sorted((used | pending) - set(codes))]
    if strict and pending:
        problems.append(f"{len(pending)} error codes are still pending")
    return problems


def check_grid(model: Model, strict: bool) -> list:
    problems = []
    for region_id in model.regions():
        states = model.states(region_id, built_only=True)
        rows = [r for r in model.rows if r["region"] == region_id]
        mine = [r for r in rows if r["actor"] == "you"]
        for action in dict.fromkeys(r["action"] for r in mine):
            filled = {r["from"] for r in mine if r["action"] == action}
            for state in states:
                if state not in filled:
                    problems.append(f"{region_id}: '{action}' in {state} has no row")
        if strict:
            touched = {r["from"] for r in rows} | {r["to"] for r in rows}
            problems += [f"{region_id}: no row enters or leaves {s}" for s in states if s not in touched]
    return problems


def check_refs(model: Model, root: Path) -> list:
    smoke_path = root / "scripts/smoke.sh"
    smoke = smoke_path.read_text(encoding="utf-8") if smoke_path.exists() else ""

    cited = [(f"row {r['id']}", r) for r in model.rows if r["outcome"] != "unreachable"]
    cited += [
        (f"{card['id']} {e['status']} {e['code']}", e)
        for card in model.endpoints
        for e in card["requestErrors"]
    ]
    problems = []
    for name, item in cited:
        problems += _code_ref(name, item["codeRef"], root)
        if item["evidence"] == "smoke" and item["evidenceRef"] not in smoke:
            problems.append(f"{name}: no probe in scripts/smoke.sh is labelled '{item['evidenceRef']}'")
        if item["evidence"] == "test":
            problems += _test_ref(name, item["evidenceRef"], root)
    for entry in model.everywhere:
        problems += _code_ref(f"everywhere {entry['status']} {entry['code']}", entry["codeRef"], root)
    return problems


def _code_ref(name: str, ref: str, root: Path) -> list:
    path, separator, text = ref.partition("#")
    if not separator or not text:
        return [f"{name}: codeRef is 'path#text found in that file', not '{ref}'"]
    file = root / path
    if not file.is_file():
        return [f"{name}: {path} does not exist"]
    if text not in file.read_text(encoding="utf-8"):
        return [f"{name}: '{text}' is not in {path}; the code has moved on, so read it again"]
    return []


def _test_ref(name: str, ref: str, root: Path) -> list:
    class_name, separator, test_name = ref.partition("#")
    if not separator or not test_name:
        return [f"{name}: a test's evidenceRef is 'ClassName#text of the test name', not '{ref}'"]
    files = [f for top in SOURCE_ROOTS for f in (root / top).glob(f"**/src/test/**/{class_name}.kt")]
    if not any(test_name in f.read_text(encoding="utf-8") for f in files):
        return [f"{name}: no test '{test_name}' in a {class_name}.kt under src/test"]
    return []
```

- [ ] **Step 4: Run the tests and watch them pass**

Run: `PYTHONPATH=scripts python3 -m unittest discover -s scripts/statemap/tests`
Expected: `Ran 43 tests` and `OK`

- [ ] **Step 5: Prove two tests can fail**

In `check_contract`, delete the loop `for status in sorted((from_rows | from_card) - documented):`
and its body. Run the tests. Expected:
`test_a_row_status_the_contract_does_not_document` fails. Restore.

In `_code_ref`, delete the `if text not in file.read_text(...)` branch. Run the tests.
Expected: `test_code_that_has_moved_on` and `test_the_everywhere_errors_are_checked_too`
fail. Restore, and run once more. Expected: `OK`.

- [ ] **Step 6: Commit**

```bash
git add scripts/statemap
git commit -m "docs(state-map): check the map against the contract, the codes and its evidence"
```

---

### Task 3: The generator and the two commands

**Files:**
- Create: `scripts/statemap/mermaid.py`
- Create: `scripts/statemap/generate.py`
- Create: `scripts/statemap/cli.py`
- Create: `scripts/statemap/__main__.py`
- Create: `scripts/state-map-check`
- Create: `scripts/state-map-generate`
- Create: `scripts/statemap/tests/test_mermaid.py`
- Create: `scripts/statemap/tests/test_cli.py`
- Create: `.github/workflows/state-map.yml`

**Interfaces:**
- Consumes: `Model`, `load`, `validate`, every `check_*`, `operations`, `error_codes`.
- Produces: `mermaid.clean(text) -> str`; `mermaid.state_diagram(model, machine_id) -> str`;
  `mermaid.grid_table(model, region_id) -> str`; `mermaid.sequence_diagram(model, journey) -> str`;
  `mermaid.machine_md(model, machine_id) -> str`; `mermaid.journeys_md(model) -> str`;
  `generate.outputs(model) -> dict[str, str]`; `generate.write(model, out_dir)`;
  `generate.stale(model, out_dir) -> list[str]`; `cli.main(argv=None, root=ROOT) -> int`.

- [ ] **Step 1: Write the failing tests**

**Create `scripts/statemap/tests/test_mermaid.py`:**

```python
import tempfile
import unittest
from pathlib import Path

from fixture import refusal, row, tiny
from statemap.generate import outputs, stale, write
from statemap.mermaid import clean, grid_table, machine_md, sequence_diagram, state_diagram

TIMER = row(
    id="lamp-on-timer", **{"from": "ON"}, to="OFF", action="event:timer", actor="system",
    status=None, reason="It turns itself off.",
)


class MermaidTests(unittest.TestCase):
    def test_clean_removes_what_breaks_mermaid(self):
        self.assertEqual(clean('write: "today\'s" entry {bondId}; #1'), "write today's entry bondId 1")

    def test_the_diagram_draws_states_and_successes(self):
        diagram = state_diagram(tiny([row(), refusal(), TIMER]), "lamp")
        self.assertEqual(
            diagram.splitlines(),
            [
                "stateDiagram-v2",
                '    state "The lamp" as lamp {',
                '        state "OFF" as lamp__OFF',
                '        state "ON" as lamp__ON',
                '        state "BROKEN" as lamp__BROKEN',
                "        lamp__OFF --> lamp__ON: you - press the switch",
                "        lamp__ON --> lamp__OFF: system - the timer runs out",
                "    }",
                "    classDef unbuilt fill:#eeeeee,stroke:#aaaaaa,color:#888888",
                "    class lamp__BROKEN unbuilt",
            ],
        )

    def test_a_refusal_is_not_drawn(self):
        self.assertNotIn("lamp__ON --> lamp__ON", state_diagram(tiny(), "lamp"))

    def test_many_actions_between_two_states_are_counted(self):
        rows = [row(id=f"r{n}", when=f"case {n}", action=f"POST /api/v1/lamp/{n}") for n in range(3)]
        cards = [{**tiny().endpoints[0], "id": f"POST /api/v1/lamp/{n}", "summary": f"way {n}"} for n in range(3)]
        diagram = state_diagram(tiny(rows, endpoints=cards), "lamp")
        self.assertIn("lamp__OFF --> lamp__ON: you - 3 actions", diagram)

    def test_the_grid_has_a_cell_for_every_built_state(self):
        self.assertEqual(
            grid_table(tiny(), "lamp").splitlines(),
            [
                "| Action | `OFF` | `ON` |",
                "|---|---|---|",
                "| press the switch<br>`POST /lamp` | 200 → `ON` | 409 `LAMP_ALREADY_ON` |",
            ],
        )

    def test_an_empty_cell_is_shouted(self):
        self.assertIn("**EMPTY**", grid_table(tiny([row()]), "lamp"))

    def test_the_page_lists_refusals_and_what_happens_without_you(self):
        page = machine_md(tiny([row(), refusal(), TIMER]), "lamp")
        self.assertIn("main @ abc1234", page)
        self.assertIn("| `ON` | press the switch | 409 `LAMP_ALREADY_ON` | It is already on. | BR-1 | never-run |", page)
        self.assertIn("| `ON` | the timer runs out | system | `OFF` | It turns itself off. | never-run |", page)

    def test_a_journey_reads_as_a_conversation(self):
        journey = {"id": "J1", "title": "On and off", "steps": [
            {"row": "lamp-off-press", "note": ""},
            {"row": "lamp-on-press", "note": ""},
            {"row": "lamp-on-timer", "note": ""},
        ]}
        model = tiny([row(), refusal(), TIMER], journeys=[journey])
        self.assertEqual(
            sequence_diagram(model, journey).splitlines(),
            [
                "sequenceDiagram",
                "    actor You",
                "    actor Partner",
                "    participant API",
                "    participant Job as Scheduled job",
                "    You->>API: press the switch",
                "    API-->>You: 200, lamp is ON",
                "    You->>API: press the switch",
                "    API-->>You: 409 LAMP_ALREADY_ON",
                "    Job->>API: the timer runs out",
                "    Note over API: lamp is OFF",
            ],
        )


class GenerateTests(unittest.TestCase):
    def test_a_machine_with_no_rows_gets_no_file(self):
        self.assertEqual(outputs(tiny(rows=[])), {})

    def test_written_files_are_not_stale_until_the_data_moves(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            self.assertEqual(stale(tiny(), out), ["lamp.md is missing; run scripts/state-map-generate"])
            write(tiny(), out)
            self.assertEqual(stale(tiny(), out), [])
            self.assertEqual(stale(tiny([row(reason="Changed."), refusal()]), out), [])
            self.assertEqual(
                stale(tiny([row(), refusal(reason="Changed.")]), out),
                ["lamp.md is stale; run scripts/state-map-generate"],
            )


if __name__ == "__main__":
    unittest.main()
```

**Create `scripts/statemap/tests/test_cli.py`:**

```python
import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path

from fixture import tiny
from statemap.cli import main

KOTLIN = "enum class ErrorCode {\n    UNAUTHENTICATED,\n    LAMP_ALREADY_ON,\n}\n"
OPENAPI = {"paths": {"/api/v1/lamp": {"post": {"responses": {"200": {}, "401": {}, "409": {}}}}}}


def repository(tmp, model):
    root = Path(tmp)
    data = root / "docs/state-map/data"
    data.mkdir(parents=True)
    base = {k: getattr(model, k) for k in ("stamp", "machines", "events", "everywhere", "pending")}
    (data / "model.json").write_text(json.dumps(base))
    (data / "endpoints.json").write_text(json.dumps(model.endpoints))
    (data / "day.json").write_text(json.dumps(model.rows))
    (root / "contracts").mkdir()
    (root / "contracts/openapi.json").write_text(json.dumps(OPENAPI))
    enum = root / "common/web/src/main/kotlin/com/moyi/common/web"
    enum.mkdir(parents=True)
    (enum / "ErrorCode.kt").write_text(KOTLIN)
    (root / "src").mkdir()
    (root / "src/Lamp.kt").write_text("fun press() {}\n")
    return root


def run(argv, root):
    err = io.StringIO()
    with contextlib.redirect_stderr(err), contextlib.redirect_stdout(io.StringIO()):
        code = main(argv, root)
    return code, err.getvalue()


class CliTests(unittest.TestCase):
    def test_generate_then_check_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = repository(tmp, tiny())
            self.assertEqual(run(["check", "--strict"], root)[0], 1)
            self.assertEqual(run(["generate"], root)[0], 0)
            self.assertEqual(run(["check", "--strict"], root), (0, ""))

    def test_check_names_what_is_wrong_and_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = repository(tmp, tiny())
            run(["generate"], root)
            (root / "src/Lamp.kt").write_text("fun toggle() {}\n")
            code, err = run(["check"], root)
            self.assertEqual(code, 1)
            self.assertIn("state-map: row lamp-off-press: 'fun press(' is not in src/Lamp.kt", err)

    def test_generate_refuses_malformed_data(self):
        with tempfile.TemporaryDirectory() as tmp:
            model = tiny()
            model.rows[0]["to"] = "DIM"
            root = repository(tmp, model)
            self.assertEqual(run(["generate"], root)[0], 1)
            self.assertFalse((root / "docs/state-map/lamp.md").exists())


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run the tests and watch them fail**

Run: `PYTHONPATH=scripts python3 -m unittest discover -s scripts/statemap/tests`
Expected: `ModuleNotFoundError: No module named 'statemap.generate'`

- [ ] **Step 3: Write the Mermaid and Markdown text**

**Create `scripts/statemap/mermaid.py`:**

```python
"""The map as text: Mermaid diagrams and Markdown tables (spec §5, §6).

Refusals are not drawn. Standard UML does not draw an event a state does
not handle, and the grid is their conventional home (spec §2).
"""
from __future__ import annotations

import re

from statemap.model import Model

NOTICE = "<!-- Generated by scripts/state-map-generate from docs/state-map/data. Do not edit. -->"


def clean(text: str) -> str:
    """Text safe inside a Mermaid label: no colon, brace, quote mark, hash or semicolon."""
    return " ".join(re.sub(r'[:;{}"#<>|]', " ", text).split())


def cell_text(text: str) -> str:
    return text.replace("|", "\\|").replace("\n", " ")


def short(action: str) -> str:
    return action.replace("/api/v1", "", 1)


def node(region_id: str, state: str) -> str:
    return f"{region_id.replace('.', '_')}__{state}"


def _edges(model: Model, region_id: str) -> dict:
    edges = {}
    for row in model.rows:
        if row["region"] == region_id and row["outcome"] == "ok" and row["to"] != row["from"]:
            labels = edges.setdefault((row["from"], row["to"]), {}).setdefault(row["actor"], [])
            label = clean(model.label(row["action"]))
            if label not in labels:
                labels.append(label)
    return edges


def _edge_label(by_actor: dict) -> str:
    parts = []
    for actor, labels in by_actor.items():
        text = " / ".join(labels) if len(labels) <= 2 else f"{len(labels)} actions"
        parts.append(f"{actor} - {text}")
    return ", ".join(parts)


def state_diagram(model: Model, machine_id: str) -> str:
    machine = next(m for m in model.machines if m["id"] == machine_id)
    lines = ["stateDiagram-v2"]
    unbuilt = []
    for region in machine["regions"]:
        region_id = region["id"]
        lines.append(f'    state "{clean(region["label"])}" as {region_id.replace(".", "_")} {{')
        for state in region["states"]:
            lines.append(f'        state "{clean(state["label"])}" as {node(region_id, state["id"])}')
            if not state.get("built", True):
                unbuilt.append(node(region_id, state["id"]))
        for (source, target), by_actor in _edges(model, region_id).items():
            lines.append(f"        {node(region_id, source)} --> {node(region_id, target)}: {_edge_label(by_actor)}")
        lines.append("    }")
    if unbuilt:
        lines.append("    classDef unbuilt fill:#eeeeee,stroke:#aaaaaa,color:#888888")
        lines += [f"    class {name} unbuilt" for name in unbuilt]
    return "\n".join(lines)


def _cell(row: dict) -> str:
    if row["outcome"] == "unreachable":
        text = "not reachable"
    elif row["outcome"] == "refused":
        text = f"{row['status']} `{row['code']}`"
    elif row["to"] == row["from"]:
        text = f"{row['status']} stays"
    else:
        text = f"{row['status']} → `{row['to']}`"
    return text + (f" ({cell_text(row['when'])})" if row["when"] else "")


def _table(header: list, rows: list) -> str:
    lines = ["| " + " | ".join(header) + " |", "|---" * len(header) + "|"]
    lines += ["| " + " | ".join(r) + " |" for r in rows]
    return "\n".join(lines)


def grid_table(model: Model, region_id: str) -> str:
    states = model.states(region_id, built_only=True)
    mine = [r for r in model.rows if r["region"] == region_id and r["actor"] == "you"]
    body = []
    for action in dict.fromkeys(r["action"] for r in mine):
        cells = []
        for state in states:
            rows = [r for r in mine if r["action"] == action and r["from"] == state]
            cells.append("<br>".join(_cell(r) for r in rows) or "**EMPTY**")
        body.append([f"{cell_text(model.label(action))}<br>`{short(action)}`"] + cells)
    return _table(["Action"] + [f"`{s}`" for s in states], body) if body else ""


def _refusals(model: Model, region_id: str) -> str:
    rows = [
        [f"`{r['from']}`", cell_text(model.label(r["action"])), f"{r['status']} `{r['code']}`",
         cell_text(r["reason"]), r["rule"], r["evidence"]]
        for r in model.rows
        if r["region"] == region_id and r["actor"] == "you" and r["outcome"] == "refused"
    ]
    return _table(["In", "Action", "Answer", "Why", "Rule", "Evidence"], rows) if rows else ""


def _without_you(model: Model, region_id: str) -> str:
    rows = [
        [f"`{r['from']}`", cell_text(model.label(r["action"])), r["actor"], f"`{r['to']}`",
         cell_text(r["reason"]), r["evidence"]]
        for r in model.rows
        if r["region"] == region_id and r["actor"] != "you"
    ]
    return _table(["In", "What happens", "Who", "Leads to", "Why", "Evidence"], rows) if rows else ""


def machine_md(model: Model, machine_id: str) -> str:
    machine = next(m for m in model.machines if m["id"] == machine_id)
    parts = [
        NOTICE,
        f"# The {machine['label'].lower()} machine",
        f"Describes `main @ {model.stamp}`. How to read this: [README](README.md).",
        "```mermaid\n" + state_diagram(model, machine_id) + "\n```",
        "Each arrow says who acts: you, your partner or the system. "
        "Refusals are not drawn; they are in the tables below.",
    ]
    for region in machine["regions"]:
        sections = (
            ("Every action in every state", grid_table(model, region["id"])),
            ("Refused here", _refusals(model, region["id"])),
            ("Happens without you", _without_you(model, region["id"])),
        )
        if any(text for _, text in sections):
            parts.append(f"## {region['label']}")
            for title, text in sections:
                if text:
                    parts += [f"### {title}", text]
    return "\n\n".join(parts) + "\n"


def sequence_diagram(model: Model, journey: dict) -> str:
    rows = {r["id"]: r for r in model.rows}
    lines = [
        "sequenceDiagram",
        "    actor You",
        "    actor Partner",
        "    participant API",
        "    participant Job as Scheduled job",
    ]
    for step in journey["steps"]:
        row = rows[step["row"]]
        who = {"you": "You", "partner": "Partner", "system": "Job"}[row["actor"]]
        lines.append(f"    {who}->>API: {clean(model.label(row['action']))}")
        if row["outcome"] == "refused":
            lines.append(f"    API-->>{who}: {row['status']} {row['code']}")
        elif row["status"] is None:
            lines.append(f"    Note over API: {row['region']} is {row['to']}")
        else:
            lines.append(f"    API-->>{who}: {row['status']}, {row['region']} is {row['to']}")
    return "\n".join(lines)


def journeys_md(model: Model) -> str:
    rows = {r["id"]: r for r in model.rows}
    parts = [
        NOTICE,
        "# Journeys",
        f"Describes `main @ {model.stamp}`. Each journey is a path through the three machines, "
        "taken from doc 02. How to read this: [README](README.md).",
    ]
    for journey in model.journeys:
        parts.append(f"## {journey['id']}: {journey['title']}")
        steps = []
        for number, step in enumerate(journey["steps"], start=1):
            row = rows[step["row"]]
            note = f" {step['note']}" if step.get("note") else ""
            steps.append(f"{number}. **{row['actor']}**: {model.label(row['action'])}. {row['reason']}{note}")
        parts.append("\n".join(steps))
        parts.append("```mermaid\n" + sequence_diagram(model, journey) + "\n```")
    return "\n\n".join(parts) + "\n"
```

- [ ] **Step 4: Write the file writer and the stale check**

**Create `scripts/statemap/generate.py`:**

```python
"""Which generated files exist, writing them, and noticing when they are stale."""
from __future__ import annotations

from pathlib import Path

from statemap.mermaid import journeys_md, machine_md
from statemap.model import Model


def outputs(model: Model) -> dict:
    files = {}
    for machine in model.machines:
        regions = {r["id"] for r in machine["regions"]}
        if any(row["region"] in regions for row in model.rows):
            files[f"{machine['id']}.md"] = machine_md(model, machine["id"])
    if model.journeys:
        files["journeys.md"] = journeys_md(model)
    return files


def write(model: Model, out_dir: Path) -> None:
    for name, text in outputs(model).items():
        (out_dir / name).write_text(text, encoding="utf-8")


def stale(model: Model, out_dir: Path) -> list:
    problems = []
    for name, text in outputs(model).items():
        path = out_dir / name
        if not path.exists():
            problems.append(f"{name} is missing; run scripts/state-map-generate")
        elif path.read_text(encoding="utf-8") != text:
            problems.append(f"{name} is stale; run scripts/state-map-generate")
    return problems
```

`test_written_files_are_not_stale_until_the_data_moves` changes the reason of a success
row and expects no staleness: a success's reason appears nowhere in a machine file, only a
refusal's does. If you later add reasons for successes to the page, that assertion is the
one to change.

- [ ] **Step 5: Write the two commands**

**Create `scripts/statemap/cli.py`:**

```python
"""`check` reads and reports; `generate` writes. Neither does the other's job."""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from statemap.check import (
    check_contract,
    check_error_codes,
    check_grid,
    check_refs,
    error_codes,
    operations,
)
from statemap.generate import stale, write
from statemap.model import load, validate

ROOT = Path(__file__).resolve().parents[2]
ERROR_CODES = "common/web/src/main/kotlin/com/moyi/common/web/ErrorCode.kt"


def main(argv=None, root: Path = ROOT) -> int:
    parser = argparse.ArgumentParser(prog="state-map")
    parser.add_argument("command", choices=("check", "generate"))
    parser.add_argument("--strict", action="store_true", help="fail while anything is still pending")
    args = parser.parse_args(argv)

    docs = root / "docs/state-map"
    model = load(docs / "data")
    problems = validate(model)

    if args.command == "generate" and not problems:
        write(model, docs)
        return 0

    if args.command == "check" and not problems:
        contract = json.loads((root / "contracts/openapi.json").read_text(encoding="utf-8"))
        codes = error_codes((root / ERROR_CODES).read_text(encoding="utf-8"))
        problems += check_contract(model, operations(contract), args.strict)
        problems += check_error_codes(model, codes, args.strict)
        problems += check_grid(model, args.strict)
        problems += check_refs(model, root)
        problems += stale(model, docs)

    for problem in problems:
        print(f"state-map: {problem}", file=sys.stderr)
    if problems:
        return 1

    never_run = sum(1 for r in model.rows if r["evidence"] == "never-run" and r["outcome"] != "unreachable")
    waiting = len(model.pending["endpoints"]) + len(model.pending["codes"])
    print(f"state-map: {len(model.rows)} rows, {never_run} never run, {waiting} pending. Nothing wrong.")
    return 0
```

**Create `scripts/statemap/__main__.py`:**

```python
from statemap.cli import main

raise SystemExit(main())
```

**Create `scripts/state-map-check`:**

```bash
#!/usr/bin/env bash
# Is the state map complete and still true? Reads only; writes nothing.
#   scripts/state-map-check            pending endpoints and codes are allowed
#   scripts/state-map-check --strict   nothing may be pending
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PYTHONPATH="$here" exec python3 -m statemap check "$@"
```

**Create `scripts/state-map-generate`:**

```bash
#!/usr/bin/env bash
# Rewrites docs/state-map/*.md from docs/state-map/data. Commit what it writes.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PYTHONPATH="$here" exec python3 -m statemap generate "$@"
```

Then: `chmod +x scripts/state-map-check scripts/state-map-generate`

- [ ] **Step 6: Run the tests and watch them pass**

Run: `PYTHONPATH=scripts python3 -m unittest discover -s scripts/statemap/tests`
Expected: `Ran 56 tests` and `OK`

- [ ] **Step 7: Run the real check**

Run: `scripts/state-map-check`
Expected: `state-map: 0 rows, 0 never run, 60 pending. Nothing wrong.`

Run: `scripts/state-map-check --strict`
Expected: exit 1, with `34 endpoints are still pending`, `26 error codes are still pending`
and one `no row enters or leaves` line per built state.

- [ ] **Step 8: Add the workflow**

**Create `.github/workflows/state-map.yml`:**

```yaml
name: State map

# docs/state-map is generated from docs/state-map/data and claims to cover
# every endpoint and every error code. This is what holds it to that: a new
# endpoint, a new ErrorCode, or a changed status in contracts/openapi.json
# fails here until the map says what it does.
on:
  pull_request:
  push:
    branches: [main]

permissions:
  contents: read

concurrency:
  group: state-map-${{ github.ref }}
  cancel-in-progress: true

jobs:
  state-map:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7

      - name: The checker's own tests
        run: PYTHONPATH=scripts python3 -m unittest discover -s scripts/statemap/tests

      - name: The map against the contract, the error codes and its evidence
        run: scripts/state-map-check
```

- [ ] **Step 9: Commit**

```bash
git add scripts .github/workflows/state-map.yml
git commit -m "docs(state-map): generate the diagrams and grids, and check them in CI"
```

---

### Task 4: The day machine

**Files:**
- Modify: `docs/state-map/data/model.json` (`events`, `pending`)
- Modify: `docs/state-map/data/endpoints.json`
- Create: `docs/state-map/data/day.json`
- Create: `docs/state-map/day.md` (generated)

**Interfaces:**
- Consumes: the data format (Task 1), `scripts/state-map-check`, `scripts/state-map-generate`.
- Produces: row ids beginning `day-`, which Task 7's journeys name.

**The endpoints of this machine** (take each off `pending.endpoints` as its card is written):

| Endpoint | Controller |
|---|---|
| `POST /api/v1/bonds/{bondId}/entries` | `modules/gratitude/.../web/EntriesController.kt` → `service/SubmitEntry.kt` |
| `GET /api/v1/bonds/{bondId}/today` | `web/EntriesController.kt` → `service/GetToday.kt` |
| `PATCH /api/v1/entries/{entryId}` | `web/EntryChangesController.kt` → `service/PatchEntry.kt`, `ChangeEntry.kt` |
| `DELETE /api/v1/entries/{entryId}` | `web/EntryChangesController.kt` → `service/EraseEntry.kt`, `ChangeEntry.kt` |
| `GET /api/v1/bonds/{bondId}/streak` | `web/StreakController.kt` → `service/GetStreak.kt` |

**Where the transitions are.** Read at `174b474`; open each before writing its row.

| Transition | Code |
|---|---|
| You write into `OPEN` → `PARTIAL`; into `SUSPENDED` it stays `SUSPENDED` | `domain/BondDay.kt#fun withEntry()` |
| Second entry: → `REVEALED`, or → `PENDING_REVEAL` when a reveal time is set and not yet reached | `domain/BondDay.kt#fun revealWhenDue(` |
| You delete before the reveal: `PARTIAL` → `OPEN`; a settled day does not change | `domain/BondDay.kt#fun withoutEntry()` |
| Close job: `OPEN` → `EMPTY`, `PARTIAL` → `SOLO`, `PENDING_REVEAL` → `REVEALED` | `domain/BondDay.kt#fun close(` |
| The bond gains its second member: `SUSPENDED` → `OPEN` or `PARTIAL` | `domain/BondDay.kt#fun resumeJoiningDay(` |
| A day nobody opened is written `EMPTY`, `FROZEN` or `SUSPENDED` | `service/CreateMissingDays.kt` |
| The close job runs every fifteen minutes | `modules/scheduling/.../service/CloseJob.kt#@Scheduled` |

**Where the refusals are:** `modules/gratitude/src/main/kotlin/com/moyi/gratitude/service/GratitudeErrors.kt`
defines `BOND_ARCHIVED` (409), `MEDIA_NOT_YET_SUPPORTED` (422), `ENTRY_ALREADY_EXISTS`
(409, BR-2), `DAY_CLOSED` (409, BR-10), `ENTRY_IMMUTABLE` (409, BR-7) and the one `404` for
an entry the caller may not act on. Idempotency errors (`IDEMPOTENCY_KEY_REUSED`,
`IDEMPOTENCY_KEY_IN_FLIGHT`, the 413) are in
`common/web/src/main/kotlin/com/moyi/common/web/idempotency/IdempotencyInterceptor.kt`.

**Which kind of fact goes where:**

- Depends on the day's state → a row in region `day`.
- Depends on your entry's state (edit, delete) → a row in region `day.entry`.
- What `GET /today` shows of the partner's entry → rows in `day.partnerEntry`.
- Depends on the bond's state (`BOND_ARCHIVED`) → leave for Task 5; keep `BOND_ARCHIVED`
  in `pending.codes`.
- Depends only on the request (a missing `Idempotency-Key`, a media id, a body too large) →
  `requestErrors` on the endpoint card.
- You are not a member of the bond, or it does not exist → `404 NOT_FOUND`, a request
  error on the card of every bond-scoped endpoint. It is 404 and never 403, so that the
  answer does not confirm the bond exists.
- 401, 403, 429 and the other everywhere errors → nowhere; `model.json` has them.

- [ ] **Step 1: Add the events**

In `docs/state-map/data/model.json`, set `events` to:

```json
[
  {"id": "event:reveal-time-arrives", "label": "the reveal time arrives", "actor": "system"},
  {"id": "event:day-ends", "label": "the day ends and the close job runs", "actor": "system"},
  {"id": "event:second-member-joins", "label": "your partner accepts the invite", "actor": "partner"},
  {"id": "event:missed-day-recorded", "label": "the close job records a day nobody opened", "actor": "system"}
]
```

Before keeping `event:reveal-time-arrives`, read `service/CloseDay.kt` and
`service/RevealDay.kt` and find what moves a `PENDING_REVEAL` day to `REVEALED` when the
time passes and nobody calls the API. If it is the close job alone, delete this event and
use `event:day-ends`.

- [ ] **Step 2: Write the five endpoint cards**

For each endpoint: read the controller for the headers it requires and the service for
what it throws. Find evidence for each request error with
`grep -n "<the code or status>" scripts/smoke.sh`, then in the test sources with
`grep -rn "<the code>" modules/gratitude/src/test common/web/src/test`. One card, as the
shape to follow:

```json
{
  "id": "POST /api/v1/bonds/{bondId}/entries",
  "summary": "write today's entry",
  "auth": "bearer",
  "headers": ["Idempotency-Key"],
  "requestErrors": [
    {
      "status": 422,
      "code": "MEDIA_NOT_YET_SUPPORTED",
      "reason": "The request named a photo or a voice note, which nothing can store until Phase 4.",
      "evidence": "never-run",
      "evidenceRef": "",
      "codeRef": "modules/gratitude/src/main/kotlin/com/moyi/gratitude/service/GratitudeErrors.kt#ErrorCode.MEDIA_NOT_YET_SUPPORTED"
    }
  ],
  "curl": "curl -X POST \"$API/bonds/$BOND/entries\" -H \"Authorization: Bearer $TOKEN\" -H \"Idempotency-Key: $(uuidgen)\" -H 'Content-Type: application/json' -d '{\"text\": \"...\"}'"
}
```

The `evidence` above is a placeholder for what you find: if a probe or a test asserts the
422, record that instead. Never write `never-run` without having searched.

- [ ] **Step 3: Write the rows, one endpoint at a time**

Create `docs/state-map/data/day.json` as a JSON array. For each endpoint, for each built
state of the region it belongs to, write the row or rows for that cell. A cell that cannot
happen is a row with `"outcome": "unreachable"` and a reason. Two rows, as the shape to
follow:

```json
[
  {
    "id": "day-open-write",
    "region": "day",
    "from": "OPEN",
    "action": "POST /api/v1/bonds/{bondId}/entries",
    "actor": "you",
    "when": "",
    "guards": [
      {"region": "account", "states": ["SIGNED_IN"]},
      {"region": "bond", "states": ["PENDING_MEMBER", "ACTIVE"]}
    ],
    "outcome": "ok",
    "to": "PARTIAL",
    "status": 201,
    "code": null,
    "reason": "Yours is the first entry of the day; your partner sees that you wrote and nothing else.",
    "rule": "BR-1",
    "evidence": "never-run",
    "evidenceRef": "",
    "codeRef": "modules/gratitude/src/main/kotlin/com/moyi/gratitude/domain/BondDay.kt#fun withEntry()"
  },
  {
    "id": "day-partial-close",
    "region": "day",
    "from": "PARTIAL",
    "action": "event:day-ends",
    "actor": "system",
    "when": "",
    "guards": [],
    "outcome": "ok",
    "to": "SOLO",
    "status": null,
    "code": null,
    "reason": "The day ended with one entry, so it closes as a solo day.",
    "rule": "FR-063",
    "evidence": "never-run",
    "evidenceRef": "",
    "codeRef": "modules/gratitude/src/main/kotlin/com/moyi/gratitude/domain/BondDay.kt#BondDayStatus.PARTIAL -> copy(status = BondDayStatus.SOLO"
  }
]
```

Both `evidence` values are placeholders: `day-open-write` is certainly asserted by
`scripts/smoke.sh`, and the close by a test in `modules/gratitude/src/test`. Find them and
record them. Confirm `guards` for `PENDING_MEMBER` against `SubmitEntry.kt` (a creator may
write before the invitee joins; that day is `SUSPENDED`).

After each endpoint, run `scripts/state-map-check`. It names every cell still empty and
every citation that does not resolve. Work until the only output is the summary line.

Cells that need care, each of which has been got wrong in this project before:

- **`PARTIAL`, write.** Two rows: `when: "you already wrote today"` is `409
  ENTRY_ALREADY_EXISTS`; `when: "your partner wrote and you have not"` leads to `REVEALED`
  or `PENDING_REVEAL`, which is two more rows told apart by the reveal time.
- **Closed days, write.** Read ADR-0031 on BR-3a before writing `DAY_CLOSED`: a claim for a
  settled day may be redirected to the current day once rather than refused. Record what
  the code does, and cite it.
- **`SUSPENDED`.** It has two meanings (spec §3.3). The joining day accepts an entry; the
  called-off deletion day is closed. Use `when` to tell them apart.
- **Delete after the bond ended.** Allowed by the owner's ruling (ADR-0032 q1); cite
  `ChangeEntry.kt`.
- **A partner's entry.** Editing or deleting it is `404`, never `403` or `409`.

- [ ] **Step 4: Generate, and look at the diagram**

Run: `scripts/state-map-generate && scripts/state-map-check`
Expected: the summary line, with `pending` reduced by 5 endpoints and by every code this
machine returns.

Render the diagram before trusting it:

```bash
npx -y @mermaid-js/mermaid-cli -i docs/state-map/day.md -o "$TMPDIR/day.svg" && open "$TMPDIR"/day-1.svg
```

Expected: one composite box per region, every state inside its region, arrows labelled
`you -`, `partner -` or `system -`. If the tool cannot be installed, push the branch and
read `docs/state-map/day.md` on GitHub, which renders Mermaid. If the diagram does not
parse, the fault is a character `clean()` let through: add it to `clean`'s pattern and to
`test_clean_removes_what_breaks_mermaid`, then regenerate.

- [ ] **Step 5: Check the map against the running service**

Rows are claims about behaviour, so run it (CLAUDE.md, "Green tests are a claim about the
machine they ran on").

Run: `colima start && docker compose up -d && scripts/smoke.sh`
Expected: every probe passes. Then, for each row whose evidence is `smoke`, confirm the
probe named in `evidenceRef` asserts the status the row gives. Fix any row where it does
not; if the service disagrees with the spec or an ADR, stop and report it.

- [ ] **Step 6: Commit, push and open the first pull request**

```bash
git add docs/state-map
git commit -m "docs(state-map): the day machine, every action in every state"
git -c http.version=HTTP/1.1 push -u origin docs/backend-state-map
gh pr create --draft --title "docs(state-map): the tooling and the day machine" --body-file - <<'EOF'
## What this is
The first part of the backend state map (spec: docs/superpowers/specs/2026-10-07-backend-state-map-design.md):
the data format, the checks, the generator, and the day machine.

## What was run
- `PYTHONPATH=scripts python3 -m unittest discover -s scripts/statemap/tests`
- `scripts/state-map-check`
- `scripts/smoke.sh`

## What is not done
Bond, account and journeys follow. `pending` in docs/state-map/data/model.json names what is unmapped.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
```

Fill "What was run" with the real output counts, and list any row left `never-run`.

---

### Task 5: The bond machine

**Files:**
- Modify: `docs/state-map/data/model.json` (`events`, `pending`)
- Modify: `docs/state-map/data/endpoints.json`
- Create: `docs/state-map/data/bond.json`
- Create: `docs/state-map/bond.md` (generated)
- Modify: `docs/state-map/day.md` (regenerated, if guards or labels moved)

**Interfaces:**
- Consumes: the data format; the method of Task 4, repeated here in full.
- Produces: row ids beginning `bond-`.

**The endpoints of this machine** (17):

| Endpoint | Controller, under `modules/bond/src/main/kotlin/com/moyi/bond/web/` |
|---|---|
| `GET /api/v1/bonds`, `POST /api/v1/bonds`, `GET /api/v1/bonds/{bondId}`, `PATCH /api/v1/bonds/{bondId}`, `POST .../leave`, `POST .../block` | `BondsController.kt` |
| `POST /api/v1/bonds/{bondId}/invites`, `DELETE .../invites/{inviteId}` | `BondInvitesController.kt` |
| `GET /api/v1/invites/{code}`, `POST /api/v1/invites/{code}/accept` | `InvitesController.kt` |
| `GET` and `PUT /api/v1/bonds/{bondId}/members/me/settings` | `BondMemberSettingsController.kt` |
| `PATCH` and `DELETE /api/v1/bonds/{bondId}/timezone`, `POST .../timezone/confirm` | `BondTimezoneController.kt` |
| `POST` and `DELETE /api/v1/bonds/{bondId}/deletion-request` | `BondDeletionController.kt` |

Confirm each mapping with `grep -n "Mapping" modules/bond/src/main/kotlin/com/moyi/bond/web/*.kt`;
`leave` and `block` may live in a controller this table does not name.

**Where the rules are:** `adr/0026` (the guard), `adr/0027` (invites and the one answer),
`adr/0028` (leave, block), `adr/0029` (ETag and `If-Match`: `PRECONDITION_REQUIRED` 428,
`PRECONDITION_FAILED` 412), `adr/0030` (two-party consent: `PROPOSAL_PENDING`,
`PROPOSAL_NEEDS_OTHER_MEMBER`, `TIMEZONE_CHANGE_TOO_SOON`), `adr/0035` (withdrawal).
Refusals: `grep -rn "ErrorCode\." modules/bond/src/main`.

**Which kind of fact goes where:**

- Depends on the bond's status → region `bond`.
- Depends on the invite → region `bond.invite`.
- Depends on whether a proposal is open, and who opened it → region `bond.proposal`. One
  mechanism serves the timezone change and the deletion; both sets of endpoints get rows
  here.
- `BOND_ARCHIVED` on an entry write → a row in region `bond`, `from` and `to` `ARCHIVED`,
  action `POST /api/v1/bonds/{bondId}/entries`. This is where Task 4 left it. An action
  that has a row in a region needs a row for every built state of that region, so the
  entry write gets a row for each bond state.
- A non-member → `404 NOT_FOUND`, never 403. Record it once per endpoint as a request
  error on the card ("you are not a member of this bond, or it does not exist"), not as a
  row: it does not depend on the bond's state.
- `If-Match` missing or stale → request errors on the `PATCH` card.

- [ ] **Step 1: Add this machine's events**

Read `modules/bond/src/main/kotlin/com/moyi/bond/service/` and
`modules/scheduling/src/main/kotlin/com/moyi/scheduling/service/` for what changes a bond
without the reader acting. Add to `events` in `model.json` at least:

```json
{"id": "event:partner-leaves", "label": "your partner leaves", "actor": "partner"},
{"id": "event:partner-blocks", "label": "your partner blocks you", "actor": "partner"},
{"id": "event:partner-proposes", "label": "your partner proposes a change", "actor": "partner"},
{"id": "event:partner-confirms", "label": "your partner confirms your proposal", "actor": "partner"},
{"id": "event:deletion-countdown-ends", "label": "the deletion countdown runs out", "actor": "system"},
{"id": "event:invite-expires", "label": "the invite expires", "actor": "system"}
```

Delete any the code does not do, and say which in the pull request. If nothing deletes a
bond when the countdown ends, `DELETED` is not built: mark it `"built": false` in
`model.json` and write no row for it.

- [ ] **Step 2: Write the seventeen endpoint cards**

Same shape as Task 4 Step 2: `id`, `summary`, `auth`, `headers`, `requestErrors`, `curl`.
Evidence: `grep -n "<code>" scripts/smoke.sh`, then
`grep -rn "<code>" modules/bond/src/test`. Curl lines use `$API`, `$TOKEN`, `$BOND`,
`$INVITE_CODE`, `$ETAG`; never a real value.

- [ ] **Step 3: Write the rows, one endpoint at a time**

Create `docs/state-map/data/bond.json`. Same shape as Task 4 Step 3. Run
`scripts/state-map-check` after each endpoint and work until only the summary line is left.

Cells that need care:

- **`POST /bonds` with a bond already.** `BOND_LIMIT_REACHED`. Read what the limit is and
  which statuses count toward it.
- **Accepting an invite.** `BOND_FULL`, `ALREADY_MEMBER`, `INVITE_NOT_USABLE` and
  `EMAIL_NOT_VERIFIED` are four different refusals with four different causes. ADR-0027's
  "one answer" means some causes deliberately share a response; record the response the
  code gives and name every cause in `reason`.
- **Proposals.** Confirming your own proposal is `PROPOSAL_NEEDS_OTHER_MEMBER`; opening a
  second is `PROPOSAL_PENDING`. Use `when` for who opened it.
- **Leave and block, with and without `withdrawEntries`.** #58 changed these. Both end in
  `ARCHIVED`; say in `reason` what happens to the entries, and cite ADR-0035.
- **`PENDING_DELETION`.** Read which writes it refuses and which it allows; the day machine
  calls these days `SUSPENDED` if the deletion is called off.

- [ ] **Step 4: Generate, render, run**

```bash
scripts/state-map-generate && scripts/state-map-check
npx -y @mermaid-js/mermaid-cli -i docs/state-map/bond.md -o "$TMPDIR/bond.svg" && open "$TMPDIR"/bond-1.svg
scripts/smoke.sh
```

Expected: the summary line with 22 endpoints mapped and 12 pending; a diagram with three
regions; every smoke probe passing. Check each `smoke` row against its probe as in Task 4
Step 5.

- [ ] **Step 5: Commit and open the pull request**

```bash
git add docs/state-map
git commit -m "docs(state-map): the bond machine, with invites and proposals"
```

If the first pull request has merged, branch from `main` first
(`git switch -c docs/state-map-bond origin/main`). If it has not, stack on it and say so in
the description. Open a draft pull request titled
`docs(state-map): the bond machine` with the same three sections as Task 4 Step 6.

---

### Task 6: The account machine, and strict mode

**Files:**
- Modify: `docs/state-map/data/model.json` (`events`; `pending` emptied)
- Modify: `docs/state-map/data/endpoints.json`
- Create: `docs/state-map/data/account.json`
- Create: `docs/state-map/account.md` (generated)
- Modify: `.github/workflows/state-map.yml` (add `--strict`)

**Interfaces:**
- Consumes: the data format; the method of Tasks 4 and 5, repeated here in full.
- Produces: row ids beginning `account-`; an empty `pending`.

**The endpoints of this machine** (12), under
`modules/identity/src/main/kotlin/com/moyi/identity/web/`:

| Endpoint | Controller |
|---|---|
| `POST /api/v1/auth/register` | `RegistrationController.kt` |
| `POST /api/v1/auth/verify-email`, `POST /api/v1/auth/resend-verification` | `VerificationController.kt` |
| `POST /api/v1/auth/login` | `LoginController.kt` |
| `POST /api/v1/auth/refresh` | `RefreshController.kt` |
| `POST /api/v1/auth/logout`, `POST /api/v1/auth/logout-all` | `LogoutController.kt` |
| `GET /api/v1/auth/sessions`, `DELETE /api/v1/auth/sessions/{id}` | `SessionsController.kt` |
| `POST /api/v1/auth/forgot-password`, `POST /api/v1/auth/reset-password` | `PasswordResetController.kt` |
| `GET /api/v1/me` | `MeController.kt` |

**Where the rules are:** `adr/0015` (registration does not confirm an address), `adr/0018`
(verification), `adr/0019` (access tokens), `adr/0020` (login, lockout), `adr/0021`
(refresh rotation, reuse detection), `adr/0022` (reset), `adr/0025` (sessions). Refusals:
`web/IdentityExceptionHandler.kt` and `grep -rn "ErrorCode\." modules/identity/src/main`.

**Which kind of fact goes where:**

- Depends on the account → region `account`.
- Depends on the tokens → region `account.session`.
- Depends on a reset token → region `account.reset`.
- `SUSPENDED`, `PENDING_DELETION`, `DELETED` are `"built": false`: confirm no endpoint
  reaches them, and write no rows for them. If one does reach them, mark it built and map
  it.

- [ ] **Step 1: Add this machine's events**

```json
{"id": "event:access-token-expires", "label": "the access token expires", "actor": "system"},
{"id": "event:refresh-token-expires", "label": "the refresh token expires", "actor": "system"},
{"id": "event:verification-token-expires", "label": "the verification link expires", "actor": "system"},
{"id": "event:reset-token-expires", "label": "the reset link expires", "actor": "system"}
```

Read the lifetimes from configuration (`grep -rn "ttl\|lifetime\|expires" app/src/main/resources modules/identity/src/main`)
and put each in the `reason` of the row that uses it.

- [ ] **Step 2: Write the twelve endpoint cards**

Same shape as Task 4 Step 2. Curl lines use `$EMAIL`, `$PASSWORD`, `$TOKEN`, `$REFRESH`,
`$VERIFY_TOKEN`, `$RESET_TOKEN`. `auth` is `none` for every `/auth` route that takes no
bearer; read `SecurityConfiguration` to be sure rather than inferring it from the path.

- [ ] **Step 3: Write the rows, one endpoint at a time**

Create `docs/state-map/data/account.json`. Run `scripts/state-map-check` after each
endpoint.

Cells that need care:

- **Answers that are deliberately the same.** Registering an address that exists, asking
  for a reset for one that does not, and resending a verification all answer as though it
  worked (ADR-0015). The row's `status` is the success; `reason` says what really happened
  and why the caller is not told.
- **Login.** `INVALID_CREDENTIALS` covers a wrong password and an unknown address, on
  purpose. `EMAIL_NOT_VERIFIED` is a separate refusal; read when it is given. Lockout is a
  429; it is an everywhere error, but say on the card how many attempts cause it.
- **Refresh.** A used refresh token is `TOKEN_REUSE_DETECTED` and revokes every session of
  that family: a refusal in `account.session` whose `reason` says so. Since a refusal may
  not change state, record the revocation as a second row with the event
  `event:reuse-detected` (add it, actor `system`) from `ACCESS_VALID` to `REVOKED`.
- **`HASHING_CAPACITY_EXCEEDED`.** A request error on the cards that hash a password.
- **`NOT_FOUND`.** `DELETE /auth/sessions/{id}` for a session that is not yours.
- **Without a bearer, an unknown `/auth` path is 401, not 404** (seen by hand on 3 October).
  It belongs to no row; it is why 401 is an everywhere error.

- [ ] **Step 4: Empty the pending list**

Run: `scripts/state-map-generate && scripts/state-map-check --strict`
Expected: the summary line ending `0 pending. Nothing wrong.`

If a code or endpoint is still pending, it is unmapped: find what returns it and write the
row. If an `ErrorCode` is returned by nothing at all, that is a finding. Stop and report
it; do not delete it from the enum in this work.

- [ ] **Step 5: Make CI strict**

In `.github/workflows/state-map.yml`, change the last step's command:

```yaml
        run: scripts/state-map-check --strict
```

- [ ] **Step 6: Render, run, commit, open the pull request**

```bash
npx -y @mermaid-js/mermaid-cli -i docs/state-map/account.md -o "$TMPDIR/account.svg" && open "$TMPDIR"/account-1.svg
scripts/smoke.sh
git add docs/state-map .github/workflows/state-map.yml
git commit -m "docs(state-map): the account machine; nothing is pending any more"
```

Open a draft pull request titled `docs(state-map): the account machine, and strict mode`
with the same three sections as Task 4 Step 6. State the number of rows whose evidence is
`never-run`; those are the probes `scripts/smoke.sh` lacks.

---

### Task 7: The journeys

**Files:**
- Create: `docs/state-map/data/journeys.json`
- Create: `docs/state-map/journeys.md` (generated)

**Interfaces:**
- Consumes: row ids from Tasks 4 to 6; `journeys.json` as `validate` reads it:
  `[{"id": "J1", "title": "...", "steps": [{"row": "<row id>", "note": ""}]}]`.

The five journeys are in `documents/02-personas-and-user-journeys.md` §2, in the parent
repository (`../documents/` from this repository's root; from a worktree under
`.worktrees/`, `../../../documents/`). Read each before tracing it.

| Journey | Doc 02 | Must pass through |
|---|---|---|
| J1 | First run and pairing | register, verify, sign in, create a bond, create an invite; partner accepts; the joining day resumes |
| J2 | The daily loop | you write (`OPEN` → `PARTIAL`); partner writes; the reveal; next day opens |
| J3 | The broken streak | one writes, the other does not; the close job writes `SOLO`; the streak answer changes |
| J4 | Looking back | `GET /today` on a revealed day, `GET /streak`. The archive is not built (C5b): the journey stops where the API stops, and its last step's `note` says so |
| J5 | Ending | leave, or block with withdrawal; `ARCHIVED`; a write is then `409 BOND_ARCHIVED` |

- [ ] **Step 1: Trace J2 first**

It is the product, and it crosses all three kinds of actor. A step names a row; the row
supplies who acts, the call, the answer and the reason. `note` adds only what the row
cannot say about this journey, such as "the next morning".

```json
[
  {
    "id": "J2",
    "title": "The daily loop",
    "steps": [
      {"row": "day-open-write", "note": ""},
      {"row": "day-partial-partner-writes-reveal", "note": ""}
    ]
  }
]
```

The second row id is an example of the shape; use the ids `day.json` really has
(`grep '"id"' docs/state-map/data/day.json`). If a step a journey needs has no row, the
map is missing a fact: add the row to its machine's file, not a step without one.

- [ ] **Step 2: Generate and read it as a story**

Run: `scripts/state-map-generate && scripts/state-map-check --strict`
Expected: `journeys.md` written; the summary line.

Read `docs/state-map/journeys.md` top to bottom. Each numbered list must make sense to
someone who has not read the code, and each step must be possible from the state the step
before it left. The check does not prove the second; you do, by following `from` and `to`
down the list.

- [ ] **Step 3: Trace J1, J3, J4 and J5**

One at a time, regenerating and reading after each. Include at least one refusal in each
journey where doc 02 describes something going wrong (J1: accepting your own invite or a
used one; J5: writing after the bond ended).

- [ ] **Step 4: Render the sequence diagrams**

```bash
npx -y @mermaid-js/mermaid-cli -i docs/state-map/journeys.md -o "$TMPDIR/journeys.svg" && open "$TMPDIR"
```

Expected: five diagrams, each with You, Partner, API and Scheduled job across the top.

- [ ] **Step 5: Commit**

```bash
git add docs/state-map
git commit -m "docs(state-map): journeys J1 to J5 traced across the three machines"
```

---

### Task 8: The way in, and the last look

**Files:**
- Create: `docs/state-map/README.md`
- Modify: `README.md` (one paragraph under "How is it structured")
- Modify: `CLAUDE.md` ("Where things are" table, one row)
- Modify: `.github/PULL_REQUEST_TEMPLATE.md` (one line in the Definition of Done)

- [ ] **Step 1: Write the map's own README**

**Create `docs/state-map/README.md`:**

````markdown
# The state map

What the backend does in every state it can be in: every action, where it leads, what
refuses it and why. Three machines, each with a diagram, a grid and its refusals:

- [Account](account.md): registering, signing in, sessions, password reset
- [Bond](bond.md): pairing, invites, two-party consent, leaving and blocking
- [Day](day.md): writing, the reveal, the close job

[Journeys](journeys.md) traces the five journeys of doc 02 across all three.

## How to read a machine

- **The diagram** shows states and the actions that succeed. Each arrow says who acts: you,
  your partner, or the system. A grey state exists in the code and nothing reaches it yet.
- **Every action in every state** is the complete record. A cell is a success and where it
  leads, an error code, or "not reachable".
- **Refused here** gives the reason and the rule for each refusal.
- **Happens without you** lists what your partner and the system can do to this state.

Refusals are not drawn on the diagram. An action in one machine may require a state in
another; the data records that as the row's `guards`.

Some errors can follow any call and are in no grid: 400, 401, 403, 405, 415, 422, 429 and
500. They are listed in `data/model.json` under `everywhere`.

## Where a fact comes from

Every row cites the code it was read from, and says what executed it:

| Evidence | Meaning |
|---|---|
| `smoke` | `scripts/smoke.sh` asserts it against the running jar |
| `test` | an automated test asserts it |
| `hand` | a person saw it with curl |
| `never-run` | read from the code; nobody has executed it |

## How to change it

The `.md` files here are generated. Edit `data/`, then:

```
scripts/state-map-generate
scripts/state-map-check --strict
```

Commit the data and the generated files together. CI runs the check, so a new endpoint, a
new `ErrorCode` or a changed status in `contracts/openapi.json` fails until the map says
what it does. When the check says a citation no longer resolves, read the code again before
changing the citation: the row may no longer be true.
````

- [ ] **Step 2: Point at it from the three places a reader starts**

In `README.md`, under "How is it structured", after the first paragraph, add:

```markdown
What the API does in each state — every action, where it leads, and every refusal with its
reason — is drawn in [`docs/state-map/`](docs/state-map/README.md), generated from data
that CI checks against the contract.
```

In `CLAUDE.md`, add a row to the "Where things are" table, after the API contract row:

```markdown
| What the API does in each state, and every error with its cause | `docs/state-map/` (generated from `docs/state-map/data/`) |
```

In `.github/PULL_REQUEST_TEMPLATE.md`, add to the Definition of Done list:

```markdown
- [ ] If an endpoint, a status or an error code changed, `docs/state-map/data/` says so and `scripts/state-map-check --strict` passes
```

`AGENTS.md` repeats four of `CLAUDE.md`'s rules and none of its tables, so it does not
change.

- [ ] **Step 3: Check the whole against the spec**

Read the spec's "Success means" list (§1) and confirm each line with a command or a file:

```bash
scripts/state-map-check --strict
PYTHONPATH=scripts python3 -m unittest discover -s scripts/statemap/tests
./gradlew build
git diff --stat origin/main -- contracts/ modules/ common/ app/
```

Expected: the summary line with `0 pending`; `OK`; `BUILD SUCCESSFUL`; and an empty diff
for the last, because this work changes no product code and no contract.

- [ ] **Step 4: Re-stamp**

Run `git rev-parse --short origin/main`. If it is not `174b474`, the code moved while this
was built: run `scripts/state-map-check --strict` on a branch rebased onto it, fix what it
reports, set `stamp` in `model.json` to the new commit, and regenerate.

- [ ] **Step 5: Commit and open the last pull request**

```bash
git add docs/state-map README.md CLAUDE.md .github/PULL_REQUEST_TEMPLATE.md
git commit -m "docs(state-map): journeys, and where to find the map"
```

Open a draft pull request titled `docs(state-map): the journeys and the way in`. In the
description give: the number of rows, the number `never-run`, and any place the map found
the code, the contract and an ADR disagreeing.

---

## After this plan

- **The published page** (spec §8 step 5): its own plan. It reads the same data and adds
  the clickable map, the endpoint cards, the search and the refusal toggle.
- **The `never-run` rows** are the smoke script's missing probes. Adding them is separate
  work; each one turns a row's evidence from `never-run` to `smoke`.
