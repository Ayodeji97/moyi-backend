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
