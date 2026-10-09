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
