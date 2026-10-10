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

    def test_two_regions_may_not_share_an_id(self):
        machines, rows = two_regions()
        machines[0]["regions"][1]["id"] = "lamp"
        self.assertIn("two regions share an id", self.problems(tiny([row(), refusal()], machines=machines)))

    def test_two_states_of_a_region_may_not_share_an_id(self):
        model = tiny()
        model.machines[0]["regions"][0]["states"].append({"id": "ON", "label": "on again"})
        self.assertIn("lamp: two states share an id", self.problems(model))

    def test_a_state_id_is_one_word(self):
        model = tiny()
        model.machines[0]["regions"][0]["states"].append({"id": "ON AIR", "label": "on air", "built": False})
        self.assertIn("lamp: state id 'ON AIR' must match", self.problems(model))

    def states(self, *states):
        model = tiny()
        model.machines[0]["regions"][0]["states"] = list(states)
        return self.problems(model)

    def test_a_region_has_exactly_one_initial_state(self):
        off, on = {"id": "OFF", "label": "OFF"}, {"id": "ON", "label": "ON"}
        self.assertIn("lamp: 0 states are marked initial; a region starts in exactly one", self.states(off, on))
        both = self.states({**off, "initial": True}, {**on, "initial": True})
        self.assertIn("lamp: 2 states are marked initial; a region starts in exactly one", both)

    def test_the_initial_state_is_built(self):
        problems = self.states({"id": "OFF", "label": "OFF"}, {"id": "ON", "label": "ON"},
                               {"id": "BROKEN", "label": "BROKEN", "built": False, "initial": True})
        self.assertIn("lamp: the initial state BROKEN is not built", problems)

    def everywhere(self, cell, **over):
        model = tiny([row(), cell])
        model.everywhere[0].update(over)
        return self.problems(model)

    def test_an_everywhere_error_is_not_a_row(self):
        cell = refusal(status=401, code="UNAUTHENTICATED")
        sentence = "row lamp-on-press: 401 UNAUTHENTICATED can follow any call and is not a row here"
        self.assertIn(sentence, self.everywhere(cell))
        self.assertIn(sentence, self.everywhere(cell, rowsAllowedIn=["lamp.bulb"]))

    def test_an_everywhere_error_is_a_row_where_the_data_allows_it(self):
        cell = refusal(status=401, code="UNAUTHENTICATED")
        self.assertEqual(self.everywhere(cell, rowsAllowedIn=["lamp"]), "")

    def test_a_row_shares_a_status_with_an_everywhere_error_and_not_its_code(self):
        self.assertEqual(self.everywhere(refusal(status=401)), "")
        self.assertEqual(self.everywhere(refusal(code="UNAUTHENTICATED")), "")

    def card(self, **over):
        model = tiny()
        model.endpoints[0].update(over)
        return model

    def test_a_card_missing_a_key_is_named(self):
        model = tiny()
        del model.endpoints[0]["curl"]
        self.assertIn("endpoint POST /api/v1/lamp: missing curl", self.problems(model))

    def test_a_card_id_is_a_method_and_a_path(self):
        self.assertIn("endpoint lamp: the id is the method, a space, then the path", self.problems(self.card(id="lamp")))

    def test_a_card_auth_is_none_or_bearer(self):
        self.assertIn("endpoint POST /api/v1/lamp: auth is 'none' or 'bearer'", self.problems(self.card(auth="cookie")))

    def test_a_request_error_missing_a_key_is_named(self):
        errors = [{"status": 422, "code": "LAMP_NO_BULB"}]
        self.assertIn(
            "endpoint POST /api/v1/lamp: a request error is missing reason, evidence, evidenceRef, codeRef",
            self.problems(self.card(requestErrors=errors)),
        )

    def test_a_request_error_evidence_is_one_of_the_four(self):
        errors = [{"status": 422, "code": "LAMP_NO_BULB", "reason": "No bulb.", "evidence": "guess",
                   "evidenceRef": "", "codeRef": "src/Lamp.kt#fun press("}]
        self.assertIn(
            "endpoint POST /api/v1/lamp: evidence 'guess' is not one of smoke, test, hand, never-run",
            self.problems(self.card(requestErrors=errors)),
        )

    def test_an_unknown_region_is_refused(self):
        self.assertIn("row lamp-off-press: unknown region 'kettle'", self.problems(tiny([row(region="kettle")])))

    def test_an_actor_is_one_of_the_three(self):
        self.assertIn("row lamp-off-press: actor 'cat' is not one of you, partner, system", self.problems(tiny([row(actor="cat")])))

    def test_a_row_needs_a_reason(self):
        self.assertIn("row lamp-off-press: no reason", self.problems(tiny([row(reason="  ")])))

    def test_a_success_carries_no_error_code(self):
        self.assertIn("a success carries no error code", self.problems(tiny([row(code="LAMP_ALREADY_ON")])))

    def test_a_successful_request_needs_a_2xx(self):
        self.assertIn("a successful request needs a 2xx status", self.problems(tiny([row(status=404)])))

    def test_an_unreachable_cell_has_no_status(self):
        cell = row(outcome="unreachable", to="OFF")
        self.assertIn("an unreachable cell has no status and no code", self.problems(tiny([cell])))

    def test_a_row_needs_a_code_ref(self):
        self.assertIn("row lamp-off-press: no codeRef", self.problems(tiny([row(codeRef=" ")])))

    def test_a_guard_names_states_of_its_region(self):
        machines, rows = two_regions()
        rows[0] = {**rows[0], "guards": [{"region": "lamp.bulb", "states": ["COLD", "HOT"]}]}
        self.assertIn(
            "row lamp-off-press: guard names HOT, not states of lamp.bulb",
            self.problems(tiny(rows, machines=machines)),
        )

    def test_a_journey_needs_a_title_and_a_step(self):
        sentence = "journey J1: needs a title and at least one step"
        self.assertIn(sentence, self.problems(tiny(journeys=[{"id": "J1", "title": "On", "steps": []}])))
        untitled = {"id": "J1", "title": "", "steps": [{"row": "lamp-off-press", "note": ""}]}
        self.assertIn(sentence, self.problems(tiny(journeys=[untitled])))

    def test_a_journey_may_not_step_on_an_unreachable_cell(self):
        cell = row(id="lamp-on-press", **{"from": "ON"}, to="ON", outcome="unreachable", status=None)
        journey = {"id": "J1", "title": "On", "steps": [{"row": "lamp-on-press", "note": ""}]}
        self.assertIn(
            "journey J1: step 1 is a cell that cannot be reached",
            self.problems(tiny([row(), cell], journeys=[journey])),
        )


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
