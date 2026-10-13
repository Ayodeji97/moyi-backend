# The state map

What the backend does in every state it can be in: every action, where it leads, what
refuses it and why. Three machines (account, bond and day), each with diagrams and tables,
and one page of every endpoint with every answer it can give. All of it is built from
hand-written data that a script checks against the API contract and the error codes. It is
for learning the system as a set of state machines, not for building a client.

- [Account](account.md): registering, signing in, sessions, password reset
- [Bond](bond.md): pairing, invites, two-party consent, leaving and blocking
- [Day](day.md): writing, the reveal, the close job
- [Endpoints](endpoints.md): each endpoint, what it needs, a curl line, and every answer
  with its cause
- [Journeys](journeys.md): the five journeys of doc 02, traced as paths through the rows

Those five pages are generated from `data/`. Do not edit them by hand. This page is written
by hand.

**The stamp.** Each generated page says `main @ <sha>`. That is the commit of `main` the map
was last read against in full. Nothing keeps it current but a person: the check below proves
the map still fits the contract and the code it cites, not that somebody has read the code
again since that commit.

## How to read a machine

Each machine is split into regions: the account has the account itself, the session and the
password reset; the bond has the bond, the invite and the proposal; the day has the day, your
entry and what you see of your partner's. Each region has a diagram of its own and, under
it, up to four tables.

- **The diagram.** The region's states and the actions that succeed and change the state.
  Each arrow says who acts: you, your partner or the system. Where one actor has more than
  one action between the same two states the arrow says how many ("you - 3 actions"), and
  the tables name them. Refusals are not drawn. Grey means designed or only partly built,
  and no endpoint reaches it.
- **Every action in every state.** The complete record of your own actions. A cell is a
  success and where it leads, an error code, or "not reachable"; where a cell has more than
  one answer, each carries its condition in brackets.
- **You can.** Each success of your own, with its condition, where it leads ("stays" when
  the state does not change), the reason, and the evidence that it happens.
- **Refused here.** Each refusal with its condition, status, code, reason, the rule behind
  it, and the evidence.
- **Happens without you.** What your partner and the system do to this state, with the
  condition. A request your partner makes is worded as theirs ("delete their entry").

**The endpoints page** turns the same rows the other way: for one endpoint, every answer it
can give across all regions, in one table, with the request errors that do not depend on any
state, what the contract does not document for it, what it needs and a curl line. Each
endpoint is filed under the machine its card names (`machine` in `data/endpoints.json`):
the one the endpoint belongs to, whichever regions its rows are in. Leaving a bond is
under Bond, though most of its rows say what becomes of the day and its entries.

**States that name an absence.** Some states are not enum values but real absences that the
code has: `NO_BOND`, `ANONYMOUS`, `NO_SESSION`, and the day's `NOT_OPENED`, which means "no
row yet" (a day has no database row until the first entry is written or the close job
records it).

**Guards.** An action in one machine may require a state in another: writing an entry needs
a signed-in account and a bond that is `PENDING_MEMBER` or `ACTIVE`. The data records this as
the row's `guards`. The generated pages do not print guards, so where a cell is mirrored from
another machine, its `when` or its reason carries the condition.

`account: SIGNED_IN` on a bond or day row stands for "the call carries an access token the
server accepts"; the session region says when that is.

**Errors that can follow any call.** 400, 401, 403, 405, 406, 415, 422, 429 and 500 can
follow any call and are in no grid. They are listed once, in `data/model.json` under
`everywhere`, and printed at the top of the endpoints page. One exception, which the data
states as `rowsAllowedIn` on the 401 entry: in the account machine's `account` and
`account.session` regions, a `401 UNAUTHENTICATED` is a row, because there the missing or
expired token is the state. The check refuses such a row anywhere else.

One more fact about 401, because a client will trip on it. A route that needs no access token
still checks one that is sent, and an expired or unaccepted token is `401` there too. This is
read from the code; nothing runs it. Send no `Authorization` header to a public route.

**Journeys.** In `journeys.md` a step names one row. A step marked as the same request as the
one before is drawn in the sequence diagram as one call with one answer. The fourth
participant, "The service, on its own schedule", is the close job and the clock: an arrow
from it is something the service does by itself, not a call anybody makes. A journey stops
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

The `never-run` rows are a list of the probes that are missing. The pages show the evidence
of every row. To count rows by evidence:

```
python3 -c "
import json, collections
c = collections.Counter()
for f in ('account','bond','day'):
    for r in json.load(open(f'docs/state-map/data/{f}.json')):
        c['unreachable' if r['outcome'] == 'unreachable' else r['evidence']] += 1
print(dict(c))"
```

`unreachable` in that count is not a fifth kind of evidence. It is an outcome: a cell that
cannot occur, which has nothing to run. The snippet counts those rows apart so that they do
not swell `never-run`; the pages print them as "not reachable".

## Where the contract is silent

`contracts/openapi.json` does not document some statuses that the code answers. The map does
not edit the contract and does not bend a row to fit it. Each such status is listed in
`contractGaps` in `data/model.json`, with a note. Each machine page ends with a "Where the
contract is silent" section, and the endpoints page prints an endpoint's gaps under it. The
check fails when a listed gap has since been documented, so the list can only shrink.

## How to verify it yourself

Two commands need nothing running:

```
scripts/state-map-check --strict
PYTHONPATH=scripts python3 -m unittest discover -s scripts/statemap/tests
```

The first checks the data against the contract, the error codes and the code it cites, and
prints a summary line with the number of rows, of `never-run` rows, of pending items and of
contract gaps. The second tests the checker itself.

To run the probes that the `smoke` rows cite, bring up the compose Postgres and Valkey, then
run the script against a database of its own, and drop that database afterwards:

```
docker compose up -d
MOYI_DB=moyi_statemap scripts/smoke.sh
docker compose exec postgres dropdb -U moyi moyi_statemap
```

The script builds the jar with Gradle and boots it. It needs JDK 25, which it finds for
itself; `--no-build` reuses a jar that is already built. It flushes the rate-limit buckets in
the shared Valkey, prints `ok` or `FAIL` for each probe, and ends with `<n> passed, <n>
failed`. If a probe fails, search `docs/state-map/data/*.json` for its label to find the rows
that lean on it.

## How to change it

Edit `data/`, then:

```
scripts/state-map-generate
scripts/state-map-check --strict
```

If you changed the tooling in `scripts/statemap/`, also run the unit tests above. Commit the
data and the generated pages together.

CI runs the unit tests and `scripts/state-map-check --strict`. The check fails when:

- the data is malformed: a row without a reason or a `codeRef`, two rows in one cell with
  nothing to tell them apart, a region without exactly one initial state, an everywhere
  error written as a row outside the regions that allow it;
- an endpoint in the contract has no card, or a card names one that is not in the contract;
- an endpoint documents a status that no row and no request error of its card explains,
  unless that status is one of the everywhere errors, which are not checked per endpoint;
- a row or a card answers a status the contract does not document for that endpoint and
  `contractGaps` does not list it, or a listed gap has since been documented;
- an `ErrorCode` appears nowhere in the map: not in a row, not in a card's request errors,
  not among the everywhere errors;
- a cell of a grid is empty (this one runs without `--strict` too);
- with `--strict`: anything is still on the `pending` list, or a built state other than
  its region's initial one is entered by no row;
- a citation no longer resolves: the `codeRef` text has left the file it names, a cited
  probe label is not in `scripts/smoke.sh`, or a cited test name is not a whole test name
  in that class;
- a generated page is stale, missing or orphaned.

When the check says a citation no longer resolves, read the code again before changing the
citation: the row may no longer be true.

### What the check cannot tell you

It proves that every endpoint, every documented status that is not an everywhere error, and
every `ErrorCode` is accounted for; that every grid cell the data opens is filled; that
every state is entered; that every citation still resolves to text in a file and every cited
probe label and test name exists; that the pages match the data; and that a contract gap is
really undocumented.

It does not prove:

- that the service behaves as a row says;
- that a target state, a `when` or a guard is right;
- that the conditions in a cell do not overlap;
- that an action has rows in every region it belongs to;
- that a citation points at the line that makes the row true, or that a cited test asserts
  what the row claims;
- that a journey can be walked.

Those rest on review and on running the service.

## What this is not

- Not a guide to a client or its screens. It is organised by state, not by screen.
- Not a substitute for `contracts/openapi.json`, which stays the contract.
- Not finished as a page. The clickable page described in the design spec is not built yet
  (the spec's step 5). The Markdown pages here are what exists: the diagrams, the tables,
  the endpoint cards and the journeys. Search, a toggle for drawing refusals and a map you
  can click belong to that page.

The design is in `docs/superpowers/specs/2026-10-07-backend-state-map-design.md`.
