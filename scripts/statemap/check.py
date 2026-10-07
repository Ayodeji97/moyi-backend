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
