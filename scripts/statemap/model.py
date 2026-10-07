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
