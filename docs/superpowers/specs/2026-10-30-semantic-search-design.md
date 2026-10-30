# Phase 10, slice S1 — semantic search

**Status:** design approved in conversation 2026-10-30; for review · **Date:** 2026-10-30 · **Author:** Claude, for Daniel
**Covers:** FR-204 (`03-product-requirements.md:150`), the first item of doc 15's Phase 10
"ML/AI layer" (`15-roadmap-and-milestones.md:145`), pulled forward under Option B
(`15:21`). Also lays the shared `ai` module that FR-200, FR-201 and FR-205 will reuse.
**Base:** `main` @ `e88944b`. Migrations from **V37**, the next free after V36; ADR from
**0053**. Renumber at merge if a peer has moved either sequence.

Read ADR-0039 (search and on-this-day) and ADR-0035 (the outbox poller and withdrawal)
first: this slice changes neither of their rulings and depends on both.

Corpus references are `NN:line` into `Gratitude/documents/NN-*.md`. **[inferred]** marks
what was reasoned or recalled, not read or run.

---

## 1. What this slice must satisfy

| Source | Requirement |
|---|---|
| FR-204 | Semantic search over the archive (pgvector embeddings). |
| ADR-0006 | pgvector "without adding a second datastore". `07:339`: `vector` is enabled at Phase 10. |
| ADR-0039 controller default 2 | Search results are days in the archive's shape, newest first, keyset on the date. |
| ADR-0039 plan decision 4 | A shown day is the whole day; **nothing says which entry matched**. No snippet, no highlight (default 1). |
| ADR-0039 plan decisions 2–3 | The match and the visibility are asked of the same row; a day is shown only if a matching entry was read in full through the gate. |
| T-01 (`09:25`), FR-060 | A member never learns a partner's entry before the reveal. **Never cut** (`03`, scope guardrail). |
| ADR-0035 | Erasure empties every copy of entry text. |
| FR-064 | No surveillance. FR-202's "never used to score the relationship" sets the tone for all AI work. |

**Product rule (brainstorm, 2026-10-30):** AI never writes the gratitude. In this slice it
never writes anything a user reads: every result is an entry a person wrote.

## 2. Owner's rulings (2026-10-30)

1. **Purpose:** every AI feature must ship *and* teach an interview-grade skill.
2. **A third-party provider may receive entry text** for embedding. Owed: a privacy-policy
   line and a sub-processor entry (doc 20); how this sits with application-level encryption
   (`09` §7, D-13) is decided when Phase 5 encryption is designed, not here.
3. **Two providers behind one port:** OpenAI `text-embedding-3-small` (1536 dims, Spring AI
   built-in) and Voyage (1024 dims [inferred: `voyage-3.5`; confirm the current model at
   build], our own adapter). An eval on the golden set picks the live one.
4. **Consent:** on by default, disclosed in the privacy policy. **Either member turns it off
   → off for the bond, and the bond's embeddings are deleted.** Back on needs **both**
   members (the B5 consent pattern), then a backfill.
5. **Client:** the existing search box stays as it looks, with meaning matches added, plus a new
   natural-language **"Remember when…"** entry point.
6. **The search box keeps ADR-0039's order** (2026-10-30, after the conflict was found):
   meaning matches join by a precision-tuned threshold; relevance ranking exists only in
   "Remember when".

## 3. Design

### 3.1 The `ai` module

`modules/ai`, a new module on the existing convention plugins.

- `api/EmbeddingPort`: `embed(texts: List<String>): List<Embedding>`; `Embedding` carries
  the vector and the `EmbeddingModelId` (provider + model + dimensions). Other modules depend
  on this port only.
- `infra/OpenAiEmbeddingAdapter`: Spring AI **2.0.x** `EmbeddingModel` (2.0.1 is the stable
  line for Boot 4.0/4.1; 2.1 milestones target Boot 4.2, so they are not taken).
- `infra/VoyageEmbeddingAdapter`: our own `EmbeddingModel` over Voyage's HTTP API
  (`RestClient`, timeout, retry with backoff on 429/5xx, typed errors).
- `moyi.ai.embedding.provider = openai | voyage | none`. `none` is the default in tests and
  whenever no key is set: the port reports itself unavailable and every caller degrades
  (§3.5).
- Keys come from env (`MOYI_OPENAI_API_KEY`, `MOYI_VOYAGE_API_KEY`), passed to Kamal as
  secrets. Never logged, never in a response. Covered by the existing gitleaks rules [inferred].
- Metrics (Micrometer): `moyi.ai.embedding.requests{provider,outcome}`,
  `moyi.ai.embedding.tokens{provider}`, `moyi.ai.embedding.latency{provider}`.

### 3.2 Storage (V37)

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE entry_embeddings_openai_1536 (
  entry_id   uuid PRIMARY KEY REFERENCES entries(id) ON DELETE CASCADE,
  bond_id    uuid NOT NULL,
  embedding  vector(1536) NOT NULL,
  text_hash  bytea NOT NULL,          -- sha256 of the embedded text; skips a re-embed of unchanged text
  created_at timestamptz NOT NULL
);
CREATE INDEX ON entry_embeddings_openai_1536 (bond_id);
-- entry_embeddings_voyage_1024: identical, vector(1024)
```

- **One table per model**, so every column has a fixed size and the index stays a plain one. A
  third model is a new table, and switching models is a backfill into it, never an in-place
  rewrite.
- **No HNSW or IVFFlat index.** A bond holds at most ~730 entries a year. An exact scan of
  one bond's rows through the `bond_id` index is faster than approximate search at that size
  and never misses a result. Approximate search combined with a selective filter (`bond_id`)
  is known to lose results, because it finds the nearest vectors first and drops other bonds'
  rows afterwards. Revisit only if one query must cover more than one bond, or a bond exceeds
  ~50k entries. Recorded in the ADR.
- `bond_id` is copied from the entry so the scan never joins before filtering.
- What is embedded is `text_search` (the projection ADR-0039 already writes and erasure already
  empties), never `text` directly. That keeps one rule: what search may see is what
  `text_search` holds.
- The pgvector image replaces `postgres:18` in compose, Testcontainers and moyi-prod-1
  (`pgvector/pgvector:0.8.7-pg18`, pinned; checked 2026-10-30 on Docker Hub: amd64 and arm64).

### 3.3 The setting (V38, bond module)

- `bonds.semantic_search_enabled boolean NOT NULL DEFAULT true`, beside the B4 settings,
  with the B4 `ETag`/412 behaviour.
- **Off:** any member, one request. In the same transaction, set false, delete the bond's rows
  from every embedding table, and append `SemanticSearchDisabled` to the outbox.
- **On:** a consent request in the B5 pattern: one member proposes, the other accepts, it
  expires like the others. On acceptance, set true and append `SemanticSearchEnabled`, which
  starts the backfill (§3.4).
- An ended bond keeps its setting; search on an ended bond reads as ADR-0039 default 3 allows.

### 3.4 The write path

- Triggers: `EntrySubmitted` (exists), and a new **`EntryTextEdited`** appended in the same
  transaction as the pre-reveal edit. Checked 2026-10-30: `EntryStore.kt:53` rewrites
  `text_search` on an edit, and no event records it, so without this event an edited entry
  would keep the vector of its old words. The `SemanticSearchEnabled` backfill also
  triggers it.
- `EmbedEntries` worker in `gratitude` (it owns the text; `ai` owns only the port) runs on
  the outbox poller, under ShedLock. Embedding is batched. It skips a bond whose setting is
  off and an entry whose `text_hash` is unchanged. It upserts the row for the **live**
  provider only.
- **The user's request never waits on a provider.** Until the row exists, the entry is found
  by keywords only.
- Erasure and withdrawal: `ON DELETE CASCADE` covers a deleted entry. Any path that empties
  `text_search` also deletes the entry's embedding rows in the same transaction, which closes
  ADR-0035's "every copy" for this new copy. A test asserts it for each erasure path ADR-0035
  and ADR-0050 list.
- `moyi.ai.embedding.lag` = age of the oldest un-embedded eligible entry, so the gap is
  visible in metrics.

### 3.5 The search box: `GET /api/v1/bonds/{bondId}/search` (unchanged contract)

- `GetDays.search` gains a second candidate predicate. An entry matches if
  `search_vector @@ plainto_tsquery(...)` **OR**
  `1 - (embedding <=> :q) >= :threshold`.
- Both predicates are asked **of the same row, inside the same visibility gate** (ADR-0039
  plan decisions 2–3). The semantic half therefore cannot reveal a locked, withdrawn or
  tombstoned entry, which closes T-01 for the new path. The existing cross-tenant and
  pre-reveal fixtures are extended to run with semantic matching on.
- Order, keyset, `DaysResponse`, limits, `q` rules, no snippet: all unchanged.
- `q` is embedded once per request (5 s timeout). The semantic predicate is **dropped** (keyword
  only, still `200`) when the setting is off, the provider is `none` or failing, or the
  timeout passes. Counted as `moyi.search.semantic{outcome=used|disabled|degraded}`.
- `:threshold` is per model, set in config, chosen by the eval (§4) for **precision ≥ 0.9** on
  the golden set. A stray day with no highlight would confuse people, so missing a match costs
  less than showing a wrong one.

### 3.6 "Remember when": `GET /api/v1/bonds/{bondId}/remember?q=&limit=`

- A question in plain language → the entries ranked by relevance. The keyword ranking and the
  vector ranking are merged with **Reciprocal Rank Fusion** (k = 60). Results go through the
  same gate. Default limit 10, maximum 20. No cursor: it is a top-k answer, not a feed.
- Response: the same day shape (`DayResponse`), ordered by relevance. Consistent with
  decision 4, it does not say which entry matched.
- **Returns entries only, never generated text.** A generated answer waits for the
  prompt-injection defence in a later slice (FR-205/FR-201).
- Setting off or provider down: `200` with keyword-only RRF (one list), never an error, so the
  client renders one state.
- Its own Bucket4j bucket, because each call spends a provider request
  [inferred: 30/hour per user; set in the plan].
- `q` follows ADR-0039 decision 9's rules (1–100 graphemes, never echoed, never logged).
- Placement in the client is a design gap (as ADR-0039 default 6 was for on-this-day): a frame
  is owed in moyi-app; proposed near On This Day.

## 4. Evals

- **Golden set** (`modules/ai/src/test/resources/eval/`): about 150 synthetic entries across 3
  test bonds, plus about 60 queries, each labelled with its relevant entry IDs. Queries are
  paraphrases that share **no keyword** with their targets, so the eval measures meaning, not
  word overlap. Synthetic only: no real entry text in the repo.
- **Stored vectors:** each provider's embeddings of the golden set are committed as fixtures,
  so CI never calls a provider and the eval is deterministic.
- **CI gate** (`./gradlew :modules:ai:eval`): for the live model, "Remember when" recall@10 ≥
  [set from the first measured run] and MRR ≥ [same], and search-box precision at the
  configured threshold ≥ 0.9. A drop fails the build.
- **Provider comparison** (manual workflow, uses real keys): re-embeds the golden set with
  both providers, regenerates the fixtures, and prints recall@10, MRR, the threshold for
  precision 0.9, p95 latency and cost per 1k entries. The result is recorded in the ADR and
  decides the live provider.

## 5. Testing

- Unit: RRF merge, the threshold predicate builder, degradation (`none`, timeout, 5xx), the
  Voyage adapter against WireMock (429 retry, malformed body, timeout).
- Integration (Testcontainers, pgvector image): T-01 and cross-tenant fixtures with semantic
  matching on; the setting off deletes rows in the same transaction; erasure leaves zero rows;
  an `EXPLAIN` probe shows an index scan on `bond_id` and no seq scan across bonds.
- Contract: the search endpoint's OpenAPI is unchanged (the contract diff in CI proves it);
  `/remember` and the setting are additions.
- Smoke (run sheet): provider `none` → search `200` keyword-only; setting off → zero embedding
  rows for the bond; `/remember` with no key → `200`.

## 6. Owed outside the backend

- ADR-0053: the `ai` module, one table per model, no approximate index, threshold vs RRF,
  consent and deletion.
- Privacy policy line + sub-processor entry (doc 20).
- moyi-app: the "Remember when" frame and the setting toggle in bond settings (each endpoint
  gets a screen).
- A course brief: embeddings, exact vs approximate nearest-neighbour search and when each
  wins, RRF, retrieval evals.

## 7. Out of scope

Generated text of any kind (FR-205, the stuck-writer nudge); theme extraction (FR-200);
prompt ranking (FR-201); per-locale `text_search` (`07:230`); application-level encryption
(Phase 5).

## 8. Open for the plan

- Whether `EntryTextEdited` needs a notification consumer (expected: none; the outbox
  poller must skip it for push).
- Whether the semantic predicate can run in the same SQL statement as the gate, or needs the
  query embedding bound as a parameter of the existing candidate query (expected: the
  latter).
- Moving moyi-prod-1's Postgres to the pgvector image: a restart with the same data volume,
  rehearsed against the restore drill first.
