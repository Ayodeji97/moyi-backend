import tempfile
import unittest
from pathlib import Path

from fixture import refusal, row, tiny, two_regions
from statemap.generate import outputs, stale, write
from statemap.mermaid import (
    NOTICE, clean, endpoints_md, grid_table, journeys_md, machine_md, sequence_diagram, state_diagram, told,
)

TIMER = row(
    id="lamp-on-timer", **{"from": "ON"}, to="OFF", action="event:timer", actor="system",
    status=None, reason="It turns itself off.",
)


GAP = {"endpoint": "POST /api/v1/lamp", "status": 409, "note": "It is already on."}


class MermaidTests(unittest.TestCase):
    def test_a_machine_page_prints_the_gaps_of_its_endpoints(self):
        page = machine_md(tiny(gaps=[GAP]), "lamp")
        self.assertIn("## Where the contract is silent", page)
        self.assertIn("| `POST /lamp` | 409 | It is already on. |", page)

    def test_a_machine_without_gaps_prints_no_such_section(self):
        self.assertNotIn("Where the contract is silent", machine_md(tiny(), "lamp"))

    def test_clean_removes_what_breaks_mermaid(self):
        self.assertEqual(clean('write: "today\'s" entry {bondId}; #1'), "write today's entry bondId 1")

    def test_the_diagram_draws_states_and_successes(self):
        diagram = state_diagram(tiny([row(), refusal(), TIMER]), "lamp")
        self.assertEqual(
            diagram.splitlines(),
            [
                "stateDiagram-v2",
                '    state "OFF" as lamp__OFF',
                '    state "ON" as lamp__ON',
                '    state "BROKEN" as lamp__BROKEN',
                "    lamp__OFF --> lamp__ON: you - press the switch",
                "    lamp__ON --> lamp__OFF: system - timer runs out",
                "    classDef unbuilt fill:#eeeeee,stroke:#aaaaaa,color:#888888",
                "    class lamp__BROKEN unbuilt",
            ],
        )

    def test_a_diagram_is_of_one_region(self):
        machines, rows = two_regions()
        self.assertEqual(state_diagram(tiny(rows, machines=machines), "lamp.bulb").splitlines(), [
            "stateDiagram-v2",  # no composite state, and no classDef: every state of the bulb is built
            '    state "COLD" as lamp_bulb__COLD',
            '    state "WARM" as lamp_bulb__WARM',
            "    lamp_bulb__COLD --> lamp_bulb__WARM: you - press the switch",
            "    lamp_bulb__WARM --> lamp_bulb__COLD: system - timer runs out",
        ])

    def test_each_region_has_its_own_diagram_under_its_heading(self):
        machines, rows = two_regions()
        page = machine_md(tiny(rows, machines=machines), "lamp")
        self.assertEqual(page.count("```mermaid"), 2)
        lamp, bulb = page.index("## The lamp"), page.index("## The bulb")
        first, second = page.index("```mermaid"), page.rindex("```mermaid")
        self.assertTrue(lamp < first < page.index("### Every action in every state") < bulb < second)
        self.assertIn("lamp_bulb__COLD", page[second:])
        self.assertNotIn("lamp_bulb__COLD", page[:bulb])

    def test_a_refusal_is_not_drawn(self):
        self.assertNotIn("lamp__ON --> lamp__ON", state_diagram(tiny(), "lamp"))

    def ways(self, count, **over):
        rows = [row(id=f"r{n}", when=f"case {n}", action=f"POST /api/v1/lamp/{n}", **over) for n in range(count)]
        cards = [{**tiny().endpoints[0], "id": f"POST /api/v1/lamp/{n}", "summary": f"way {n}"} for n in range(count)]
        return rows, cards

    def test_more_than_one_action_between_two_states_is_counted(self):
        for count in (2, 3):
            rows, cards = self.ways(count)
            diagram = state_diagram(tiny(rows, endpoints=cards), "lamp")
            self.assertIn(f"lamp__OFF --> lamp__ON: you - {count} actions", diagram)

    def test_one_action_each_for_two_actors_is_spelled_out(self):
        rows, cards = self.ways(1)
        rows.append({**TIMER, **{"from": "OFF"}, "to": "ON"})
        diagram = state_diagram(tiny(rows, endpoints=cards), "lamp")
        self.assertIn("lamp__OFF --> lamp__ON: you - way 0, system - timer runs out", diagram)

    def test_one_action_under_two_conditions_is_one_action(self):
        rows = [row(when="the bulb is new"), row(id="lamp-off-press-again", when="the bulb is old")]
        self.assertIn("lamp__OFF --> lamp__ON: you - press the switch", state_diagram(tiny(rows), "lamp"))

    # What a partner does is said as their act, not yours (the product's promise is that they
    # cannot touch your entry).

    def partners(self):
        model = tiny([row(), refusal(), row(id="lamp-on-partner", **{"from": "ON"}, to="OFF", actor="partner", status=204)])
        model.endpoints[0]["summary"] = "press your switch when you like, as you do"
        return model

    def test_a_partners_request_is_told_as_theirs(self):
        model = self.partners()
        self.assertEqual(told(model, model.rows[2]), "press their switch when they like, as they do")
        self.assertEqual(told(model, model.rows[0]), "press your switch when you like, as you do")

    def test_a_word_that_only_contains_you_is_left_alone(self):
        model = self.partners()
        model.endpoints[0]["summary"] = "press yours, youthfully"
        self.assertEqual(told(model, model.rows[2]), "press yours, youthfully")

    def test_a_partners_request_reads_as_theirs_on_the_arrow_and_in_the_table(self):
        page = machine_md(self.partners(), "lamp")
        self.assertIn("lamp__ON --> lamp__OFF: partner - press their switch when they like, as they do", page)
        self.assertIn("| `ON` | press their switch when they like, as they do | partner |", page)
        self.assertIn("lamp__OFF --> lamp__ON: you - press your switch when you like, as you do", page)

    def test_an_arrow_does_not_name_the_actor_twice(self):
        knock = row(id="lamp-on-knock", **{"from": "ON"}, to="OFF", action="event:knock", actor="partner", status=None)
        events = tiny().events + [{"id": "event:knock", "label": "your partner knocks it over", "actor": "partner"}]
        page = machine_md(tiny([row(), refusal(), knock, {**TIMER, "from": "OFF", "to": "ON"}], events=events), "lamp")
        self.assertIn("lamp__ON --> lamp__OFF: partner - knocks it over", page)
        self.assertIn("lamp__OFF --> lamp__ON: you - press the switch, system - timer runs out", page)
        # the table has a Who column and keeps the sentence whole
        self.assertIn("| `ON` | your partner knocks it over | partner |", page)
        self.assertIn("| `OFF` | the timer runs out | system |", page)

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
        self.assertIn("| In | Action | When | Answer | Why | Rule | Evidence |", page)
        self.assertIn("| `ON` | press the switch |  | 409 `LAMP_ALREADY_ON` | It is already on. | BR-1 | never-run |", page)
        self.assertIn("| In | What happens | Who | When | Leads to | Why | Evidence |", page)
        self.assertIn("| `ON` | the timer runs out | system |  | `OFF` | It turns itself off. | never-run |", page)

    def test_the_condition_is_printed_in_both_tables(self):
        rows = [row(), refusal(when="the bulb | is hot"), {**TIMER, "when": "an hour\nhas passed"}]
        page = machine_md(tiny(rows), "lamp")
        self.assertIn("| `ON` | press the switch | the bulb \\| is hot | 409 `LAMP_ALREADY_ON` |", page)
        self.assertIn("| `ON` | the timer runs out | system | an hour has passed | `OFF` |", page)

    def test_the_page_lists_what_you_can_do_between_the_grid_and_the_refusals(self):
        stays = row(id="lamp-on-look", **{"from": "ON"}, to="ON", action="GET /api/v1/lamp", when="it is dark",
                    reason="Looking | changes nothing.", evidence="smoke", evidenceRef="looking at a lamp")
        cards = tiny().endpoints + [{**tiny().endpoints[0], "id": "GET /api/v1/lamp", "summary": "look at it"}]
        page = machine_md(tiny([row(), refusal(), stays, TIMER], endpoints=cards), "lamp")
        table = page[page.index("### You can"):page.index("### Refused here")]
        self.assertTrue(page.index("### Every action in every state") < page.index("### You can"))
        self.assertEqual(table.splitlines()[2:6], [
            "| In | Action | When | Leads to | Why | Evidence |",
            "|---|---|---|---|---|---|",
            "| `OFF` | press the switch |  | `ON` | Pressing turns it on. | never-run |",
            "| `ON` | look at it | it is dark | stays | Looking \\| changes nothing. | smoke |",
        ])
        self.assertNotIn("It is already on.", table)
        self.assertNotIn("It turns itself off.", table)

    def test_a_region_where_nothing_succeeds_has_no_you_can(self):
        self.assertNotIn("### You can", machine_md(tiny([refusal(**{"from": "OFF"}, to="OFF", id="a"), refusal()]), "lamp"))

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
                "    participant Job as The service, on its own schedule",
                "    You->>API: press the switch",
                "    API-->>You: 200, lamp is ON",
                "    You->>API: press the switch",
                "    API-->>You: 409 LAMP_ALREADY_ON",
                "    Job->>API: the timer runs out",
                "    Note over API: lamp is OFF",
            ],
        )


HEAD = ["sequenceDiagram", "    actor You", "    actor Partner", "    participant API",
        "    participant Job as The service, on its own schedule"]


def grouped(first, second, extra=()):
    machines, rows = two_regions()
    journey = {"id": "J1", "title": "Both", "steps": [
        {"row": first, "note": ""},
        {"row": second, "note": "", "sameRequest": True},
        *extra,
    ]}
    return tiny(rows, machines=machines, journeys=[journey]), journey


class SameRequestTests(unittest.TestCase):
    def test_two_ok_steps_of_one_request_are_one_call_with_one_answer(self):
        model, journey = grouped("lamp-off-press", "bulb-cold-press")
        self.assertEqual(sequence_diagram(model, journey).splitlines(), HEAD + [
            "    You->>API: press the switch",
            "    API-->>You: 200, lamp is ON, lamp.bulb is WARM",
        ])

    def test_two_event_steps_are_one_call_with_one_note(self):
        model, journey = grouped("lamp-on-timer", "bulb-warm-timer")
        self.assertEqual(sequence_diagram(model, journey).splitlines(), HEAD + [
            "    Job->>API: the timer runs out",
            "    Note over API: lamp is OFF, lamp.bulb is COLD",
        ])

    def test_two_refused_steps_are_one_call_with_one_refusal(self):
        model, journey = grouped("lamp-on-press", "bulb-warm-press")
        self.assertEqual(sequence_diagram(model, journey).splitlines(), HEAD + [
            "    You->>API: press the switch",
            "    API-->>You: 409 LAMP_ALREADY_ON",
        ])

    def test_a_group_is_followed_by_an_ordinary_step_as_before(self):
        model, journey = grouped("lamp-off-press", "bulb-cold-press", [{"row": "lamp-on-timer", "note": ""}])
        self.assertEqual(sequence_diagram(model, journey).splitlines()[5:], [
            "    You->>API: press the switch",
            "    API-->>You: 200, lamp is ON, lamp.bulb is WARM",
            "    Job->>API: the timer runs out",
            "    Note over API: lamp is OFF",
        ])

    def test_the_same_action_without_the_flag_is_two_requests(self):
        model, journey = grouped("lamp-off-press", "bulb-cold-press")
        del journey["steps"][1]["sameRequest"]
        self.assertEqual(sequence_diagram(model, journey).splitlines(), HEAD + [
            "    You->>API: press the switch",
            "    API-->>You: 200, lamp is ON",
            "    You->>API: press the switch",
            "    API-->>You: 200, lamp.bulb is WARM",
        ])

    def test_the_list_marks_the_grouped_step_and_only_that_one(self):
        model, journey = grouped("lamp-off-press", "bulb-cold-press")
        page = journeys_md(model)
        self.assertIn("\n2. (the same request) **you**: press the switch.", page)
        self.assertIn("\n1. **you**: press the switch.", page)
        self.assertEqual(page.count("(the same request)"), 1)


CARD_ERROR = {"status": 422, "code": "LAMP_NO_BULB", "reason": "No bulb | was named.",
              "evidence": "smoke", "evidenceRef": "a lamp with no bulb", "codeRef": "src/Lamp.kt#fun press("}
LOOK = {"id": "GET /api/v1/lamp", "summary": "look at it", "auth": "none",
        "headers": ["If-None-Match (optional)", "Accept (application/json)"], "requestErrors": [],
        "curl": 'curl "$API/lamp"'}


class EndpointPageTests(unittest.TestCase):
    def test_the_page_is_every_card_with_every_answer(self):
        model = tiny(gaps=[GAP])
        model.endpoints[0]["requestErrors"] = [CARD_ERROR]
        model.everywhere[0]["rowsAllowedIn"] = ["lamp", "lamp.bulb"]
        self.assertEqual(endpoints_md(model).split("\n\n"), [
            NOTICE,
            "# Every endpoint",
            "Describes `main @ abc1234`. Every answer each endpoint can give, with its cause and how to try it. "
            "How to read this: [README](README.md).",
            "## Errors that can follow any call",
            "Any of these can follow any endpoint below. They are not repeated under each one.",
            "| Status | Code | Why |\n|---|---|---|\n| 401 | `UNAUTHENTICATED` | No valid token. |",
            "`401 UNAUTHENTICATED` is also a row in the regions `lamp` and `lamp.bulb`, where it depends on the state.",
            "## Lamp",
            "### POST /lamp",
            "press the switch",
            "**Needs:** an access token.",
            "**Try it:**",
            '```sh\ncurl -X POST "$API/lamp" -H "Authorization: Bearer $TOKEN"\n```',
            "#### Every answer",
            "| Region | In | When | Answer | Why | Rule | Evidence |\n|---|---|---|---|---|---|---|\n"
            "| `lamp` | `OFF` |  | 200 → `ON` | Pressing turns it on. | BR-1 | never-run |\n"
            "| `lamp` | `ON` |  | 409 `LAMP_ALREADY_ON` | It is already on. | BR-1 | never-run |",
            "#### Whatever the state",
            "| Status | Code | Why | Evidence |\n|---|---|---|---|\n| 422 | `LAMP_NO_BULB` | No bulb \\| was named. | smoke |",
            "#### The contract is silent on",
            "| Status | What the code does |\n|---|---|\n| 409 | It is already on. |\n",
        ])

    def test_a_card_with_no_request_errors_and_no_gaps_prints_neither_section(self):
        page = endpoints_md(tiny())
        self.assertIn("#### Every answer", page)
        self.assertNotIn("Whatever the state", page)
        self.assertNotIn("The contract is silent on", page)
        self.assertNotIn("is also a row", page)

    def test_a_gap_is_printed_under_its_own_endpoint_only(self):
        look = row(id="lamp-off-look", action=LOOK["id"], to="OFF")
        looked = row(id="lamp-on-look", action=LOOK["id"], **{"from": "ON"}, to="ON")
        page = endpoints_md(tiny([row(), refusal(), look, looked], endpoints=tiny().endpoints + [LOOK], gaps=[GAP]))
        press, looking = page.split("### GET /lamp")
        self.assertIn("The contract is silent on", press)
        self.assertNotIn("The contract is silent on", looking)

    def test_what_a_card_needs(self):
        look = row(id="lamp-off-look", action=LOOK["id"], to="OFF")
        page = endpoints_md(tiny([row(), refusal(), look], endpoints=tiny().endpoints + [LOOK]))
        self.assertIn(
            "### GET /lamp\n\nlook at it\n\n**Needs:** no access token.\n\n"
            "- If-None-Match (optional)\n- Accept (application/json)\n\n**Try it:**\n\n```sh\ncurl \"$API/lamp\"\n```",
            page,
        )

    def test_a_success_and_its_reason_are_on_the_page(self):
        stays = row(id="lamp-on-press-again", **{"from": "ON"}, to="ON", when="it is | hot", reason="Nothing\nchanges.")
        page = endpoints_md(tiny([row(), stays]))
        self.assertIn("| `lamp` | `OFF` |  | 200 → `ON` | Pressing turns it on. | BR-1 | never-run |", page)
        self.assertIn("| `lamp` | `ON` | it is \\| hot | 200 stays | Nothing changes. | BR-1 | never-run |", page)

    def test_a_cell_that_cannot_occur_says_so_with_its_reason(self):
        cell = row(id="lamp-on-press", **{"from": "ON"}, to="ON", outcome="unreachable", status=None, reason="It cannot be on.")
        self.assertIn("| `lamp` | `ON` |  | not reachable | It cannot be on. | BR-1 | never-run |", endpoints_md(tiny([row(), cell])))

    def test_answers_are_only_your_own_and_only_this_endpoints(self):
        theirs = row(id="lamp-on-partner", **{"from": "ON"}, to="OFF", actor="partner", reason="They pressed it.")
        look = row(id="lamp-off-look", action=LOOK["id"], to="OFF", reason="Looking changes nothing.")
        page = endpoints_md(tiny([row(), refusal(), theirs, TIMER, look], endpoints=tiny().endpoints + [LOOK]))
        press, looking = page.split("### GET /lamp")
        self.assertNotIn("They pressed it.", page)
        self.assertNotIn("It turns itself off.", page)
        self.assertNotIn("Looking changes nothing.", press)
        self.assertIn("Looking changes nothing.", looking)
        self.assertNotIn("Pressing turns it on.", looking)

    def test_answers_are_in_region_then_state_then_file_order(self):
        machines, rows = two_regions()
        rows = [
            refusal(id="bulb-warm-press", region="lamp.bulb", **{"from": "WARM"}, to="WARM", reason="4"),
            refusal(reason="2", when="b"),
            row(id="bulb-cold-press", region="lamp.bulb", **{"from": "COLD"}, to="WARM", reason="3"),
            refusal(id="lamp-on-press-c", reason="2c", when="c"),
            row(reason="1"),
        ]
        table = endpoints_md(tiny(rows, machines=machines)).split("#### Every answer\n\n")[1].splitlines()[2:]
        self.assertEqual([line.split(" | ")[4] for line in table], ["1", "2", "2c", "3", "4"])

    def machines(self):
        machines, _ = two_regions()
        bulb = machines[0]["regions"].pop()
        return machines + [{"id": "bulb", "label": "Bulb", "regions": [bulb]}]

    def headings(self, rows):
        cards = tiny().endpoints + [LOOK]
        page = endpoints_md(tiny(rows, machines=self.machines(), endpoints=cards))
        return [line for line in page.splitlines() if line.startswith(("## ", "### "))][1:]

    def test_a_card_is_filed_under_the_machine_where_it_has_most_rows_of_your_own(self):
        look = dict(action=LOOK["id"], region="lamp.bulb")
        rows = [
            row(), refusal(),
            row(id="l1", **look, **{"from": "COLD"}, to="COLD"), row(id="l2", **look, **{"from": "WARM"}, to="WARM"),
            row(id="l3", action=LOOK["id"], to="OFF"),
            # the partner's rows are not yours and do not count
            row(id="p1", actor="partner", action=LOOK["id"]), row(id="p2", actor="partner", action=LOOK["id"]),
            row(id="p3", actor="partner", action=LOOK["id"]),
        ]
        self.assertEqual(self.headings(rows), ["## Lamp", "### POST /lamp", "## Bulb", "### GET /lamp"])

    def test_a_tie_goes_to_the_machine_listed_first(self):
        rows = [row(), refusal(), row(id="l1", action=LOOK["id"], region="lamp.bulb", **{"from": "COLD"}, to="COLD"),
                row(id="l3", action=LOOK["id"], to="OFF")]
        self.assertEqual(self.headings(rows), ["## Lamp", "### POST /lamp", "### GET /lamp"])

    def test_a_machine_page_links_to_the_endpoints(self):
        line = "Every answer of every endpoint, with its cause and how to try it: [endpoints](endpoints.md)"
        self.assertEqual(machine_md(tiny(), "lamp").split("\n\n")[1:4], [
            "# The lamp machine", "Describes `main @ abc1234`. How to read this: [README](README.md).", line,
        ])
        self.assertNotIn("endpoints.md", machine_md(tiny(endpoints=[], rows=[]), "lamp"))


class GenerateTests(unittest.TestCase):
    def test_a_machine_with_no_rows_gets_no_file(self):
        self.assertEqual(list(outputs(tiny(rows=[]))), ["endpoints.md"])

    def test_the_endpoints_page_is_written_while_there_is_a_card(self):
        self.assertEqual(list(outputs(tiny())), ["lamp.md", "endpoints.md"])
        self.assertEqual(outputs(tiny())["endpoints.md"], endpoints_md(tiny()))
        self.assertEqual(outputs(tiny(rows=[], endpoints=[])), {})

    def test_written_files_are_not_stale_until_the_data_moves(self):
        every = ["lamp.md is stale; run scripts/state-map-generate", "endpoints.md is stale; run scripts/state-map-generate"]
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            self.assertEqual(stale(tiny(), out), [
                "lamp.md is missing; run scripts/state-map-generate",
                "endpoints.md is missing; run scripts/state-map-generate",
            ])
            write(tiny(), out)
            self.assertEqual(stale(tiny(), out), [])
            # a success's reason and a refusal's are both on both pages
            self.assertEqual(stale(tiny([row(reason="Changed."), refusal()]), out), every)
            self.assertEqual(stale(tiny([row(), refusal(reason="Changed.")]), out), every)

    def test_a_card_that_changes_makes_only_the_endpoints_page_stale(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            write(tiny(), out)
            model = tiny()
            model.endpoints[0]["curl"] = 'curl -X POST "$API/lamp"'
            self.assertEqual(stale(model, out), ["endpoints.md is stale; run scripts/state-map-generate"])

    def test_a_file_the_data_no_longer_produces_is_reported(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            write(tiny(), out)
            self.assertEqual(stale(tiny(rows=[], endpoints=[]), out), [
                "endpoints.md is no longer generated from the data; delete it",
                "lamp.md is no longer generated from the data; delete it",
            ])

    def test_a_hand_written_file_is_left_alone(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            write(tiny(), out)
            (out / "README.md").write_text("# The state map\n")
            self.assertEqual(stale(tiny(), out), [])


if __name__ == "__main__":
    unittest.main()
