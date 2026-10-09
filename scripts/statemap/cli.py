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
