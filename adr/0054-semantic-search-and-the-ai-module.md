# ADR-0054 — Semantic search and the `ai` module: one port, one table per model, an exact scan per bond, a threshold in the search box and RRF in "Remember when", and consent either member withdraws

**Status:** Accepted, with the eval's measurements owed (below) · **Date:** 2026-11-02 ·
**Deciders:** Daniel, for the rulings of 2026-10-30 it builds on (spec §2); decision 9 was made
without him and is provisional, and two questions are his (below).

## Context

FR-204 asks for semantic search over the archive with pgvector embeddings; doc 15 pulls it forward
as the first item of Phase 10 (Option B). ADR-0006 chose pgvector "without adding a second
datastore". The owner ruled on 2026-10-30 (spec §2): a third-party provider may receive entry text
for embedding; two providers sit behind one port (OpenAI `text-embedding-3-small`, 1536 dimensions,
through Spring AI; Voyage `voyage-3.5`, 1024, our own adapter) and an eval picks the live one;
semantic search is on by default, either member turns it off for the bond and the bond's vectors
are deleted, and on again needs both; the search box keeps ADR-0039's order, with meaning matches
joining by a precision-tuned threshold; relevance ranking exists only in a new "Remember when".
AI never writes the gratitude, and in this slice writes nothing a user reads.

What binds the design: a member never learns a partner's entry before the reveal (T-01, FR-060,
"never cut"); a shown day is the whole day and nothing says which entry matched (ADR-0039 decision
4); the match and the visibility are asked of the same row (ADR-0039 plan decisions 2–3); erasure
empties every copy of entry text (ADR-0035, ADR-0050); and the words two people write never reach a
log, an exception message or a second column (CLAUDE.md), which this slice extends deliberately
(decision 3).

The spec (`docs/superpowers/specs/2026-10-30-semantic-search-design.md`) and the plan
(`docs/superpowers/plans/2026-10-31-semantic-search-s1.md`, whose decisions 1–9 are numbered as
here) hold the detail. This record is what was built, what it cost, and what was measured.

## Decision

### The module

**A. `modules/ai`, a module of its own, reached only through its `api` port.** `EmbeddingPort`
has `val space: EmbeddingSpace?` and `embed(texts): List<FloatArray>`; `EmbeddingSpace` is the model
id with its dimensions (`OPENAI_TEXT_EMBEDDING_3_SMALL(1536)`, `VOYAGE_3_5(1024)`), and `null` means
no provider. A process embeds in one space at a time, so the space is the port's, not each
vector's. `EmbeddingUnavailableException` carries a fixed reason (`"provider error"`, `"no
provider"`, `"wrong dimensions"`), never the input; the provider's exception is kept only as its
cause. `gratitude` depends on the port alone; FR-200, FR-201 and FR-205 will reuse it.

- **OpenAI through Spring AI 2.0.2** (`OpenAiEmbeddingModel`, built by its builder; the
  constructor taking options is deprecated in 2.0). It runs on the official OpenAI Java SDK over
  OkHttp, with `maxRetries` 2. **Its base URL includes `/v1`**: the SDK appends only `/embeddings`
  to a configured value, and without it every request was a 404 that the port reported as
  "provider error" while search fell back to keywords in silence (found by `OpenAiWiringTest`,
  which pins the default).
- **Voyage through our own Spring AI `EmbeddingModel`** (`VoyageEmbeddingModel`) over a
  `RestClient`: `POST /v1/embeddings`, Bearer key, one retry after 500 ms on 429 or 5xx, no retry
  on another 4xx, and an error that names the status and never the input. Its request's
  `toString` is redacted: `RestClient` logs the body it writes, by `toString`, at DEBUG
  (decision R below).
- **The adapter** (`SpringAiEmbeddingAdapter`) checks the count and the dimensions of what comes
  back, maps every provider failure to `"provider error"`, and restores the thread's interrupt
  only for a real `InterruptedException` in the cause chain: OkHttp reports its own call timeout
  as a bare `InterruptedIOException`, and treating that as an interrupt stopped the sweep's next
  bond (`TimedOutCallTest`); a real interrupt is sent once and not retried by either client
  (`CancelledCallTest`).
- **`moyi.ai.embedding.provider = openai | voyage | none`, defaulting to `openai`, which refuses
  to start without its key** (plan decision 4, the `PushProperties` posture, over the spec's
  first draft of `none` as the default). `none` is named by the `local`, `media-worker` and test
  profiles, and by `config/deploy.yml` until the owner holds a key (decision 15). One `@Bean` with
  an exhaustive `when` chooses the adapter; the provider is logged at INFO, never the key. The
  timeout is 10 s a request.
- Metrics: `gratitude.ai.embedding.requests{provider,outcome}` and
  `gratitude.ai.embedding.latency{provider}`.

**B. The contract does not move with the AI dependency.** The OpenAI SDK needs Jackson 2's
`jackson-module-kotlin` at runtime, which brought that module onto the jar's classpath for the first
time. Springdoc 3.1.1 registers it on swagger-core's global mapper whenever the class is present,
and the OpenAPI document then marked every non-null Kotlin property `required`: 33 response schemas
changed, with no change to any route. `contracts/SchemaNullabilityPin` removes springdoc's
`springdocKotlinObjectMapperProvider` bean definition, so the contract stays byte-identical, and
`OpenApiContractTest` guards the pin (seen failing with it removed). Adopting Kotlin nullability in
the contract is a deliberate follow-up, not part of this slice: it changes the generated client's
types, so it ships in its own pull request with the `breaking-api-change` label, and the pin goes
with it.

### Storage

**C. One table per model, a vector beside its entry, no foreign key out of the module.** V41:
`entry_embeddings_openai_1536` and `entry_embeddings_voyage_1024`, each `entry_id` (primary key,
`REFERENCES entries(id) ON DELETE CASCADE`), `bond_id` (copied from the entry, so the scan filters
before it joins), `embedding vector(N)`, `text_hash bytea` (SHA-256 of the embedded text) and
`created_at`, with an index on `bond_id`. Every column has a fixed size and the index stays a plain
one; a third model is a new table, and switching models is a backfill into it (the sweep, decision
1), never an in-place rewrite. `EntryEmbeddings.tableOf(space)`, an exhaustive `when`, is the one
place a table name comes from. **What is embedded is `entries.text_search`**, the projection ADR-0039
writes and every erasure empties, never `text`: what search may see is what `text_search` holds.
The pgvector image (`pgvector/pgvector:0.8.7-pg18`) replaces `postgres:18` in compose,
Testcontainers and the restore drill; production's is owed (Owed).

**D. No approximate index; an exact scan of one bond's rows.** A bond holds about 730 entries a
year. Approximate search with a selective filter finds the nearest vectors first and drops other
bonds' rows afterwards, so it loses results; an exact scan of one bond's rows through the `bond_id`
index never misses one and, at this size, is fast. **Measured** (`SearchPlanMeasurement`, opt-in,
run 2026-11-02 on the pgvector image): half a million entries, the measured bond's 1,460 entries
with vectors and 20,000 vectors of other bonds, the search's statement with a meaning match on,
warm: `thank` (in every entry) 0.80 ms, `lamp` (an eighth) 2.54 ms, `zebra` (one entry) 2.58 ms;
every plan reads through `entries_bond_recent_idx` and `entry_embeddings_openai_1536_bond_idx`, the
widest index scan is the bond's 1,460 rows, and nothing scans `entries`, `bond_days` or the vector
table sequentially. The keyword-only form on the same run: 0.80, 0.82 and 0.39 ms. NFR-004's 300 ms
and NFR-033 hold. (The vector table was not filled to 500k rows: that is 3 GB of 1536-dimension
vectors; its other bonds bear only on its own `bond_id` scan, which the plan shows.) Revisit only if
one query must cover more than one bond, or a bond passes ~50k entries.

### Writing vectors

**1. A sweep, not outbox consumers, fills the vectors** (plan decision 1, amending spec §3.4).
`OutboxJob` holds a database transaction for each delivery, and there is no dead-letter queue: a
provider call inside a consumer would hold a transaction across the network and retry for ever.
`EmbeddingJob` (`scheduling`, every minute, ShedLock `embedding`, `lockAtMostFor` 5 min,
`lockAtLeastFor` 20 s) asks `gratitude.api.EmbeddingSweep.embedPending(200)`. The sweep walks bonds
with the setting on (open or ended, paired or solo; 100 a page by id) and takes at most **64** of a
bond's entries a run (`BATCH`), oldest first: live, `text_search` not null, no row in the live
model's table. For each bond: the setting is read again and, if still on, the words are read (a
short transaction); the provider is called **with no transaction open**; the vectors are written in
a short transaction **under the bond's row lock**, after the setting is read once more under it,
and only where `text_search` still hashes to what was sent (`upsert`'s `INSERT … SELECT … WHERE
sha256(…) = ? AND e.bond_id = ?`), timestamps truncated to microseconds. This one mechanism covers new
entries, edits, the backfill after the setting comes back on, catching up after an outage, and
switching models; no entry event was needed. **The user's request never waits on a provider**: until
the row exists, an entry is found by its words.

- **`lockClosingViewOf` takes `UpdateBond`'s lock** (spec §8's open question): it is
  `bonds.lockBond(id)` → `lockRow`, the same `SELECT 1 FROM bonds … FOR UPDATE` that
  `UpdateBond.patch` takes, so no narrower lock method is needed. A turn-off that commits while the
  sweep waits on the lock leaves no vector: `EmbedPendingEntriesTest` holds the lock on its own
  connection, sees the sweep queued behind it (`pg_blocking_pids`, no sleep), commits the turn-off,
  and finds nothing written. The sweep takes the bond lock and the vector table only, never a day
  or entry lock, so it cannot invert bond → day → entry.
- **A refused bond does not stop the run** (the controller's ruling of Task 6's review). On
  `EmbeddingUnavailableException` the bond is counted and skipped; **three failures in a row end
  the run**, because the provider is then down and is not hammered. A success resets the count.
  The skipped entries stay pending for the next run.
- **The last-success gauge** (`gratitude.embedding.job.last.success.timestamp`) is stamped when the
  run made at least one successful call or had no failure (`!failed || embedded + skipped > 0`),
  and **not when every call failed**: a stale gauge is what an alert reads, so stamping on failure
  would hide an outage. A bond whose input the provider always refuses then shows in
  `gratitude.ai.embedding.requests{outcome=failed}` rather than freezing the gauge (keyword search
  covers that bond meanwhile). The cost of the rule: an outage that fails exactly one or two
  bonds' calls in a run that also succeeded looks healthy on the gauge; the failed-request counter
  still shows it. Counters `gratitude.ai.embedding.entries{outcome=embedded|skipped}` count work
  written before a failure too.
- The media worker runs no sweep (`moyi.scheduling.embedding.cron: "-"`) and names the provider
  `none` in its profile: it never holds an embedding key.

**3. An entry's vector dies with its words** (plan decision 3). `EntryStore.update` is the single
place `text_search` changes; edit, the author's delete, the withdrawal consumer, the close job's
pre-step and a read's withdrawal pre-step all pass through it. When the projection changes, it
deletes the entry's rows from **every** embedding table in the same transaction; `ON DELETE
CASCADE` covers a deleted row. `EntryEmbeddingsTest` asserts it for each erasure path ADR-0035 and
ADR-0050 list, with a vector in both tables, and that an in-flight vector of the old words is
refused at the write. It relies on every caller handing `update` an entity read after its lock
(`lockAndFind`, `findForDayFresh`), which `RevealFreshReadTest` holds.

### The setting and consent

**2. Off: one member, through `PATCH`, and nothing is read or written from that moment** (plan
decision 2, amending spec §3.3's "same transaction"). V40 adds `bonds.semantic_search_enabled
boolean NOT NULL DEFAULT true`, with the B4 `ETag`/412 behaviour. `PATCH /bonds/{id}
{"semanticSearchEnabled": false}` sets it and, in the same transaction, publishes
`SemanticSearchDisabled` (references `{bondId}` only) and cancels a live request to turn it on.
`bond` cannot call `gratitude`, so the `gratitude.semantic-search-off` consumer (`startFrom
BEGINNING`, `@Transactional(MANDATORY)`, SQL only) deletes the bond's rows from every table. Between
the commit and the delivery the rows still exist, but nothing reads them: search and remember check
`BondMembership.semanticSearchEnabled` for each read (and `q` is never embedded when it is off),
and the sweep re-checks under the lock. A turn-off committed after a search's guard resolved means
`q` was sent but no vector is read (`SearchWithdrawalTest`). Smoke: a vector put in by hand is gone
after the outbox pass.

**6. `PATCH` takes `false` only.** `true` is `422` on the field, code `ASSERT_FALSE`
(`@field:AssertFalse`): a standard Bean Validation code, not a new `ErrorCode`, so no breaking
change. Turning it off when it is already off publishes nothing more and leaves a pending request
to turn it on for the other member to answer.

**5. On: both members, the deletion request's shape** (plan decision 5). `POST
/bonds/{id}/semantic-search` asks, or agrees when the other member asked (`202` with the bond;
`pendingSemanticSearchRequest {requestedByMemberId, requestedAt, expiresAt}` while one waits);
`DELETE` calls a waiting request off, either member's (`204`; nothing waiting is `404`). A request
lapses after seven days. A bond still waiting for its second member applies the change at once.
Asking while it is on changes nothing. An ended bond (or one counting down to deletion) is `409
BOND_ARCHIVED`. Two members asking at once give one proposal, confirmed (`ProposalRaceTest`). Once
on, the sweep backfills.

**9. An ended bond can still be turned off (provisional; the owner's question 1).** `PATCH
{"semanticSearchEnabled": false}` alone is accepted on a bond that has ended or is counting down to
deletion, skipping the ended-bond refusal for that body alone, as cancelling a deletion request
does; any other field, or `false` with another field, is still `409`. Why: the sweep keeps
embedding an ended bond whose setting is on (an ended bond is still read, ADR-0039 default 3), so a
member must be able to withdraw consent to the third-party processor; refusing a privacy feature's
off switch is never right (the same reasoning as ADR-0053's report on an ended bond). **What it
implies, for the owner:** a member who has *left* an archived bond can turn it off too, and the
other member sees the change in the bond's state. That is an act of a member, not a signal of a
block, so it opens no new oracle in doc 26 §2.1's sense; it is still a thing one former member does
that the other sees. If the owner prefers "the sweep skips ended bonds", it is one predicate in
`bondsWithSemanticSearch` and one test, and decision 9 goes.

### Reading

**7. The search box: a meaning match joins by a maximum distance, newest first as before.** The
search's one statement (`ArchiveDays.matchingQuery`) becomes `AND (WORDS_MATCH OR EXISTS (SELECT 1
FROM <table> x WHERE x.bond_id = ? AND x.entry_id = e.id AND x.embedding <=> ?::vector <= ?))`, asked
of the same row inside the same gate (`SEEN_ROW`, `LIVE_ROW`, the withdrawn filter), so the meaning
half cannot reveal a locked, withdrawn or tombstoned entry (T-01; the cross-tenant and pre-reveal
fixtures run with it on). Order, keyset, `DaysResponse`, limits, `q` rules and the absence of a
snippet are unchanged, and so is the search operation in the contract.

- **The threshold is a distance**, `<=>` being cosine distance (1 − similarity): spec §3.5's
  `1 - (embedding <=> q) >= threshold` is `embedding <=> q <= 1 - threshold`. It is configured per
  model as `moyi.gratitude.semantic-search.max-distance.<MODEL>`; a model with none configured is
  never asked, and search stays keywords only (counted `disabled`). The eval chooses it (decision E).
- **`x.bond_id = ?` binds the bond's id as a constant**, not `x.bond_id = e.bond_id`. The two mean
  the same, but Postgres planned the `EXISTS` inside the `OR` as a hashed SubPlan over a sequential
  scan of the whole vector table with the correlated form (`SearchCandidatesTest`'s `EXPLAIN` went
  red on it); with the constant, it is a bitmap scan of `_bond_idx`. Remember's join binds the same
  constant (`JOIN <table> x ON x.entry_id = e.id AND x.bond_id = ?`). In that join form the
  correlated `x.bond_id = e.bond_id` would also be bond-bounded, the planner deriving the constant
  through the equivalence; without either condition it scans the table, and `RankedCandidatesTest`
  fails. Both statements bind the constant.
- **The cost:** with a meaning match the `OR` takes the keyword half off `entries_search_idx`, and
  the plan starts from `entries_bond_recent_idx` and filters the bond's rows. It is bounded by the
  bond, and measured (decision D).
- **`q` is embedded once a request, outside any transaction, with a 5 s timeout** (spec §3.5,
  the controller's ruling F16; the plan had 2 s), on a shared virtual-thread executor; at the
  timeout the call is cancelled (`future.cancel(true)`) so the provider is not left sending the
  user's words after we stopped waiting. The meaning half is **dropped**, the answer still `200`
  and keyword-only, when the setting is off, the provider is `none`, the model has no threshold,
  the call fails, times out, is refused or returns a vector of the wrong size, or the thread is
  interrupted. Counted `gratitude.search.semantic{outcome=used|disabled|degraded}`. `q` is never
  logged; an unexpected failure logs its class name only.
- **Voyage receives `input_type: "document"` for queries too.** The port has no input-type
  parameter. `"query"` for queries is a measured follow-up: taken only if the eval shows it helps,
  which needs the parameter on the port.

**8. "Remember when": `GET /bonds/{id}/remember?q=&limit=`, ranked by Reciprocal Rank Fusion.**
The top 50 entries by `ts_rank` and the top 50 by distance, each already through the same gate
(one gate builder, `gatedEntries`, shared with search), are fused by entry with k = 60, grouped to
days in the order of each day's best entry, and every day then passes `read()` and the same
`matchedInFull` as search. No cursor (a top-k answer, not a feed): `limit` 1–20, default 10;
`limit * 2` days are read and shown up to `limit`, bounded by the page's text size, so fewer days
than `limit` is usual. `q` follows search's rules. The answer is the archive's `DayResponse` shape,
ordered by relevance, and says nothing of which entry was relevant. **Entries only, never generated
text.** With the setting off, no provider, or a failure, it is the words' ranking alone, still `200`.
Its own bucket, `remember:user`, 30 an hour, because each call may spend a provider request. The
meaning ranking has **no cut-off**, as the spec says (question 2).

**Why a threshold in the box and RRF in remember** (the owner's ruling 6): the search box promises
ADR-0039's newest-first days, and a stray day with no highlight would confuse, so a meaning match
joins only when it is close (precision ≥ 0.9 on the golden set), and missing one costs less than
showing a wrong one. Remember is asked a question, and is answered best-first; fusing ranks needs no
score calibration between `ts_rank` and a distance, and needs no threshold at all.

### Safety and deployment

**R. The words sent to a provider, and its key, held to the entry's standard in the log.**
`SecretsNeverLoggedTest` now runs each provider's real client against a stub of its API, one
context each (`SecretsNeverLoggedWithVoyageTest`, `SecretsNeverLoggedWithOpenAiTest`), with a
watched fake key, at TRACE: the sweep embeds an entry, and remember embeds a question that matches
no word, and the stub's count shows both really reached it. It found three roads, now closed and
each seen failing when removed:

- `RestClient` printing Voyage's request body by its `toString` at DEBUG (the request type now
  redacts it);
- the JDK's `HttpURLConnection`, under Voyage's client, printing the `Authorization` header at DEBUG
  (pinned at INFO in `application.yml`, the sixteenth pin);
- Spring AI's `OpenAiEmbeddingModel.call` printing "… with the following options : " and the
  OpenAI SDK's `EmbeddingCreateParams`, whose `toString` holds every input, at TRACE: the entry's
  words, and search's and remember's `q` (pinned as the package `org.springframework.ai` at INFO,
  the seventeenth: Spring AI exists to carry what people write to a model, and any of its classes
  may print it). Spring AI builds its own HTTP client over OkHttp, with no logging interceptor; the
  SDK's `OPENAI_LOG` switch plays no part in this stack.

**15. Production names `none` until a key exists** (`config/deploy.yml`, top-level `env.clear`,
so both roles inherit it). Switching on is the API's role alone: the provider in
`servers.web.env.clear` and the key in `servers.web.env.secret`, never the top-level ones, because
the worker embeds nothing and an environment variable would outrank the `none` its profile names;
and the chosen model's max-distance shipped in `application.yml`.
`ProductionStartsWithoutMediaSecretsTest` holds, for each role's merged environment, that the
provider is `none` and no `MOYI_AI_EMBEDDING_*_API_KEY` is named.

### The eval

**E. A golden set and stored vectors, so CI never calls a provider.** `golden.json` (gratitude test
resources): 150 synthetic entries across 3 bonds of two writers, and 60 queries (30 with one
relevant entry, 20 ambiguous with 2–4, 10 with none), paraphrases sharing no content word with
their targets (`GoldenSetTest`). `tools/embedding-eval` embeds them through `modules:ai`'s own
adapters with real keys, batches of 64 as the sweep sends, queries one at a time, and prints p95
latency and estimated tokens (characters / 4: the port drops the provider's usage). The vectors
are committed; `RetrievalEvalTest` drives the real `GetDays.remember` and `GetDays.search` over
them and prints recall@10 and MRR (remember), precision at the shipped max-distance (search), the
**largest** max-distance on a 0.01 grid that keeps pooled precision ≥ 0.9 (the smallest is
degenerate: precision only rises as the distance shrinks, down to nothing found), and, for the 10
no-answer queries, how many days remember returned and the nearest distance. It fails the build
when recall or MRR drops below `floors.json`, or precision at the shipped threshold below 0.9. A
space named in `floors.json` with no vectors fails; a space with neither is skipped.

## Measured (Task 9 Step 4, owed)

**Not yet measured.** The owner runs the tool with his keys
(`./gradlew :tools:embedding-eval:run --args="generate"`, then `RetrievalEvalTest`). Until then no
vectors file and no `floors.json` exist, both spaces are skipped, and no max-distance is shipped,
so the search box and remember stay keyword-only even where a provider is configured. To be filled
in from the printout, for each model:

| | OpenAI `text-embedding-3-small` | Voyage `voyage-3.5` |
|---|---|---|
| remember recall@10 | owed | owed |
| remember MRR | owed | owed |
| search precision at the shipped max-distance | owed | owed |
| max-distance chosen (largest with precision ≥ 0.9) | owed | owed |
| p95 latency, one query | owed | owed |
| p95 latency, a sweep batch of 64 | owed | owed |
| estimated tokens for the golden set (chars / 4) | owed | owed |
| no-answer queries: days remember returned (each of 10) | owed | owed |
| no-answer queries: nearest distance (each of 10) | owed | owed |

**The live provider: pending**, chosen from this table. Then: set the max-distance in
`application.yml`, write `floors.json` (measured values less 0.02), commit the vectors, and switch
production on (decision 15).

## Consequences

**Positive.** A search finds by meaning without changing its contract or order; "Remember when"
answers a question with what the two of them wrote. A provider that is down, slow or absent costs
nothing but the meaning half. Either member withdraws consent alone, and the bond's vectors are
deleted. A vector never outlives its words. The scan is exact and bounded by the bond.

**Negative.** Entry text goes to a third party once a provider is switched on (the owner's ruling
2; the privacy policy line is owed). The vectors are a second copy derived from the words (decision
3's deliberate exception, now in CLAUDE.md). The search box's `OR` takes keyword search off its
text index (decision 7; measured cheap). With the provider `none`, which is production today,
remember is the words' ranking alone, and that ranking needs every word of `q` in one entry, so a
question in plain words usually answers no day (question 2). The new dependency brings Jackson 2's
Kotlin module and OkHttp onto the classpath (decision B).

**Neutral.** V40 (`bonds.semantic_search_enabled`, `SEMANTIC_SEARCH_ON` proposals), V41 (the
extension and two tables). Three routes (`remember`, `POST`/`DELETE semantic-search`), one bucket,
one event, one consumer, one job. No `ErrorCode` added.

## Alternatives considered

- **Outbox consumers that call the provider** (the spec's first draft): a transaction held across
  the network, and retries for ever with no dead-letter queue (decision 1).
- **One table with a `model` column and `vector` of no fixed size:** no plain index, and a model
  switch rewrites in place (decision C).
- **HNSW or IVFFlat:** loses results under the `bond_id` filter, and is not faster at a bond's
  size (decision D).
- **Relevance order in the search box:** breaks ADR-0039's order and keyset (the owner's ruling 6).
- **A score blend instead of RRF:** needs `ts_rank` and a distance on one scale.
- **Deleting the vectors in `UpdateBond`'s transaction:** `bond` cannot reach `gratitude`'s tables
  (decision 2).
- **`spring-ai-openai` replaced by our own client** (Task 2's option c), to keep Jackson 2 and
  OkHttp out: departs from the spec's "OpenAI through Spring AI"; the contract pin cost less
  (decision B).

## Open questions (the owner's)

1. **Decision 9 — turning off on an ended bond (open, provisional).** Built: `false` alone is
   accepted on an ended or counting-down bond, including from a member who has *left* an archived
   bond, and the other member sees the setting change. The alternative: the sweep skips ended bonds
   (one predicate, one test) and an ended bond refuses the `PATCH` as before.
2. **Does "Remember when" get a relevance floor? (open)** Today, with a provider up, **any** query,
   nonsense too, returns up to `limit` days, because every visible embedded entry is in the meaning
   ranking and it has no cut-off. With the provider down or `none`, the answer is the words'
   ranking alone, which needs every word of `q` in one entry, so an empty answer is usual (seen in
   the smoke run: "when did we have soup" answers no day where "soup" finds one). The eval prints,
   for its 10 no-answer queries, how many days come back and how near the nearest is (owed above),
   which is the number to rule on. Options: a loose meaning floor for remember; the words' ranking
   matching any word rather than every word; or both accepted as they are, with client copy that
   says what an answer means. Nothing is built until the owner rules.

## Owed

- **Production's Postgres on the pgvector image, before this branch is deployed.**
  `config/deploy.yml`'s `postgres` accessory is still `postgres:18`, which has no `vector`
  extension, so V41's `CREATE EXTENSION` would fail the deploy's migration. A restart with the same
  data volume, rehearsed against the restore drill first (spec §8).
- **The eval's measurements and the provider choice** (Measured, above), then production switched
  on (decision 15).
- **The documents (the corpus is read-only to a slice):** a Privacy Policy line and a sub-processor
  entry for the chosen provider (doc 20); doc 07's `vector` note and retention table (the vectors
  live and die with `text_search`).
- **moyi-app:** the "Remember when" frame (proposed near On This Day) and the setting in bond
  settings, with the pending request; every endpoint gets a screen.
- **The search operation's description** in the contract still says one entry "holds every word of
  `q`", which is no longer the whole truth when a meaning match joins. Left unchanged here so the
  search operation stays byte-identical, as the spec required; a description-only change is not
  breaking.
- **Deferred metrics:** provider token usage (the port returns vectors only; widening it is not this
  slice's) and a lag gauge (the age of the oldest pending entry). Remember's provider calls are
  counted under search's `gratitude.search.semantic`; a tag of its own is owed if a dashboard needs
  them apart. `QueryEmbedder` returns no match for a model with no max-distance, which turns
  remember keyword-only too, though remember needs no threshold: harmless while every model with
  vectors has one, but a coupling.
- **Kotlin nullability in the contract**, in its own breaking-labelled pull request, and the pin
  removed (decision B).
- **`input_type: "query"` for Voyage queries**, if the eval shows it matters (decision 7).
- **A course brief:** embeddings, exact against approximate nearest-neighbour search and when each
  wins, RRF, retrieval evals.

## Revisit when

The owner answers question 1 or 2. The eval is run and a provider is chosen. A query must cover
more than one bond, or a bond passes ~50k entries (decision D). A third model is added (a new
table, decision C). Application-level encryption is designed (Phase 5): what is embedded, and
where, is decided again then.
