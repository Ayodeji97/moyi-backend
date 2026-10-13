"""A two-state lamp: the smallest model every check can be shown against."""
from statemap.model import Model


def row(**over):
    base = {
        "id": "lamp-off-press",
        "region": "lamp",
        "from": "OFF",
        "action": "POST /api/v1/lamp",
        "actor": "you",
        "when": "",
        "guards": [],
        "outcome": "ok",
        "to": "ON",
        "status": 200,
        "code": None,
        "reason": "Pressing turns it on.",
        "rule": "BR-1",
        "evidence": "never-run",
        "evidenceRef": "",
        "codeRef": "src/Lamp.kt#fun press(",
    }
    return {**base, **over}


def refusal(**over):
    return row(
        **{
            "id": "lamp-on-press",
            "from": "ON",
            "outcome": "refused",
            "to": "ON",
            "status": 409,
            "code": "LAMP_ALREADY_ON",
            "reason": "It is already on.",
            **over,
        }
    )


def tiny(rows=None, **over):
    fields = {
        "stamp": "abc1234",
        "machines": [
            {
                "id": "lamp",
                "label": "Lamp",
                "regions": [
                    {
                        "id": "lamp",
                        "label": "The lamp",
                        "states": [
                            {"id": "OFF", "label": "OFF", "initial": True},
                            {"id": "ON", "label": "ON"},
                            {"id": "BROKEN", "label": "BROKEN", "built": False},
                        ],
                    }
                ],
            }
        ],
        "events": [{"id": "event:timer", "label": "the timer runs out", "actor": "system"}],
        "everywhere": [
            {
                "status": 401,
                "code": "UNAUTHENTICATED",
                "reason": "No valid token.",
                "codeRef": "src/Lamp.kt#fun press(",
            }
        ],
        "pending": {"endpoints": [], "codes": []},
        "endpoints": [
            {
                "id": "POST /api/v1/lamp",
                "summary": "press the switch",
                "machine": "lamp",
                "auth": "bearer",
                "headers": [],
                "requestErrors": [],
                "curl": 'curl -X POST "$API/lamp" -H "Authorization: Bearer $TOKEN"',
            }
        ],
        "rows": [row(), refusal()] if rows is None else rows,
        "journeys": [],
    }
    return Model(**{**fields, **over})


def two_regions():
    """The lamp with a bulb inside it: one request can move both regions.

    Returns (machines, rows) to pass to tiny(rows, machines=machines).
    """
    machines = [
        {
            "id": "lamp",
            "label": "Lamp",
            "regions": [
                {
                    "id": "lamp",
                    "label": "The lamp",
                    "states": [{"id": "OFF", "label": "OFF", "initial": True}, {"id": "ON", "label": "ON"}],
                },
                {
                    "id": "lamp.bulb",
                    "label": "The bulb",
                    "states": [{"id": "COLD", "label": "COLD", "initial": True}, {"id": "WARM", "label": "WARM"}],
                },
            ],
        }
    ]
    timer = {"action": "event:timer", "actor": "system", "status": None}
    rows = [
        row(),
        row(id="bulb-cold-press", region="lamp.bulb", **{"from": "COLD"}, to="WARM"),
        row(id="lamp-on-timer", **{"from": "ON"}, to="OFF", reason="It turns itself off.", **timer),
        row(id="bulb-warm-timer", region="lamp.bulb", **{"from": "WARM"}, to="COLD",
            reason="It cools down.", **timer),
        refusal(),
        refusal(id="bulb-warm-press", region="lamp.bulb", **{"from": "WARM"}, to="WARM"),
    ]
    return machines, rows
