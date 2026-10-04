# Slice C1 — the day and the first entry

> **Superseded.** This is the first C1 plan. It was replaced by the rework plan, `2026-09-30-gratitude-day-c1-rework.md`, and what was actually built is recorded in ADR-0031. Kept for the record; do not implement from it.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A verified member of an open bond can write one entry a day, the server decides which Bond-day it belongs to, and `GET /today` shows them their own words and nothing of their partner's.

**Architecture:** A new `modules/gratitude` owning `bond_days` and `entries`, reaching `bond` through a new `bond.api.BondAccess` port whose `BondMembership` has an **internal constructor** — so the compiler carries ADR-0026's "only the guard can construct this" across the module boundary. The Bond-day row is created lazily under `INSERT … ON CONFLICT DO NOTHING` and is the row every later transition will lock. `Idempotency-Key` lands in `common:web` because `POST /entries` is the first endpoint doc 06 §1 requires it on.

**Tech Stack:** Kotlin 2.2, Spring Boot 4.1.1, Postgres 18, Flyway, JPA/Hibernate 7, Testcontainers, JUnit 5, Kotest matchers, Konsist.

**Spec:** `docs/superpowers/specs/2026-09-28-the-daily-loop-design.md` — read §2, §3, §5 and §6.1–§6.2 before Task 1. This plan implements the C1 row of §10 and nothing beyond it.

## Global Constraints

- **Every rule here is doc 04's or doc 06's, not this plan's.** Where the two disagree, the spec §12 says which is stale and why.
- **BR-2:** at most one entry per member per Bond-day, **enforced by a unique index, not by application logic alone**.
- **BR-3:** the Bond-day is `instant.atZone(bondDay.anchorTimezone).toLocalDate()`, computed server-side. The client never names the date.
- **BR-3a:** `intendedAt` is used unless it is >5 minutes in the future, >36 hours in the past, or lands on a day already `REVEALED`, `SOLO` **or `EMPTY`**.
- **BR-8:** a locked entry serialises to `{authorMemberId, status: LOCKED}` and **nothing else** — no length, no timestamp, no media flag.
- **FR-041:** 1–500 **grapheme clusters** counted with `BreakIterator`, plus `octet_length(text) <= 8192` at the edge *and* in the database.
- **Doc 06 §2:** a non-member, an unknown id and a value that is not a UUID are **one byte-identical 404**.
- **Doc 18 §5/§9:** no entry text, email or token reaches a log. Ids only.
- **Migrations are forward-only** and versions are one global sequence. `V10` is the last one Phase 2 uses; this slice takes **V11 and V12**.
- **Every new `ErrorCode` value makes the PR a breaking change** (ADR-0024's 2026-09-24 amendment) — label it `breaking-api-change`.
- **Work in `.worktrees/feat-gratitude-day`**, branch `feat/gratitude-day`, based on `main` at `53ac58e`.

## What C1 deliberately leaves broken

**A day with two entries stays `PARTIAL` and both entries stay locked.** The reveal is C2: it needs the row lock, the concurrent-submission test, `PENDING_REVEAL` and the outbox, and bundling them here would make one task the whole slice. This is the same shape as B1, which shipped bonds nobody could join until B2 — `main` carries an incomplete loop between slices, on purpose, and there is no deployed server for it to matter to.

Do not "fix" it in C1. Task 9's ADR records it as a decision so C2's author does not read it as an oversight.

## File structure

| File | Responsibility |
|---|---|
| `settings.gradle.kts` | add nothing — `modules:gratitude` is already included |
| `modules/gratitude/build.gradle.kts` | the module's dependencies |
| `modules/bond/.../api/BondAccess.kt` | the port: `BondAccess`, `BondMembership` (internal constructor) |
| `modules/bond/.../service/BondAccessAdapter.kt` | the port's only implementation, delegating to `BondAccessGuard` |
| `common/web/.../idempotency/IdempotencyKey.kt` | the header's value type and its validation |
| `common/web/.../idempotency/IdempotencyRecord.kt` | the JPA entity + repository for `idempotency_keys` |
| `common/web/.../idempotency/IdempotencyInterceptor.kt` | reserve-before / complete-after, and the replay |
| `common/web/src/main/resources/db/migration/V11__common_idempotency_keys.sql` | the table |
| `modules/gratitude/.../domain/Ids.kt` | `BondDayId`, `EntryId`, and the ids this module borrows |
| `modules/gratitude/.../domain/EntryText.kt` | FR-041's three limits, counted once |
| `modules/gratitude/.../domain/BondDay.kt` | the aggregate, its statuses and its transitions |
| `modules/gratitude/.../domain/Entry.kt` | the entry, and BR-1's `canBeReadBy` |
| `modules/gratitude/.../domain/DayAssignment.kt` | BR-3 and BR-3a, as a pure function |
| `modules/gratitude/.../infra/database/*` | entities, mappers, `BondDayStore`, `EntryStore` |
| `modules/gratitude/src/main/resources/db/migration/V12__gratitude_bond_days_and_entries.sql` | both tables |
| `modules/gratitude/.../service/SubmitEntry.kt` | the write path |
| `modules/gratitude/.../service/GetToday.kt` | the read path |
| `modules/gratitude/.../service/GratitudeErrors.kt` | this module's exceptions |
| `modules/gratitude/.../web/*` | controller, request and the two response types |

---

### Task 1: The module, and the port it reaches `bond` through

**Files:**
- Create: `modules/gratitude/build.gradle.kts`
- Create: `modules/bond/src/main/kotlin/com/moyi/bond/api/BondAccess.kt`
- Create: `modules/bond/src/main/kotlin/com/moyi/bond/service/BondAccessAdapter.kt`
- Test: `modules/bond/src/test/kotlin/com/moyi/bond/api/BondAccessTest.kt`

**Interfaces:**
- Consumes: `BondAccessGuard.membershipOf(caller: UserId, bondId: BondId): Membership` and `BondStore` — both `internal` to `bond`.
- Produces: `bond.api.BondAccess.membershipOf(userId: UUID, bondId: UUID): BondMembership`, and `BondMembership` with `bondId`, `memberId`, `userId`, `anchorTimezone: String`, `revealTimeLocal: LocalTime?`, `strictMode: Boolean`, `isOpen: Boolean`, `hasLeft: Boolean`. Every later task in this slice takes a `BondMembership` rather than a bond id.

- [ ] **Step 1: Write the failing test**

```kotlin
// modules/bond/src/test/kotlin/com/moyi/bond/api/BondAccessTest.kt
@SpringBootTest(classes = [BondTestApplication::class])
internal class BondAccessTest(
    @Autowired private val access: BondAccess,
    @Autowired private val create: CreateBond,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
    }

    @Test
    fun `a member gets the four facts gratitude needs, and nobody else gets anything`() {
        val ada = users.verified("Ada")
        val bond = create.create(draft(ada))

        val membership = access.membershipOf(ada, bond.id.value)

        membership.userId shouldBe ada
        membership.anchorTimezone shouldBe "Africa/Lagos"
        membership.revealTimeLocal.shouldBeNull()
        membership.strictMode shouldBe false
        membership.isOpen shouldBe true
        membership.hasLeft shouldBe false

        // Doc 06 §2 and T-02: one answer for a stranger and for an id that
        // names nobody, and it is the same one the bond routes give.
        shouldThrow<NotFoundException> { access.membershipOf(users.verified("Eve"), bond.id.value) }
        shouldThrow<NotFoundException> { access.membershipOf(ada, UUID.randomUUID()) }
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew :modules:bond:test --tests "com.moyi.bond.api.BondAccessTest"`
Expected: FAIL — `Unresolved reference 'BondAccess'`.

- [ ] **Step 3: Write the port**

```kotlin
// modules/bond/src/main/kotlin/com/moyi/bond/api/BondAccess.kt
package com.moyi.bond.api

import java.time.LocalTime
import java.util.UUID

/**
 * What another module may know about a caller's place in a bond (ADR-0026's
 * pattern, second use). `identity.api.UserDirectory` answers "who is this
 * person"; this answers "may they write here, and by whose calendar".
 *
 * **[BondMembership]'s constructor is `internal`, and that is the mechanism.**
 * ADR-0026 made `bond.service.Membership` a value only `BondAccessGuard` can
 * construct, held by two Konsist rules. Kotlin's `internal` is scoped to the
 * Gradle module, so a caller outside `bond` can hold one of these, read it and
 * pass it down — and cannot forge one. The guarantee crosses the module
 * boundary as a compiler error rather than as a rule somebody has to write and
 * somebody else has to not forget. `UserDirectory` needed nothing like this: a
 * display name is not an authorisation decision.
 */
interface BondAccess {
    /**
     * @throws com.moyi.common.web.NotFoundException the caller holds no
     * membership row in [bondId], or there is no such bond — one answer for
     * both, because a 403 would confirm the bond is real (doc 06 §2, T-02).
     */
    fun membershipOf(userId: UUID, bondId: UUID): BondMembership
}

/**
 * A caller's place in a bond, and the four facts about the bond that a write
 * outside this module needs: whose calendar decides the day (doc 04 §6), when
 * a day reveals (FR-062), whether freezes are off (FR-073), and whether the
 * bond takes writes at all (BR-9).
 *
 * [hasLeft] is carried rather than withheld, for the reason `Membership` gives:
 * `states.md` §9 keeps the archive readable after a bond ends. **A caller must
 * check it.** Do not assume `isOpen` covers it — that assumption is exactly
 * what the second review of PR #41 found in `RequestDeletion.cancel`.
 */
class BondMembership internal constructor(
    val bondId: UUID,
    val memberId: UUID,
    val userId: UUID,
    val anchorTimezone: String,
    val revealTimeLocal: LocalTime?,
    val strictMode: Boolean,
    val isOpen: Boolean,
    val hasLeft: Boolean,
) {
    /** Ids only — a bond's name is the couple's words (doc 18 §9). */
    override fun toString(): String = "BondMembership(bondId=$bondId, memberId=$memberId)"
}
```

```kotlin
// modules/bond/src/main/kotlin/com/moyi/bond/service/BondAccessAdapter.kt
package com.moyi.bond.service

import com.moyi.bond.api.BondAccess
import com.moyi.bond.api.BondMembership
import com.moyi.bond.domain.BondId
import com.moyi.bond.domain.UserId
import com.moyi.bond.infra.database.BondStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * The port's only implementation: the guard, plus the four bond facts, in one
 * read. `internal`, so the boundary is the interface and not this class.
 *
 * It calls [BondAccessGuard] rather than reimplementing the check, so there is
 * one definition of "is this caller a member" and one 404. The guard's own
 * store read is scoped to the caller, so this is two reads of the same row and
 * not a fetch-then-check anybody could reorder.
 */
@Service
internal class BondAccessAdapter(
    private val guard: BondAccessGuard,
    private val bonds: BondStore,
) : BondAccess {
    @Transactional(readOnly = true)
    override fun membershipOf(userId: UUID, bondId: UUID): BondMembership {
        val caller = UserId(userId)
        val id = BondId(bondId)
        val membership = guard.membershipOf(caller, id)
        val bond = bonds.findByMember(id, caller) ?: throw BondNotFoundException()
        return BondMembership(
            bondId = membership.bondId.value,
            memberId = membership.memberId.value,
            userId = membership.userId.value,
            anchorTimezone = bond.anchorTimezone.id,
            revealTimeLocal = bond.revealTimeLocal,
            strictMode = bond.strictMode,
            isOpen = bond.isOpen,
            hasLeft = membership.left,
        )
    }
}
```

```kotlin
// modules/gratitude/build.gradle.kts
plugins {
    id("spring-boot-service-convention")
}

dependencies {
    // Ids (UUID v7 for Bond-days, v4 for entries — doc 06 §1) and the Clock.
    implementation(projects.common.core)
    // CurrentUser, and the per-user rate-limit bucket on the write.
    implementation(projects.common.security)
    // ApiException, ErrorCode, NotFoundException, and the Idempotency-Key
    // interceptor this slice adds there (doc 06 §1, §2).
    implementation(projects.common.web)
    // `com.moyi.bond.api` only. `implementation`, not `api`: nothing that
    // depends on gratitude learns about bonds by doing so (ADR-0026).
    implementation(projects.modules.bond)
    // A display name beside an entry, and nothing else.
    implementation(projects.modules.identity)

    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation(projects.common.testing)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testRuntimeOnly("org.springframework.boot:spring-boot-starter-flyway")
    testRuntimeOnly("org.flywaydb:flyway-database-postgresql")
    testRuntimeOnly("org.postgresql:postgresql")
}
```

- [ ] **Step 4: Run the test and the architecture rules**

Run: `./gradlew :modules:bond:test --tests "com.moyi.bond.api.BondAccessTest" :app:test --tests "com.moyi.app.ArchitectureTest"`
Expected: PASS. If `declarations outside a module's api package are internal, not public` fails, something in `bond.api` is missing a visibility modifier — `BondMembership` is public **by design**; its constructor is not.

- [ ] **Step 5: Commit**

```bash
git add modules/gratitude/build.gradle.kts modules/bond/src/main/kotlin/com/moyi/bond/api modules/bond/src/main/kotlin/com/moyi/bond/service/BondAccessAdapter.kt modules/bond/src/test/kotlin/com/moyi/bond/api
git commit -m "feat(bond): the access port gratitude asks its questions through"
```

---

### Task 2: `Idempotency-Key`, and the table behind it

**Files:**
- Create: `common/web/src/main/resources/db/migration/V11__common_idempotency_keys.sql`
- Create: `common/web/src/main/kotlin/com/moyi/common/web/idempotency/IdempotencyRecord.kt`
- Create: `common/web/src/main/kotlin/com/moyi/common/web/idempotency/IdempotencyInterceptor.kt`
- Modify: `common/web/src/main/kotlin/com/moyi/common/web/ErrorCode.kt` — add `IDEMPOTENCY_KEY_REUSED`
- Test: `common/web/src/test/kotlin/com/moyi/common/web/idempotency/IdempotencyInterceptorTest.kt`

**Interfaces:**
- Produces: `@Idempotent` on a controller method makes the header required; a replay carries `Idempotency-Replayed: true`. Task 7's `POST /entries` is the first user.

Doc 06 §1 in full, and it has specified this since before any code existed: keyed on **userId + endpoint + key**, storing a hash of the request body and the original response for **24h**; a replay returns the stored response with `Idempotency-Replayed: true`; the same key against a different endpoint or a different body is **`422 IDEMPOTENCY_KEY_REUSED`**. Postgres, not Redis (spec §5.4, §12.3).

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `a replay returns the first response and does not run the handler twice`() {
    val key = UUID.randomUUID().toString()
    val first = post(ada, key, """{"text":"thank you"}""")
    first.status shouldBe 201
    val second = post(ada, key, """{"text":"thank you"}""")

    second.status shouldBe 201
    second.contentAsString shouldBe first.contentAsString
    second.getHeader("Idempotency-Replayed") shouldBe "true"
    first.getHeader("Idempotency-Replayed").shouldBeNull()
    handlerRuns shouldBe 1
}

@Test
fun `the same key with a different body is 422, not the wrong stored response`() {
    val key = UUID.randomUUID().toString()
    post(ada, key, """{"text":"thank you"}""").status shouldBe 201

    val reused = post(ada, key, """{"text":"something else"}""")

    reused.status shouldBe 422
    reused.contentAsString shouldContain "\"code\":\"IDEMPOTENCY_KEY_REUSED\""
}

@Test
fun `a key is scoped to its user, so two people may pick the same one`() {
    val key = UUID.randomUUID().toString()
    post(ada, key, """{"text":"thank you"}""").status shouldBe 201
    post(bea, key, """{"text":"thank you"}""").status shouldBe 201
    handlerRuns shouldBe 2
}

@Test
fun `a missing key on a required endpoint is 422 naming the header`() {
    post(ada, key = null, body = """{"text":"thank you"}""").status shouldBe 422
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `./gradlew :common:web:test --tests "*IdempotencyInterceptorTest"`
Expected: FAIL — the interceptor does not exist.

- [ ] **Step 3: Write the migration**

```sql
-- V11__common_idempotency_keys.sql
-- Doc 06 §1's idempotency record, owned by `common:web` because doc 05 §2.1
-- puts the idempotency filter there and the table is that filter's state.
--
-- Postgres and not Redis: doc 06 §1 writes the key as `idem:{userId}:{endpoint}:{key}`,
-- which reads as a Redis key, but doc 05 §3 is explicit that idempotency,
-- revocation and ShedLock are Postgres-backed so that Redis stays removable in
-- one session. The Phase 3 design §12.3 settles it that way for both this and
-- ShedLock.
CREATE TABLE idempotency_keys (
    id               uuid        PRIMARY KEY,
    user_id          uuid        NOT NULL,
    endpoint         text        NOT NULL,
    idempotency_key  text        NOT NULL,
    -- SHA-256 of the raw request body. The body itself is never stored: an
    -- entry's text is the thing this system exists not to leak (doc 18 §9).
    request_hash     text        NOT NULL,
    -- Null until the handler returns. A row that exists with a null status is
    -- a request in flight, which is what makes the second one a 409 rather
    -- than a second execution.
    response_status  smallint,
    response_body    text,
    created_at       timestamptz NOT NULL,
    expires_at       timestamptz NOT NULL,

    CONSTRAINT idempotency_keys_unique UNIQUE (user_id, endpoint, idempotency_key),
    CONSTRAINT idempotency_keys_expiry_check CHECK (expires_at > created_at)
);

-- The reaper's index, partial so it stays small as the table grows.
CREATE INDEX idempotency_keys_expiry_idx ON idempotency_keys (expires_at);
```

- [ ] **Step 4: Write the interceptor**

`IdempotencyInterceptor` is a `HandlerInterceptor` registered for any handler method annotated `@Idempotent`:

```kotlin
/**
 * Doc 06 §1's `Idempotency-Key`, built when `POST /entries` made it required.
 *
 * **The record is written before the handler runs, not after.** A row inserted
 * on the way in, under the unique constraint, is what makes a concurrent retry
 * a `409` rather than a second execution — the reserve-then-complete shape.
 * Writing it afterwards would leave the window the header exists to close.
 *
 * The body is hashed, never stored: doc 18 §9, and an entry's text is the one
 * thing this system exists not to leak.
 */
```

Behaviour, in order:
1. No `@Idempotent` on the handler → do nothing.
2. Header absent → `422 VALIDATION_FAILED` naming `Idempotency-Key`.
3. Insert `(userId, endpoint, key, sha256(body))`. On a unique violation, read the existing row:
   - `request_hash` differs → `422 IDEMPOTENCY_KEY_REUSED`.
   - `response_status` null → `409` (in flight).
   - otherwise → write the stored status and body, set `Idempotency-Replayed: true`, and stop the chain.
4. After the handler, fill `response_status` and `response_body`.

The body must be readable twice, so register a `ContentCachingRequestWrapper` filter.

- [ ] **Step 5: Run the tests**

Run: `./gradlew :common:web:test --tests "*IdempotencyInterceptorTest"`
Expected: PASS, all four.

- [ ] **Step 6: Prove the reservation is load-bearing**

Delete the insert-before-handler and move it after. Expected: the replay test still passes and a concurrent test fails. Restore it. **Record what you saw in the PR body** — this is the project's mutation-testing rule (doc 18 §4).

- [ ] **Step 7: Commit**

```bash
git add common/web/src/main/kotlin/com/moyi/common/web/idempotency common/web/src/main/resources/db/migration/V11__common_idempotency_keys.sql common/web/src/main/kotlin/com/moyi/common/web/ErrorCode.kt common/web/src/test/kotlin/com/moyi/common/web/idempotency
git commit -m "feat(web): Idempotency-Key, reserved before the handler runs (doc 06 §1)"
```

---

### Task 3: `EntryText` — FR-041's three limits, counted once

**Files:**
- Create: `modules/gratitude/src/main/kotlin/com/moyi/gratitude/domain/EntryText.kt`
- Test: `modules/gratitude/src/test/kotlin/com/moyi/gratitude/domain/EntryTextTest.kt`

**Interfaces:**
- Produces: `EntryText.of(raw: String): EntryText` (throws `IllegalArgumentException`), `EntryText.value: String`, `EntryText.MAX_GRAPHEMES = 500`, `EntryText.MAX_OCTETS = 8192`.

- [ ] **Step 1: Write the failing test**

```kotlin
internal class EntryTextTest {
    @Test
    fun `five hundred graphemes are accepted however many code points they cost`() {
        // A ZWJ family emoji is one grapheme and ten code points. FR-041 names
        // this case: a 4,000-code-point backstop allows only 8 per grapheme, so
        // 500 legitimately typed characters could trip it.
        val family = "👨‍👩‍👧"
        EntryText.of(family.repeat(500)).value.shouldNotBeEmpty()
        shouldThrow<IllegalArgumentException> { EntryText.of(family.repeat(501)) }
    }

    @Test
    fun `an entry that is only whitespace is refused, whatever kind of space it is`() {
        // ADR-0029 §13: `U+00A0` is not blank to Java and is blank to Kotlin.
        for (blank in listOf("", " ", " ", " ", "\n\t")) {
            shouldThrow<IllegalArgumentException> { EntryText.of(blank) }
        }
    }

    @Test
    fun `the octet cap refuses what the grapheme count allows`() {
        // Graphemes are unbounded in byte length. Without this a single entry
        // could reach megabytes and flow into text_search, the stored tsvector,
        // the outbox payload and the 256 KB response cap.
        val heavy = "👨‍👩‍👧‍👦"
        val text = heavy.repeat(400)
        text.toByteArray(Charsets.UTF_8).size shouldBeGreaterThan EntryText.MAX_OCTETS
        shouldThrow<IllegalArgumentException> { EntryText.of(text) }
    }

    @Test
    fun `text is NFKC-normalised and trimmed, and otherwise left exactly as written`() {
        EntryText.of("  thank you  ").value shouldBe "thank you"
        // Doc 04 §7: stored raw and unmodified. No case folding, no collapsing
        // of internal whitespace, no stripping of emoji.
        EntryText.of("Thank  YOU 🙏").value shouldBe "Thank  YOU 🙏"
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew :modules:gratitude:test --tests "*EntryTextTest"`
Expected: FAIL — `Unresolved reference 'EntryText'`.

- [ ] **Step 3: Implement**

```kotlin
@JvmInline
value class EntryText private constructor(val value: String) {
    companion object {
        const val MAX_GRAPHEMES = 500
        const val MAX_OCTETS = 8192

        fun of(raw: String): EntryText {
            val text = Normalizer.normalize(raw, Normalizer.Form.NFKC).trim()
            require(text.isNotBlank()) { "an entry must say something" }
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_OCTETS) { "an entry is at most $MAX_OCTETS bytes" }
            require(graphemes(text) <= MAX_GRAPHEMES) { "an entry is at most $MAX_GRAPHEMES characters" }
            return EntryText(text)
        }

        private fun graphemes(text: String): Int {
            val it = BreakIterator.getCharacterInstance(Locale.ROOT)
            it.setText(text)
            var n = 0
            while (it.next() != BreakIterator.DONE) n++
            return n
        }
    }
}
```

Order matters: **octets before graphemes**, so a megabyte of text is refused without walking it with a `BreakIterator` first.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :modules:gratitude:test --tests "*EntryTextTest"`
Expected: PASS, all four.

- [ ] **Step 5: Commit**

```bash
git add modules/gratitude/src/main/kotlin/com/moyi/gratitude/domain/EntryText.kt modules/gratitude/src/test/kotlin/com/moyi/gratitude/domain/EntryTextTest.kt
git commit -m "feat(gratitude): an entry's text, and the three limits FR-041 asks for"
```

---

### Task 4: `DayAssignment` — BR-3 and BR-3a as a pure function

**Files:**
- Create: `modules/gratitude/src/main/kotlin/com/moyi/gratitude/domain/DayAssignment.kt`
- Test: `modules/gratitude/src/test/kotlin/com/moyi/gratitude/domain/DayAssignmentTest.kt`

**Interfaces:**
- Produces: `DayAssignment.dateFor(submittedAt: Instant, intendedAt: Instant?, zone: ZoneId, isClosed: (LocalDate) -> Boolean): LocalDate`.

The closed-day check is a **lambda**, not a store: the rule stays a pure function that a unit test can drive through every branch without a database, and the service supplies the lookup. Task 7 passes `{ date -> days.statusOf(bondId, date)?.isClosed == true }`.

- [ ] **Step 1: Write the failing test**

```kotlin
internal class DayAssignmentTest {
    private val lagos = ZoneId.of("Africa/Lagos")
    private val never: (LocalDate) -> Boolean = { false }

    @Test
    fun `the bond's zone decides the day, not the writer's`() {
        // Doc 04 §6's worked example, as a test rather than a comment. Tunde is
        // in Manchester and writes at 23:30 his time on the 14th; in Lagos it is
        // already 00:30 on the 15th, so the entry is the 15th's.
        val manchester2330 = ZonedDateTime.of(2026, 9, 14, 23, 30, 0, 0, ZoneId.of("Europe/London")).toInstant()

        DayAssignment.dateFor(manchester2330, null, lagos, never) shouldBe LocalDate.of(2026, 9, 15)
    }

    @Test
    fun `intendedAt is used when it is recent, honest and lands on an open day`() {
        val now = Instant.parse("2026-09-15T08:00:00Z")
        val yesterdayEvening = Instant.parse("2026-09-14T20:00:00Z")

        DayAssignment.dateFor(now, yesterdayEvening, lagos, never) shouldBe LocalDate.of(2026, 9, 14)
    }

    @Test
    fun `intendedAt more than five minutes ahead is ignored`() {
        val now = Instant.parse("2026-09-15T08:00:00Z")

        DayAssignment.dateFor(now, now.plusSeconds(299), lagos, never) shouldBe LocalDate.of(2026, 9, 15)
        DayAssignment.dateFor(now, now.plusSeconds(301), lagos, never) shouldBe LocalDate.of(2026, 9, 15)
        // The second one fell back rather than filing tomorrow's date:
        DayAssignment.dateFor(now, now.plus(Duration.ofDays(2)), lagos, never) shouldBe LocalDate.of(2026, 9, 15)
    }

    @Test
    fun `intendedAt more than thirty-six hours old is ignored`() {
        val now = Instant.parse("2026-09-15T08:00:00Z")

        DayAssignment.dateFor(now, now.minus(Duration.ofHours(35)), lagos, never) shouldBe LocalDate.of(2026, 9, 13)
        DayAssignment.dateFor(now, now.minus(Duration.ofHours(37)), lagos, never) shouldBe LocalDate.of(2026, 9, 15)
    }

    @Test
    fun `intendedAt landing on a closed day falls back rather than filing into it`() {
        // BR-3a's third clause, and EMPTY is the one the corpus's first draft
        // left out: BR-10 makes a closed day's status authoritative and BR-1
        // grants read access only on REVEALED or closed SOLO, so two entries
        // back-filled onto a closed EMPTY day would satisfy neither — unreadable
        // by either member, with no transition able to release them. Silently
        // swallowing words is the harm BR-3a exists to prevent.
        val now = Instant.parse("2026-09-15T08:00:00Z")
        val yesterday = Instant.parse("2026-09-14T20:00:00Z")
        val closed: (LocalDate) -> Boolean = { it == LocalDate.of(2026, 9, 14) }

        DayAssignment.dateFor(now, yesterday, lagos, closed) shouldBe LocalDate.of(2026, 9, 15)
    }

    @Test
    fun `a forty-five minute offset and a DST boundary both land where the calendar says`() {
        val kathmandu = ZoneId.of("Asia/Kathmandu") // UTC+05:45
        DayAssignment.dateFor(Instant.parse("2026-09-14T18:20:00Z"), null, kathmandu, never) shouldBe LocalDate.of(2026, 9, 15)

        // Europe/London springs forward 2026-03-29 at 01:00 — a 23-hour day.
        val london = ZoneId.of("Europe/London")
        DayAssignment.dateFor(Instant.parse("2026-03-29T01:30:00Z"), null, london, never) shouldBe LocalDate.of(2026, 3, 29)
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew :modules:gratitude:test --tests "*DayAssignmentTest"`
Expected: FAIL — `Unresolved reference 'DayAssignment'`.

- [ ] **Step 3: Implement**

```kotlin
object DayAssignment {
    private val CLOCK_SKEW: Duration = Duration.ofMinutes(5)
    private val OFFLINE_WINDOW: Duration = Duration.ofHours(36)

    fun dateFor(
        submittedAt: Instant,
        intendedAt: Instant?,
        zone: ZoneId,
        isClosed: (LocalDate) -> Boolean,
    ): LocalDate {
        val fallback = submittedAt.atZone(zone).toLocalDate()
        if (intendedAt == null) return fallback
        if (intendedAt.isAfter(submittedAt.plus(CLOCK_SKEW))) return fallback
        if (intendedAt.isBefore(submittedAt.minus(OFFLINE_WINDOW))) return fallback
        val intended = intendedAt.atZone(zone).toLocalDate()
        return if (isClosed(intended)) fallback else intended
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :modules:gratitude:test --tests "*DayAssignmentTest"`
Expected: PASS, all six.

- [ ] **Step 5: Commit**

```bash
git add modules/gratitude/src/main/kotlin/com/moyi/gratitude/domain/DayAssignment.kt modules/gratitude/src/test/kotlin/com/moyi/gratitude/domain/DayAssignmentTest.kt
git commit -m "feat(gratitude): whose day it is (BR-3), and what an offline draft may claim (BR-3a)"
```

---

### Task 5: The `BondDay` aggregate and the `Entry`

**Files:**
- Create: `modules/gratitude/src/main/kotlin/com/moyi/gratitude/domain/Ids.kt`
- Create: `modules/gratitude/src/main/kotlin/com/moyi/gratitude/domain/BondDay.kt`
- Create: `modules/gratitude/src/main/kotlin/com/moyi/gratitude/domain/Entry.kt`
- Test: `modules/gratitude/src/test/kotlin/com/moyi/gratitude/domain/BondDayTest.kt`

**Interfaces:**
- Produces: `BondDayStatus` (eight values), `BondDay.open(...)`, `BondDay.withEntry(): BondDay`, `BondDay.isClosed`, `BondDay.anchorTimezone: ZoneId`; `Entry.submit(...)`, `Entry.canBeReadBy(memberId: UUID, day: BondDay): Boolean`.

`BondDayStatus` carries all **eight** of doc 04 §3's values — `OPEN, PARTIAL, PENDING_REVEAL, REVEALED, SOLO, EMPTY, SUSPENDED, FROZEN` — even though C1 produces only `OPEN`, `PARTIAL` and `SUSPENDED`. Doc 07's DDL lists five and is stale (spec §12.1); the enum and the CHECK are written once, from doc 04.

`BondDay` copies the anchor zone onto the row (spec §3.1, §12.5): BR-6 requires a zone change never to recompute an existing day, and reading the zone live from the bond would move every historical day the first time the anchor moves.

- [ ] **Step 1: Write the failing test**

```kotlin
internal class BondDayTest {
    @Test
    fun `a day opens with no entries, in the zone it was opened in`() {
        val day = BondDay.open(bondId, LocalDate.of(2026, 9, 15), ZoneId.of("Africa/Lagos"), now)

        day.status shouldBe BondDayStatus.OPEN
        day.entryCount shouldBe 0
        day.anchorTimezone shouldBe ZoneId.of("Africa/Lagos")
        day.isClosed shouldBe false
    }

    @Test
    fun `the first entry makes it partial and the second does not reveal it yet`() {
        // C1 has no reveal — that is C2, with the row lock and the race test.
        // The day is left honest about its count and wrong about nothing else.
        val partial = BondDay.open(bondId, date, lagos, now).withEntry()
        partial.status shouldBe BondDayStatus.PARTIAL
        partial.entryCount shouldBe 1

        val both = partial.withEntry()
        both.entryCount shouldBe 2
        both.status shouldBe BondDayStatus.PARTIAL
    }

    @Test
    fun `a day opened while the bond waits for its second member is suspended`() {
        // Doc 04 §8.3a, as the Phase 3 design §12.4 resolves it: the row must
        // exist because the entry hangs off it, and J1 guarantees the creator
        // writes before the invitee joins. SUSPENDED is §8.1's own mechanism —
        // the close job leaves it alone and the streak walk skips it.
        val day = BondDay.openSuspended(bondId, date, lagos, now)

        day.status shouldBe BondDayStatus.SUSPENDED
        day.withEntry().status shouldBe BondDayStatus.SUSPENDED
    }

    @Test
    fun `an author always reads their own entry, and nobody reads a locked one`() {
        // BR-1's three clauses. In C1 only the first can be true.
        val day = BondDay.open(bondId, date, lagos, now).withEntry()
        val mine = Entry.submit(EntryId(UUID.randomUUID()), day.id, bondId, ada, text, now, now)

        mine.canBeReadBy(ada, day) shouldBe true
        mine.canBeReadBy(bea, day) shouldBe false
    }

    @Test
    fun `the eight statuses doc 04 defines all exist, whatever this slice produces`() {
        BondDayStatus.entries.map { it.name } shouldContainExactlyInAnyOrder
            listOf("OPEN", "PARTIAL", "PENDING_REVEAL", "REVEALED", "SOLO", "EMPTY", "SUSPENDED", "FROZEN")
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew :modules:gratitude:test --tests "*BondDayTest"`
Expected: FAIL — `Unresolved reference 'BondDay'`.

- [ ] **Step 3: Implement**

Immutable Kotlin with `require`d invariants, flat data, no JPA annotations — the `Bond` pattern from ADR-0026. `isClosed` is `status in setOf(REVEALED, SOLO, EMPTY, FROZEN)`; a `SUSPENDED` day is **not** closed (nothing closed it) and **not** open to evaluation either, which is why BR-3a asks `isClosed` and the streak walk asks the status.

Ids: `BondDayId` is **UUID v7** (`IdGenerator.timeOrdered`) — a day is not a secret and the index locality is worth having. `EntryId` is **UUID v4**: doc 06 §1 reserves v4 for entry and media ids, because a v7 embeds a creation time and BR-8 exists to suppress exactly that metadata about a locked entry.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :modules:gratitude:test --tests "*BondDayTest"`
Expected: PASS, all five.

- [ ] **Step 5: Commit**

```bash
git add modules/gratitude/src/main/kotlin/com/moyi/gratitude/domain modules/gratitude/src/test/kotlin/com/moyi/gratitude/domain/BondDayTest.kt
git commit -m "feat(gratitude): the Bond-day, its eight statuses, and BR-1 on an entry"
```

---

### Task 6: V12, and the stores

**Files:**
- Create: `modules/gratitude/src/main/resources/db/migration/V12__gratitude_bond_days_and_entries.sql`
- Create: `modules/gratitude/src/main/kotlin/com/moyi/gratitude/infra/database/{BondDayEntity,EntryEntity,GratitudeMappers,GratitudeRepositories,BondDayStore,EntryStore}.kt`
- Create: `modules/gratitude/src/test/kotlin/com/moyi/gratitude/infra/GratitudeTestApplication.kt`
- Test: `modules/gratitude/src/test/kotlin/com/moyi/gratitude/infra/database/BondDayPersistenceTest.kt`

**Interfaces:**
- Produces: `BondDayStore.openOrGet(bondId, date, zone, now): BondDay`, `BondDayStore.statusOf(bondId, date): BondDayStatus?`, `BondDayStore.update(day)`, `EntryStore.insert(entry)`, `EntryStore.findForDay(bondDayId): List<Entry>`.

- [ ] **Step 1: Write the migration**

```sql
-- V12__gratitude_bond_days_and_entries.sql
-- Doc 07 §2 "gratitude", with three deltas that document records, each in the
-- Phase 3 design §12:
--
--   * `status` carries all EIGHT of doc 04 §3's values. Doc 07's DDL listed
--     five; PENDING_REVEAL (FR-062), SUSPENDED (04 §8.1-8.3a) and FROZEN
--     (BR-5, 04 §8.5) were missing, each required by a rule stated elsewhere.
--   * `bond_days.anchor_timezone` is new. BR-6 and ADR-0030 require an anchor
--     change never to recompute an existing day; the row carries the zone it
--     was opened in so that is structural rather than remembered.
--   * `entries`' text bound is `octet_length(text) <= 8192` plus a code-point
--     cap, not `char_length(text) <= 4000`. FR-041 names that number as its own
--     first draft's error: 4,000 code points allows 8 per grapheme and one ZWJ
--     family emoji is 10.
--
-- No foreign key leaves this module: `bond_id` and `author_member_id` are ids
-- that bond owns, and referential integrity across a module boundary is the
-- application's job (doc 25 §6, ADR-0026).
CREATE TABLE bond_days (
    id              uuid        PRIMARY KEY,
    bond_id         uuid        NOT NULL,
    date            date        NOT NULL,
    status          text        NOT NULL,
    anchor_timezone text        NOT NULL,
    entry_count     smallint    NOT NULL DEFAULT 0,
    revealed_at     timestamptz,
    closed_at       timestamptz,
    created_at      timestamptz NOT NULL,
    version         integer     NOT NULL DEFAULT 0,

    CONSTRAINT bond_days_status_check CHECK (
        status IN ('OPEN', 'PARTIAL', 'PENDING_REVEAL', 'REVEALED', 'SOLO', 'EMPTY', 'SUSPENDED', 'FROZEN')
    ),
    CONSTRAINT bond_days_entry_count_check CHECK (entry_count BETWEEN 0 AND 2),
    CONSTRAINT bond_days_timezone_length_check CHECK (char_length(anchor_timezone) BETWEEN 1 AND 64)
);

-- The constraint the whole phase rests on: one row per bond per date.
CREATE UNIQUE INDEX bond_days_bond_date_key ON bond_days (bond_id, date);
-- The archive feed, which is the hottest read in the product (doc 07 §3).
CREATE INDEX bond_days_feed_idx ON bond_days (bond_id, date DESC);
-- The close job's scan (C3), partial so it stays small.
CREATE INDEX bond_days_open_idx ON bond_days (status, date) WHERE status IN ('OPEN', 'PARTIAL', 'PENDING_REVEAL');

CREATE TABLE entries (
    id                     uuid        PRIMARY KEY,
    bond_day_id            uuid        NOT NULL REFERENCES bond_days (id) ON DELETE CASCADE,
    bond_id                uuid        NOT NULL,
    author_member_id       uuid        NOT NULL,
    text                   text,
    text_search            text,
    image_media_id         uuid,
    voice_media_id         uuid,
    voice_duration_ms      integer,
    prompt_id              uuid,
    status                 text        NOT NULL,
    author_deleted_account boolean     NOT NULL DEFAULT false,
    created_at             timestamptz NOT NULL,
    intended_at            timestamptz NOT NULL,
    updated_at             timestamptz NOT NULL,
    revealed_at            timestamptz,
    deleted_at             timestamptz,

    CONSTRAINT entries_status_check CHECK (status IN ('SUBMITTED', 'REVEALED', 'DELETED')),
    CONSTRAINT entries_text_octets_check CHECK (text IS NULL OR octet_length(text) <= 8192),
    CONSTRAINT entries_text_points_check CHECK (text IS NULL OR char_length(text) <= 8192)
);

-- BR-2, and BR-2 says "enforced by a unique index, not by application logic
-- alone". Partial, so a deleted entry does not hold the slot: an author who
-- deletes before reveal may write again that day.
CREATE UNIQUE INDEX entries_one_per_member_per_day ON entries (bond_day_id, author_member_id) WHERE deleted_at IS NULL;
CREATE INDEX entries_bond_recent_idx ON entries (bond_id, created_at DESC);
```

`text_search` and the `tsvector` arrive in C6 with search; the column exists now so that C6 and Phase 5's encryption are additive (spec §1).

- [ ] **Step 2: Write the failing persistence test**

```kotlin
@Test
fun `two first entries racing produce one day, not two`() {
    // The lazy open is INSERT ... ON CONFLICT DO NOTHING, which is B2's
    // invite-creation shape (ADR-0027) and not a check-then-insert.
    val results = inParallel(listOf({ days.openOrGet(bondId, date, lagos, now) }, { days.openOrGet(bondId, date, lagos, now) }))

    results.map { it.id }.toSet().size shouldBe 1
    jdbc.queryForObject("SELECT count(*) FROM bond_days", Int::class.java) shouldBe 1
}

@Test
fun `a second entry by the same member is refused by the index, not by a check`() {
    val day = days.openOrGet(bondId, date, lagos, now)
    entries.insert(entry(day, ada))

    shouldThrow<DataIntegrityViolationException> { entries.insert(entry(day, ada)) }
    // …and the other member is fine.
    entries.insert(entry(day, bea))
    jdbc.queryForObject("SELECT count(*) FROM entries", Int::class.java) shouldBe 2
}

@Test
fun `the day keeps the zone it was opened in even after the bond's anchor moves`() {
    val day = days.openOrGet(bondId, date, ZoneId.of("Africa/Lagos"), now)

    // Nothing in this module reads the bond's current zone for an existing day.
    days.statusOf(bondId, date).shouldNotBeNull()
    days.find(day.id)!!.anchorTimezone shouldBe ZoneId.of("Africa/Lagos")
}
```

- [ ] **Step 3: Run them and watch them fail**

Run: `./gradlew :modules:gratitude:test --tests "*BondDayPersistenceTest"`
Expected: FAIL — no `BondDayStore`.

- [ ] **Step 4: Implement the entities, mappers and stores**

Flat entities in `infra/database`, hand mappers, no JPA object graph between `BondDayEntity` and `EntryEntity` — ADR-0026's rule, and the reason `entries.bond_day_id` is a plain column with a database-level FK rather than a `@ManyToOne`.

`openOrGet` is one statement then one read:

```kotlin
@Modifying
@Query(value = "INSERT INTO bond_days (...) VALUES (...) ON CONFLICT (bond_id, date) DO NOTHING", nativeQuery = true)
fun insertIfAbsent(...): Int
```

`GratitudeTestApplication` mirrors `BondTestApplication`: scans `com.moyi.common` and `com.moyi.gratitude`, supplies a `FakeBondAccess` and a `FakeUserDirectory`, and stubs `TokenRevocations`.

- [ ] **Step 5: Run the tests**

Run: `./gradlew :modules:gratitude:test --tests "*BondDayPersistenceTest"`
Expected: PASS, all three.

- [ ] **Step 6: Commit**

```bash
git add modules/gratitude/src/main/resources/db/migration modules/gratitude/src/main/kotlin/com/moyi/gratitude/infra modules/gratitude/src/test/kotlin/com/moyi/gratitude/infra
git commit -m "feat(gratitude): V12 bond_days and entries, and the lazy open"
```

---

### Task 7: `POST /bonds/{bondId}/entries`

**Files:**
- Create: `modules/gratitude/src/main/kotlin/com/moyi/gratitude/service/{SubmitEntry,GratitudeErrors}.kt`
- Create: `modules/gratitude/src/main/kotlin/com/moyi/gratitude/web/{EntriesController,SubmitEntryRequest}.kt`
- Modify: `common/web/.../ErrorCode.kt` — add `ENTRY_ALREADY_EXISTS`, `DAY_CLOSED`, `MEDIA_NOT_YET_SUPPORTED`
- Test: `modules/gratitude/src/test/kotlin/com/moyi/gratitude/web/EntriesEndpointTest.kt`

**Interfaces:**
- Consumes: `BondAccess.membershipOf` (Task 1), `EntryText.of` (Task 3), `DayAssignment.dateFor` (Task 4), the stores (Task 6), `@Idempotent` (Task 2).
- Produces: `SubmitEntry.submit(membership: BondMembership, draft: EntryDraft): EntryView`.

The order of checks **is** the contract, and it is the order B2's accept established: the guard first (so a non-member never sees anything else), then the caller's own state, then the bond's, then the day's.

1. Guard — `BondAccess.membershipOf`, which is the controller's first statement.
2. `membership.hasLeft` → `409 BOND_ARCHIVED`. **Check the flag; do not lean on `isOpen`** — that assumption is what the second review of PR #41 found in `RequestDeletion.cancel`.
3. `!membership.isOpen` → `409 BOND_ARCHIVED` (BR-9).
4. Media ids non-null → `422 MEDIA_NOT_YET_SUPPORTED` (spec §1: refused, never stored and ignored).
5. Day assignment, then `openOrGet` — `SUSPENDED` when the bond is `PENDING_MEMBER` (spec §12.4).
6. Insert. A unique-violation → `409 ENTRY_ALREADY_EXISTS`, **caught from the constraint rather than checked first** (BR-2).

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `a member writes one entry, and the day is theirs by the bond's calendar`() {
    val response = submit(ada, bondId, """{"text":"thank you for the coffee"}""")

    response.status shouldBe 201
    response.contentAsString shouldContain "\"status\":\"SUBMITTED\""
    jdbc.queryForObject("SELECT date::text FROM bond_days", String::class.java) shouldBe "2026-09-15"
}

@Test
fun `a second entry the same day is 409, and the first is untouched`() {
    submit(ada, bondId, """{"text":"first"}""").status shouldBe 201

    val second = submit(ada, bondId, """{"text":"second"}""")

    second.status shouldBe 409
    second.contentAsString shouldContain "\"code\":\"ENTRY_ALREADY_EXISTS\""
    jdbc.queryForObject("SELECT text FROM entries", String::class.java) shouldBe "first"
}

@Test
fun `a non-member, an unknown bond and a value that is not a uuid are one answer`() {
    val bodies = listOf(
        submit(eve, bondId, """{"text":"hello"}"""),
        submit(ada, UUID.randomUUID().toString(), """{"text":"hello"}"""),
        submit(ada, "not-a-uuid", """{"text":"hello"}"""),
    )

    bodies.forEach { it.status shouldBe 404 }
    bodies.map { normalise(it.contentAsString) }.toSet().size shouldBe 1
}

@Test
fun `an archived bond takes no entries, and a member who left is refused too`() {
    leave(ada, bondId).status shouldBe 204

    submit(ada, bondId, """{"text":"one more"}""").contentAsString shouldContain "\"code\":\"BOND_ARCHIVED\""
    submit(bea, bondId, """{"text":"one more"}""").contentAsString shouldContain "\"code\":\"BOND_ARCHIVED\""
}

@Test
fun `a media id is refused rather than ignored, until Phase 4 can honour it`() {
    val response = submit(ada, bondId, """{"text":"look","imageMediaId":"${UUID.randomUUID()}"}""")

    response.status shouldBe 422
    response.contentAsString shouldContain "\"code\":\"MEDIA_NOT_YET_SUPPORTED\""
}

@Test
fun `the creator may write before anybody joins, and that day is suspended`() {
    // 02 J1: never gate the creator on the invitee. Doc 04 §8.3a as the Phase 3
    // design §12.4 resolves it — the row exists so the entry has somewhere to
    // live, SUSPENDED so the streak never counts it.
    val solo = createBond(cara)

    submit(cara, bondIdOf(solo), """{"text":"waiting for you"}""").status shouldBe 201

    jdbc.queryForObject("SELECT status FROM bond_days", String::class.java) shouldBe "SUSPENDED"
}

@Test
fun `an entry of only whitespace is 422 naming the field, and never a 500`() {
    for (blank in listOf("", " ", "\\u00a0")) {
        val response = submit(ada, bondId, """{"text":"$blank"}""")
        response.status shouldBe 422
        response.contentAsString shouldContain "\"field\":\"text\""
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `./gradlew :modules:gratitude:test --tests "*EntriesEndpointTest"`
Expected: FAIL — no controller.

- [ ] **Step 3: Implement the service, the request and the controller**

The request's `text` carries `@field:Pattern(regexp = NOT_ONLY_SPACE)` — `gratitude` declares its own copy of the constant rather than importing `bond`'s, because that one is `internal`. `@field:Size(max = 8192)` is the octet backstop at the edge; the grapheme count is `EntryText.of`'s, in the domain, and the edge does not restate it (ADR-0029 §13's lesson: one statement of a rule).

The controller's first statement is the guard, and `@Idempotent` sits on the method.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :modules:gratitude:test --tests "*EntriesEndpointTest"`
Expected: PASS, all seven.

- [ ] **Step 5: Add the cross-tenant fixture**

`BondCrossTenantTest`'s route-driven suite reads `{bondId}` routes off Spring's handler mapping, so **a new endpoint without a fixture fails the build** (ADR-0026). That suite lives in `bond`, which cannot see `gratitude`'s controller — so add `modules/gratitude/src/test/kotlin/com/moyi/gratitude/web/GratitudeCrossTenantTest.kt` with the same shape, covering `POST /api/v1/bonds/{bondId}/entries`.

Run: `./gradlew :modules:gratitude:test :app:test`
Expected: PASS.

- [ ] **Step 6: Prove the index is what enforces BR-2**

Drop `entries_one_per_member_per_day` in a scratch database and run `a second entry the same day is 409`. Expected: it fails — two rows, `200` twice. Restore. Record it in the PR body.

- [ ] **Step 7: Commit**

```bash
git add modules/gratitude/src/main/kotlin/com/moyi/gratitude/{service,web} modules/gratitude/src/test/kotlin/com/moyi/gratitude/web common/web/src/main/kotlin/com/moyi/common/web/ErrorCode.kt
git commit -m "feat(gratitude): POST /bonds/{bondId}/entries, and the day the server chooses"
```

---

### Task 8: `GET /bonds/{bondId}/today`, and the locked shape

**Files:**
- Create: `modules/gratitude/src/main/kotlin/com/moyi/gratitude/service/GetToday.kt`
- Create: `modules/gratitude/src/main/kotlin/com/moyi/gratitude/web/{TodayResponse,LockedEntryResponse}.kt`
- Test: `modules/gratitude/src/test/kotlin/com/moyi/gratitude/web/RevealGateTest.kt`

**Interfaces:**
- Produces: `GET /api/v1/bonds/{bondId}/today` → `{bondDay, myEntry, partnerEntry, partner}`. `streak` and `prompt` arrive in C4 and C6; they are **absent**, not null placeholders.

**`LockedEntryResponse` is a distinct type, and that is the security control.** BR-8 says a locked entry serialises to `{authorMemberId, status: LOCKED}` and nothing else. A `RevealedEntryResponse` with nulled fields would leak the moment somebody adds a property in six months; a type that cannot carry the text cannot leak it.

- [ ] **Step 1: Write the failing test — the one doc 12 calls the most important file in the repository**

```kotlin
/**
 * FR-060's acceptance clause, parameterised. A failure here is a P0 (doc 11).
 * C1 covers the statuses C1 can produce; C2 extends the matrix to REVEALED and
 * C3 to SOLO, and neither may narrow what is asserted here.
 */
internal class RevealGateTest {
    @Test
    fun `a locked entry is exactly an author and a status, and nothing else`() {
        submit(bea, bondId, """{"text":"a secret kindness"}""").status shouldBe 201

        val today = getToday(ada, bondId)

        today.contentAsString shouldContain "\"partnerEntry\":{\"authorMemberId\":\""
        today.contentAsString shouldContain "\"status\":\"LOCKED\""
        // Not the text, and not one thing about it.
        today.contentAsString shouldNotContain "a secret kindness"
        today.contentAsString shouldNotContain "createdAt"
        today.contentAsString shouldNotContain "length"
        today.contentAsString shouldNotContain "hasImage"
        today.contentAsString shouldNotContain "intendedAt"
    }

    @Test
    fun `the day's status IS returned, because it is deliberately shared`() {
        // Doc 04 §6.1: a member who has not written can infer from PARTIAL that
        // their partner has, and J2's "Waiting for Tunde" depends on it. The
        // converse assertion matters as much as the one above — a later change
        // that hid the status to be "safe" would break the product.
        submit(bea, bondId, """{"text":"a secret kindness"}""").status shouldBe 201

        getToday(ada, bondId).contentAsString shouldContain "\"status\":\"PARTIAL\""
    }

    @Test
    fun `an author reads their own entry in full`() {
        submit(ada, bondId, """{"text":"thank you"}""").status shouldBe 201

        val today = getToday(ada, bondId)

        today.contentAsString shouldContain "\"myEntry\""
        today.contentAsString shouldContain "thank you"
    }

    @Test
    fun `priming today as one member does not serve it to the other`() {
        // Doc 12 names cache poisoning as a reveal-gate bypass no authorisation
        // layer sees. C1 caches nothing; this test is what makes adding a cache
        // later a deliberate act rather than an accident.
        submit(bea, bondId, """{"text":"a secret kindness"}""").status shouldBe 201
        getToday(bea, bondId).status shouldBe 200

        getToday(ada, bondId).contentAsString shouldNotContain "a secret kindness"
    }

    @Test
    fun `a day nobody has written to yet is OPEN with two absent entries`() {
        val today = getToday(ada, bondId)

        today.contentAsString shouldContain "\"status\":\"OPEN\""
        today.contentAsString shouldContain "\"myEntry\":null"
        today.contentAsString shouldContain "\"partnerEntry\":null"
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `./gradlew :modules:gratitude:test --tests "*RevealGateTest"`
Expected: FAIL — no `today` route.

- [ ] **Step 3: Implement**

`GetToday` computes the date with `DayAssignment.dateFor(now, null, zone) { false }` — today is never closed — reads the day if it exists, and maps each entry through `Entry.canBeReadBy`. A day that does not exist yet is reported as `OPEN` **without being created**: a read must not write, or `GET /today` would manufacture rows for every bond anyone opened the app on.

- [ ] **Step 4: Run the tests**

Run: `./gradlew :modules:gratitude:test --tests "*RevealGateTest"`
Expected: PASS, all five.

- [ ] **Step 5: Prove the gate is load-bearing**

Change `canBeReadBy` to `true` and run the suite. Expected: `a locked entry is exactly an author and a status` fails on the text. Restore it, and record it in the PR body — this is the single most important assertion in the slice.

- [ ] **Step 6: Commit**

```bash
git add modules/gratitude/src/main/kotlin/com/moyi/gratitude/service/GetToday.kt modules/gratitude/src/main/kotlin/com/moyi/gratitude/web modules/gratitude/src/test/kotlin/com/moyi/gratitude/web/RevealGateTest.kt
git commit -m "feat(gratitude): GET /bonds/{bondId}/today, and a locked entry that cannot leak"
```

---

### Task 9: The contract, the smoke section, and the record

**Files:**
- Modify: `contracts/src/main/kotlin/com/moyi/contracts/OpenApiConfiguration.kt` — add the new operations to `CONFLICTING_OPERATIONS`
- Modify: `contracts/openapi.json` — regenerated, never hand-edited
- Modify: `app/src/test/kotlin/com/moyi/app/OpenApiContractTest.kt`
- Modify: `scripts/smoke.sh`
- Create: `adr/0031-the-bond-day-and-the-first-entry.md`
- Modify: `docs/learning-log.md`

- [ ] **Step 1: Add the contract assertions**

```kotlin
@Test
fun `the entry endpoints document their conflicts, and today its 404`() {
    val entries = api.paths["/api/v1/bonds/{bondId}/entries"]!!.post
    entries.responses.keys shouldContainAll listOf("201", "404", "409", "422")
    entries.parameters.map { it.name } shouldContain "Idempotency-Key"
    entries.parameters.first { it.name == "Idempotency-Key" }.required shouldBe true

    api.paths["/api/v1/bonds/{bondId}/today"]!!.get.responses shouldContainKey "404"
}
```

`Idempotency-Key`'s requiredness is added by the same rule that made `If-Match` required (ADR-0029 §11): the handler takes it as optional so an absent key is our `422` rather than Spring's `400`, and the document states what the API enforces.

- [ ] **Step 2: Regenerate and read the diff**

Run: `./gradlew :app:test --tests "com.moyi.app.OpenApiContractTest"` — it fails with "contracts/openapi.json is stale".
Then: `cp app/build/openapi/openapi.json contracts/openapi.json` and **read the diff**. It is the API contract changing.

- [ ] **Step 3: Write the smoke section**

Against the packaged jar, the whole slice on the wire: a paired bond; a first entry (201); the same member's second (409 `ENTRY_ALREADY_EXISTS`); the same `Idempotency-Key` replayed (201 with `Idempotency-Replayed: true`, and one row in `entries`); the key with a different body (422); a non-breaking-space entry (422 on `text`, not 500); the partner's entry locked in `GET /today` with the text absent from the response **and from the log**; a creator writing into a bond nobody has joined (201, day `SUSPENDED`).

- [ ] **Step 4: Run everything**

```bash
./gradlew build
docker compose up -d && ./gradlew :app:bootJar && PORT=18086 scripts/smoke.sh --no-build
```
Expected: `BUILD SUCCESSFUL`, and smoke `0 failed`.

- [ ] **Step 5: Write ADR-0031**

Carrying, at minimum: the `internal` constructor on `BondMembership` and why it replaces a Konsist rule; the lazy `ON CONFLICT DO NOTHING` open; the day copying its anchor zone; `SUSPENDED` for a `PENDING_MEMBER` bond and the §8.3a contradiction it resolves; entry ids as v4 against day ids as v7; media refused rather than stored; `LockedEntryResponse` as a distinct type; idempotency reserved before the handler; **and the reveal deliberately absent, so C2's author reads it as a decision**. Copy it to `../documents/adr/` on the Gratitude branch `docs/phase-3-daily-loop`.

- [ ] **Step 6: Write the learning-log entry**

Expected / Reality / Wrong about, honest about what was wrong rather than what was built.

- [ ] **Step 7: Commit and open the PR**

```bash
git add -A
git commit -m "docs(gratitude): ADR-0031, the contract, the smoke section and the log"
git push -u origin feat/gratitude-day
```

The PR body carries what `.github/PULL_REQUEST_TEMPLATE.md` asks for and what PR #33 models: what & why, the concept brief, the Figma alignment table (`states.md` §3 *Today* and §4 *Compose* both exist; there is no screen for "your entry landed on yesterday's day" and **that is a gap to record**), the ten hostile-reviewer questions, and the DoD checklist. **Label it `breaking-api-change`** — four new `ErrorCode` values.

---

## Self-review

**Spec coverage.** §2.1 the port → Task 1. §5.4 idempotency → Task 2. §3.2 `EntryText` → Task 3. §6.1 BR-3/BR-3a → Task 4. §3.1 the aggregate and the eight statuses → Task 5. §7 V11/V12 and the indexes → Tasks 2 and 6. §6.2 submission and BR-2 → Task 7. §4 and §5.1 the reveal gate and `today` → Task 8. §12.4's `SUSPENDED` resolution → Tasks 5, 6 and 7. The contract, smoke and ADR → Task 9.

**Not in C1, by the spec's own C1 row:** the reveal and `PENDING_REVEAL` (C2), `PATCH`/`DELETE /entries/{id}` (C2), the outbox (C2), the close job (C3), streak and prompt in the `today` payload (C4, C6). The "What C1 deliberately leaves broken" section above is the one place that has to be read before someone tries to be helpful.

**Types.** `BondMembership` (Task 1) is the argument every service in Tasks 7 and 8 takes. `EntryText.of` (Task 3) is called only in `SubmitEntry` (Task 7). `DayAssignment.dateFor`'s fourth parameter is the `isClosed` lambda in Tasks 4, 7 and 8. `BondDayStore.statusOf` (Task 6) is what Task 7 passes into it.
