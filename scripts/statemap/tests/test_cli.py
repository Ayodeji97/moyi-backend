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
    if model.gaps:
        base["contractGaps"] = model.gaps
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


def run_with_output(argv, root):
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stderr(err), contextlib.redirect_stdout(out):
        code = main(argv, root)
    return code, err.getvalue(), out.getvalue()


def run(argv, root):
    code, err, _ = run_with_output(argv, root)
    return code, err


class CliTests(unittest.TestCase):
    def test_generate_then_check_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = repository(tmp, tiny())
            self.assertEqual(run(["check", "--strict"], root)[0], 1)
            self.assertEqual(run(["generate"], root)[0], 0)
            self.assertEqual(run(["check", "--strict"], root), (0, ""))

    def test_the_summary_counts_contract_gaps(self):
        gap = {"endpoint": "POST /api/v1/lamp", "status": 304, "note": "A conditional request is answered 304."}
        with tempfile.TemporaryDirectory() as tmp:
            root = repository(tmp, tiny(gaps=[gap]))
            self.assertEqual(run(["generate"], root)[0], 0)
            code, err, out = run_with_output(["check", "--strict"], root)
        self.assertEqual((code, err), (0, ""))
        self.assertTrue(out.strip().endswith("1 contract gaps. Nothing wrong."), out)

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

    def test_check_fails_on_a_stale_page_and_says_which(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = repository(tmp, tiny())
            run(["generate"], root)
            (root / "docs/state-map/lamp.md").write_text("old\n")
            code, err = run(["check"], root)
            self.assertEqual(code, 1)
            self.assertIn("state-map: lamp.md is stale; run scripts/state-map-generate", err)


if __name__ == "__main__":
    unittest.main()
