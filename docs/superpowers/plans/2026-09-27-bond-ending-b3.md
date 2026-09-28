# Slice B3 — Ending (leave and block): Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A member can end a bond and, if they need to, block the other person — and from the other side the two are indistinguishable.

**Architecture:** Two `204` endpoints on B1's guard. Both take the bond's row lock, archive the aggregate, revoke any live invite, and write nothing anyone is notified about. Block adds one `blocks` row per other member and is the only bond-scoped write permitted on a bond that has already ended. The archived bond stays readable for both members, including whoever left; every other bond-scoped write becomes `409 BOND_ARCHIVED`.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Spring Data JPA, Postgres 18 + Valkey via Testcontainers, kotest, Konsist, springdoc.

**Spec:** `docs/superpowers/specs/2026-09-24-bonds-and-invites-design.md` — §5.2 rows #8–#9, §6.3, §8, §9 (B3 row), §11 decisions 8 and 9.

**Depends on:** B1 (PR #37) and B2 (PR #38), both merged. This branch bases on `main` at `cdd7ac8`.

## Global Constraints

Everything in B1's and B2's plans still binds. Added or sharpened for this slice:

- **Doc 26 §2.1 is the hard rule.** From the other side, a block must be *indistinguishable* from a leave: same archived bond, same absence of notification, same wording, **same `ETag`**. A blocked person is never told they were identified as a threat (T-09), and no response anywhere carries the word "block".
- **Leave** (FR-026): active or left member of an *open* bond — `PENDING_MEMBER` or `ACTIVE`. Sets `leftAt`, archives the bond, revokes any live invite, notifies nobody. On an already-archived bond: `409 BOND_ARCHIVED`.
- **Block** (FR-029): everything leave does, **plus** one `blocks` row per other member, current *or* left — and permitted on an already-archived bond, which is the one place the two differ in what they accept (spec §11 decision 8). Blocking twice is `204` and writes one row.
- **Archived means read-only for both** (spec §11 decision 9, `states.md` §9): both members, the one who left included, keep `GET /bonds/{bondId}` and stay in each other's `GET /bonds`. Every other bond-scoped write is `409 BOND_ARCHIVED`.
- **No new migration.** V9 already carries `blocks` and `bond_members.left_at`.
- **No new `ErrorCode`.** `BOND_ARCHIVED` arrived with B2, so **this slice is not a breaking API change** and does not carry the `breaking-api-change` label — the first Phase 2 slice that does not. Adding two operations, and a `409` to an existing one, is additive; check the oasdiff job's verdict rather than assuming it.
- **No new rate-limit bucket.** Spec §5.4 lists none for #8/#9; the `authenticated:user` 120/min bucket already applies.
- **`Membership` only from the guard.** Both new service functions take a `Membership`; the two Konsist rules in `app` already hold that, and both new routes must appear in `BondCrossTenantTest.fixtures` or the build fails — **let it fail once first** (handover §4.2 done-condition 3).
- Commit trailer:
  ```
  Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01GPjbihrTNz2YwDjuwUmysH
  ```
- Work from `/Users/danzucker/Desktop/Learning/project_base_learning/Gratitude/moyi-backend/.worktrees/feat-bond-ending` (branch `feat/bond-ending`, based on `main`).
- **Not in this slice:** FR-029a's withdraw-my-entries option (Phase 3, when there are entries to withdraw); cancelling a live *proposal* on ending (B5 owns `bond_proposals` — leave a note where it will hook in).

---

## File structure

**bond domain** — `Bond.kt`: `leave`, `end`, the `endedAt` bookkeeping. Nothing else in the domain changes; `Block` already exists.

**bond infra** — `BondMappers.kt`: `Member.applyTo` (the member update path, as `Bond.applyTo` is the bond's). `BondStore.kt`: `archive(bond)`. `BondRepositories.kt`: `BlockRepository.insertIfAbsent` (native `ON CONFLICT DO NOTHING`). `BlockStore.kt`: `insertIfAbsent(block)`.

**bond service** — `EndBond.kt` (new): `leave(membership)` and `block(membership)`. `RevokeInvite.kt`: the archived check, so §6.3's "every other bond-scoped write is `409 BOND_ARCHIVED`" is true rather than nearly true.

**bond web** — `BondsController.kt`: `leaveBond`, `blockBond`.

**bond test** — `BondTest.kt` (domain cases), `BondPersistenceTest.kt` (archive + block upsert), `BondEndingEndpointTest.kt` (new — every §5.2 status for #8/#9, the archived write refusals, the reads that still work), `DiscreetExitTest.kt` (new — doc 26 §2.1, bytes and `ETag`), `EndBondRaceTest.kt` (new — leave against accept), `BondCrossTenantTest.kt` (+2 fixtures), `InviteEndpointTest.kt` (the revoke-on-archived case).

**contracts / app** — `OpenApiConfiguration.kt` (the `409` operation ids), `OpenApiContractTest.kt` (+2 paths, +1 test), `contracts/openapi.json` (regenerated).

**scripts / docs** — `scripts/smoke.sh` (a B3 section), `adr/0028-leave-block-and-the-discreet-exit.md`, `docs/learning-log.md`, `.claude/HANDOVER.md`; corpus amendments in `../documents` on branch `docs/bonds-phase-2`.

---

### Task 1: The domain — leaving, and ending without leaving twice

**Files:**
- Modify: `modules/bond/src/main/kotlin/com/moyi/bond/domain/Bond.kt`
- Test: `modules/bond/src/test/kotlin/com/moyi/bond/domain/BondTest.kt`

**Interfaces:**
- Produces: `Bond.leave(memberId: MemberId, now: Instant): Bond` — `check(isOpen)`, then `end`. `Bond.end(memberId: MemberId, now: Instant): Bond` — idempotent, no status check, used by block.

- [ ] **Step 1: Write the failing tests** in `BondTest`:

```kotlin
    @Test
    fun `leaving archives the bond and stamps the member who left`() {
        val bond = create()
        val ownerId = bond.members.single().id

        val left = bond.leave(ownerId, now.plusSeconds(60))

        left.status shouldBe BondStatus.ARCHIVED
        left.archivedAt shouldBe now.plusSeconds(60)
        left.activeMembers.shouldBeEmpty()
        left.memberOf(bond.createdBy)!!.leftAt shouldBe now.plusSeconds(60)
    }

    @Test
    fun `the other member stays active when one leaves`() {
        val joined = create().accept(joiner())
        val owner = joined.members.first { it.role == MemberRole.OWNER }

        val left = joined.leave(owner.id, now)

        left.status shouldBe BondStatus.ARCHIVED
        left.activeMembers.map { it.role } shouldBe listOf(MemberRole.MEMBER)
    }

    @Test
    fun `an archived bond cannot be left again`() {
        val bond = create()
        val archived = bond.leave(bond.members.single().id, now)

        shouldThrow<IllegalStateException> { archived.leave(bond.members.single().id, now) }
    }

    @Test
    fun `ending is idempotent, so blocking twice changes nothing`() {
        val bond = create()
        val memberId = bond.members.single().id
        val ended = bond.end(memberId, now)

        ended.end(memberId, now.plusSeconds(600)) shouldBe ended
    }

    @Test
    fun `ending a bond somebody else already left leaves the archive stamp alone`() {
        val joined = create().accept(joiner())
        val owner = joined.members.first { it.role == MemberRole.OWNER }
        val other = joined.members.first { it.role == MemberRole.MEMBER }
        val archived = joined.leave(owner.id, now)

        val blocked = archived.end(other.id, now.plusSeconds(3600))

        blocked.archivedAt shouldBe now
        blocked.memberOf(other.userId)!!.leftAt shouldBe now.plusSeconds(3600)
        blocked.activeMembers.shouldBeEmpty()
    }

    @Test
    fun `ending a bond already heading for deletion does not drag it back to archived`() {
        // B5 owns PENDING_DELETION and its cooling-off. Blocking during it
        // must not reset the status the deletion job reads.
        val pending = create().copy(status = BondStatus.PENDING_DELETION)

        pending.end(pending.members.single().id, now).status shouldBe BondStatus.PENDING_DELETION
    }
```

  `joiner()` is a local helper; if `BondTest` does not already have one, add it next to the existing `create()`:

```kotlin
    private fun joiner(): Member =
        Member.member(MemberId(UUID.randomUUID()), create().id, UserId(UUID.randomUUID()), lagos, now)
```

  Check the names `create()`, `now` and `lagos` against the file before writing — reuse whatever it already calls them, and add only what is missing.

- [ ] **Step 2: Run them and watch them fail.** `./gradlew :modules:bond:test --tests '*BondTest'` — expected FAIL: unresolved reference `leave`, `end`.

- [ ] **Step 3: Add the two transitions** to `Bond`, below `accept`:

```kotlin
    /**
     * FR-026: this member walks away, and the bond becomes a record.
     *
     * One member leaving archives the whole bond rather than leaving the other
     * alone in it. Doc 04 §4.3 has no state for a bond of one, and the product
     * has no screen for it: a gratitude exchange between two people is over
     * when either stops, and `states.md` §9 keeps what was written readable
     * for both afterwards instead of pretending the bond continues.
     *
     * Nobody is notified — T-09's "discreet exit". The other member finds out
     * by opening the app, which is the same way they would find out about a
     * block, and that is the point (doc 26 §2.1).
     *
     * `check`, not `require`: leaving twice is a state conflict, and the
     * service turns it into `409 BOND_ARCHIVED`.
     */
    fun leave(
        memberId: MemberId,
        now: Instant,
    ): Bond {
        check(isOpen) { "a bond that has ended cannot be left again" }
        return end(memberId, now)
    }

    /**
     * [leave]'s effect with no state check — what FR-029's block does, and the
     * reason it is a separate function.
     *
     * Block is permitted on an **archived** bond, because blocking someone who
     * left first is exactly the case FR-029 exists for (spec §11 decision 8),
     * and it must also be safe to repeat: the two calls that follow one another
     * produce the same object, so the row is not rewritten and the `version`
     * behind the `ETag` does not move. That is not tidiness — a version that
     * ticked on a second block would let the other side count how many times
     * this was called, and doc 26 §2.1 forbids the other side learning
     * anything at all.
     *
     * A bond already in `PENDING_DELETION` keeps that status: B5's cooling-off
     * is running and the deletion job reads it. Only [archivedAt] is filled,
     * and only if it was empty.
     */
    fun end(
        memberId: MemberId,
        now: Instant,
    ): Bond =
        copy(
            status = if (isOpen) BondStatus.ARCHIVED else status,
            archivedAt = archivedAt ?: now,
            members = members.map { if (it.id == memberId && it.isActive) it.copy(leftAt = now) else it },
        )
```

- [ ] **Step 4: Run the tests.** `./gradlew :modules:bond:test --tests '*BondTest'` — expected PASS. Then `./gradlew :modules:bond:ktlintMainSourceSetCheck :modules:bond:detektMain`.

- [ ] **Step 5: Commit.**

```bash
git add modules/bond/src/main/kotlin/com/moyi/bond/domain/Bond.kt modules/bond/src/test/kotlin/com/moyi/bond/domain/BondTest.kt
git commit -m "feat(bond): the aggregate learns to end (FR-026, FR-029)" -m "leave() refuses a bond that has already ended; end() is the same effect without the check, because a block is allowed on an archived bond and must be safe to repeat — the second call returns an equal object, so the row version behind the ETag does not move." -m "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GPjbihrTNz2YwDjuwUmysH"
```

---

### Task 2: Persistence — the member update path, archiving, and a block that can be written twice

**Files:**
- Modify: `modules/bond/src/main/kotlin/com/moyi/bond/infra/database/BondMappers.kt`, `BondStore.kt`, `BondRepositories.kt`, `BlockStore.kt`
- Test: `modules/bond/src/test/kotlin/com/moyi/bond/infra/database/BondPersistenceTest.kt`

**Interfaces:**
- Produces: `Member.applyTo(entity: BondMemberEntity)`; `BondStore.archive(bond: Bond)`; `BlockStore.insertIfAbsent(block: Block): Boolean`; `BlockRepository.insertIfAbsent(id, blockerUserId, blockedUserId, bondId, createdAt): Int`.
- Consumes: `Bond.leave` / `Bond.end` from Task 1.

- [ ] **Step 1: Write the failing tests** in `BondPersistenceTest` (match the class's existing fixture helpers — it already creates bonds and members; reuse them rather than inventing new ones):

```kotlin
    @Test
    fun `archiving writes the members who left and the bond's status together`() {
        val bond = insertedBond()
        val memberId = bond.members.single().id

        transactions.execute { store.archive(bond.leave(memberId, clock.instant())) }

        val reloaded = store.findByMember(bond.id, bond.createdBy)!!
        reloaded.status shouldBe BondStatus.ARCHIVED
        reloaded.archivedAt.shouldNotBeNull()
        reloaded.memberOf(bond.createdBy)!!.leftAt.shouldNotBeNull()
        reloaded.version shouldBe 1
    }

    @Test
    fun `archiving an already archived bond does not move the version`() {
        // The ETag is the version, and doc 26 §2.1 says the other side must
        // learn nothing — including how many times this was called.
        val bond = insertedBond()
        val memberId = bond.members.single().id
        transactions.execute { store.archive(bond.leave(memberId, clock.instant())) }
        val once = store.findByMember(bond.id, bond.createdBy)!!

        transactions.execute { store.archive(once.end(memberId, clock.instant().plusSeconds(60))) }

        store.findByMember(bond.id, bond.createdBy)!!.version shouldBe once.version
    }

    @Test
    fun `a block is written once, however many times it is made`() {
        val bond = insertedBond()
        val other = UserId(UUID.randomUUID())
        val block = Block(bond.createdBy, other, bond.id, clock.instant())

        transactions.execute { blocks.insertIfAbsent(block) shouldBe true }
        transactions.execute { blocks.insertIfAbsent(block.copy(createdAt = clock.instant().plusSeconds(60))) shouldBe false }

        jdbc.queryForObject("SELECT count(*) FROM blocks", Int::class.java) shouldBe 1
        blocks.existsBetween(other, listOf(bond.createdBy)) shouldBe true
    }
```

  The class already has whatever it uses for a transaction boundary and a `JdbcTemplate`; if it has no `TransactionTemplate`, inject one:

```kotlin
    @Autowired private val transactions: TransactionTemplate,
```

- [ ] **Step 2: Run them and watch them fail.** `./gradlew :modules:bond:test --tests '*BondPersistenceTest'` — expected FAIL: unresolved `archive`, `insertIfAbsent`.

- [ ] **Step 3: Add `Member.applyTo`** to `BondMappers.kt`, directly under `Member.toEntity`:

```kotlin
/**
 * Carries a changed [Member] onto the managed row it came from — the member's
 * update path, as [Bond.applyTo] is the bond's, and for the same reason:
 * `save(member.toEntity())` would hand Hibernate a detached object whose
 * `isNew` flag is true, which persists rather than merges.
 *
 * `id`, `bondId`, `userId` and `joinedAt` are not copied — the columns are
 * `updatable = false`, so the database would refuse. Everything else a member
 * can change is here, which is what slice B4's settings `PUT` will use.
 */
internal fun Member.applyTo(entity: BondMemberEntity) {
    require(entity.getId() == id.value) { "cannot apply a member onto a different member's row" }
    entity.role = role
    entity.leftAt = leftAt
    entity.reminderTimeLocal = reminderTimeLocal
    entity.reminderTimezone = reminderTimezone.id
    entity.quietHoursStart = quietHoursStart
    entity.quietHoursEnd = quietHoursEnd
    entity.nicknameForOther = nicknameForOther
}
```

- [ ] **Step 4: Add `BondStore.archive`**, below `addMember`:

```kotlin
    /**
     * Writes a bond that has ended: the status and `archived_at` on the bond
     * row, and `left_at` on whichever member rows the aggregate now says have
     * left — in one place, so the two cannot disagree about what happened.
     *
     * [bond] is the aggregate *after* `leave` or `end`. Nothing is written for
     * a member whose row already matches, and nothing at all is written if the
     * bond is unchanged: Hibernate's dirty check is what keeps `version` — the
     * `ETag` — still after a second block (doc 26 §2.1).
     */
    fun archive(bond: Bond) {
        val rows = members.findAllByBondId(bond.id.value).associateBy { it.getId() }
        bond.members.forEach { member -> rows[member.id.value]?.let(member::applyTo) }
        members.saveAll(rows.values)
        val entity = bonds.findById(bond.id.value) ?: error("cannot archive a bond that does not exist")
        bond.applyTo(entity)
        bonds.save(entity)
    }
```

- [ ] **Step 5: Add the upsert** to `BlockRepository` in `BondRepositories.kt`:

```kotlin
    /**
     * Records a block, or does nothing if that exact one is already recorded.
     *
     * `ON CONFLICT DO NOTHING` rather than a read followed by an insert,
     * because FR-029 permits blocking a bond that has already ended and
     * therefore permits blocking twice: the second call has to be the same
     * `204` as the first, and a check-then-insert would turn two concurrent
     * ones into a constraint violation the caller sees as a 500.
     *
     * Native, because JPQL has no `INSERT`. `flushAutomatically` so that
     * anything pending in this transaction is written first and the statement
     * sees a consistent table.
     *
     * @return `1` if this call wrote the row, `0` if it was already there.
     */
    @Modifying(flushAutomatically = true)
    @Query(
        nativeQuery = true,
        value = """
            INSERT INTO blocks (id, blocker_user_id, blocked_user_id, bond_id, created_at)
            VALUES (:id, :blockerUserId, :blockedUserId, :bondId, :createdAt)
            ON CONFLICT (blocker_user_id, blocked_user_id, bond_id) DO NOTHING
            """,
    )
    fun insertIfAbsent(
        id: UUID,
        blockerUserId: UUID,
        blockedUserId: UUID,
        bondId: UUID,
        createdAt: Instant,
    ): Int
```

- [ ] **Step 6: Add `BlockStore.insertIfAbsent`**, and leave `insert` alone (`BondPersistenceTest` uses it):

```kotlin
    /**
     * Records a block unless the same one is already recorded.
     *
     * @return `false` when it was already there — a repeat block, which
     *   FR-029 allows and which must look to the caller exactly like the first.
     */
    fun insertIfAbsent(block: Block): Boolean =
        blocks.insertIfAbsent(
            id = ids.timeOrdered(),
            blockerUserId = block.blockerUserId.value,
            blockedUserId = block.blockedUserId.value,
            bondId = block.bondId.value,
            createdAt = block.createdAt,
        ) == 1
```

- [ ] **Step 7: Run the tests.** `./gradlew :modules:bond:test --tests '*BondPersistenceTest'` — expected PASS. Then `./gradlew :modules:bond:check`.

- [ ] **Step 8: Commit.**

```bash
git add modules/bond/src/main/kotlin/com/moyi/bond/infra modules/bond/src/test/kotlin/com/moyi/bond/infra
git commit -m "feat(bond): archive a bond, and a block that can be made twice" -m "Member.applyTo is the member update path Bond.applyTo already was for the bond. archive() writes the ended aggregate in one call and writes nothing when nothing changed, which is what keeps the ETag still after a repeat block. The block insert is ON CONFLICT DO NOTHING, because FR-029 allows blocking an already-archived bond and a check-then-insert would make two concurrent calls a 500." -m "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GPjbihrTNz2YwDjuwUmysH"
```

---

### Task 3: The two endpoints, and the archived bond that takes no writes

**Files:**
- Create: `modules/bond/src/main/kotlin/com/moyi/bond/service/EndBond.kt`
- Modify: `modules/bond/src/main/kotlin/com/moyi/bond/service/RevokeInvite.kt`, `modules/bond/src/main/kotlin/com/moyi/bond/web/BondsController.kt`, `modules/bond/src/test/kotlin/com/moyi/bond/web/BondCrossTenantTest.kt`, `modules/bond/src/test/kotlin/com/moyi/bond/web/InviteEndpointTest.kt`
- Test: `modules/bond/src/test/kotlin/com/moyi/bond/web/BondEndingEndpointTest.kt` (new)

**Interfaces:**
- Produces: `EndBond.leave(membership: Membership)`, `EndBond.block(membership: Membership)`; routes `POST /api/v1/bonds/{bondId}/leave` and `POST /api/v1/bonds/{bondId}/block`, both `204`; controller methods `leaveBond`, `blockBond`.
- Consumes: `Bond.leave` / `Bond.end` (Task 1); `BondStore.archive`, `BlockStore.insertIfAbsent` (Task 2); `BondAccessGuard.membershipOf`, `BondStore.lockBond`, `InviteStore.revokeLiveOf`, `BondArchivedException`, `BondNotFoundException` (all existing).

- [ ] **Step 1: Write the failing endpoint test.** New file `modules/bond/src/test/kotlin/com/moyi/bond/web/BondEndingEndpointTest.kt`:

```kotlin
package com.moyi.bond.web

import com.moyi.bond.domain.InviteCode
import com.moyi.bond.infra.BondTestApplication
import com.moyi.bond.infra.FakeUserDirectory
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import javax.sql.DataSource

/**
 * `POST /bonds/{bondId}/leave` and `POST /bonds/{bondId}/block` (FR-026,
 * FR-029), every status spec §5.2 rows #8–#9 list, and the rule §6.3 states
 * about what an archived bond still answers.
 *
 * The *indistinguishability* of the two is `DiscreetExitTest`'s subject; this
 * file is about each one's own behaviour.
 */
@SpringBootTest(classes = [BondTestApplication::class])
@AutoConfigureMockMvc
internal class BondEndingEndpointTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
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
    fun `leaving is 204, archives the bond and kills the live code`() {
        val ada = users.verified("Ada")
        val created = createBond(ada)
        val bondId = bondIdOf(created)
        val code = codeOf(created)

        leave(ada, bondId).status shouldBe 204

        val after = getBond(ada, bondId)
        after.status shouldBe 200
        after.contentAsString shouldContain "\"status\":\"ARCHIVED\""
        after.contentAsString shouldContain "\"archivedAt\":\""
        after.contentAsString shouldContain "\"invite\":null"
        resolve(users.verified("Joiner"), code).status shouldBe 404
    }

    @Test
    fun `leaving twice is 409 BOND_ARCHIVED`() {
        val ada = users.verified("Ada")
        val bondId = bondIdOf(createBond(ada))
        leave(ada, bondId).status shouldBe 204

        val again = leave(ada, bondId)

        again.status shouldBe 409
        again.contentAsString shouldContain "\"code\":\"BOND_ARCHIVED\""
    }

    @Test
    fun `the member who left still reads the archive, and so does the other`() {
        // states.md §9, spec §11 decision 9: both keep the record.
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        val bondId = bondIdOf(createBond(ada))
        accept(bea, codeOf(getBond(ada, bondId))).status shouldBe 200
        leave(ada, bondId).status shouldBe 204

        getBond(ada, bondId).status shouldBe 200
        getBond(bea, bondId).status shouldBe 200
        listBonds(ada).contentAsString shouldContain bondId
        listBonds(bea).contentAsString shouldContain bondId
    }

    @Test
    fun `an archived bond takes no writes`() {
        val ada = users.verified("Ada")
        val created = createBond(ada)
        val bondId = bondIdOf(created)
        val inviteId = inviteIdOf(created)
        leave(ada, bondId).status shouldBe 204

        val invited = createInvite(ada, bondId)
        invited.status shouldBe 409
        invited.contentAsString shouldContain "\"code\":\"BOND_ARCHIVED\""

        val revoked = revokeInvite(ada, bondId, inviteId)
        revoked.status shouldBe 409
        revoked.contentAsString shouldContain "\"code\":\"BOND_ARCHIVED\""
    }

    @Test
    fun `leaving frees the slot the three-bond limit counts`() {
        val ada = users.verified("Ada")
        val first = bondIdOf(createBond(ada))
        createBond(ada).status shouldBe 201
        createBond(ada).status shouldBe 201
        createBond(ada).status shouldBe 409

        leave(ada, first).status shouldBe 204

        createBond(ada).status shouldBe 201
    }

    @Test
    fun `blocking is 204 and writes one row per other member, current or left`() {
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        val bondId = bondIdOf(createBond(ada))
        accept(bea, codeOf(getBond(ada, bondId))).status shouldBe 200

        block(ada, bondId).status shouldBe 204

        jdbc.queryForObject("SELECT count(*) FROM blocks", Int::class.java) shouldBe 1
        jdbc.queryForObject("SELECT blocked_user_id FROM blocks", UUID::class.java) shouldBe bea
    }

    @Test
    fun `blocking an already archived bond is allowed, and blocking twice writes one row`() {
        // The one place leave and block differ in what they accept (FR-029,
        // spec §11 decision 8): the other person left first, and blocking them
        // afterwards is the whole point.
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        val bondId = bondIdOf(createBond(ada))
        accept(bea, codeOf(getBond(ada, bondId))).status shouldBe 200
        leave(bea, bondId).status shouldBe 204

        block(ada, bondId).status shouldBe 204
        block(ada, bondId).status shouldBe 204

        jdbc.queryForObject("SELECT count(*) FROM blocks", Int::class.java) shouldBe 1
        jdbc.queryForObject("SELECT count(*) FROM bond_members WHERE left_at IS NULL", Int::class.java) shouldBe 0
    }

    @Test
    fun `blocking a bond nobody else ever joined behaves exactly as leaving`() {
        val ada = users.verified("Ada")
        val bondId = bondIdOf(createBond(ada))

        block(ada, bondId).status shouldBe 204

        jdbc.queryForObject("SELECT count(*) FROM blocks", Int::class.java) shouldBe 0
        getBond(ada, bondId).contentAsString shouldContain "\"status\":\"ARCHIVED\""
    }

    @Test
    fun `a blocked pair cannot pair again, and the refusal names nothing`() {
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        val bondId = bondIdOf(createBond(ada))
        accept(bea, codeOf(getBond(ada, bondId))).status shouldBe 200
        block(ada, bondId).status shouldBe 204

        val fresh = createBond(ada)
        val response = accept(bea, codeOf(fresh))

        response.status shouldBe 404
        response.contentAsString shouldContain "\"code\":\"INVITE_NOT_USABLE\""
    }

    @Test
    fun `no response on the ending paths says block`() {
        // Doc 26 §2.1: the word never reaches the wire, in either direction.
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        val bondId = bondIdOf(createBond(ada))
        accept(bea, codeOf(getBond(ada, bondId))).status shouldBe 200

        val blocked = block(ada, bondId)

        blocked.contentAsString shouldBe ""
        val seen = getBond(bea, bondId).contentAsString + listBonds(bea).contentAsString
        seen.lowercase() shouldNotContain "block"
    }

    // ---- helpers ------------------------------------------------------------

    private fun createBond(userId: UUID): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Us","type":"COUPLE","anchorTimezone":"Africa/Lagos"}"""
            }.andReturn()
            .response

    private fun getBond(
        userId: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc.get("/api/v1/bonds/$bondId") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun listBonds(userId: UUID): MockHttpServletResponse =
        mockMvc.get("/api/v1/bonds") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun leave(
        userId: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc.post("/api/v1/bonds/$bondId/leave") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun block(
        userId: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc.post("/api/v1/bonds/$bondId/block") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun createInvite(
        userId: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc.post("/api/v1/bonds/$bondId/invites") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun revokeInvite(
        userId: UUID,
        bondId: String,
        inviteId: String,
    ): MockHttpServletResponse =
        mockMvc
            .delete("/api/v1/bonds/$bondId/invites/$inviteId") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }
            .andReturn()
            .response

    private fun resolve(
        userId: UUID,
        code: String,
    ): MockHttpServletResponse =
        mockMvc.get("/api/v1/invites/$code") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun accept(
        userId: UUID,
        code: String,
    ): MockHttpServletResponse =
        mockMvc.post("/api/v1/invites/$code/accept") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun bearer(userId: UUID): String = "Bearer ${tokens.issue(userId).token}"

    private fun bondIdOf(response: MockHttpServletResponse): String =
        Regex(""""id":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    private fun inviteIdOf(response: MockHttpServletResponse): String =
        Regex(""""invite":\{"id":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    private fun codeOf(response: MockHttpServletResponse): String =
        Regex(""""code":"([A-Z0-9]{${InviteCode.LENGTH}})"""").find(response.contentAsString)!!.groupValues[1]
}
```

- [ ] **Step 2: Run it and watch it fail.** `./gradlew :modules:bond:test --tests '*BondEndingEndpointTest'` — expected FAIL: every `leave`/`block` call is 405 or 404, because the routes do not exist.

- [ ] **Step 3: Write `EndBond`.** New file `modules/bond/src/main/kotlin/com/moyi/bond/service/EndBond.kt`:

```kotlin
package com.moyi.bond.service

import com.moyi.bond.domain.Block
import com.moyi.bond.domain.Bond
import com.moyi.bond.domain.Membership
import com.moyi.bond.infra.database.BlockStore
import com.moyi.bond.infra.database.BondStore
import com.moyi.bond.infra.database.InviteStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * `POST /bonds/{bondId}/leave` and `POST /bonds/{bondId}/block` (FR-026,
 * FR-029) — the two ways a bond ends.
 *
 * **One service for both, because they are one operation with one difference.**
 * Leave archives the bond; block archives it and records that these two
 * accounts are not to be put in touch again. Everything else — the row lock,
 * the `left_at`, the revoked invite, the silence — is shared, and sharing the
 * code is what makes doc 26 §2.1 true by construction rather than by two
 * implementations happening to agree: *from the other side a block must be
 * indistinguishable from a leave.* `DiscreetExitTest` asserts that on the
 * bytes; this class is the reason it passes.
 *
 * Nobody is notified, by either path (T-09, "the discreet exit"). There is no
 * email, no push, and no field anywhere that says which of the two happened.
 *
 * Both take the bond's row lock before reading, the same lock `AcceptInvite`
 * and `CreateInvite` take. Without it a leave and an accept can interleave so
 * that someone joins a bond as it is being archived — the seat looked free to
 * the accept, because the archiving transaction had not committed.
 */
@Service
internal class EndBond(
    private val bonds: BondStore,
    private val blocks: BlockStore,
    private val invites: InviteStore,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * FR-026. `204`.
     *
     * @throws BondArchivedException the bond has already ended (BR-9) — the
     *   only thing that distinguishes this from [block]
     * @throws BondNotFoundException the caller's membership disappeared
     *   between the guard and here
     */
    @Transactional
    fun leave(membership: Membership) {
        val bond = lockAndLoad(membership)
        if (!bond.isOpen) throw BondArchivedException()
        end(bond.leave(membership.memberId, clock.instant()), bond)
        log.info("A member left bond {}", membership.bondId.value)
    }

    /**
     * FR-029. `204`, and the same `204` as [leave]'s.
     *
     * Permitted on a bond that has already ended, which is the one place the
     * two differ in what they accept (spec §11 decision 8): the other person
     * leaving first is exactly when a block is needed. Repeating it is
     * permitted too, and writes nothing the second time.
     *
     * A block is recorded per *other member, current or left* — someone who
     * walked away is still someone this account does not want to meet again —
     * and the store's insert is `ON CONFLICT DO NOTHING`, so two of these at
     * once are two `204`s rather than one and a 500.
     *
     * The log line says "ended", not "blocked". Doc 18 §5 keeps personal data
     * out of logs, and which of two people blocked the other is as personal as
     * this system gets.
     */
    @Transactional
    fun block(membership: Membership) {
        val bond = lockAndLoad(membership)
        val now = clock.instant()
        bond.members
            .map { it.userId }
            .filter { it != membership.userId }
            .distinct()
            .forEach { other -> blocks.insertIfAbsent(Block(membership.userId, other, bond.id, now)) }
        end(bond.end(membership.memberId, now), bond)
        log.info("A member ended bond {}", membership.bondId.value)
    }

    private fun lockAndLoad(membership: Membership): Bond {
        bonds.lockBond(membership.bondId)
        // Re-read under the lock: the guard's copy was loaded without it, and
        // between the two an accept could have added a member or another leave
        // could have archived the bond.
        return bonds.findByMember(membership.bondId, membership.userId) ?: throw BondNotFoundException()
    }

    /**
     * Writes the ended aggregate and kills any live code — and does neither
     * when nothing changed.
     *
     * The equality check is load-bearing: a repeat block would otherwise write
     * the same values back, and while Hibernate's dirty check would spare the
     * bond row, the invite revocation is an unconditional statement. Doing
     * nothing is also the honest answer, because nothing happened.
     */
    private fun end(
        ended: Bond,
        before: Bond,
    ) {
        if (ended == before) return
        bonds.archive(ended)
        // After the aggregate is written, so the bulk update's automatic flush
        // has the member and bond changes to flush. An invite outliving the
        // bond it belongs to would be a live code into a closed room.
        invites.revokeLiveOf(ended.id, ended.archivedAt ?: clock.instant())
    }
}
```

- [ ] **Step 4: Wire the routes** into `BondsController` — add `private val endBond: EndBond` to the constructor and the two methods after `getBond`:

```kotlin
    /**
     * FR-026. `204` and no body: there is nothing to return, the bond the
     * caller just left is still readable at [getBond], and a response body
     * would be a place for a difference between this and [blockBond] to hide.
     *
     * `POST` rather than `DELETE`: nothing is deleted. The bond becomes a
     * record both members keep (`states.md` §9).
     */
    @PostMapping("/{bondId}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun leaveBond(
        caller: CurrentUser,
        @PathVariable bondId: String,
    ) {
        val membership = guard.membershipOf(UserId(caller.id), bondIdOrNotFound(bondId))
        endBond.leave(membership)
    }

    /**
     * FR-029. `204`, byte-for-byte the same response as [leaveBond]'s, which
     * doc 26 §2.1 requires and `DiscreetExitTest` proves.
     *
     * Unlike leave, it is accepted on a bond that has already ended: blocking
     * someone who left first is what the requirement is for.
     */
    @PostMapping("/{bondId}/block")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun blockBond(
        caller: CurrentUser,
        @PathVariable bondId: String,
    ) {
        val membership = guard.membershipOf(UserId(caller.id), bondIdOrNotFound(bondId))
        endBond.block(membership)
    }
```

  Add `import com.moyi.bond.service.EndBond` to the file's imports.

- [ ] **Step 5: Run the endpoint test and the cross-tenant suite.** `./gradlew :modules:bond:test --tests '*BondEndingEndpointTest' --tests '*BondCrossTenantTest'`

  Expected: `BondEndingEndpointTest` passes except the two archived-write cases for `revokeInvite` (Step 7 fixes that), and **`BondCrossTenantTest` FAILS** with "Bond-scoped routes with no cross-tenant fixture: [POST /api/v1/bonds/{bondId}/leave, POST /api/v1/bonds/{bondId}/block]". That failure is the suite doing its job — read it before fixing it.

- [ ] **Step 6: Add the two fixtures** to `BondCrossTenantTest.fixtures`:

```kotlin
            // Slice B3. Neither takes a body: a non-member must be refused
            // before anything about the bond's state is examined — including,
            // for block, that it is archived, which is the one state that path
            // accepts.
            "POST /api/v1/bonds/{bondId}/leave" to Fixture(),
            "POST /api/v1/bonds/{bondId}/block" to Fixture(),
```

  Run `./gradlew :modules:bond:test --tests '*BondCrossTenantTest'` — expected PASS.

- [ ] **Step 7: Make the archived rule true for revoke as well.** In `RevokeInvite`, take the bond store and check the bond first:

```kotlin
@Service
internal class RevokeInvite(
    private val bonds: BondStore,
    private val invites: InviteStore,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @throws BondArchivedException the bond has ended (BR-9, spec §6.3: every
     *   bond-scoped write on an archived bond is this). Ending a bond revokes
     *   its live invite already, so this is the honest answer rather than the
     *   404 a dead invite would otherwise produce — and it is the same answer
     *   the other write paths give, which is what makes the rule one rule.
     * @throws InviteNotFoundException the invite is already dead, belongs to
     *   another bond, or never existed — one answer for all three, because the
     *   caller is entitled to know about their own bond's invites and nothing
     *   else, and "which of those was it" is not theirs to learn.
     */
    @Transactional
    fun revoke(
        membership: Membership,
        inviteId: InviteId,
    ) {
        val bond = bonds.findByMember(membership.bondId, membership.userId) ?: throw BondNotFoundException()
        if (!bond.isOpen) throw BondArchivedException()
        if (!invites.revoke(membership.bondId, inviteId, clock.instant())) throw InviteNotFoundException()
        log.info("Invite {} revoked in bond {}", inviteId.value, membership.bondId.value)
    }
```

  Add the `BondStore` import. Then add the matching case to `InviteEndpointTest`, next to its other revoke cases:

```kotlin
    @Test
    fun `revoking an invite on a bond that has ended is 409 BOND_ARCHIVED`() {
        // Spec §6.3: an archived bond takes no writes, and this is a write.
        // Ending the bond already revoked the code, so the alternative answer
        // would be a 404 that is true but tells the member less.
        val ada = users.verified("Ada")
        val created = create(ada)
        val bondId = bondIdOf(created.contentAsString)
        val inviteId = Regex(""""invite":\{"id":"([^"]+)"""").find(created.contentAsString)!!.groupValues[1]
        mockMvc
            .post("/api/v1/bonds/$bondId/leave") { header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(ada).token}") }
            .andReturn()
            .response.status shouldBe 204

        val response =
            mockMvc
                .delete("/api/v1/bonds/$bondId/invites/$inviteId") {
                    header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(ada).token}")
                }.andReturn()
                .response

        response.status shouldBe 409
        response.contentAsString shouldContain "\"code\":\"BOND_ARCHIVED\""
    }
```

  Match `create(...)` and `bondIdOf(...)` to whatever `InviteEndpointTest` already calls its helpers, and add the `delete`/`post` imports if the file lacks them.

- [ ] **Step 8: Run the module's whole suite.** `./gradlew :modules:bond:check` — expected PASS, ktlint and detekt included.

- [ ] **Step 9: Commit.**

```bash
git add modules/bond/src/main/kotlin/com/moyi/bond modules/bond/src/test/kotlin/com/moyi/bond/web
git commit -m "feat(bond): leave and block, and an archived bond that takes no writes (FR-026, FR-029)" -m "One service for both, because they are one operation with one difference — which is how doc 26 §2.1's indistinguishability is made structural rather than left to two implementations agreeing. Both take the bond's row lock, so a leave cannot interleave with an accept. Revoking an invite on an archived bond is now 409 BOND_ARCHIVED like every other write there, rather than the 404 a dead invite gave." -m "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GPjbihrTNz2YwDjuwUmysH"
```

---

### Task 4: The discreet-exit test — the point of the slice

**Files:**
- Test: `modules/bond/src/test/kotlin/com/moyi/bond/web/DiscreetExitTest.kt` (new)

**Interfaces:**
- Consumes: the routes and behaviour from Task 3. Produces no production code — if it fails, Task 3 is wrong.

- [ ] **Step 1: Write the test.** New file `modules/bond/src/test/kotlin/com/moyi/bond/web/DiscreetExitTest.kt`:

```kotlin
package com.moyi.bond.web

import com.moyi.bond.domain.InviteCode
import com.moyi.bond.infra.BondTestApplication
import com.moyi.bond.infra.FakeUserDirectory
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.identity.api.UserDirectory
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import javax.sql.DataSource

/**
 * **Doc 26 §2.1, proved on the bytes.** From the other side, a block is
 * indistinguishable from a leave.
 *
 * Doc 26 calls the moment a block is discovered the point where retaliation
 * risk peaks, and T-09 turns that into a requirement: the person who was
 * blocked must not be able to tell that they were *identified as a threat*
 * rather than simply left. So there is no notification, no status of their
 * own, no field, no different wording — and this test is the one that would
 * notice if a later slice added any of them.
 *
 * Two bonds are built identically, by people with the same display names, and
 * then one ends by a leave and the other by a block. Everything the remaining
 * member can see of each is compared: the body of `GET /bonds/{id}`, the body
 * of `GET /bonds`, the status, and the **`ETag`** — which is the row version,
 * and would betray a block that wrote one more time than a leave did.
 *
 * Ids and timestamps differ between two genuinely different bonds, so they are
 * normalised positionally. Nothing else is: `B2`'s `InviteOneAnswerTest` is the
 * model, and the rule there holds here — normalise what the two cannot share,
 * compare the rest byte for byte.
 */
@SpringBootTest(classes = [BondTestApplication::class])
@AutoConfigureMockMvc
internal class DiscreetExitTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
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
    fun `after a leave and after a block, the other member sees exactly the same thing`() {
        val left = endedBond { ada, bondId -> post(ada, "/api/v1/bonds/$bondId/leave") }
        val blocked = endedBond { ada, bondId -> post(ada, "/api/v1/bonds/$bondId/block") }

        withClue("the 204 itself") {
            left.ending.status shouldBe blocked.ending.status
            left.ending.contentAsString shouldBe blocked.ending.contentAsString
        }

        val leftDetail = getBond(left.other, left.bondId)
        val blockedDetail = getBond(blocked.other, blocked.bondId)

        withClue("GET /bonds/{id} as the other member") {
            blockedDetail.status shouldBe leftDetail.status
            // The ETag is the row version: a block that wrote once more than a
            // leave would show up here and nowhere else.
            blockedDetail.getHeader(HttpHeaders.ETAG) shouldBe leftDetail.getHeader(HttpHeaders.ETAG)
            normalise(blockedDetail.contentAsString) shouldBe normalise(leftDetail.contentAsString)
        }

        withClue("GET /bonds as the other member") {
            normalise(listBonds(blocked.other).contentAsString) shouldBe normalise(listBonds(left.other).contentAsString)
        }

        // And the thing being compared is not two empty responses.
        leftDetail.contentAsString shouldContain "\"status\":\"ARCHIVED\""
        jdbc.queryForObject("SELECT count(*) FROM blocks", Int::class.java) shouldBe 1
    }

    @Test
    fun `the blocked member is refused nothing the member who was left is allowed`() {
        // The blocked party still holds a membership row, so the archive stays
        // theirs to read (states.md §9). A 403 or a 404 here would be the
        // disclosure T-09 forbids — and the more dangerous kind, because it
        // would arrive at exactly the person who must not be told.
        val left = endedBond { ada, bondId -> post(ada, "/api/v1/bonds/$bondId/leave") }
        val blocked = endedBond { ada, bondId -> post(ada, "/api/v1/bonds/$bondId/block") }

        val leftWrite = post(left.other, "/api/v1/bonds/${left.bondId}/invites")
        val blockedWrite = post(blocked.other, "/api/v1/bonds/${blocked.bondId}/invites")

        blockedWrite.status shouldBe leftWrite.status
        normalise(blockedWrite.contentAsString) shouldBe normalise(leftWrite.contentAsString)
        leftWrite.status shouldBe 409
    }

    /**
     * A bond built the same way every time: Ada creates it, Bea joins, and
     * then [ending] finishes it. The display names are fixed so that two of
     * these differ only in their ids and timestamps.
     */
    private fun endedBond(ending: (UUID, String) -> MockHttpServletResponse): Ended {
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        val created = createBond(ada)
        val bondId = bondIdOf(created)
        accept(bea, codeOf(created)).status shouldBe 200
        val response = ending(ada, bondId)
        response.status shouldBe 204
        return Ended(bondId = bondId, other = bea, ending = response)
    }

    private data class Ended(
        val bondId: String,
        val other: UUID,
        val ending: MockHttpServletResponse,
    )

    /**
     * Replaces what two different bonds cannot share — uuids and timestamps —
     * with positional placeholders, so that the *n*th id in one body is
     * compared with the *n*th in the other. Anything else that differs is a
     * real difference, which is the whole subject of this file.
     */
    private fun normalise(body: String): String {
        var index = 0
        val ids = mutableMapOf<String, String>()
        return body
            .replace(Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")) { match ->
                ids.getOrPut(match.value) { "uuid-${index++}" }
            }.replace(Regex("""\d{4}-\d{2}-\d{2}T[0-9:.]+Z"""), "timestamp")
            .replace(Regex("""/api/v1/bonds/[^"]+"""), "/api/v1/bonds/path")
    }

    // ---- helpers ------------------------------------------------------------

    private fun createBond(userId: UUID): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Us","type":"COUPLE","anchorTimezone":"Africa/Lagos"}"""
            }.andReturn()
            .response

    private fun post(
        userId: UUID,
        path: String,
    ): MockHttpServletResponse = mockMvc.post(path) { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun getBond(
        userId: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc.get("/api/v1/bonds/$bondId") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun listBonds(userId: UUID): MockHttpServletResponse =
        mockMvc.get("/api/v1/bonds") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun accept(
        userId: UUID,
        code: String,
    ): MockHttpServletResponse =
        mockMvc.post("/api/v1/invites/$code/accept") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun bearer(userId: UUID): String = "Bearer ${tokens.issue(userId).token}"

    private fun bondIdOf(response: MockHttpServletResponse): String =
        Regex(""""id":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    private fun codeOf(response: MockHttpServletResponse): String =
        Regex(""""code":"([A-Z0-9]{${InviteCode.LENGTH}})"""").find(response.contentAsString)!!.groupValues[1]
}
```

- [ ] **Step 2: Run it.** `./gradlew :modules:bond:test --tests '*DiscreetExitTest'` — expected PASS. If the `ETag`s differ, the block path wrote the bond row more often than the leave path did: fix `EndBond`/`BondStore.archive`, not the test.

- [ ] **Step 3: Commit.**

```bash
git add modules/bond/src/test/kotlin/com/moyi/bond/web/DiscreetExitTest.kt
git commit -m "test(bond): the discreet exit, on the bytes and on the ETag (doc 26 §2.1, T-09)" -m "Two bonds built identically, one left and one blocked, and everything the remaining member can see of each compared: status, body, list entry and the ETag — the row version, which is where a block that wrote once more than a leave would show." -m "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GPjbihrTNz2YwDjuwUmysH"
```

---

### Task 5: The race — leaving while somebody is joining

**Files:**
- Test: `modules/bond/src/test/kotlin/com/moyi/bond/web/EndBondRaceTest.kt` (new)

**Interfaces:**
- Consumes: Task 3's routes and the row lock in `EndBond.lockAndLoad`.

- [ ] **Step 1: Write the test.** New file `modules/bond/src/test/kotlin/com/moyi/bond/web/EndBondRaceTest.kt`, using `InviteRaceTest`'s `inParallel` shape:

```kotlin
package com.moyi.bond.web

import com.moyi.bond.domain.InviteCode
import com.moyi.bond.infra.BondTestApplication
import com.moyi.bond.infra.FakeUserDirectory
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeIn
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * What the bond's row lock in `EndBond` is for: **nobody joins a bond as it is
 * being ended.**
 *
 * Without the lock, an accept and a leave are two transactions over READ
 * COMMITTED snapshots. The accept sees `PENDING_MEMBER` with a free seat, the
 * leave sees an open bond, and both commit — leaving a member in an archived
 * bond, which is a person who joined something that no longer exists and is
 * the shape of bug no amount of reading the code reveals. The same lesson as
 * PR #32's refresh-token family and B2's two concurrent invites: fixing a race
 * once does not fix its class.
 */
@SpringBootTest(classes = [BondTestApplication::class])
@AutoConfigureMockMvc
internal class EndBondRaceTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
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
    fun `a leave and an accept at the same moment cannot both win`() {
        val ada = users.verified("Ada")
        val created = createBond(ada)
        val bondId = bondIdOf(created)
        val code = codeOf(created)
        val joiner = users.verified("Joiner")

        val statuses = inParallel(listOf({ leave(ada, bondId) }, { accept(joiner, code) })).map { it.status }

        // Either the accept got in first (200, then a 204 leave archives a
        // bond of two) or the leave did (204, and the code is dead: 404).
        statuses.first() shouldBe 204
        statuses.last() shouldBeIn listOf(200, 404)
        val active = jdbc.queryForObject("SELECT count(*) FROM bond_members WHERE left_at IS NULL", Int::class.java)!!
        val status = jdbc.queryForObject("SELECT status FROM bonds", String::class.java)
        status shouldBe "ARCHIVED"
        // Whoever lost, nobody is left active in an archived bond except a
        // joiner who genuinely got in before it closed.
        active shouldBeIn listOf(0, 1)
        if (statuses.last() == 404) active shouldBe 0
    }

    @Test
    fun `both members leaving at the same moment is two 204s and one archive`() {
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        val created = createBond(ada)
        val bondId = bondIdOf(created)
        accept(bea, codeOf(created)).status shouldBe 200

        val statuses = inParallel(listOf({ leave(ada, bondId) }, { block(bea, bondId) })).map { it.status }

        // The leave and the block serialise on the row lock. Whichever is
        // second finds the bond archived: the leave would be 409, the block is
        // always 204 — so the pair is either two 204s or a 204 and a 409, and
        // never anything else.
        statuses shouldContainExactlyInAnyOrder statuses.sorted()
        statuses.count { it == 204 } shouldBeIn listOf(1, 2)
        statuses.filter { it != 204 }.forEach { it shouldBe 409 }
        jdbc.queryForObject("SELECT count(*) FROM bond_members WHERE left_at IS NULL", Int::class.java) shouldBe 0
        jdbc.queryForObject("SELECT count(*) FROM bonds WHERE archived_at IS NOT NULL", Int::class.java) shouldBe 1
    }

    /** Runs every call on its own thread and releases them together. */
    private fun <T> inParallel(calls: List<() -> T>): List<T> {
        val pool = Executors.newFixedThreadPool(calls.size)
        return try {
            val ready = CountDownLatch(calls.size)
            val go = CountDownLatch(1)
            val futures =
                calls.map { call ->
                    pool.submit(
                        Callable {
                            ready.countDown()
                            go.await(10, TimeUnit.SECONDS)
                            call()
                        },
                    )
                }
            ready.await(10, TimeUnit.SECONDS)
            go.countDown()
            futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun createBond(userId: UUID): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Us","type":"COUPLE","anchorTimezone":"Africa/Lagos"}"""
            }.andReturn()
            .response

    private fun leave(
        userId: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc.post("/api/v1/bonds/$bondId/leave") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun block(
        userId: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc.post("/api/v1/bonds/$bondId/block") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun accept(
        userId: UUID,
        code: String,
    ): MockHttpServletResponse =
        mockMvc.post("/api/v1/invites/$code/accept") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun bearer(userId: UUID): String = "Bearer ${tokens.issue(userId).token}"

    private fun bondIdOf(response: MockHttpServletResponse): String =
        Regex(""""id":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    private fun codeOf(response: MockHttpServletResponse): String =
        Regex(""""code":"([A-Z0-9]{${InviteCode.LENGTH}})"""").find(response.contentAsString)!!.groupValues[1]
}
```

- [ ] **Step 2: Run it.** `./gradlew :modules:bond:test --tests '*EndBondRaceTest'` — expected PASS. The first test's assertions are written so that both orderings are legal but "a member active in an archived bond after a lost accept" is not; if that combination appears, the lock is missing or taken too late.

- [ ] **Step 3: Prove the lock is load-bearing** — delete `bonds.lockBond(membership.bondId)` from `EndBond.lockAndLoad`, run `./gradlew :modules:bond:test --tests '*EndBondRaceTest'` repeatedly (`--rerun-tasks`, three times), and record in the learning log whether it fails. If it never fails, say so honestly there and keep the lock: a race that a test cannot reliably reproduce is still a race, and the argument for the lock is the interleaving, not the observation. **Restore the line.**

- [ ] **Step 4: Commit.**

```bash
git add modules/bond/src/test/kotlin/com/moyi/bond/web/EndBondRaceTest.kt
git commit -m "test(bond): a leave against an accept, and two endings at once" -m "The bond row lock in EndBond is what stops somebody joining a bond as it is being archived. Both orderings are legal; a member left active in an archived bond is not." -m "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GPjbihrTNz2YwDjuwUmysH"
```

---

### Task 6: The contract

**Files:**
- Modify: `contracts/src/main/kotlin/com/moyi/contracts/OpenApiConfiguration.kt`, `app/src/test/kotlin/com/moyi/app/OpenApiContractTest.kt`, `contracts/openapi.json`

**Interfaces:**
- Consumes: operation ids `leaveBond`, `blockBond` (Task 3), and `revokeBondInvite`'s new `409`.

- [ ] **Step 1: Write the failing contract expectations.** In `OpenApiContractTest`, add the two paths to the route list in `it is OpenAPI 3 and knows every route that exists`:

```kotlin
                "/api/v1/bonds/{bondId}/leave",
                "/api/v1/bonds/{bondId}/block",
```

  and add a test next to the other bond ones:

```kotlin
    @Test
    fun `ending a bond is documented as 204, and only leaving can conflict`() {
        // Spec §5.2 rows #8–#9. Leave answers 409 BOND_ARCHIVED; block has no
        // conflict at all, because it is accepted on a bond that has already
        // ended. A generated client that modelled a 409 on block would be
        // modelling a state this API never returns.
        val leave = api.paths["/api/v1/bonds/{bondId}/leave"]!!.post
        val block = api.paths["/api/v1/bonds/{bondId}/block"]!!.post

        leave.responses.keys shouldContainAll listOf("204", "404", "409")
        block.responses.keys shouldContainAll listOf("204", "404")
        block.responses.keys shouldNotContain "409"
    }
```

  Add `io.kotest.matchers.collections.shouldNotContain` to the imports if it is not there.

- [ ] **Step 2: Run it and watch it fail.** `./gradlew :app:test --tests '*OpenApiContractTest'` — expected FAIL: the paths are absent from the document (the app module has not seen the new controller methods until it recompiles, and the `409` rule does not know `leaveBond`).

- [ ] **Step 3: Teach the customizer which operations conflict.** In `OpenApiConfiguration.statusesFor`, extend the set:

```kotlin
            // Operations that answer 409, by id. A list rather than a rule
            // because "can this conflict" is a property of the operation's
            // logic, not of its shape — and a wrong entry here is a generated
            // client modelling a state the API never returns. `blockBond` is
            // deliberately absent: it is accepted on an archived bond, which is
            // the only thing it could have conflicted with.
            if (operation.operationId in CONFLICTING_OPERATIONS) add(HttpStatus.CONFLICT)
```

  and in the companion:

```kotlin
        private val CONFLICTING_OPERATIONS =
            setOf("createBond", "createBondInvite", "accept", "leaveBond", "revokeBondInvite")
```

- [ ] **Step 4: Regenerate and run.**

```bash
./gradlew :app:test --tests '*OpenApiContractTest'   # writes app/build/openapi/openapi.json, then fails on the stale committed file
cp app/build/openapi/openapi.json contracts/openapi.json
./gradlew :app:test --tests '*OpenApiContractTest'   # expected PASS
git diff --stat contracts/openapi.json
```

  Read the diff. It must contain exactly: the two new paths with `204`/`401`/`403`/`404`/`409` (leave) and `204`/`401`/`403`/`404` (block), the added `409` on `delete /bonds/{bondId}/invites/{inviteId}`, and nothing else. No new `ErrorCode`, no changed schema — which is why this slice is not a breaking change.

- [ ] **Step 5: Commit.**

```bash
git add contracts app/src/test/kotlin/com/moyi/app/OpenApiContractTest.kt
git commit -m "feat(contracts): the ending operations in the document (ADR-0024)" -m "Leave documents its 409 BOND_ARCHIVED, block deliberately documents none — it is accepted on a bond that has already ended, so a client modelling a conflict there would be modelling a state the API never returns. Revoking an invite gains the 409 it now answers on an archived bond. No new error code, so no client's sealed class breaks." -m "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GPjbihrTNz2YwDjuwUmysH"
```

---

### Task 7: Verify by running — the whole build, the mutations, and the jar

**Files:**
- Modify: `scripts/smoke.sh`

- [ ] **Step 1: The whole build.** `./gradlew build` — ktlint, detekt, every module's tests on Testcontainers, the JaCoCo 80% gate, the Konsist rules. Expected PASS. If Konsist complains about `EndBond`, read the rule before changing the code: both functions take a `Membership`, which is what it is checking.

- [ ] **Step 2: Add the B3 section to `scripts/smoke.sh`**, after the invites section and before the section that follows it. It uses the script's existing `expect`, `pass`, `fail`, `register_and_login`-style helpers — match the names actually in the file:

```bash
echo; echo "ending — leave and block (FR-026, FR-029, T-09, doc 26 §2.1, ADR-0028)"

# Two pairs built identically, so that the leave and the block can be compared
# from the other side. The display names are the same on purpose: doc 26 §2.1 is
# about what the *other member* can see, and anything that differs between these
# two bodies is something they could use to tell the two apart.
#
# Pair one ends by a leave.
LEAVER_ACCESS="$(new_account "Same Name")"
LEFT_PARTNER_ACCESS="$(new_account "Same Name")"
expect "a bond to leave is 201" 201 '"status":"PENDING_MEMBER"' -- -X POST "$API/bonds" -H "Authorization: Bearer $LEAVER_ACCESS" -d "$(bond_body "Us")"
LEFT_BOND="$(python3 -c "import json,sys; print(json.load(sys.stdin)['id'])" <<<"$LAST_BODY")"
LEFT_CODE="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['code'])" <<<"$LAST_BODY")"
LEFT_INVITE="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['id'])" <<<"$LAST_BODY")"
expect "the partner joins it" 200 '"status":"ACTIVE"' -- -X POST "$API/invites/$LEFT_CODE/accept" -H "Authorization: Bearer $LEFT_PARTNER_ACCESS"
expect "leaving is 204" 204 "" -- -X POST "$API/bonds/$LEFT_BOND/leave" -H "Authorization: Bearer $LEAVER_ACCESS"

# Pair two ends by a block.
BLOCKER_ACCESS="$(new_account "Same Name")"
BLOCKED_ACCESS="$(new_account "Same Name")"
expect "a bond to block in is 201" 201 '"status":"PENDING_MEMBER"' -- -X POST "$API/bonds" -H "Authorization: Bearer $BLOCKER_ACCESS" -d "$(bond_body "Us")"
BLOCK_BOND="$(python3 -c "import json,sys; print(json.load(sys.stdin)['id'])" <<<"$LAST_BODY")"
BLOCK_CODE="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['code'])" <<<"$LAST_BODY")"
expect "the partner joins that one too" 200 '"status":"ACTIVE"' -- -X POST "$API/invites/$BLOCK_CODE/accept" -H "Authorization: Bearer $BLOCKED_ACCESS"
expect "blocking is 204 — the same 204" 204 "" -- -X POST "$API/bonds/$BLOCK_BOND/block" -H "Authorization: Bearer $BLOCKER_ACCESS"

# The archive stays readable for both, including whoever left (states.md §9).
expect "the member who left still reads the bond" 200 '"status":"ARCHIVED"' -- "$API/bonds/$LEFT_BOND" -H "Authorization: Bearer $LEAVER_ACCESS"
expect "…and so does the one who stayed" 200 '"status":"ARCHIVED"' -- "$API/bonds/$LEFT_BOND" -H "Authorization: Bearer $LEFT_PARTNER_ACCESS"
LEFT_VIEW="$LAST_BODY"
LEFT_ETAG="$(curl -sS -o /dev/null -D - "$API/bonds/$LEFT_BOND" -H "Authorization: Bearer $LEFT_PARTNER_ACCESS" | tr -d '\r' | awk 'tolower($1)=="etag:"{print $2}')"
expect "the blocked member still reads theirs" 200 '"status":"ARCHIVED"' -- "$API/bonds/$BLOCK_BOND" -H "Authorization: Bearer $BLOCKED_ACCESS"
BLOCK_VIEW="$LAST_BODY"
BLOCK_ETAG="$(curl -sS -o /dev/null -D - "$API/bonds/$BLOCK_BOND" -H "Authorization: Bearer $BLOCKED_ACCESS" | tr -d '\r' | awk 'tolower($1)=="etag:"{print $2}')"

# Doc 26 §2.1 on the wire: normalise the ids and timestamps two different bonds
# cannot share, and the two views must be the same bytes.
if python3 - "$LEFT_VIEW" "$BLOCK_VIEW" <<'PY'
import re, sys

def normalise(body: str) -> str:
    ids: dict[str, str] = {}
    body = re.sub(
        r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
        lambda m: ids.setdefault(m.group(0), f"uuid-{len(ids)}"),
        body,
    )
    return re.sub(r"\d{4}-\d{2}-\d{2}T[0-9:.]+Z", "timestamp", body)

left, blocked = normalise(sys.argv[1]), normalise(sys.argv[2])
if left != blocked:
    print(left)
    print(blocked)
    sys.exit(1)
PY
then pass "a block is indistinguishable from a leave, byte for byte (doc 26 §2.1)"
else fail "discreet exit" "the two archived bonds do not read the same"; fi
if [[ "$LEFT_ETAG" == "$BLOCK_ETAG" ]]; then pass "…and the ETags match, so the version does not count the blocks"; else fail "discreet exit etag" "leave $LEFT_ETAG vs block $BLOCK_ETAG"; fi
if [[ "$BLOCK_VIEW" != *lock* ]]; then pass "…and no response says block"; else fail "discreet exit wording" "${BLOCK_VIEW:0:200}"; fi

# An archived bond takes no writes (BR-9, spec §6.3).
expect "leaving twice is 409 BOND_ARCHIVED" 409 '"code":"BOND_ARCHIVED"' -- -X POST "$API/bonds/$LEFT_BOND/leave" -H "Authorization: Bearer $LEFT_PARTNER_ACCESS"
expect "inviting into an archived bond is 409" 409 '"code":"BOND_ARCHIVED"' -- -X POST "$API/bonds/$LEFT_BOND/invites" -H "Authorization: Bearer $LEFT_PARTNER_ACCESS"
expect "revoking its invite is 409 too" 409 '"code":"BOND_ARCHIVED"' -- -X DELETE "$API/bonds/$LEFT_BOND/invites/$LEFT_INVITE" -H "Authorization: Bearer $LEAVER_ACCESS"
expect "the code it carried is dead" 404 '"code":"INVITE_NOT_USABLE"' -- "$API/invites/$LEFT_CODE" -H "Authorization: Bearer $LEFT_PARTNER_ACCESS"

# Block is the one write an archived bond accepts, and it repeats.
expect "blocking an archived bond is 204" 204 "" -- -X POST "$API/bonds/$LEFT_BOND/block" -H "Authorization: Bearer $LEFT_PARTNER_ACCESS"
expect "blocking again is 204" 204 "" -- -X POST "$API/bonds/$LEFT_BOND/block" -H "Authorization: Bearer $LEFT_PARTNER_ACCESS"

# FR-029: the pair cannot be put back in touch, whichever of them holds the code.
expect "the blocker starts a new bond" 201 '"code"' -- -X POST "$API/bonds" -H "Authorization: Bearer $BLOCKER_ACCESS" -d "$(bond_body "Again")"
REPAIR_CODE="$(python3 -c "import json,sys; print(json.load(sys.stdin)['invite']['code'])" <<<"$LAST_BODY")"
expect "the blocked account cannot join it: 404 INVITE_NOT_USABLE" 404 '"code":"INVITE_NOT_USABLE"' -- -X POST "$API/invites/$REPAIR_CODE/accept" -H "Authorization: Bearer $BLOCKED_ACCESS"
expect "a stranger cannot leave someone else's bond" 404 '"code":"NOT_FOUND"' -- -X POST "$API/bonds/$LEFT_BOND/leave" -H "Authorization: Bearer $STRANGER_ACCESS"
expect "…nor block in it" 404 '"code":"NOT_FOUND"' -- -X POST "$API/bonds/$LEFT_BOND/block" -H "Authorization: Bearer $STRANGER_ACCESS"

# Leaving frees the slot FR-025 counts.
expect "a third bond for the leaver" 201 "" -- -X POST "$API/bonds" -H "Authorization: Bearer $LEAVER_ACCESS" -d "$(bond_body "Three")"
expect "…and a fourth is refused" 409 '"code":"BOND_LIMIT_REACHED"' -- -X POST "$API/bonds" -H "Authorization: Bearer $LEAVER_ACCESS" -d "$(bond_body "Four")"
if grep -qiE '\bblock' "$MOYI_LOG"; then fail "block in log" "the log says block"; else pass "the log never says who blocked whom"; fi
```

  `new_account` stands for whatever the script already uses to register, verify and sign in an account returning an access token — reuse it; if it does not exist as a function, factor the existing inline sequence into one, which is a smaller change than four copies of it. Adjust the leaver's bond count so the third/fourth probes match what the account has actually created.

- [ ] **Step 3: Run the smoke script against the packaged jar.**

```bash
docker compose up -d
./gradlew :app:bootJar
PORT=18086 scripts/smoke.sh --no-build
```

  Expected: 0 failures, and the probe count above `cdd7ac8`'s 151. Record the new count — the handover and the field guide both carry it.

- [ ] **Step 4: Mutation-check the three load-bearing guards.** For each: break it, run the named test, confirm it fails for the right reason, revert. Record all three in the learning log.

  1. **The archived check.** Delete `if (!bond.isOpen) throw BondArchivedException()` from `EndBond.leave` → `BondEndingEndpointTest.leaving twice is 409 BOND_ARCHIVED` must fail.
  2. **The block's indifference to state.** Change `EndBond.block` to call `bond.leave(...)` instead of `bond.end(...)` → `blocking an already archived bond is allowed…` must fail with an `IllegalStateException` turned 500.
  3. **The guard itself.** In `BondsController.leaveBond`, replace the guard call with a hand-built `Membership` — it will not compile, which *is* the result: `Membership`'s constructor is `BondAccessGuard`'s alone (ADR-0026). Then instead delete the two new fixtures from `BondCrossTenantTest` → the suite must fail naming the uncovered routes. Revert.

- [ ] **Step 5: Commit the smoke script.**

```bash
git add scripts/smoke.sh
git commit -m "test(smoke): the ending section, and the discreet exit on the wire" -m "Two pairs built identically, one left and one blocked, compared after normalising the ids and timestamps two bonds cannot share — including the ETag, and that no body says block. Plus the archived-write refusals, the repeat block, and that a blocked pair cannot be put back in touch." -m "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GPjbihrTNz2YwDjuwUmysH"
```

---

### Task 8: ADR-0028, the corpus, and the PR

**Files:**
- Create: `adr/0028-leave-block-and-the-discreet-exit.md`, and a copy at `../documents/adr/0028-leave-block-and-the-discreet-exit.md`
- Modify: `docs/learning-log.md`, `.claude/HANDOVER.md`; in `../documents`: `06-api-contract.md` §3.3, `00-*` amendment log, `../.claude/skills/moyi-design-system/references/states.md` §9

- [ ] **Step 1: Write ADR-0028** in `adr/`, in the shape ADR-0026 and ADR-0027 use. It carries:
  - **Context** — FR-026, FR-029, T-09, doc 26 §2.1, and spec §11 decisions 8 and 9.
  - **Decision** — one service for both paths, `leave` checking `isOpen` and `block` not; block permitted on an archived bond and idempotent; one `blocks` row per other member ever, written `ON CONFLICT DO NOTHING`; the bond row lock on both; archived means read-only for both members, the leaver included; `409 BOND_ARCHIVED` for every other bond-scoped write, `DELETE …/invites/{id}` now included.
  - **The indistinguishability argument** — what is compared (status, body, list entry, `ETag`) and why the `ETag` is the subtle one: it is the row version, so a block that wrote once more than a leave would be a counter the other side could read.
  - **Consequences** — no new error code, so not a breaking change; B4's writes inherit the archived rule; B5 must cancel live proposals here and gets `PENDING_DELETION` preserved by `Bond.end`; FR-029a (withdraw entries) is Phase 3.
  - **What was not chosen** — a distinct status or code for a blocked bond (rejected: it is the oracle T-09 forbids); deleting the member row on leave (rejected: `states.md` §9 keeps the archive readable); blocking at the account level rather than per bond (rejected: doc 07 §2 scopes it to the bond, and FR-025 allows three).

- [ ] **Step 2: Copy it to the corpus and amend the docs** on the Gratitude repo's `docs/bonds-phase-2` branch (that repo has no remote; commit there, do not push):

```bash
cp adr/0028-leave-block-and-the-discreet-exit.md ../documents/adr/
```

  Then in `../documents`: doc 06 §3.3's leave/block rows (both `204`, leave's `409 BOND_ARCHIVED`, block's absence of one, and that neither notifies); doc 00's amendment log (one line naming ADR-0028 and the date); `states.md` §9 confirmed rather than open — OQ-09 is answered by having built it. Commit in that repo with the same trailer.

- [ ] **Step 3: Write the learning-log entry** in `docs/learning-log.md` — Expected / Reality / Wrong about, per doc 16 §5. It must name honestly: the three mutation results from Task 7 step 4 (including whether deleting the lock actually made `EndBondRaceTest` fail); whether the `ETag` oracle was found by thinking or by the test; and anything the plan got wrong.

- [ ] **Step 4: Update `.claude/HANDOVER.md`** — the date and `main` line, the B3 row in §3's table with the PR number, §4's "next concrete step" rewritten for **B4 settings** (`PATCH /bonds/{bondId}`, the two member-settings endpoints, `If-Match`/`ETag`, ADR-0029), the new probe count and test count (counted, not estimated), and any gotcha this slice added to §5. Remove the duplicated gotcha bullets at the end of §5 while there — the list repeats itself from "**The bond module's test context**" onward.

- [ ] **Step 5: Final verification before the PR.**

```bash
./gradlew build
git log --oneline main..HEAD
git status
```

  Expected: build green, six or seven commits, a clean tree.

- [ ] **Step 6: Push and open the PR — and do not merge it.**

```bash
git push -u origin feat/bond-ending
```

  The body follows `.github/PULL_REQUEST_TEMPLATE.md` with PR #33 as the model: what and why; the **concept brief** (the pattern here is the *capability-plus-idempotent-transition*, and the interview question it answers is "how do you make two operations indistinguishable to a third party, and how do you test that?" — name the market gap too: this project teaches neither event sourcing nor outbox patterns, which a production block would often use to fan out); the **Figma alignment** table (#8 → Bond settings › Leave (confirm), exists; #9 → Bond settings › Block confirm, exists); the ten hostile-reviewer questions answered honestly — including "what does the `ETag` tell the other side?" and "what happens if both members leave at the same instant?"; the DoD checklist; and **no `breaking-api-change` label** with a sentence saying why (no new `ErrorCode`, additive operations) plus the oasdiff job's actual verdict.

---

## Done when

1. `./gradlew build` green, `scripts/smoke.sh` green with the B3 section and its new probe count recorded.
2. A test proves that after a leave and after a block, the other member's `GET /bonds/{id}` and `GET /bonds` are byte-identical — and so are their `ETag`s.
3. Both new routes are in `BondCrossTenantTest.fixtures`, added *after* watching the suite fail without them.
4. Each guard verified by breaking it on purpose, with the result written in the learning log.
5. ADR-0028 here and in `../documents/adr/`; doc 06 §3.3, doc 00's log and `states.md` §9 amended on `docs/bonds-phase-2`; a learning-log entry; `.claude/HANDOVER.md` current.
6. PR opened with the template, the concept brief and the Figma table — and **not merged**.
