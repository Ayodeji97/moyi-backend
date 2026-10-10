import json
import tempfile
import unittest
from pathlib import Path

from fixture import refusal, row, tiny, two_regions
from statemap.model import load, validate

GAP = {"endpoint": "POST /api/v1/lamp", "status": 409, "note": "It is already on."}


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

    def same_request(self, first, second, **over):
        machines, rows = two_regions()
        journey = {"id": "J1", "title": "On", "steps": [
            {"row": first, "note": ""},
            {"row": second, "note": "", "sameRequest": True, **over},
        ]}
        return self.problems(tiny(rows, machines=machines, journeys=[journey]))

    def test_a_step_may_be_the_same_request_as_the_one_before(self):
        self.assertEqual(self.same_request("lamp-off-press", "bulb-cold-press"), "")

    def test_a_chain_of_same_request_steps_is_clean(self):
        machines, rows = two_regions()
        rows[0] = {**rows[0], "when": "first"}
        rows.append(row(id="lamp-off-press-again", when="again"))
        journey = {"id": "J1", "title": "On", "steps": [
            {"row": "lamp-off-press", "note": ""},
            {"row": "bulb-cold-press", "note": "", "sameRequest": True},
            {"row": "lamp-off-press-again", "note": "", "sameRequest": True},
        ]}
        self.assertEqual(validate(tiny(rows, machines=machines, journeys=[journey])), [])

    def test_the_first_step_cannot_be_the_same_request(self):
        machines, rows = two_regions()
        journey = {"id": "J1", "title": "On", "steps": [{"row": "lamp-off-press", "note": "", "sameRequest": True}]}
        self.assertIn(
            "journey J1: step 1 says it is the same request as the step before, and there is none",
            self.problems(tiny(rows, machines=machines, journeys=[journey])),
        )

    def test_a_same_request_step_must_have_the_same_action_and_actor(self):
        sentence = "journey J1: step 2 says it is the same request as the step before, but who acts or what they call differs"
        self.assertIn(sentence, self.same_request("lamp-off-press", "bulb-warm-timer"))

    def test_a_same_request_step_must_answer_with_the_same_status(self):
        machines, rows = two_regions()
        rows[1] = {**rows[1], "status": 201}
        journey = {"id": "J1", "title": "On", "steps": [
            {"row": "lamp-off-press", "note": ""},
            {"row": "bulb-cold-press", "note": "", "sameRequest": True},
        ]}
        self.assertIn(
            "journey J1: step 2 says it is the same request as the step before, but the two answer with different statuses",
            self.problems(tiny(rows, machines=machines, journeys=[journey])),
        )

    def test_a_same_request_step_must_be_in_another_region(self):
        machines, rows = two_regions()
        rows.append(row(id="lamp-off-press-again", when="again"))
        journey = {"id": "J1", "title": "On", "steps": [
            {"row": "lamp-off-press", "note": ""},
            {"row": "lamp-off-press-again", "note": "", "sameRequest": True},
        ]}
        self.assertIn(
            "journey J1: step 2 says it is the same request as the step before, but both rows are in the same region",
            self.problems(tiny(rows, machines=machines, journeys=[journey])),
        )

    def test_same_request_must_be_true_or_false(self):
        self.assertIn(
            "journey J1: step 2 has a sameRequest that is not true or false",
            self.same_request("lamp-off-press", "bulb-cold-press", sameRequest="yes"),
        )

    def test_same_request_false_is_an_ordinary_step(self):
        self.assertEqual(self.same_request("lamp-off-press", "lamp-on-timer", sameRequest=False), "")

    def test_two_rows_may_not_share_an_id(self):
        self.assertIn("two rows share the id", self.problems(tiny([row(when="a"), row(when="b")])))

    def test_a_well_formed_gap_is_clean(self):
        self.assertEqual(validate(tiny(gaps=[GAP])), [])

    def test_a_gap_missing_keys_is_named(self):
        self.assertIn("contractGaps: an entry is missing status, note", self.problems(tiny(gaps=[{"endpoint": "POST /api/v1/lamp"}])))

    def test_a_gap_needs_an_endpoint_card(self):
        gap = {**GAP, "endpoint": "GET /api/v1/lamp"}
        self.assertIn("contractGaps: GET /api/v1/lamp has no endpoint card", self.problems(tiny(gaps=[gap])))

    def test_a_gap_status_must_be_a_whole_number(self):
        gap = {**GAP, "status": "409"}
        self.assertIn("contractGaps: POST /api/v1/lamp 409 needs a whole-number status and a note", self.problems(tiny(gaps=[gap])))

    def test_a_gap_note_may_not_be_blank(self):
        gap = {**GAP, "note": "  "}
        self.assertIn("contractGaps: POST /api/v1/lamp 409 needs a whole-number status and a note", self.problems(tiny(gaps=[gap])))

    def test_a_gap_may_not_be_listed_twice(self):
        self.assertIn("contractGaps: POST /api/v1/lamp 409 is listed twice", self.problems(tiny(gaps=[GAP, dict(GAP)])))


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
        self.assertEqual(loaded.gaps, [])

    def test_contract_gaps_are_read_from_model_json(self):
        model = tiny(gaps=[GAP])
        with tempfile.TemporaryDirectory() as tmp:
            data = Path(tmp)
            base = {k: getattr(model, k) for k in ("stamp", "machines", "events", "everywhere", "pending")}
            (data / "model.json").write_text(json.dumps({**base, "contractGaps": model.gaps}))
            (data / "endpoints.json").write_text(json.dumps(model.endpoints))
            (data / "day.json").write_text(json.dumps(model.rows))
            loaded = load(data)
        self.assertEqual(loaded.gaps, [GAP])


if __name__ == "__main__":
    unittest.main()
