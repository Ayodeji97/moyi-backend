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
    probe_labels,
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


def with_card_error(**over):
    model = tiny()
    model.endpoints[0]["requestErrors"] = [{
        "status": 422, "code": "LAMP_NO_BULB", "reason": "No bulb was named.",
        "evidence": "never-run", "evidenceRef": "", "codeRef": "src/Lamp.kt#fun press(", **over,
    }]
    return model


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

    def test_a_card_error_explains_a_documented_status(self):
        ops = {"POST /api/v1/lamp": {200, 401, 409, 422}}
        self.assertEqual(check_contract(with_card_error(), ops, True), [])

    def test_a_card_error_not_in_the_contract(self):
        self.assertIn("answers 422, which the contract does not document", self.problems(with_card_error()))

    def test_a_card_error_code_is_counted_as_used(self):
        codes = ["UNAUTHENTICATED", "LAMP_ALREADY_ON", "LAMP_NO_BULB"]
        self.assertEqual(check_error_codes(with_card_error(), codes, True), [])


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
        (root / "scripts/smoke.sh").write_text('# a probe nobody wrote, mentioned in a comment\nexpect "pressing an off lamp turns it on" 200 -- "$BASE/lamp"\n')
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

    def test_card_error_code_ref_is_validated(self):
        problems = self.problems(with_card_error(codeRef="src/Lamp.kt#fun toggle("))
        self.assertIn("POST /api/v1/lamp 422 LAMP_NO_BULB", problems)
        self.assertIn("'fun toggle(' is not in src/Lamp.kt", problems)

    def test_comment_text_does_not_match_probe_labels(self):
        self.assertIn("no probe in scripts/smoke.sh", self.problems(tiny([row(evidence="smoke", evidenceRef="a probe nobody wrote"), refusal()])))

    def test_card_error_with_empty_evidence_ref(self):
        problems = self.problems(with_card_error(evidence="smoke", evidenceRef=""))
        self.assertIn("POST /api/v1/lamp 422 LAMP_NO_BULB", problems)
        self.assertIn("no evidenceRef says what ran it", problems)

    def test_probe_labels_regex(self):
        source = """# a probe nobody wrote, mentioned in a comment
expect "a \\"quoted\\" label" 200
  header_is "the ETag is quoted" ETag x
"""
        self.assertEqual(probe_labels(source), ['a \\"quoted\\" label', 'the ETag is quoted'])


if __name__ == "__main__":
    unittest.main()
