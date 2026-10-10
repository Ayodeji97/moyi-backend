# The state map

What the backend does in every state it can be in: every action, where it leads, what
refuses it and why. Three machines (account, bond and day), each with a diagram and tables,
built from hand-written data that a script checks against the API contract and the error
codes. It is for learning the system as a set of state machines, not for building a client.

- [Account](account.md): registering, signing in, sessions, password reset
- [Bond](bond.md): pairing, invites, two-party consent, leaving and blocking
- [Day](day.md): writing, the reveal, the close job
- [Journeys](journeys.md): the five journeys of doc 02, traced as paths through the rows

The diagrams and tables are generated from `data/`. Do not edit them by hand.

## How to read a machine

Each machine is split into regions: the account has the account itself, the session and the
password reset; the bond has the bond, the invite and the proposal; the day has the day, your
entry and what you see of your partner's. Each region has three tables, under a diagram that
shows the states and the actions that succeed.

- **The diagram.** Each arrow says who acts: you, your partner or the system. Refusals are
  not drawn.
- **Every action in every state.** The complete record. A cell is a success and where it
  leads, an error code, or "not reachable".
- **Refused here.** Each refusal with its status, code, reason, the rule behind it, and the
  evidence that it happens.
- **Happens without you.** What your partner and the system can do to this state.

**States that name an absence.** Some states are not enum values but real absences that the
code has: `NO_BOND`, `ANONYMOUS`, `NO_SESSION`, and the day's `NOT_OPENED`, which means "no
row yet" (a day has no database row until its first entry is written). A grey state on a
diagram exists in the code, or is designed, and nothing reaches it yet.

**Guards.** An action in one machine may require a state in another: writing an entry needs
a signed-in account and a bond that is `PENDING_MEMBER` or `ACTIVE`. The data records this as
the row's `guards`. The generated pages do not print guards, so where a cell is mirrored from
another machine, its `when` or its reason carries the condition.

**Errors that can follow any call.** 400, 401, 403, 405, 415, 422, 429 and 500 can follow
any call and are in no grid. They are listed once, in `data/model.json` under `everywhere`.
One exception: in the account machine's `account` and `account.session` regions, a
`401 UNAUTHENTICATED` is a row, because there the missing or expired token is the state.

One more fact about 401, because a client will trip on it. A route that needs no access token
still checks one that is sent, and an expired or unaccepted token is `401` there too. This is
read from the code; nothing runs it. Send no `Authorization` header to a public route.

**Journeys.** In `journeys.md` a step names one row. A step marked as the same request as the
one before is drawn in the sequence diagram as one call with one answer. A journey stops
where the built API stops.

## Where a fact comes from

Every row cites the code it was read from (`codeRef`) and says what showed it (`evidence`).

| Evidence | Meaning |
|---|---|
| `smoke` | a probe in `scripts/smoke.sh` asserts it |
| `test` | an automated test asserts it |
| `hand` | a person saw it with curl |
| `never-run` | read from the code; nothing has shown it |

`smoke` and `test` mean the cited probe or test establishes both the status and the error
code the row claims. For a success, they mean it shows a response that only a success could
give. A test that asserts only the status does not count: such a row is `never-run`, and its
reason says so. One further form is accepted: a test that asserts the status and that the
body is byte-identical to another response whose code a named probe or test asserts.

These rows were checked by reading the probe or the test, not by running it for this work.
Nobody ran `scripts/smoke.sh` while the map was built. A reader closes that gap by running it
(see "How to verify it yourself").

The `never-run` rows are a list of the probes that are missing. Each machine page shows the
evidence of every refusal. To count rows by evidence:

```
python3 -c "
import json, collections
c = collections.Counter()
for f in ('account','bond','day'):
    for r in json.load(open(f'docs/state-map/data/{f}.json')):
        c['unreachable' if r['outcome'] == 'unreachable' else r['evidence']] += 1
print(dict(c))"
```

## Where the contract is silent

`contracts/openapi.json` does not document some statuses that the code answers. The map does
not edit the contract and does not bend a row to fit it. Each such status is listed in
`contractGaps` in `data/model.json`, with a note, and each machine page ends with a "Where
the contract is silent" section. The check fails when a listed gap has since been documented,
so the list can only shrink.

## How to verify it yourself

Two commands need nothing running:

```
scripts/state-map-check --strict
PYTHONPATH=scripts python3 -m unittest discover -s scripts/statemap/tests
```

The first checks the data against the contract, the error codes and the code it cites, and
prints a summary line with the number of rows, of `never-run` rows, of pending items and of
contract gaps. The second tests the checker itself.

To run the probes that the `smoke` rows cite, bring up the compose Postgres and Valkey, then:

```
MOYI_DB=moyi_statemap scripts/smoke.sh
docker compose exec postgres dropdb -U moyi moyi_statemap
```

The script boots the jar, flushes the rate-limit buckets in the shared Valkey, and ends with
`<n> passed, <n> failed`. The second command removes the database it used. If a probe fails,
search `docs/state-map/data/*.json` for its label to find the rows that lean on it.

## How to change it

Edit `data/`, then:

```
scripts/state-map-generate
scripts/state-map-check --strict
```

If you changed the tooling in `scripts/statemap/`, also run the unit tests above. Commit the
data and the generated pages together.

CI runs `scripts/state-map-check --strict` and fails when:

- an endpoint in the contract has no card, or a card names one that is not in the contract;
- an `ErrorCode` is returned by no row;
- the contract adds or drops a status that the map does not explain;
- a cell of a grid is empty, or a built state is neither entered nor left by any row;
- anything is still on the `pending` list;
- a citation no longer resolves, because its text has left the file it names;
- a generated page is stale, missing or orphaned;
- a contract gap has since been documented.

When the check says a citation no longer resolves, read the code again before changing the
citation: the row may no longer be true.

## What this is not

- Not a guide to a client or its screens. It is organised by state, not by screen.
- Not a substitute for `contracts/openapi.json`, which stays the contract.
- Not finished as a page. The clickable page described in the design spec is not built yet
  (the spec's step 5). The Markdown pages here are what exists: the diagrams, the tables and
  the journeys. Endpoint cards, search and a toggle for drawing refusals belong to that page.

The design is in `docs/superpowers/specs/2026-10-07-backend-state-map-design.md`.
