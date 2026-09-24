# Slice B2 — Invites (milestone M2): Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Two accounts can pair through a six-character code, and every way a code can fail to work is one indistinguishable answer.

**Architecture:** Four endpoints on top of B1's aggregate and guard. Invite creation and revocation are bond-scoped and go through `BondAccessGuard`; resolve and accept are keyed on the code itself and are authenticated but not bond-scoped. Acceptance is a single transaction under a row lock, ending in a compare-and-set on the invite so a lost race is the same 404 as a spent code.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Spring Data JPA, Postgres 18 + Valkey via Testcontainers, Bucket4j, kotest, Konsist, springdoc.

**Spec:** `docs/superpowers/specs/2026-09-24-bonds-and-invites-design.md` — §3.3 (codes), §5.2 rows #4–#7, §5.3, §5.4, §6.2 (the join order), §9 (B2 row).

**Depends on:** slice B1 (PR #37). This branch is stacked on `feat/bond-core`; rebase if B1 changes.

## Global Constraints

Everything in B1's plan still binds. Added for this slice:

- **FR-024 is the hard rule.** Expired, revoked, already used, bond full, never existed, and blocked are **one** response: `404` with `code: INVITE_NOT_USABLE` and a body that is byte-identical across all six, `instance` aside. A test asserts that directly.
- Conditions about the **caller's own account** may be specific, because they disclose nothing about any bond: `403 EMAIL_NOT_VERIFIED`, `409 ALREADY_MEMBER`, `409 BOND_LIMIT_REACHED`.
- **One live invite per bond**: creating one revokes the outstanding one in the same transaction (doc 06 §3.3, `states.md` §2 "Replaced").
- Invite TTL 7 days (FR-023), single use, revocable. Codes are `InviteCode`, already built in B1.
- New buckets (doc 06 §4): `invite:create` 10/day per user; `invite:lookup` + `invite:accept` 20/hour per IP **combined**; plus a per-user lookup bucket from spec §5.4.
- New error codes: `BOND_FULL`, `BOND_ARCHIVED`, `ALREADY_MEMBER`, `INVITE_NOT_USABLE`.
- **No new migration.** V9 already carries `bond_invites` and `blocks`.
- Commit trailer:
  ```
  Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01GPjbihrTNz2YwDjuwUmysH
  ```
- Work from `/Users/danzucker/Desktop/Learning/project_base_learning/Gratitude/moyi-backend/.worktrees/feat-bond-invites`.

---

## File structure

**common:security** — `RateLimitBucket.kt` (three entries), `RateLimited.kt` (vararg + USER), `RateLimitInterceptor.kt` (consume USER buckets), `RateLimitInterceptorTest.kt`.

**common:web** — `ErrorCode.kt` (four entries).

**bond domain** — `Bond.kt` (`accept`), `Invite.kt` (unchanged), `Block.kt` (new).

**bond infra** — `BlockEntity.kt`, `BondRepositories.kt` (+`BlockRepository`, invite CAS queries, bond lock), `BondMappers.kt` (+block, +`applyTo`), `BondStore.kt` (+invite and membership operations), `BlockStore.kt`.

**bond service** — `BondErrors.kt` (+four), `CreateInvite.kt`, `RevokeInvite.kt`, `ResolveInvite.kt`, `AcceptInvite.kt`.

**bond web** — `BondInvitesController.kt`, `InvitesController.kt`, `InviteResponses.kt`.

**bond test** — `InviteEndpointTest.kt`, `InviteOneAnswerTest.kt`, `InviteRaceTest.kt`, `InviteRateLimitTest.kt`, `BondCrossTenantTest.kt` (+2 fixtures), `BondPersistenceTest.kt` (+invite/block cases).

**app / contracts / scripts / docs** — `OpenApiContractTest.kt`, `contracts/openapi.json`, `scripts/smoke.sh`, `adr/0027-invite-codes-and-the-one-answer.md`, `docs/learning-log.md`, `.claude/HANDOVER.md`, corpus on `docs/bonds-phase-2`.

---

### Task 1: `@RateLimited` learns per-user buckets, and the three invite buckets exist

**Files:**
- Modify: `common/security/src/main/kotlin/com/moyi/common/security/ratelimit/RateLimitBucket.kt`, `RateLimited.kt`, `RateLimitInterceptor.kt`
- Test: `common/security/src/test/kotlin/com/moyi/common/security/ratelimit/RateLimitInterceptorTest.kt`

**Interfaces:**
- Produces: `RateLimitBucket.INVITE_CREATE_USER` (USER, 10/day), `INVITE_LOOKUP_USER` (USER, 10/hour), `INVITE_CODE_IP` (IP, 20/hour); `@RateLimited(vararg buckets: RateLimitBucket)`.

- [ ] **Step 1: Write the failing tests.** In `RateLimitInterceptorTest`, add: a handler annotated with a USER bucket consumes it keyed on the token's subject; a handler annotated with two buckets consumes both; an EMAIL bucket on a handler is still refused with `IllegalStateException`; the headers describe whichever of the consumed buckets has fewest tokens left.

- [ ] **Step 2: Run to see them fail.** `./gradlew :common:security:test --tests '*RateLimitInterceptorTest'` — expected FAIL (no such buckets; annotation takes one argument).

- [ ] **Step 3: Add the buckets** to `RateLimitBucket`, each with the doc 06 §4 line as its KDoc:

```kotlin
    /** FR-022: 10 invites a day per user. Creating one revokes the last, so this bounds churn, not concurrency. */
    INVITE_CREATE_USER("invite:create:user", Subject.USER, 10, Duration.ofDays(1)),

    /** Spec §5.4: 10 resolves an hour per user, above the per-IP bucket both endpoints share. */
    INVITE_LOOKUP_USER("invite:lookup:user", Subject.USER, 10, Duration.ofHours(1)),

    /**
     * Doc 06 §4: "`invite:lookup` + `invite:accept` (per IP) 20 / hour combined
     * — brute-forcing 6-char codes". One bucket shared by both endpoints, which
     * is what "combined" means and what makes the 7.3e8 code space enough in
     * practice (T-06).
     */
    INVITE_CODE_IP("invite:code:ip", Subject.IP, 20, Duration.ofHours(1)),
```

- [ ] **Step 4: Widen the annotation** to `@Target(FUNCTION) annotation class RateLimited(vararg val buckets: RateLimitBucket)`, KDoc explaining that IP and USER may both be named (both are known before the handler runs) and EMAIL may not (it is in the body).

- [ ] **Step 5: Teach the interceptor.** Replace the single-annotation branch with a loop over `annotation.buckets`, each consumed with the key its subject implies: `Subject.IP` → `addresses.resolve(request).rateLimitKey`, `Subject.USER` → the token subject (`check` that there is one), `Subject.EMAIL` → `error(...)` as today. Collect every `Allowed` alongside the per-user one and keep the existing `minByOrNull { it.remaining }` header rule.

- [ ] **Step 6: Run the tests.** Expected PASS. Then `./gradlew :common:security:check` and `:modules:identity:test` (the existing `@RateLimited` call sites must still compile and behave).

- [ ] **Step 7: Commit.**
```bash
git add common/security
git commit -m "feat(security): @RateLimited accepts per-user buckets, and several at once" -m "The invite endpoints need a per-user and a per-IP bucket on one handler, and both are known before the body is read. An EMAIL bucket is still refused there, because it is not." -m "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GPjbihrTNz2YwDjuwUmysH"
```

---

### Task 2: The error codes, and the domain's accept

**Files:**
- Modify: `common/web/.../ErrorCode.kt`, `modules/bond/.../domain/Bond.kt`
- Create: `modules/bond/.../domain/Block.kt`
- Test: `modules/bond/src/test/kotlin/com/moyi/bond/domain/BondTest.kt`

**Interfaces:**
- Produces: `ErrorCode.{BOND_FULL, BOND_ARCHIVED, ALREADY_MEMBER, INVITE_NOT_USABLE}`; `Bond.accept(member: Member, now: Instant): Bond`; `Bond.hasRoom`, `Bond.isOpen`; `data class Block(blockerUserId, blockedUserId, bondId, createdAt)`.

- [ ] **Step 1: Write the failing tests** in `BondTest`:

```kotlin
    @Test
    fun `accepting a member makes the bond active`() {
        val bond = create()
        val joiner = Member.member(MemberId(UUID.randomUUID()), bond.id, UserId(UUID.randomUUID()), lagos, now)

        val active = bond.accept(joiner, now)

        active.status shouldBe BondStatus.ACTIVE
        active.activeMembers shouldHaveSize 2
        active.memberOf(joiner.userId)?.role shouldBe MemberRole.MEMBER
    }

    @Test
    fun `only a bond waiting for a member has room`() {
        val bond = create()
        bond.hasRoom shouldBe true
        bond.isOpen shouldBe true

        val full = bond.accept(Member.member(MemberId(UUID.randomUUID()), bond.id, UserId(UUID.randomUUID()), lagos, now), now)
        full.hasRoom shouldBe false
        shouldThrow<IllegalStateException> {
            full.accept(Member.member(MemberId(UUID.randomUUID()), bond.id, UserId(UUID.randomUUID()), lagos, now), now)
        }

        val archived = bond.copy(status = BondStatus.ARCHIVED, archivedAt = now)
        archived.hasRoom shouldBe false
        archived.isOpen shouldBe false
        shouldThrow<IllegalStateException> {
            archived.accept(Member.member(MemberId(UUID.randomUUID()), bond.id, UserId(UUID.randomUUID()), lagos, now), now)
        }
    }
```

- [ ] **Step 2: Run to see them fail.** `./gradlew :modules:bond:test --tests '*BondTest'`.

- [ ] **Step 3: Implement.** In `Bond`:

```kotlin
    /** A bond that can still take its second member: waiting, and with a seat free (I-1). */
    val hasRoom: Boolean get() = status == BondStatus.PENDING_MEMBER && activeMembers.size < maxMembers

    /** Accepts writes at all — not archived, not being deleted (I-5, BR-9). */
    val isOpen: Boolean get() = status == BondStatus.PENDING_MEMBER || status == BondStatus.ACTIVE

    /**
     * The second member joins (FR-022). The bond becomes `ACTIVE`, which is
     * what starts Bond-days: doc 04 §8.3a suspends day evaluation while a bond
     * is `PENDING_MEMBER`, so the streak begins here and not at creation.
     *
     * `check`, not `require`: a full or archived bond is a state conflict, not
     * a bad argument, and the service turns it into the one 404 that tells a
     * stranger nothing (FR-024).
     */
    fun accept(member: Member, now: Instant): Bond {
        check(hasRoom) { "a bond that is not waiting for a member cannot accept one" }
        return copy(status = BondStatus.ACTIVE, members = members + member)
    }
```
Add the four `ErrorCode` entries, each with KDoc naming its requirement — and for `INVITE_NOT_USABLE`, that it is deliberately one code for six causes (FR-024). Add `Block.kt` as a plain data class with the four fields.

- [ ] **Step 4: Run.** Expected PASS, plus `./gradlew :modules:bond:check`.

- [ ] **Step 5: Commit.**

---

### Task 3: Persistence — invite lifecycle, membership insertion, blocks

**Files:**
- Modify: `modules/bond/.../infra/database/BondRepositories.kt`, `BondMappers.kt`, `BondStore.kt`
- Create: `modules/bond/.../infra/database/BlockEntity.kt`, `BlockStore.kt`
- Test: `modules/bond/src/test/kotlin/com/moyi/bond/infra/database/BondPersistenceTest.kt`

**Interfaces:**
- Produces on `BondStore`: `findLiveInviteByCode(code, now): Invite?`, `lockBond(bondId)`, `revokeLiveInvitesOf(bondId, now): Int`, `revokeInvite(bondId, inviteId, now): Boolean`, `consumeInvite(inviteId, userId, now): Boolean`, `addMember(bond: Bond, member: Member)`, `memberUserIdsEverOf(bondId): List<UserId>`, `insertInvite(invite)`.
- Produces on `BlockStore`: `existsBetween(userId: UserId, others: Collection<UserId>): Boolean`.

- [ ] **Step 1: Write the failing tests** in `BondPersistenceTest`. Cover: a live invite is found by code and an expired, revoked or used one is not; `revokeLiveInvitesOf` revokes only live ones and reports how many; `revokeInvite` is scoped to its bond and returns false for another bond's id, an unknown id, or an already-dead invite; `consumeInvite` returns true once and false on a second call with the same id (the CAS); `addMember` writes the member row and moves the bond to `ACTIVE` in one transaction; `memberUserIdsEverOf` includes members who left; `BlockStore.existsBetween` is true in **both** directions and false otherwise.

- [ ] **Step 2: Run to see them fail.**

- [ ] **Step 3: Implement.** In `BondInviteRepository`, add `findByCode`, plus two `@Modifying` compare-and-set queries written as SQL predicates rather than read-then-write, each with KDoc naming the race it closes:

```kotlin
    /** @return the number of live invites revoked — "creating one revokes the outstanding one" (doc 06 §3.3). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE BondInviteEntity i SET i.revokedAt = :now
         WHERE i.bondId = :bondId AND i.usedAt IS NULL AND i.revokedAt IS NULL AND i.expiresAt > :now
    """)
    fun revokeLiveOf(bondId: UUID, now: Instant): Int

    /**
     * Spends an invite, if it is still live. A compare-and-set in SQL: two
     * requests presenting the same code both read it as live, and only one
     * gets `1` back, because the database serialises the two UPDATEs and the
     * second finds the predicate no longer true. A read-then-write in Kotlin
     * has no such guarantee and would bond two people to a one-seat bond.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE BondInviteEntity i SET i.usedAt = :now, i.usedByUserId = :userId
         WHERE i.id = :id AND i.usedAt IS NULL AND i.revokedAt IS NULL AND i.expiresAt > :now
    """)
    fun consume(id: UUID, userId: UUID, now: Instant): Int
```
and a bond-scoped revoke for the DELETE endpoint (`i.id = :id AND i.bondId = :bondId AND …`). Add `lockBond` to `BondRepository` (`SELECT 1 FROM bonds WHERE id = :id FOR UPDATE`, native). Add `BlockRepository.existsByBlockerUserIdAndBlockedUserIdIn` **and** the reverse, or one `@Query` with an `OR` — the KDoc must say why both directions matter (FR-029: a block prevents any future invitation between two accounts, whichever of them holds the code). `addMember` saves the member row and applies the bond's new status through a new `Bond.applyTo(entity)` mapper, which does **not** copy `id` or `createdAt`.

- [ ] **Step 4: Run.** Expected PASS, plus `./gradlew :modules:bond:check`.

- [ ] **Step 5: Mutation check.** Replace `consume`'s CAS with a read-then-write and watch `InviteRaceTest` (Task 6) fail; revert. Record for the PR.

- [ ] **Step 6: Commit.**

---

### Task 4: Create and revoke an invite (bond-scoped, behind the guard)

**Files:**
- Create: `modules/bond/.../service/CreateInvite.kt`, `RevokeInvite.kt`, `modules/bond/.../web/BondInvitesController.kt`, `InviteResponses.kt`
- Modify: `modules/bond/.../service/BondErrors.kt`
- Test: `modules/bond/src/test/kotlin/com/moyi/bond/web/InviteEndpointTest.kt`, `BondCrossTenantTest.kt`

**Interfaces:**
- Produces: `CreateInvite.forBond(membership): InviteView`, `RevokeInvite.revoke(membership, inviteId: UUID)`, `InviteDetailResponse`.
- Routes: `POST /api/v1/bonds/{bondId}/invites` → `201`; `DELETE /api/v1/bonds/{bondId}/invites/{inviteId}` → `204`.

- [ ] **Step 1: Write the failing endpoint tests.** A member creates an invite and gets a new code; the previous code stops working and the new one appears on `GET /bonds/{id}`; creating on a full bond is `409 BOND_FULL`; on an archived bond `409 BOND_ARCHIVED`; a member who has left cannot create one; revoking is `204` and the code then resolves to the one 404; revoking another bond's invite id, an unknown id, or an already-revoked one is `404`; a non-member gets `404` on both (that is also the cross-tenant suite's job).

- [ ] **Step 2: Run to see them fail.**

- [ ] **Step 3: Implement.** `CreateInvite` takes a `Membership`, loads the bond, refuses `!membership.left` violations and `!bond.isOpen` (`BondArchivedException`) and `!bond.hasRoom` (`BondFullException`), then in one transaction revokes the live invites and inserts a new one. `RevokeInvite` takes a `Membership` and an invite id and returns nothing, throwing `InviteNotFoundException : NotFoundException` when the scoped update changes no row. The controller is a separate `@RestController` at `/api/v1/bonds/{bondId}/invites`, guard first, `@RateLimited(RateLimitBucket.INVITE_CREATE_USER)` on the POST.

- [ ] **Step 4: Add both routes to `BondCrossTenantTest.fixtures`** — `POST /api/v1/bonds/{bondId}/invites` with an empty body, `DELETE /api/v1/bonds/{bondId}/invites/{inviteId}`. The suite fails without them, which is the point.

- [ ] **Step 5: Run.** Expected PASS including the cross-tenant suite.

- [ ] **Step 6: Commit.**

---

### Task 5: Resolve and accept a code

**Files:**
- Create: `modules/bond/.../service/ResolveInvite.kt`, `AcceptInvite.kt`, `modules/bond/.../web/InvitesController.kt`, `modules/bond/.../infra/database/BlockStore.kt` (if not in Task 3)
- Test: `modules/bond/src/test/kotlin/com/moyi/bond/web/InviteEndpointTest.kt`

**Interfaces:**
- Produces: `ResolveInvite.resolve(code: InviteCode): InvitePreview`, `AcceptInvite.accept(caller: UserId, code: InviteCode): BondView`.
- Routes: `GET /api/v1/invites/{code}` → `200 {bondName, bondType, inviterDisplayName}`; `POST /api/v1/invites/{code}/accept` → `200` BondResponse.

- [ ] **Step 1: Write the failing tests.** The happy path: Ada creates, Bob resolves and sees her name and the bond's, accepts, and both now see an `ACTIVE` bond with two members and no live invite. Then each refusal: unverified caller `403`; a malformed code `422` naming `code`; already a member `409 ALREADY_MEMBER`; at the three-bond limit `409 BOND_LIMIT_REACHED`; and every unusable-code case `404`.

- [ ] **Step 2: Run to see them fail.**

- [ ] **Step 3: Implement `AcceptInvite` in exactly the spec's §6.2 order**, in one transaction, with a KDoc that gives the order and says why it is that order:

1. caller verified (`UserDirectory`) → `EmailNotVerifiedException`
2. the code's shape is already guaranteed by `InviteCode.parse` at the edge
3. live invite by code, then `lockBond(invite.bondId)` → none → `InviteNotUsableException`
4. caller already an active member → `AlreadyMemberException`
5. caller at `MAX_OPEN_BONDS_PER_USER` (under the per-user bond lock) → `BondLimitReachedException`
6. `!bond.hasRoom` → `InviteNotUsableException`
7. a block in either direction with anyone who has ever been a member → `InviteNotUsableException`
8. `consumeInvite` CAS; `false` → `InviteNotUsableException`; then `addMember(bond.accept(member, now), member)`

`ResolveInvite` applies 3 and 6 only, and returns `bondName`, `type` and the **inviter's** display name — the member who created the invite, not the bond's creator, though today they are the same person.

- [ ] **Step 4: The controller.** `/api/v1/invites`, both methods authenticated (neither is in `PUBLIC_AUTH_ENDPOINTS` — an unauthenticated resolve is the oracle T-06 closes). `GET` carries `@RateLimited(INVITE_LOOKUP_USER, INVITE_CODE_IP)`; `POST` carries `@RateLimited(INVITE_CODE_IP)`. The code is parsed with `InviteCode.parse`, and a malformed one is a `422` naming the field — not a 404, because it is a fact about what was typed, not about any bond.

- [ ] **Step 5: Run.** Expected PASS. `./gradlew :modules:bond:check`.

- [ ] **Step 6: Commit.**

---

### Task 6: The three tests that are the point of this slice

**Files:**
- Create: `modules/bond/src/test/kotlin/com/moyi/bond/web/InviteOneAnswerTest.kt`, `InviteRaceTest.kt`, `InviteRateLimitTest.kt`

- [ ] **Step 1: `InviteOneAnswerTest`.** Build all six unusable states — expired, revoked, already used, bond full, never existed, blocked — call `GET /invites/{code}` and `POST /invites/{code}/accept` on each, and assert every response is `404` with a body byte-identical to the others once `instance` is normalised. FR-024 and `states.md` §2's "one screen, one string, four causes" are the citation; this test is what makes them true.

- [ ] **Step 2: `InviteRaceTest`.** Two threads accept one code against a two-seat bond that already has its creator: exactly one gets `200`, the other `404`, and the database ends with one member row for the joiner. Use a `CountDownLatch` and the real HTTP stack. Then the same for two creates racing: the bond ends with exactly one live invite.

- [ ] **Step 3: `InviteRateLimitTest`** in a context with `moyi.security.rate-limit.enabled=true` (the `RateLimitingEndpointTest` precedent): the eleventh create in a day is `429` with `Retry-After`; the twenty-first resolve-or-accept from one address is `429`; and a resolve and an accept **share** the per-IP bucket, which is the "combined" in doc 06 §4 and the T-06 bound.

- [ ] **Step 4: Run all three.** Expected PASS.

- [ ] **Step 5: Mutation check.** Point `INVITE_CODE_IP` at the accept endpoint only and watch the combined-bucket assertion fail; revert. Record for the PR.

- [ ] **Step 6: Commit.**

---

### Task 7: Contract, smoke, ADR-0027, corpus, PR

- [ ] **Step 1: Contract.** Add the four paths to `OpenApiContractTest`'s route assertion; regenerate `contracts/openapi.json`; read the diff; confirm only additions. Four new error codes means the `breaking-api-change` label again (ADR-0024's amendment) — expect it and say so in the PR.

- [ ] **Step 2: Smoke.** Add the M2 story to `scripts/smoke.sh`: Ada creates a bond, a second account registers and verifies, resolves the code and sees Ada's name, accepts, both read an `ACTIVE` bond with two members; the code is then dead; a third account gets the same 404 for that code as for one never issued; and a fourth probe proves the per-IP bucket is shared between resolve and accept. Run it and record the count.

- [ ] **Step 3: ADR-0027 — invite codes and the one answer.** Context: FR-022/023/024 and T-06. Decision: the six causes collapse to one 404 and doc 06 §3.3's `410`/`409 full`/`valid` field are superseded; the caller's own state stays specific; one live invite per bond; the CAS rather than a read-then-write; blocks checked in both directions against everyone who has ever been a member; resolve shows the inviter's name because the caller already holds a valid code and is authenticated, which is the same bar doc 06 sets for the endpoint. Consequences, alternatives (a `410` for expired — rejected, it confirms the code was once real; a per-bond lock instead of the CAS — rejected, the CAS is the narrower guarantee), revisit-when.

- [ ] **Step 4: Learning log**, honest about what was wrong, not only what was built.

- [ ] **Step 5: Corpus** on Gratitude branch `docs/bonds-phase-2`: copy ADR-0027; doc 06 §3.3's four rows amended (the one 404, the `valid` field dropped, the rate-limit note); doc 09 T-06's mitigations updated to name the combined bucket as built; doc 00's amendment log; `states.md` §2 confirmed against what shipped.

- [ ] **Step 6: HANDOVER.md**, then open the PR against `feat/bond-core` (or `main` if B1 has merged by then) with the full template, the concept brief, the Figma table and the ten questions. **Do not merge.**

---

## Self-review

**Spec coverage:** §5.2 #4 → Task 4; #5 → Task 4; #6 → Task 5; #7 → Task 5. §5.3's four codes → Task 2. §5.4's three buckets → Tasks 1 and 6. §6.2's eight steps → Task 5 Step 3, each one named. §3.3's "one live invite" → Tasks 3 and 4. FR-024 → Task 6 Step 1. T-06 → Tasks 1, 6 and the ADR.

**Placeholders:** none. Task 3 and 6 describe test cases rather than printing every assertion, which is a deliberate compression — the shapes are given and the spec has the rules; if a step turns out ambiguous while executing, the spec is the tie-breaker.

**Type consistency:** `Membership` (B1) is the argument to both bond-scoped invite services; `InviteCode` and `UserId` (B1) are the arguments to the code-scoped ones; `BondView`/`InviteView` (B1) are what the accept path returns, so `BondResponse.from` is reused unchanged.
