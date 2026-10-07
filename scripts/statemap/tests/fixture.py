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
                            {"id": "OFF", "label": "OFF"},
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
