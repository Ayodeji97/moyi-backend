# Semantic search (Phase 10, S1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Entries become findable by meaning: the existing search box adds meaning matches by a threshold without changing its order or contract, and a new `GET /bonds/{id}/remember` ranks entries by relevance. Both providers sit behind one port, and an eval gates every change.

**Architecture:** A new `modules/ai` owns only the embedding port and its two adapters (OpenAI through Spring AI 2.0.2, and Voyage through our own Spring AI `EmbeddingModel`). `gratitude` owns the vectors (one table per model, beside `entries`), the sweep that fills them, and both queries. `bond` owns the setting and its two-member consent. `scheduling` runs the sweep.

**Tech Stack:** Kotlin 2.4.21, Spring Boot 4.1.2, Spring AI 2.0.2 (`spring-ai-openai`, `spring-ai-model`), pgvector 0.8.7 on Postgres 18, JdbcTemplate, ShedLock, Kotest matchers on JUnit 6, Testcontainers 2.

**Spec:** `docs/superpowers/specs/2026-10-30-semantic-search-design.md` (amended 2026-10-31 with this plan's decisions 1, 2 and 4).

**Branch:** `feat/semantic-search-s1`, cut from `origin/main` after #119 (spec) merges. **ADR-0053.** Migrations **V37** (bond), **V38** (gratitude). Renumber at merge if a peer has moved the sequence. There is no `outOfOrder` (ADR-0037:489-501).

## Global constraints

- Every module migration that needs an extension creates it itself: `CREATE EXTENSION IF NOT EXISTS vector` (V1 is on app's classpath only).
- Postgres image everywhere: `pgvector/pgvector:0.8.7-pg18` (checked 2026-10-30: amd64 + arm64).
- Spring AI **2.0.x only**. 2.1 targets Boot 4.2.
- Entry text, search terms and query text never reach a log, an exception message or an outbox payload (CLAUDE.md:82-88). Types holding them redact `toString`. API keys are redacted in `toString`.
- `OutboxEvent.references` is UUIDs only.
- Every bond-scoped route gets a cross-tenant fixture (`GratitudeCrossTenantTest`, `BondCrossTenantTest`).
- Layers depend inwards (Konsist, `ArchitectureTest.kt:85-92`). Non-`api` declarations are `internal`.
- `contracts/openapi.json` is regenerated, never hand-edited. The search operation's schema must not change.
- Commits: Conventional Commits, subject ≤ 88 chars including ` (#N)`, body ends `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Run `git config core.hooksPath .githooks` first. Only one Gradle run at a time.
- Metric names follow `gratitude.<area>.<thing>` (`ModerateMedia.kt:146`).

## Decisions this plan makes (recorded in ADR-0053; the spec is amended to match)

1. **A sweep, not outbox consumers, fills the vectors** (amends spec §3.4). `OutboxJob` holds a DB transaction for each delivery (60 s), and there is no dead-letter queue (`Backoff.kt`). A provider call inside it would hold a transaction across the network and retry for ever. Instead, `EmbeddingJob` (ShedLock, every minute) asks `gratitude.api.EmbeddingSweep` to embed up to 200 eligible entries that have no vector in the live model's table. Eligible means: live, `text_search` not null, and the bond's setting on. Steps: read (short tx), call the provider (no tx), write (short tx under the bond lock, re-checking the setting and the text hash). This one mechanism covers new entries, edits, the backfill after re-enabling, catching up after an outage and switching models. **`EntryTextEdited` is therefore not needed**: an edit deletes the old vector (decision 3), and the sweep refills it. Keyword search finds the entry in the meantime.
2. **Turning it off deletes through the outbox, and stops reading at once** (amends spec §3.3, "same transaction"). `bond` cannot call `gratitude`, which depends on it. `UpdateBond` sets false and publishes `SemanticSearchDisabled`, and the `gratitude.semantic-search-off` consumer deletes the bond's rows (SQL only, no provider call, so it is safe inside a delivery). From the moment of the switch, search and remember skip the semantic half, because `BondMembership.semanticSearchEnabled` is false, and the sweep's write re-checks the setting under the bond lock. So no vector is read, and none is added, after "off".
3. **An entry's vector dies with its words, in the same statement path.** `EntryStore.update` (`EntryStore.kt:48-64`) is the single place `text_search` changes: edit, author delete, withdrawal and the pre-close sweep all pass through `EraseEntry` or `ChangeEntry.edit`. When the projection changes, it deletes the entry's rows from every embedding table in the same transaction.
4. **Repo convention over the spec's `none` default:** `moyi.ai.embedding.provider` defaults to `OPENAI` and refuses to start without its key (the `PushProperties` posture). `NONE` must be named: in `application-local.yml`, test config and `deploy.yml` until the keys exist.
5. **Turning on uses the deletion-request shape, not timezone's:** `POST /bonds/{id}/semantic-search` proposes, or agrees when the other member proposed. `DELETE` cancels. It carries no payload, so there is no `proposalId` to name. A solo bond applies the change at once (`ChangeTimezone.propose` precedent).
6. **`PATCH /bonds/{id}` accepts `semanticSearchEnabled: false` only.** `true` is a 422 on the field, "turned on by both members", refused the way `anchorTimezone` is (`PatchBondRequest.kt:74-75`).
7. **Cosine distance, threshold as a maximum distance.** `<=>` is cosine distance (1 − similarity). The config holds `max-distance` per model, chosen by the eval.
8. **RRF over gated candidates:** top 50 by `ts_rank` (keyword) and top 50 by distance (vector), each already through `SEEN_ROW`, `LIVE_ROW` and the withdrawn filter. They are fused by entry with k = 60, grouped to days (best entry's score), and every day then passes `read()` and `matchedInFull`, as search does.

## Review focus

1. **A partner's unrevealed entry is never surfaced by meaning.** Bea writes "the quiet lamp" before the reveal. Ada searches "a calm light" and remembers "calm light". Neither returns that day until the reveal. (Task 7, Task 8)
2. **An edited entry never matches its old words by meaning.** It is edited from "plum" to "pear", and before the sweep runs again, a meaning query near "plum" does not return it. (Task 5)
3. **A race between "off" and the sweep's write leaves no vector.** The sweep has read and embedded, the member turns off, then the sweep writes: zero rows. (Task 6)
4. **The provider hangs.** `q` embedding times out after 2 s, and search still returns its keyword page with `200` within its usual latency. (Task 7)
5. **A withdrawn member's entry is not surfaced by `/remember`.** The withdrawn-author filter applies to both ranked lists. (Task 8)

## Privacy hazards, each answered by a named test

| Hazard | Test |
|---|---|
| Query or entry text in a log at TRACE | `SecretsNeverLoggedTest` gains `scan.watch` of a remembered query and an embedded entry (Task 10) |
| API key printed | `AiPropertiesTest.redacts keys in toString` (Task 2) |
| Vectors kept after off | `ForgetBondEmbeddingsTest` (Task 6) |
| Vectors kept after erasure | `EntryEmbeddingsTest.erasure deletes the vector` (Task 5) |

## File structure

```
modules/ai/                                     NEW module
  build.gradle.kts
  src/main/kotlin/com/moyi/ai/api/EmbeddingPort.kt         port + EmbeddingSpace + EmbeddingUnavailableException
  src/main/kotlin/com/moyi/ai/infra/AiProperties.kt         moyi.ai.embedding.*
  src/main/kotlin/com/moyi/ai/infra/AiConfiguration.kt      picks the adapter (exhaustive when)
  src/main/kotlin/com/moyi/ai/infra/SpringAiEmbeddingAdapter.kt  EmbeddingPort over any Spring AI EmbeddingModel, + metrics
  src/main/kotlin/com/moyi/ai/infra/VoyageEmbeddingModel.kt  our Spring AI EmbeddingModel over Voyage HTTP
  src/test/kotlin/com/moyi/ai/infra/{AiPropertiesTest,SpringAiEmbeddingAdapterTest,VoyageEmbeddingModelTest,OpenAiWiringTest}.kt
  src/test/kotlin/com/moyi/ai/infra/StubHttpServer.kt        JDK HttpServer stub (no new dependency)
modules/bond/
  src/main/resources/db/migration/V37__bond_semantic_search.sql
  domain/Bond.kt, domain/Proposal.kt                       field + kind
  service/UpdateBond.kt, service/EnableSemanticSearch.kt (NEW), service/BondEntityTag.kt, service/BondViews.kt, service/BondAccessAdapter.kt
  api/BondAccess.kt                                         membership/closing-view field + bondsWithSemanticSearch
  web/PatchBondRequest.kt, web/BondResponse.kt, web/SemanticSearchController.kt (NEW)
modules/gratitude/
  src/main/resources/db/migration/V38__gratitude_entry_embeddings.sql
  api/EmbeddingSweep.kt                                     for scheduling
  infra/database/EntryEmbeddings.kt (NEW)                   tables, missing, upsert, delete
  infra/database/EntryStore.kt                              delete vectors when the projection changes
  infra/database/ArchiveDays.kt                             semantic predicate + ranked candidates
  service/EmbedPendingEntries.kt (NEW), service/ForgetBondEmbeddings.kt (NEW), service/QueryEmbedder.kt (NEW)
  service/GetDays.kt                                        search(semantic), remember(...)
  web/SearchController.kt, web/RememberController.kt (NEW), web/RememberResponse.kt (NEW)
  src/test/resources/eval/{golden.json, vectors-openai-1536.json, vectors-voyage-1024.json}
  src/test/kotlin/.../eval/RetrievalEvalTest.kt
modules/scheduling/service/EmbeddingJob.kt (NEW)
common/security/.../ratelimit/RateLimitBucket.kt          REMEMBER_USER
common/testing/.../PostgresIntegrationTest.kt             pgvector image
tools/embedding-eval/ (NEW)                               live re-embed + comparison (manual)
contracts/.../RememberDocumentation.kt (NEW), SemanticSearchDocumentation.kt (NEW)
adr/0053-semantic-search-and-the-ai-module.md
docker-compose.yml, config/deploy.yml, .kamal/secrets, ops/backup/restore-drill.sh, scripts/smoke.sh, tools/bruno/35-37
```

---

### Task 1: pgvector everywhere tests and local dev run Postgres

**Files:**
- Modify: `common/testing/src/main/kotlin/com/moyi/common/testing/PostgresIntegrationTest.kt:46-49`
- Modify: `modules/bond/src/test/kotlin/com/moyi/bond/infra/database/AnchorIntervalBackfillTest.kt:219`
- Modify: `docker-compose.yml:13`, `ops/backup/restore-drill.sh` (every `postgres:18`)
- Test: `common/testing/src/test/kotlin/com/moyi/common/testing/PgvectorAvailableTest.kt` (new)

**Interfaces:** Produces `PostgresIntegrationTest.IMAGE: DockerImageName` (public const-like val) for any other container.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.moyi.common.testing

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest(classes = [TestingTestApplication::class])
internal class PgvectorAvailableTest(@Autowired private val jdbc: JdbcTemplate) : PostgresIntegrationTest() {
    @Test
    fun `the test database can create the vector extension`() {
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector")
        jdbc.queryForObject("SELECT '[1,0]'::vector <=> '[0,1]'::vector", Double::class.java) shouldBe 1.0
    }
}
```
(If `common/testing` has no test application, create `TestingTestApplication` as an empty `@SpringBootApplication` in the test source set, with `spring-boot-starter-jdbc` as `testImplementation`.)

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew :common:testing:test --tests '*PgvectorAvailableTest*'`
Expected: FAIL, `extension "vector" is not available`.

- [ ] **Step 3: Switch the image**

```kotlin
// PostgresIntegrationTest.kt
val IMAGE: DockerImageName =
    DockerImageName.parse("pgvector/pgvector:0.8.7-pg18").asCompatibleSubstituteFor("postgres")
...
PostgreSQLContainer(IMAGE).withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=$MAX_CONNECTIONS").also { it.start() }
```
Use `PostgresIntegrationTest.IMAGE` in `AnchorIntervalBackfillTest.kt:219`. In `docker-compose.yml` and `restore-drill.sh`, replace `postgres:18` with `pgvector/pgvector:0.8.7-pg18`. **Do not touch `config/deploy.yml`'s accessory here.** Production is switched with Daniel in Task 11.

- [ ] **Step 4: Run it and see it pass, then the whole build**

Run: `./gradlew :common:testing:test --tests '*PgvectorAvailableTest*'`, then `./gradlew build`
Expected: PASS. The full build stays green (same Postgres 18 underneath).

- [ ] **Step 5: Commit**

```bash
git add common/testing modules/bond/src/test/kotlin/com/moyi/bond/infra/database/AnchorIntervalBackfillTest.kt docker-compose.yml ops/backup/restore-drill.sh
git commit -m "build: tests and local Postgres run the pgvector image, Postgres 18 underneath"
```

---

### Task 2: the `ai` module and its port

**Files:**
- Create: `modules/ai/build.gradle.kts`, `api/EmbeddingPort.kt`, `infra/AiProperties.kt`, `infra/AiConfiguration.kt`, `infra/SpringAiEmbeddingAdapter.kt`
- Modify: `settings.gradle.kts` (include `modules:ai`), `app/build.gradle.kts` (`implementation(projects.modules.ai)`), `gradle/libs.versions.toml`, `app/src/test/kotlin/com/moyi/app/ArchitectureTest.kt:44-54` (add `"ai"` to `MODULES`), `app/src/main/resources/application.yml` (comment table of env vars, as at :232-256), `app/src/main/resources/application-local.yml` (`moyi.ai.embedding.provider: none`), `app/src/test/resources/application-test.yml` (same)
- Test: `modules/ai/src/test/kotlin/com/moyi/ai/infra/AiPropertiesTest.kt`, `SpringAiEmbeddingAdapterTest.kt`

**Interfaces:**
- Produces (`com.moyi.ai.api`, public):
```kotlin
enum class EmbeddingSpace(val dimensions: Int) { OPENAI_TEXT_EMBEDDING_3_SMALL(1536), VOYAGE_3_5(1024) }

interface EmbeddingPort {
    /** The space vectors are made in now, or null when no provider is configured. */
    val space: EmbeddingSpace?
    /** One vector per text, in order. @throws EmbeddingUnavailableException on no provider, timeout or provider error. */
    fun embed(texts: List<String>): List<FloatArray>
}

class EmbeddingUnavailableException(reason: String, cause: Throwable? = null) : RuntimeException(reason, cause)
```
The exception message is a fixed reason ("timeout", "provider error 503", "no provider"), **never text**.

- [ ] **Step 1: Catalog and build file**

`libs.versions.toml`:
```toml
springAi = "2.0.2"
[libraries]
spring-ai-bom = { module = "org.springframework.ai:spring-ai-bom", version.ref = "springAi" }
spring-ai-openai = { module = "org.springframework.ai:spring-ai-openai" }
spring-ai-model = { module = "org.springframework.ai:spring-ai-model" }
```
`modules/ai/build.gradle.kts`:
```kotlin
plugins { id("spring-boot-service-convention") }

dependencies {
    implementation(platform(libs.spring.ai.bom))
    implementation(projects.common.core)
    implementation(libs.spring.ai.model)
    implementation(libs.spring.ai.openai)
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework:spring-web")
    implementation("io.micrometer:micrometer-core")
    testImplementation(projects.common.testing)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
```
Not the Spring AI starter: no autoconfiguration. We build the beans ourselves, as `PushSenderConfiguration` does.

- [ ] **Step 2: Write the failing tests**

```kotlin
internal class AiPropertiesTest {
    @Test
    fun `redacts keys in toString`() {
        val p = AiProperties(provider = AiProperties.Provider.OPENAI, openai = AiProperties.OpenAi(apiKey = "sk-secret"),
            voyage = AiProperties.Voyage(apiKey = "pa-secret"))
        p.toString() shouldNotContain "sk-secret"
        p.toString() shouldNotContain "pa-secret"
    }

    @Test
    fun `the real provider is the default`() {
        AiProperties().provider shouldBe AiProperties.Provider.OPENAI
    }
}

internal class SpringAiEmbeddingAdapterTest {
    private val meters = SimpleMeterRegistry()

    private fun model(answer: (List<String>) -> List<FloatArray>) = object : EmbeddingModel {
        override fun call(request: EmbeddingRequest) =
            EmbeddingResponse(answer(request.instructions).mapIndexed { i, v -> Embedding(v, i) })
        override fun embed(document: Document): FloatArray = error("unused")
    }

    @Test
    fun `returns one vector per text in order and counts the request`() {
        val adapter = SpringAiEmbeddingAdapter(model { t -> t.map { floatArrayOf(it.length.toFloat(), 0f) } },
            EmbeddingSpace.OPENAI_TEXT_EMBEDDING_3_SMALL, meters, "openai")
        adapter.embed(listOf("ab", "abcd")).map { it[0] } shouldBe listOf(2f, 4f)
        meters.counter("gratitude.ai.embedding.requests", "provider", "openai", "outcome", "ok").count() shouldBe 1.0
    }

    @Test
    fun `a provider failure becomes EmbeddingUnavailableException without the text`() {
        val adapter = SpringAiEmbeddingAdapter(model { throw IllegalStateException("boom: secret words") },
            EmbeddingSpace.OPENAI_TEXT_EMBEDDING_3_SMALL, meters, "openai")
        val thrown = shouldThrow<EmbeddingUnavailableException> { adapter.embed(listOf("secret words")) }
        thrown.message shouldBe "provider error"
        meters.counter("gratitude.ai.embedding.requests", "provider", "openai", "outcome", "failed").count() shouldBe 1.0
    }

    @Test
    fun `a vector of the wrong size is refused`() {
        val adapter = SpringAiEmbeddingAdapter(model { t -> t.map { floatArrayOf(1f) } },
            EmbeddingSpace.VOYAGE_3_5, meters, "voyage")
        shouldThrow<EmbeddingUnavailableException> { adapter.embed(listOf("x")) }.message shouldBe "wrong dimensions"
    }
}
```

- [ ] **Step 3: Run and watch them fail**

Run: `./gradlew :modules:ai:test`
Expected: FAIL, classes undefined.

- [ ] **Step 4: Implement**

```kotlin
// infra/AiProperties.kt
@Validated
@ConfigurationProperties(prefix = "moyi.ai.embedding")
internal data class AiProperties(
    val provider: Provider = Provider.OPENAI,
    @field:Valid val openai: OpenAi = OpenAi(),
    @field:Valid val voyage: Voyage = Voyage(),
    val timeout: Duration = Duration.ofSeconds(10),
) {
    /** NONE: no vector is made; search and remember answer from keywords. Never the default. */
    internal enum class Provider { OPENAI, VOYAGE, NONE }

    internal data class OpenAi(val apiKey: String? = null, val model: String = "text-embedding-3-small",
                               @field:NotBlank val baseUrl: String = "https://api.openai.com") {
        override fun toString() = "OpenAi(model=$model, baseUrl=$baseUrl, apiKey=${apiKey?.let { "<redacted>" }})"
    }

    internal data class Voyage(val apiKey: String? = null, val model: String = "voyage-3.5",
                               @field:NotBlank val baseUrl: String = "https://api.voyageai.com") {
        override fun toString() = "Voyage(model=$model, baseUrl=$baseUrl, apiKey=${apiKey?.let { "<redacted>" }})"
    }
}
```
```kotlin
// infra/SpringAiEmbeddingAdapter.kt
internal class SpringAiEmbeddingAdapter(
    private val model: EmbeddingModel,
    override val space: EmbeddingSpace,
    private val meters: MeterRegistry,
    private val provider: String,
) : EmbeddingPort {
    override fun embed(texts: List<String>): List<FloatArray> {
        val started = System.nanoTime()
        try {
            val vectors = call(texts)
            if (vectors.size != texts.size || vectors.any { it.size != space.dimensions }) {
                throw EmbeddingUnavailableException("wrong dimensions")
            }
            record("ok", started)
            return vectors
        } catch (e: EmbeddingUnavailableException) {
            record("failed", started)
            throw e
        }
    }

    /** Any provider exception becomes a fixed reason: its message may quote the input. */
    @Suppress("TooGenericExceptionCaught")
    private fun call(texts: List<String>): List<FloatArray> =
        try {
            model.call(EmbeddingRequest(texts, null)).results.sortedBy { it.index }.map { it.output }
        } catch (e: Exception) {
            throw EmbeddingUnavailableException("provider error", e)
        }

    private fun record(outcome: String, started: Long) {
        meters.counter(REQUESTS, "provider", provider, "outcome", outcome).increment()
        Timer.builder(LATENCY).tag("provider", provider).register(meters).record(Duration.ofNanos(System.nanoTime() - started))
    }

    companion object {
        const val REQUESTS = "gratitude.ai.embedding.requests"
        const val LATENCY = "gratitude.ai.embedding.latency"
    }
}
```
`NoEmbeddings` (an `EmbeddingPort` whose `space` is null and whose `embed` throws `EmbeddingUnavailableException("no provider")`) lives in the same file.

```kotlin
// infra/AiConfiguration.kt
@Configuration
@EnableConfigurationProperties(AiProperties::class)
internal class AiConfiguration {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun embeddingPort(properties: AiProperties, meters: MeterRegistry, rest: RestClient.Builder): EmbeddingPort =
        when (properties.provider) {
            AiProperties.Provider.OPENAI -> {
                val key = checkNotNull(properties.openai.apiKey?.takeIf { it.isNotBlank() }) {
                    "moyi.ai.embedding.openai.api-key is required while the provider is OPENAI (MOYI_AI_EMBEDDING_OPENAI_API_KEY)"
                }
                val options = OpenAiEmbeddingOptions.builder().apiKey(key).baseUrl(properties.openai.baseUrl)
                    .model(properties.openai.model).timeout(properties.timeout).maxRetries(2).build()
                SpringAiEmbeddingAdapter(OpenAiEmbeddingModel(options), EmbeddingSpace.OPENAI_TEXT_EMBEDDING_3_SMALL, meters, "openai")
            }
            AiProperties.Provider.VOYAGE -> {
                val key = checkNotNull(properties.voyage.apiKey?.takeIf { it.isNotBlank() }) {
                    "moyi.ai.embedding.voyage.api-key is required while the provider is VOYAGE (MOYI_AI_EMBEDDING_VOYAGE_API_KEY)"
                }
                val client = rest.clone().baseUrl(properties.voyage.baseUrl)
                    .requestFactory(SimpleClientHttpRequestFactory().apply {
                        setConnectTimeout(properties.timeout); setReadTimeout(properties.timeout) })
                    .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer $key").build()
                SpringAiEmbeddingAdapter(VoyageEmbeddingModel(client, properties.voyage.model), EmbeddingSpace.VOYAGE_3_5, meters, "voyage")
            }
            AiProperties.Provider.NONE -> NoEmbeddings
        }.also { log.info("Embedding provider: {}", properties.provider) }
}
```
The `VOYAGE` branch needs `VoyageEmbeddingModel`: write that class (Task 3, Step 3's code) in this task's commit, so the build compiles. Task 3 adds its tests and fixes whatever they find.

- [ ] **Step 5: Run and see them pass, then the architecture test**

Run: `./gradlew :modules:ai:test :app:test --tests '*ArchitectureTest*'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add modules/ai settings.gradle.kts app/build.gradle.kts gradle/libs.versions.toml app/src/test/kotlin/com/moyi/app/ArchitectureTest.kt app/src/main/resources app/src/test/resources
git commit -m "feat(ai): an embedding port, with OpenAI through Spring AI 2.0 behind it"
```

---

### Task 3: the Voyage adapter, our own Spring AI `EmbeddingModel`

**Files:**
- Create: `modules/ai/src/main/kotlin/com/moyi/ai/infra/VoyageEmbeddingModel.kt` (if not landed in Task 2)
- Create: `modules/ai/src/test/kotlin/com/moyi/ai/infra/StubHttpServer.kt`, `VoyageEmbeddingModelTest.kt`, `OpenAiWiringTest.kt`

**Interfaces:** Consumes `EmbeddingModel` (Spring AI). Produces `internal class VoyageEmbeddingModel(client: RestClient, model: String) : EmbeddingModel`.

Voyage API: `POST /v1/embeddings`, body `{"input": [..], "model": "voyage-3.5", "input_type": "document"}`. Response `{"data": [{"embedding": [..], "index": 0}], "usage": {"total_tokens": n}}`. Use `input_type: "query"` for queries; the port gains no parameter for this in S1. Record it in the ADR as a measured follow-up if the eval shows it matters. **[inferred]: check the current Voyage docs for the model name and fields before Step 4.**

- [ ] **Step 1: Write the stub and the failing tests**

```kotlin
// StubHttpServer.kt: a JDK HttpServer answering one path with a scripted status/body, recording requests.
internal class StubHttpServer(private val path: String) : AutoCloseable {
    val requests = mutableListOf<String>()
    var responses: ArrayDeque<Pair<Int, String>> = ArrayDeque()
    var delay: Duration = Duration.ZERO
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext(path) { ex ->
            requests += ex.requestBody.readAllBytes().decodeToString()
            Thread.sleep(delay.toMillis())
            val (status, body) = responses.removeFirst()
            val bytes = body.encodeToByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
        }
        start()
    }
    val baseUrl get() = "http://127.0.0.1:${server.address.port}"
    override fun close() = server.stop(0)
}
```
```kotlin
internal class VoyageEmbeddingModelTest {
    private val stub = StubHttpServer("/v1/embeddings")
    @AfterEach fun close() = stub.close()

    private fun model(timeout: Duration = Duration.ofSeconds(2)) = VoyageEmbeddingModel(
        RestClient.builder().baseUrl(stub.baseUrl).requestFactory(SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(timeout); setReadTimeout(timeout) }).build(), "voyage-3.5")

    @Test
    fun `sends the texts as documents and returns vectors by index`() {
        stub.responses += 200 to """{"data":[{"embedding":[0.2,0.1],"index":1},{"embedding":[0.9,0.8],"index":0}],"usage":{"total_tokens":7}}"""
        val out = model().call(EmbeddingRequest(listOf("a", "b"), null)).results.sortedBy { it.index }
        out.map { it.output.toList() } shouldBe listOf(listOf(0.9f, 0.8f), listOf(0.2f, 0.1f))
        stub.requests.single() shouldContain "\"input_type\":\"document\""
        stub.requests.single() shouldContain "\"model\":\"voyage-3.5\""
    }

    @Test
    fun `a 429 then 200 is retried once`() {
        stub.responses += 429 to """{"detail":"rate"}"""
        stub.responses += 200 to """{"data":[{"embedding":[1.0],"index":0}],"usage":{"total_tokens":1}}"""
        model().call(EmbeddingRequest(listOf("a"), null)).results.size shouldBe 1
        stub.requests.size shouldBe 2
    }

    @Test
    fun `a malformed body and a 500 both throw, without echoing the input`() {
        stub.responses += 200 to """{"nope":true}"""
        shouldThrow<IllegalStateException> { model().call(EmbeddingRequest(listOf("secret"), null)) }
            .message shouldNotContain "secret"
        stub.responses += 500 to "oops"; stub.responses += 500 to "oops"
        shouldThrow<Exception> { model().call(EmbeddingRequest(listOf("secret"), null)) }
    }

    @Test
    fun `a slow provider times out`() {
        stub.delay = Duration.ofSeconds(3); stub.responses += 200 to "{}"
        shouldThrow<Exception> { model(timeout = Duration.ofMillis(300)).call(EmbeddingRequest(listOf("a"), null)) }
    }
}
```
```kotlin
internal class OpenAiWiringTest {
    @Test
    fun `the OpenAI adapter talks to the configured base URL and maps the response`() {
        StubHttpServer("/v1/embeddings").use { stub ->
            stub.responses += 200 to """{"object":"list","data":[{"object":"embedding","index":0,"embedding":[${List(1536) { "0.0" }.joinToString(",")}]}],"model":"text-embedding-3-small","usage":{"prompt_tokens":1,"total_tokens":1}}"""
            val port = AiConfiguration().embeddingPort(
                AiProperties(provider = AiProperties.Provider.OPENAI,
                    openai = AiProperties.OpenAi(apiKey = "sk-test", baseUrl = "${stub.baseUrl}/v1")),
                SimpleMeterRegistry(), RestClient.builder())
            port.embed(listOf("hello")).single().size shouldBe 1536
        }
    }

    @Test
    fun `OPENAI without a key refuses to start`() {
        shouldThrow<IllegalStateException> {
            AiConfiguration().embeddingPort(AiProperties(), SimpleMeterRegistry(), RestClient.builder())
        }.message shouldContain "MOYI_AI_EMBEDDING_OPENAI_API_KEY"
    }
}
```
(The OpenAI SDK's base URL convention, with or without `/v1`, is **[inferred]**. Make the test pass by adjusting `baseUrl` in the test and the default in `AiProperties.OpenAi` consistently, then note the convention in the KDoc.)

- [ ] **Step 2: Run them and watch them fail**

Run: `./gradlew :modules:ai:test`
Expected: FAIL.

- [ ] **Step 3: Implement `VoyageEmbeddingModel`**

```kotlin
/**
 * Voyage's `/v1/embeddings` as a Spring AI [EmbeddingModel], so the port treats both providers alike.
 * One retry on 429 or 5xx after 500 ms. Errors carry the status, never the input.
 */
internal class VoyageEmbeddingModel(private val client: RestClient, private val model: String) : EmbeddingModel {
    override fun call(request: EmbeddingRequest): EmbeddingResponse {
        val body = VoyageRequest(request.instructions, model, "document")
        val reply = send(body) ?: send(body) ?: error("Voyage gave no answer after a retry")
        val data = checkNotNull(reply.data) { "Voyage answered without data" }
        return EmbeddingResponse(data.map { Embedding(it.embedding.toFloatArray(), it.index) })
    }

    override fun embed(document: Document): FloatArray = call(EmbeddingRequest(listOf(document.text.orEmpty()), null)).result.output

    /** null = retryable (429/5xx); throws on any other failure. */
    private fun send(body: VoyageRequest): VoyageResponse? =
        client.post().uri("/v1/embeddings").body(body).exchange { _, res ->
            when {
                res.statusCode.value() == 429 || res.statusCode.is5xxServerError -> null.also { Thread.sleep(RETRY_MS) }
                res.statusCode.is2xxSuccessful -> res.bodyTo(VoyageResponse::class.java)
                else -> error("Voyage refused the request: ${res.statusCode.value()}")
            }
        }

    private data class VoyageRequest(val input: List<String>, val model: String,
                                     @get:JsonProperty("input_type") val inputType: String)
    private data class VoyageResponse(val data: List<Item>?) { data class Item(val embedding: List<Float>, val index: Int) }
    private companion object { const val RETRY_MS = 500L }
}
```
(Jackson 3: `com.fasterxml.jackson.annotation.JsonProperty` annotations are unchanged. Check `moyi-stack-gotchas` for the module import path.)

- [ ] **Step 4: Run and see them pass**

Run: `./gradlew :modules:ai:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/ai
git commit -m "feat(ai): Voyage as our own Spring AI EmbeddingModel, with a retry and a timeout"
```

---

### Task 4: the setting, turned off by one member and on by both

**Files:**
- Create: `modules/bond/src/main/resources/db/migration/V37__bond_semantic_search.sql`, `service/EnableSemanticSearch.kt`, `service/SemanticSearchDisabled.kt`, `web/SemanticSearchController.kt`
- Modify: `domain/Bond.kt` (field; `BondSettings.semanticSearchEnabled: Boolean?`; `update`; `create` default true; `withSemanticSearch(on: Boolean)`), `domain/Proposal.kt:13` (`SEMANTIC_SEARCH_ON` + factory), `infra/database/BondEntity.kt` (column), `service/UpdateBond.kt` (publish on true→false), `service/BondEntityTag.kt:16-44` (field + pending proposal), `service/BondViews.kt:104-108`, `web/PatchBondRequest.kt` (field, `atLeastOneSetting`, refuse true), `web/BondResponse.kt` (`semanticSearchEnabled`, `pendingSemanticSearch`), `api/BondAccess.kt` + `service/BondAccessAdapter.kt:148-171` (`semanticSearchEnabled` on `BondMembership` and `BondClosingView`; `bondsWithSemanticSearch(after: UUID?, limit: Int): List<UUID>`), `app/.../ArchitectureTest.kt:148` (add `bondsWithSemanticSearch` to `UNGUARDED_BOND_READS`), `app/.../FlywayMigrationTest.kt:134-152`
- Test: `modules/bond/src/test/kotlin/com/moyi/bond/web/SemanticSearchSettingTest.kt` (new); extend `BondCrossTenantTest`, `ProposalRaceTest`

**Interfaces:**
- Produces: `BondMembership.semanticSearchEnabled: Boolean`, `BondClosingView.semanticSearchEnabled: Boolean`, `BondAccess.bondsWithSemanticSearch(after: UUID?, limit: Int): List<UUID>` (ordered by id, open or ended bonds with the setting on).
- Produces event `"SemanticSearchDisabled"`, aggregate `"Bond"`, references `{"bondId"}`.
- Routes: `PATCH /api/v1/bonds/{id}` `{"semanticSearchEnabled": false}`; `POST /api/v1/bonds/{id}/semantic-search` → `202` + `BondResponse`; `DELETE /api/v1/bonds/{id}/semantic-search` → `204`.

- [ ] **Step 1: Migration**

```sql
-- V37: whether a bond's entries are embedded for semantic search (FR-204, ADR-0053).
-- On by default (the owner's ruling, 2026-10-30); either member turns it off, both turn it on.
ALTER TABLE bonds ADD COLUMN semantic_search_enabled boolean NOT NULL DEFAULT true;

ALTER TABLE bond_proposals DROP CONSTRAINT bond_proposals_kind_check;
ALTER TABLE bond_proposals ADD CONSTRAINT bond_proposals_kind_check
    CHECK (kind IN ('TIMEZONE_CHANGE', 'DELETION', 'SEMANTIC_SEARCH_ON'));
```

- [ ] **Step 2: Write the failing tests** (same rig as `BondTimezoneEndpointTest`)

```kotlin
@Test fun `a new bond has semantic search on`() {
    val bond = pair(ada, bea)
    get(ada, bond)["semanticSearchEnabled"].asBoolean() shouldBe true
}

@Test fun `either member turns it off alone, and the outbox records it`() {
    val bond = pair(ada, bea)
    patch(bea, bond, """{"semanticSearchEnabled":false}""", ifMatch = etag(bea, bond)).status shouldBe 200
    get(ada, bond)["semanticSearchEnabled"].asBoolean() shouldBe false
    outboxTypes(bond) shouldContain "SemanticSearchDisabled"
}

@Test fun `PATCH cannot turn it on`() {
    val bond = pair(ada, bea); turnOff(ada, bond)
    val res = patch(ada, bond, """{"semanticSearchEnabled":true}""", ifMatch = etag(ada, bond))
    res.status shouldBe 422
    violations(res) shouldBe listOf("semanticSearchEnabled" to "TURNED_ON_BY_BOTH")
}

@Test fun `turning on needs the other member`() {
    val bond = pair(ada, bea); turnOff(ada, bond)
    post(ada, "/bonds/$bond/semantic-search").status shouldBe 202
    get(ada, bond)["semanticSearchEnabled"].asBoolean() shouldBe false
    get(bea, bond)["pendingSemanticSearch"]["proposedByMemberId"].isNull shouldBe false
    post(bea, "/bonds/$bond/semantic-search").status shouldBe 202
    get(ada, bond)["semanticSearchEnabled"].asBoolean() shouldBe true
    get(ada, bond)["pendingSemanticSearch"].isNull shouldBe true
}

@Test fun `the proposer asking again changes nothing; either member cancels`() {
    val bond = pair(ada, bea); turnOff(ada, bond)
    post(ada, "/bonds/$bond/semantic-search"); post(ada, "/bonds/$bond/semantic-search").status shouldBe 202
    get(ada, bond)["semanticSearchEnabled"].asBoolean() shouldBe false
    delete(bea, "/bonds/$bond/semantic-search").status shouldBe 204
    get(ada, bond)["pendingSemanticSearch"].isNull shouldBe true
}

@Test fun `a solo bond turns it back on alone`() {
    val bond = solo(ada); turnOff(ada, bond)
    post(ada, "/bonds/$bond/semantic-search").status shouldBe 202
    get(ada, bond)["semanticSearchEnabled"].asBoolean() shouldBe true
}

@Test fun `the ETag changes when the setting changes`() {
    val bond = pair(ada, bea); val before = etag(ada, bond); turnOff(ada, bond)
    etag(ada, bond) shouldNotBe before
}

@Test fun `the membership carries the setting`() {
    val bond = pair(ada, bea); turnOff(ada, bond)
    access.membershipOf(ada, UUID.fromString(bond)).semanticSearchEnabled shouldBe false
}
```
Add both new routes to `BondCrossTenantTest`'s fixtures (stranger → the bond's 404). Add a `ProposalRaceTest` case: both members `POST` at once on a bond that is off → exactly one proposal, and the setting ends on.

- [ ] **Step 3: Run and watch them fail**

Run: `./gradlew :modules:bond:test --tests '*SemanticSearchSettingTest*' --tests '*BondCrossTenantTest*' --tests '*ProposalRaceTest*'`
Expected: FAIL.

- [ ] **Step 4: Implement**

Follow `RequestDeletion` for `EnableSemanticSearch.request/cancel`: `bonds.lockBond`, `isOpen`, `proposals.closeLapsed`, then `when` (no live proposal → open, publishing `ProposalOpened`; mine → no-op; the other's → `proposals.confirm` CAS, then `bonds.update(bond.withSemanticSearch(true))`). A solo bond (`PENDING_MEMBER`) applies directly. If the bond is already on, `request` is a no-op `202`.

In `UpdateBond.patch`, after `bonds.update(updated)`:
```kotlin
if (bond.semanticSearchEnabled && !updated.semanticSearchEnabled) {
    support.events.publish(SemanticSearchDisabled.of(bond.id, now))
    proposals.cancelLive(bond.id, ProposalKind.SEMANTIC_SEARCH_ON, now)
}
```
`SemanticSearchDisabled.of(bondId, at) = OutboxEvent("Bond", bondId, TYPE, mapOf("bondId" to bondId), at)`, with `TYPE = "SemanticSearchDisabled"`.

In `PatchBondRequest`, add `val semanticSearchEnabled: Boolean? = null`, include it in `atLeastOneSetting`, and add a private `@get:AssertTrue(message = "TURNED_ON_BY_BOTH")` getter `semanticSearchOnlyOff = semanticSearchEnabled != true`. Map the violation code exactly as the `anchorTimezone` refusal does (copy its mechanism and field naming).

`BondAccessAdapter.assemble`: `semanticSearchEnabled = bond.semanticSearchEnabled`. `bondsWithSemanticSearch`: copy `bondsToSweep` (`BondAccess.kt:109`), adding `AND semantic_search_enabled`.

Check `NotifyBondMembers.kt:73+`: `PROPOSAL_OPENED` is generic, so no copy change. If the push text names the kind, add the new one.

Add `37` to `FlywayMigrationTest`'s version list.

- [ ] **Step 5: Run and see them pass**

Run: `./gradlew :modules:bond:test :app:test --tests '*FlywayMigrationTest*' --tests '*ArchitectureTest*'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add modules/bond app/src/test/kotlin/com/moyi/app/ArchitectureTest.kt app/src/test/kotlin/com/moyi/app/FlywayMigrationTest.kt
git commit -m "feat(bond): semantic search on by default; one member turns it off, both turn it on"
```

---

### Task 5: vectors beside entries, and dying with their words

**Files:**
- Create: `modules/gratitude/src/main/resources/db/migration/V38__gratitude_entry_embeddings.sql`, `infra/database/EntryEmbeddings.kt`
- Modify: `infra/database/EntryStore.kt:48-64`, `modules/gratitude/build.gradle.kts` (`implementation(projects.modules.ai)`), `app/.../FlywayMigrationTest.kt` (38, extension `vector`)
- Test: `modules/gratitude/src/test/kotlin/com/moyi/gratitude/infra/database/EntryEmbeddingsTest.kt`

**Interfaces:**
- Consumes: `EmbeddingSpace` (Task 2).
- Produces (`internal`):
```kotlin
internal class EntryEmbeddings(jdbc: JdbcTemplate) {
    fun tableOf(space: EmbeddingSpace): String   // "entry_embeddings_openai_1536" | "entry_embeddings_voyage_1024"
    data class Pending(val entryId: UUID, val bondId: UUID, val text: String) { override fun toString() = "Pending($entryId)" }
    fun missing(space: EmbeddingSpace, bondId: UUID, limit: Int): List<Pending>
    /** Writes only if the entry's text_search still hashes to [textHash]; returns whether a row was written. */
    fun upsert(space: EmbeddingSpace, entryId: UUID, bondId: UUID, vector: FloatArray, textHash: ByteArray, at: Instant): Boolean
    fun deleteForEntry(entryId: UUID)
    fun deleteForBond(bondId: UUID): Int
    fun countFor(bondId: UUID): Int
    companion object { fun hashOf(text: String): ByteArray }  // SHA-256 of UTF-8
}
```

- [ ] **Step 1: Migration**

```sql
-- V38: an entry's text_search as vectors, one table per embedding model (FR-204, ADR-0053).
-- No approximate index: a bond's rows are few, so an exact scan through bond_id is faster and misses nothing.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE entry_embeddings_openai_1536 (
    entry_id   uuid PRIMARY KEY REFERENCES entries (id) ON DELETE CASCADE,
    bond_id    uuid NOT NULL,
    embedding  vector(1536) NOT NULL,
    text_hash  bytea NOT NULL,
    created_at timestamptz NOT NULL
);
CREATE INDEX entry_embeddings_openai_1536_bond_idx ON entry_embeddings_openai_1536 (bond_id);

CREATE TABLE entry_embeddings_voyage_1024 (
    entry_id   uuid PRIMARY KEY REFERENCES entries (id) ON DELETE CASCADE,
    bond_id    uuid NOT NULL,
    embedding  vector(1024) NOT NULL,
    text_hash  bytea NOT NULL,
    created_at timestamptz NOT NULL
);
CREATE INDEX entry_embeddings_voyage_1024_bond_idx ON entry_embeddings_voyage_1024 (bond_id);
```

- [ ] **Step 2: Write the failing tests** (`PostgresIntegrationTest`; rows through `ArchiveRig.insertDay/insertEntry` or `WithdrawalRig.submit`)

```kotlin
@Test fun `missing lists live entries with text and no vector, and upsert fills them`() {
    val e = entry("thank you for the soup")
    embeddings.missing(OPENAI, bond, 10).map { it.entryId } shouldBe listOf(e)
    embeddings.upsert(OPENAI, e, bond, vec(1536), EntryEmbeddings.hashOf("thank you for the soup"), NOW) shouldBe true
    embeddings.missing(OPENAI, bond, 10) shouldBe emptyList()
    embeddings.missing(VOYAGE, bond, 10).size shouldBe 1          // the other table is still empty
}

@Test fun `upsert refuses a vector of words the entry no longer holds`() {
    val e = entry("an old plum")
    embeddings.upsert(OPENAI, e, bond, vec(1536), EntryEmbeddings.hashOf("a new pear"), NOW) shouldBe false
    embeddings.countFor(bond) shouldBe 0
}

@Test fun `an edit deletes the vector, so the old words are never matched by meaning`() {
    val id = rig.bonds.submit(ada, bondKey, "an old plum")
    embeddings.upsert(OPENAI, id.uuid, bond, vec(1536), EntryEmbeddings.hashOf("an old plum"), NOW)
    patch(ada, id, "a new pear")
    embeddings.countFor(bond) shouldBe 0
}

@Test fun `erasure deletes the vector`() {   // author delete; repeat for withdrawal and the pre-close sweep
    val id = rig.bonds.submit(ada, bondKey, "words")
    embeddings.upsert(OPENAI, id.uuid, bond, vec(1536), EntryEmbeddings.hashOf("words"), NOW)
    rig.bonds.delete(ada, id)
    embeddings.countFor(bond) shouldBe 0
}

@Test fun `an update that leaves the words alone keeps the vector`() {   // e.g. a reveal or an image swap
    ...upsert, then reveal the day...
    embeddings.countFor(bond) shouldBe 1
}
```
Write the withdrawal case with `WithdrawalRig` (`EntriesWithdrawn` consumer) and the pre-close case through `CloseDay`, matching `SearchWithdrawalTest`.

- [ ] **Step 3: Run and watch them fail**

Run: `./gradlew :modules:gratitude:test --tests '*EntryEmbeddingsTest*'`
Expected: FAIL.

- [ ] **Step 4: Implement**

`missing`:
```sql
SELECT e.id, e.bond_id, e.text_search FROM entries e
WHERE e.bond_id = ? AND e.text_search IS NOT NULL AND e.deleted_at IS NULL AND e.status <> 'DELETED'
  AND NOT EXISTS (SELECT 1 FROM <table> x WHERE x.entry_id = e.id)
ORDER BY e.created_at LIMIT ?
```
`upsert` (the hash check is inside the statement, so a concurrent edit can never be overwritten):
```sql
INSERT INTO <table> (entry_id, bond_id, embedding, text_hash, created_at)
SELECT e.id, e.bond_id, ?::vector, ?, ? FROM entries e
WHERE e.id = ? AND e.text_search IS NOT NULL AND sha256(convert_to(e.text_search, 'UTF8')) = ?
ON CONFLICT (entry_id) DO UPDATE SET embedding = EXCLUDED.embedding, text_hash = EXCLUDED.text_hash, created_at = EXCLUDED.created_at
```
Bind the vector as its pgvector text form, `"[" + v.joinToString(",") + "]"`. The table name comes **only** from `tableOf(space)`, an exhaustive `when` over the enum, never from input.

`EntryStore.update`: capture `val before = entity.textSearch`, set the new projection, and after `saveAndFlush`: `if (before != entity.textSearch) embeddings.deleteForEntry(entity.id)`. `deleteForEntry` runs one `DELETE` per table (`EmbeddingSpace.entries`).

- [ ] **Step 5: Run and see them pass**

Run: `./gradlew :modules:gratitude:test --tests '*EntryEmbeddingsTest*' --tests '*SearchTest*' :app:test --tests '*FlywayMigrationTest*'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add modules/gratitude app/src/test/kotlin/com/moyi/app/FlywayMigrationTest.kt
git commit -m "feat(gratitude): entry vectors, one table per model, gone whenever the words change"
```

---

### Task 6: the sweep that fills vectors, and the consumer that forgets them

**Files:**
- Create: `gratitude/api/EmbeddingSweep.kt`, `gratitude/service/EmbedPendingEntries.kt`, `gratitude/service/ForgetBondEmbeddings.kt`, `scheduling/service/EmbeddingJob.kt`
- Modify: `app/.../ArchitectureTest.kt` (`WORK_WITH_NO_CALLER` += `EmbedPendingEntries.kt`, `ForgetBondEmbeddings.kt`), `application-media-worker.yml` (`moyi.scheduling.embedding.cron: "-"`)
- Test: `gratitude/service/EmbedPendingEntriesTest.kt`, `ForgetBondEmbeddingsTest.kt`, `scheduling/service/EmbeddingJobTest.kt`

**Interfaces:**
- Consumes: `EmbeddingPort`, `EntryEmbeddings`, `BondAccess.bondsWithSemanticSearch`, `BondAccess.lockClosingViewOf`.
- Produces (`com.moyi.gratitude.api`, public):
```kotlin
interface EmbeddingSweep { fun embedPending(budget: Int): EmbeddingRun }
data class EmbeddingRun(val embedded: Int, val skipped: Int, val failed: Boolean)
```

- [ ] **Step 1: Write the test double, then the failing tests.** `modules/gratitude/src/test/kotlin/com/moyi/gratitude/infra/FakeEmbeddingPort.kt`, declared as a `@Bean` in `GratitudeTestApplication`: `var space: EmbeddingSpace? = OPENAI_TEXT_EMBEDDING_3_SMALL`, `val texts: MutableList<String>`, `var failNext = false`, `var delay = Duration.ZERO`, `var during: (() -> Unit)? = null` (run once inside `embed`, after recording), and `var table: Map<String, FloatArray>` (an exact text → vector map for Tasks 7–8). Otherwise it returns a deterministic unit vector seeded from the text's hash. `clear()` resets it, called in each test class's `@AfterEach`. `SemanticSearchProperties` is enabled on a gratitude `@Configuration`, and gratitude's test `application.yml` sets `moyi.gratitude.semantic-search.max-distance.OPENAI_TEXT_EMBEDDING_3_SMALL: 0.35` (a test value, and the fake's table is built around it).

```kotlin
@Test fun `embeds the pending entries of bonds with the setting on, up to the budget`() {
    val on = pair(); val off = pair(); turnOff(off)
    repeat(3) { submit(on, "words $it") }; submit(off, "never sent")
    sweep.embedPending(budget = 2) shouldBe EmbeddingRun(embedded = 2, skipped = 0, failed = false)
    sweep.embedPending(budget = 10).embedded shouldBe 1
    fake.texts shouldNotContain "never sent"
    embeddings.countFor(off.uuid) shouldBe 0
}

@Test fun `no provider means nothing is sent and nothing fails`() {
    fake.space = null; submit(pair(), "words")
    sweep.embedPending(10) shouldBe EmbeddingRun(0, 0, false)
}

@Test fun `a provider failure stops the run and leaves the entries pending`() {
    fake.failNext = true; submit(pair(), "words")
    sweep.embedPending(10).failed shouldBe true
    sweep.embedPending(10).embedded shouldBe 1
}

@Test fun `turning off between the provider call and the write leaves no vector`() {   // Review focus 3
    val bond = pair(); submit(bond, "words")
    fake.during = { turnOff(bond) }
    sweep.embedPending(10).skipped shouldBe 1
    embeddings.countFor(bond.uuid) shouldBe 0
}

@Test fun `an edit between the provider call and the write leaves no stale vector`() {
    val bond = pair(); val id = submit(bond, "an old plum")
    fake.during = { patch(ada, id, "a new pear") }
    sweep.embedPending(10).skipped shouldBe 1
    sweep.embedPending(10).embedded shouldBe 1      // the new words, next run
}
```
```kotlin
// ForgetBondEmbeddingsTest
@Test fun `SemanticSearchDisabled deletes the bond's vectors in every table, and only that bond's`() {
    val a = pair(); val b = pair(); submit(a, "x"); submit(b, "y"); sweep.embedPending(10)
    turnOff(a); outbox.dispatchAll()
    embeddings.countFor(a.uuid) shouldBe 0
    embeddings.countFor(b.uuid) shouldBe 1
}

@Test fun `a second delivery of the same event is harmless`() { ...handle twice, no throw... }
```
```kotlin
// EmbeddingJobTest (scheduling): the job calls the sweep with budget 200 and counts the outcome.
```

- [ ] **Step 2: Run and watch them fail**

Run: `./gradlew :modules:gratitude:test --tests '*EmbedPendingEntriesTest*' --tests '*ForgetBondEmbeddingsTest*' :modules:scheduling:test --tests '*EmbeddingJobTest*'`
Expected: FAIL.

- [ ] **Step 3: Implement**

```kotlin
@Component
internal class EmbedPendingEntries(
    private val port: EmbeddingPort,
    private val embeddings: EntryEmbeddings,
    private val access: BondAccess,
    private val transactions: TransactionTemplate,
    private val clock: Clock,
    meters: MeterRegistry,
) : EmbeddingSweep {
    private val embedded = meters.counter("gratitude.ai.embedding.entries", "outcome", "embedded")
    private val skipped = meters.counter("gratitude.ai.embedding.entries", "outcome", "skipped")

    /** Not transactional: the provider is called with no transaction open (ADR-0053 decision 1). */
    override fun embedPending(budget: Int): EmbeddingRun {
        val space = port.space ?: return EmbeddingRun(0, 0, false)
        var done = 0; var skips = 0; var after: UUID? = null
        while (done + skips < budget) {
            val bonds = transactions.execute { access.bondsWithSemanticSearch(after, BOND_PAGE) }.orEmpty()
            if (bonds.isEmpty()) break
            for (bondId in bonds) {
                val pending = transactions.execute { embeddings.missing(space, bondId, minOf(BATCH, budget - done - skips)) }.orEmpty()
                if (pending.isEmpty()) continue
                val vectors = try { port.embed(pending.map { it.text }) }
                    catch (e: EmbeddingUnavailableException) { return EmbeddingRun(done, skips, failed = true) }
                val now = clock.instant()
                transactions.execute {
                    val view = access.lockClosingViewOf(bondId)          // the lock "off" also takes
                    pending.zip(vectors).forEach { (p, v) ->
                        val wrote = view?.semanticSearchEnabled == true &&
                            embeddings.upsert(space, p.entryId, bondId, v, EntryEmbeddings.hashOf(p.text), now)
                        if (wrote) done++ else skips++
                    }
                }
                if (done + skips >= budget) break
            }
            after = bonds.last()
        }
        embedded.increment(done.toDouble()); skipped.increment(skips.toDouble())
        return EmbeddingRun(done, skips, false)
    }

    private companion object { const val BOND_PAGE = 100; const val BATCH = 64 }
}
```
(Check that `lockClosingViewOf` takes the same `bonds` row lock as `UpdateBond`'s `lockBond`, at `BondRepositories.kt:93-94`. If it locks something else, add `BondAccess.lockSemanticSearchOf(bondId): Boolean?` that does `SELECT semantic_search_enabled FROM bonds WHERE id = ? FOR UPDATE`, and use that.)

```kotlin
@Component
internal class ForgetBondEmbeddings(private val embeddings: EntryEmbeddings) : EventConsumer {
    override val id = "gratitude.semantic-search-off"
    override val eventTypes = setOf(EVENT)
    override val startFrom = StartFrom.NOW

    @Transactional(propagation = Propagation.MANDATORY)
    override fun handle(event: ReceivedEvent) {
        embeddings.deleteForBond(checkNotNull(event.references[BOND_ID]) { "A $EVENT event carries no '$BOND_ID'." })
    }

    private companion object { const val EVENT = "SemanticSearchDisabled"; const val BOND_ID = "bondId" }
}
```
`EmbeddingJob`: copy `OnThisDayJob` (`@Scheduled(cron = "\${moyi.scheduling.embedding.cron:0 * * * * *}")`, `@SchedulerLock(name = "embedding", lockAtMostFor = "PT5M", lockAtLeastFor = "PT20S")`, last-success gauge `gratitude.ai.embedding.last_success`). It calls `sweep.embedPending(200)`. The `moyi.ai.embedding.lag` gauge (age of the oldest pending entry) is **deferred**: say so in the ADR. The last-success gauge plus `gratitude.ai.embedding.requests{outcome=failed}` cover alerting in S1.

- [ ] **Step 4: Run and see them pass**

Run: same as Step 2, then `:app:test --tests '*ArchitectureTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/gratitude modules/scheduling app/src/test/kotlin/com/moyi/app/ArchitectureTest.kt app/src/main/resources/application-media-worker.yml
git commit -m "feat(gratitude): a sweep embeds pending entries outside any transaction; off forgets them"
```

---

### Task 7: the search box finds by meaning, in the same order

**Files:**
- Create: `gratitude/service/QueryEmbedder.kt`
- Modify: `infra/database/ArchiveDays.kt:187-201, 303-329` (optional semantic predicate), `service/GetDays.kt:234-255` (`semantic: SemanticMatch?` parameter), `web/SearchController.kt:40-54`
- Test: `gratitude/web/SemanticSearchTest.kt` (new); extend `SearchCandidatesTest`, `GratitudeCrossTenantTest` (no new route; add a case with semantic on)

**Interfaces:**
- Produces:
```kotlin
internal data class SemanticMatch(val space: EmbeddingSpace, val vector: FloatArray, val maxDistance: Double) {
    override fun toString() = "SemanticMatch($space, maxDistance=$maxDistance)"
}
internal class QueryEmbedder(port: EmbeddingPort, properties: SemanticSearchProperties, meters: MeterRegistry) {
    /** null whenever the semantic half must be dropped: setting off, no provider, timeout, error. Never throws. */
    fun matchFor(membership: BondMembership, query: String): SemanticMatch?
}
@ConfigurationProperties("moyi.gratitude.semantic-search")
internal data class SemanticSearchProperties(
    val queryTimeout: Duration = Duration.ofSeconds(2),
    val maxDistance: Map<EmbeddingSpace, Double> = mapOf(),   // set from the eval (Task 9); absent = semantic off for that space
)
```

- [ ] **Step 1: Write the failing tests** (the fake port maps texts to vectors by a test table, so "a calm light" sits near "the quiet lamp" and far from "bread")

```kotlin
@Test fun `a meaning match joins the keyword matches, newest first, the page shape unchanged`() {
    val bond = pair()
    bothWrite(bond, DAY_ONE, "the quiet lamp by the bed", "bread again")
    bothWrite(bond, DAY_ONE.plusDays(1), "a calm light all evening", "nothing")
    embedAll()
    found(ada, bond, "calm light") shouldBe listOf("2026-09-16", "2026-09-15")
    rig.page(ada, bond, mapOf("q" to "calm light"), route = "search").fieldNames().asSequence().toSet() shouldBe setOf("items", "nextCursor")
}

@Test fun `the partner's unrevealed entry is never a meaning match`() {   // Review focus 1, T-01
    val bond = pair(); clock.set(at(DAY_ONE))
    rig.bonds.submit(bea, bond, "the quiet lamp by the bed"); embedAll()
    found(ada, bond, "a calm light") shouldBe emptyList()
}

@Test fun `with the setting off, the same query is keywords only`() {
    ...; turnOff(bond)
    found(ada, bond, "calm light") shouldBe listOf("2026-09-16")
    meters.counter("gratitude.search.semantic", "outcome", "disabled").count() shouldBe 1.0
}

@Test fun `a provider that hangs costs at most the timeout and the page is keywords`() {   // Review focus 4
    fake.delay = Duration.ofSeconds(5)
    val started = System.nanoTime()
    found(ada, bond, "calm light") shouldBe listOf("2026-09-16")
    Duration.ofNanos(System.nanoTime() - started) shouldBeLessThan Duration.ofSeconds(4)
    meters.counter("gratitude.search.semantic", "outcome", "degraded").count() shouldBe 1.0
}

@Test fun `a withdrawn author's entry is not a meaning match`() { ...SearchWithdrawalTest's setup + embedAll... }

@Test fun `paging by cursor walks the same days with semantic matching on`() {
    ...25 days alternating keyword and meaning matches; rig.walk(ada, bond, mapOf("q" to "calm light", "limit" to "10")) == every matching date newest first, no repeats...
}
```
`SearchCandidatesTest`: an `EXPLAIN (FORMAT JSON)` of `matchingQuery` with a `SemanticMatch` shows `entry_embeddings_openai_1536_bond_idx` or the PK lookup, and no `Seq Scan` on the embedding table, over a fixture of 3 bonds × 500 entries. Run `ANALYZE` first, as `SearchPlanMeasurement` does.

- [ ] **Step 2: Run and watch them fail**

Run: `./gradlew :modules:gratitude:test --tests '*SemanticSearchTest*' --tests '*SearchCandidatesTest*'`
Expected: FAIL.

- [ ] **Step 3: Implement**

`ArchiveDays.matchingQuery`: replace the single match line (`:317`) with
```kotlin
when (semantic) {
    null -> "AND e.search_vector @@ plainto_tsquery('simple', public.immutable_unaccent(?))"
    else -> "AND (e.search_vector @@ plainto_tsquery('simple', public.immutable_unaccent(?))" +
        " OR EXISTS (SELECT 1 FROM ${embeddings.tableOf(semantic.space)} x" +
        " WHERE x.entry_id = e.id AND x.bond_id = e.bond_id AND x.embedding <=> ?::vector <= ?))"
},
```
Its arguments go after `terms.value`: `vectorLiteral(semantic.vector), semantic.maxDistance`. Every other line (`SEEN_ROW`, `LIVE_ROW`, the withdrawn filter, `ORDER BY d.date DESC`, `LIMIT`) is unchanged, so the gate and the keyset are untouched by construction.

`QueryEmbedder.matchFor`: return null (counting `disabled`) if `!membership.semanticSearchEnabled`, `port.space == null`, or no `maxDistance` is configured for the space. Otherwise run `port.embed(listOf(query))` on a bounded executor (`Executors.newVirtualThreadPerTaskExecutor()`, `future.get(queryTimeout)`). On timeout or `EmbeddingUnavailableException`, return null and count `degraded`. On success, count `used`. Log nothing of the query.

`SearchController`: `val semantic = queryEmbedder.matchFor(membership, query.terms.value)` after `joining.beforeRead`, then pass it to `getDays.search(..., semantic)`.

- [ ] **Step 4: Run and see them pass, then all search tests**

Run: `./gradlew :modules:gratitude:test --tests '*Search*'`
Expected: PASS. `SearchTest` and `SearchWithdrawalTest` are unchanged and green.

- [ ] **Step 5: Commit**

```bash
git add modules/gratitude
git commit -m "feat(gratitude): search adds meaning matches by threshold, newest first as before"
```

---

### Task 8: "Remember when", ranked by fused relevance

**Files:**
- Create: `web/RememberController.kt`, `web/RememberResponse.kt`, `web/RememberQuery.kt`
- Modify: `infra/database/ArchiveDays.kt` (`rankedByWords`, `rankedByMeaning`), `service/GetDays.kt` (`remember`), `common/security/.../RateLimitBucket.kt` (`REMEMBER_USER("remember:user", Subject.USER, 30, Duration.ofHours(1))`), `GratitudeCrossTenantTest.kt:118` (fixture)
- Test: `gratitude/web/RememberTest.kt`, `gratitude/service/ReciprocalRankFusionTest.kt`

**Interfaces:**
- Produces: `GET /api/v1/bonds/{bondId}/remember?q=&limit=` → `200 {"items":[DayResponse…]}`. `limit` 1–20, default 10. `q` follows `SearchTerms` rules (422 `VALID_SEARCH` / `REQUIRED`).
- `internal fun fuse(rankings: List<List<UUID>>, k: Int = 60): List<UUID>` (pure, in `service/ReciprocalRankFusion.kt`).
- `GetDays.remember(membership: BondMembership, terms: SearchTerms, semantic: SemanticMatch?, limit: Int): List<DayView>`.

- [ ] **Step 1: Write the failing tests**

```kotlin
internal class ReciprocalRankFusionTest {
    @Test fun `an item high in both lists beats one top in a single list`() {
        val (x, y, z) = List(3) { UUID.randomUUID() }
        // x = 1/61, y = 1/62 + 1/61, z = 1/62
        fuse(listOf(listOf(x, y), listOf(y, z))) shouldBe listOf(y, x, z)
    }
    @Test fun `equal scores keep the order of the first list`() {
        val (a, b, c) = List(3) { UUID.randomUUID() }
        // a = 1/61 + 1/62 = b; c = 1/63
        fuse(listOf(listOf(a, b, c), listOf(b, a))) shouldBe listOf(a, b, c)
    }
    @Test fun `one list alone keeps its order`() { val ids = List(5) { UUID.randomUUID() }; fuse(listOf(ids)) shouldBe ids }
    @Test fun `empty lists fuse to nothing`() { fuse(listOf(emptyList(), emptyList())) shouldBe emptyList() }
}
```
Tie rule, implemented explicitly: equal scores are ordered by first appearance in list 0, then in list 1.

```kotlin
@Test fun `remember ranks by relevance, not by date`() {
    bothWrite(bond, DAY_ONE, "you made soup when I was ill", "x")                 // the best match
    bothWrite(bond, DAY_ONE.plusDays(5), "soup", "y")                              // a keyword hit, weaker by meaning
    embedAll()
    remembered(ada, bond, "when you looked after me while sick").first() shouldBe "2026-09-15"
}
@Test fun `remember never surfaces an unrevealed partner entry or a withdrawn author's`() { ... }   // Review focus 1 and 5
@Test fun `with semantic off, remember answers from words alone, still 200`() { ... }
@Test fun `limit bounds the answer and 21 is a 422`() { ... }
@Test fun `a stranger gets the bond's 404 whatever q is`() { ... }
@Test fun `the 31st call in an hour is a 429`() { ... }   // rate limiting is on only in its own test config; follow the existing rate-limit test's setup
```

- [ ] **Step 2: Run and watch them fail**

Run: `./gradlew :modules:gratitude:test --tests '*RememberTest*' --tests '*ReciprocalRankFusionTest*'`
Expected: FAIL.

- [ ] **Step 3: Implement**

`rankedByWords(bondId, memberId, terms, withdrawn, limit = 50): List<UUID>`: the same `FROM … WHERE` as `matchingQuery` without the date keyset, `ORDER BY ts_rank(e.search_vector, plainto_tsquery('simple', public.immutable_unaccent(?))) DESC, e.id LIMIT ?`, selecting `e.id`.
`rankedByMeaning(bondId, memberId, semantic, withdrawn, limit = 50)`: `JOIN <table> x ON x.entry_id = e.id AND x.bond_id = e.bond_id`, the same `SEEN_ROW`, `LIVE_ROW` and withdrawn filters, `ORDER BY x.embedding <=> ?::vector, e.id LIMIT ?`. No `maxDistance` here, because ranking needs no cut-off.

`GetDays.remember`: `fuse(listOfNotNull(words, meaning))`, then map entry → day id (one query, `SELECT id, bond_day_id FROM entries WHERE id = ANY(?)`), keep each day's first position, take `limit * 2` candidate days, build `ArchiveDay`s (with `matched` = the fused entry ids of that day), and pass them through the **same** private `read()` → `matchedInFull` → marks/reactions/media path as `fill`. Keep fused order and cut to `limit`. Extract the per-window body of `fill` into a private `show(membership, candidates): List<DayView>` shared by both, and do not duplicate it.

`RememberController`: guard → `RememberQuery.parse(q, limit)` (reuse `SearchTerms.of` and the same `FieldViolation`s; limit 1..20) → `joining.beforeRead` → `queryEmbedder.matchFor` → `getDays.remember` → `RememberResponse.from(days, partnerDisplayName)`, with `@RateLimited(RateLimitBucket.REMEMBER_USER)`. Same KDoc posture as `SearchController`: not conditional, default `Cache-Control`.

- [ ] **Step 4: Run and see them pass**

Run: `./gradlew :modules:gratitude:test :common:security:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/gratitude common/security
git commit -m "feat(gratitude): GET /remember ranks a bond's entries by fused relevance, gated as search is"
```

---

### Task 9: the eval: a golden set, stored vectors and a CI gate

**Prerequisite (Daniel):** an OpenAI API key and a Voyage API key, in his own accounts, for a one-off local run of the tool. Never committed. The CI gate needs the vectors that run produces. **Tasks 1–8 do not wait on this; Task 9's Step 4 does.**

**Files:**
- Create: `modules/gratitude/src/test/resources/eval/golden.json`, `vectors-openai-1536.json`, `vectors-voyage-1024.json` (generated), `modules/gratitude/src/test/kotlin/com/moyi/gratitude/eval/RetrievalEvalTest.kt`, `tools/embedding-eval/{build.gradle.kts, src/main/kotlin/com/moyi/tools/embeddingeval/Main.kt}`
- Modify: `settings.gradle.kts` (include `tools:embedding-eval`; app does not depend on it, like `breach-corpus`)

**Interfaces:** `golden.json` holds:
```json
{ "bonds": [{ "key": "b1", "entries": [{ "key": "e1", "author": "ada", "date": "2026-01-03", "text": "…" }] }],
  "queries": [{ "bond": "b1", "q": "…", "relevant": ["e1", "e7"] }] }
```
`vectors-<space>.json` maps each `{ "<entry or query key>": [floats…] }`, plus `"model"` and `"generatedAt"`.

- [ ] **Step 1: Write the golden set by hand.** Make 150 synthetic entries across 3 bonds and 60 queries. Rules: every query is a paraphrase sharing **no content word** with any relevant entry. 20 queries are deliberately ambiguous, with 2–4 relevant entries. 10 queries have **no** relevant entry (precision probes). Vary register (short, long, plain, emoji). No real person's text. Commit it on its own.

- [ ] **Step 2: Write the tool.** `./gradlew :tools:embedding-eval:run --args="generate"` reads `golden.json`, embeds every entry and query with each provider whose key is in the env (`MOYI_AI_EMBEDDING_OPENAI_API_KEY`, `MOYI_AI_EMBEDDING_VOYAGE_API_KEY`) through `modules:ai`'s adapters, writes `vectors-<space>.json`, and prints per space: recall@10, MRR, the smallest `maxDistance` giving precision ≥ 0.9 on the search-box task, p95 latency and tokens. The metric functions live in the tool **and** are copied into `RetrievalEvalTest` (no shared main-code module for test math).

- [ ] **Step 3: Write `RetrievalEvalTest`** (`PostgresIntegrationTest`): insert the golden bonds and entries as revealed (through `ArchiveRig.insertDay/insertEntry`), insert the stored vectors with `EntryEmbeddings.upsert`, and drive the **real** `GetDays.search` and `GetDays.remember` with a `FixedVectorsEmbeddingPort` that answers queries from the stored file. Assert, for each space with a vectors file:
```kotlin
withClue("remember recall@10 for $space") { recallAt10 shouldBeGreaterThanOrEqual floor.recall }
withClue("remember MRR for $space") { mrr shouldBeGreaterThanOrEqual floor.mrr }
withClue("search precision at the configured max-distance for $space") { precision shouldBeGreaterThanOrEqual 0.9 }
```
`floor` lives in `eval/floors.json`, written from the first measured run **minus 0.02**, so ordinary noise does not fail the build but a real regression does. If a vectors file is missing, the test **fails** with "run tools/embedding-eval generate"; it does not skip.

- [ ] **Step 4: Run the tool with Daniel's keys** (Daniel runs the command himself, `! ./gradlew :tools:embedding-eval:run --args="generate"`), commit the vectors and floors, write the measured numbers into ADR-0053, set `moyi.gratitude.semantic-search.max-distance.*` in `application.yml` from the printout, and set the live provider from the comparison.

- [ ] **Step 5: Run the gate**

Run: `./gradlew :modules:gratitude:test --tests '*RetrievalEvalTest*'`
Expected: PASS, printing the measured recall, MRR and precision.

- [ ] **Step 6: Commit**

```bash
git add modules/gratitude/src/test tools/embedding-eval settings.gradle.kts app/src/main/resources/application.yml
git commit -m "test(gratitude): a retrieval eval on a golden set gates recall, MRR and precision in CI"
```

---

### Task 10: contract, privacy scan, docs and tools

**Files:**
- Create: `contracts/src/main/kotlin/com/moyi/contracts/RememberDocumentation.kt`, `SemanticSearchDocumentation.kt` (call from the customizer in `OpenApiConfiguration.kt:157-188`), `adr/0053-semantic-search-and-the-ai-module.md`, `tools/bruno/35 Remember when.bru`, `36 Turn semantic search off.bru`, `37 Turn semantic search on.bru`
- Modify: `contracts/openapi.json` (regenerated), `app/src/test/kotlin/com/moyi/app/OpenApiContractTest.kt`, `app/src/test/kotlin/com/moyi/app/SecretsNeverLoggedTest.kt`, `scripts/smoke.sh`, `docs/state-map/data/*` (+ `scripts/state-map-generate`, `scripts/state-map-check --strict`), `app/src/test/kotlin/com/moyi/app/ProductionStartsWithoutMediaSecretsTest.kt` (or a sibling `ProductionStartsWithoutAiSecretsTest.kt`)

- [ ] **Step 1: `SecretsNeverLoggedTest`.** Add the markers `REMEMBERED = "zqkremembersoup"` and `EMBEDDED = "Ada~embedded~this"`. Submit an entry containing `EMBEDDED`, run the sweep with the fake port, call `/remember?q=REMEMBERED`, and `scan.watch` both. Run it and watch it pass. Then **break it on purpose** (log the query at DEBUG in `QueryEmbedder`), see red, and restore.
- [ ] **Step 2: Contract.** Run `./gradlew :app:test --tests '*OpenApiContractTest*'`, `cp app/build/openapi/openapi.json contracts/openapi.json`, and read the diff. Expected: new paths `/remember` and `/semantic-search`, and new **optional** fields on `BondResponse` and `PatchBondRequest`. **The search operation is unchanged.** The new 422 code `TURNED_ON_BY_BOTH` is a field-violation code, not an `ErrorCode`, so it is not breaking. If oasdiff flags anything as breaking, stop and report; do not label it away.
- [ ] **Step 3: Production starts without the AI secret.** Add a test parsing `config/deploy.yml` that asserts `MOYI_AI_EMBEDDING_PROVIDER: none` is in `env.clear` while no `MOYI_AI_EMBEDDING_*_API_KEY` is in `env.secret`. Add that line to `deploy.yml` with the "to switch on" comment in the house style (`deploy.yml:218-222`).
- [ ] **Step 4: Smoke probes** (`scripts/smoke.sh`, near the search block at :2170): with the provider none, search is `200` and keyword-only; `/remember` is `200`; a stranger's `/remember` is 404; `PATCH` `{"semanticSearchEnabled":false}` is 200, and after the outbox pass `sql "SELECT count(*) FROM entry_embeddings_openai_1536 WHERE bond_id = …"` is `0`; `PATCH` true is 422 on the field. Run `MOYI_DB=moyi_s1_smoke scripts/smoke.sh` and update the header's "Last run" sentence with the measured counts.
- [ ] **Step 5: Bruno, state map, ADR-0053.** The ADR records: the module split, one table per model, no approximate index (with the measured `EXPLAIN`), threshold vs RRF, consent and deletion, the sweep over consumers (decision 1), the measured eval numbers and provider choice (Task 9), and the deferred token-usage metric and lag gauge.
- [ ] **Step 6: Full verification**

Run: `./gradlew build --rerun-tasks` (JDK 25). Count tests from the JUnit XML and report the number.
Expected: green.

- [ ] **Step 7: Commit**

```bash
git add contracts app/src/test adr/0053-semantic-search-and-the-ai-module.md tools/bruno scripts/smoke.sh docs config/deploy.yml
git commit -m "docs: ADR-0053, the contract, smoke probes and Bruno requests for semantic search"
```

---

### Task 11 (with Daniel, after merge): production

Not done by an agent. Daniel runs each step, with Claude guiding (the deploy-together rule):
1. Rehearse on the restore drill: `ops/backup/restore-drill.sh` against the pgvector image, and confirm V37/V38 apply on a copy of production data.
2. Switch the `postgres` accessory image in `config/deploy.yml:302-303` to `pgvector/pgvector:0.8.7-pg18` (same data volume), then `kamal accessory reboot postgres` in a quiet window.
3. Deploy the app (V37/V38 run).
4. Add the chosen provider's key to Bitwarden `moyi-prod`, the `.kamal/secrets` fetch list and `env.secret`. Remove `MOYI_AI_EMBEDDING_PROVIDER: none`. Deploy. Watch `gratitude.ai.embedding.requests` and the last-success gauge.
5. Privacy policy line and sub-processor entry published before step 4.

## Verify before "ready"

1. `./gradlew build --rerun-tasks` green; report the measured test count.
2. Mutations, one at a time (break it, see red, restore it): remove `AND $SEEN_ROW` from `rankedByMeaning` (Review focus 1 test must fail); remove the hash condition from `upsert` (the edit-race test must fail); remove the `deleteForEntry` call in `EntryStore.update` (the edit test must fail); skip the `semanticSearchEnabled` re-check in the sweep's write (the off-race test must fail).
3. Smoke run with measured counts in the header.
4. The PR description lists what was run and what was only read.
