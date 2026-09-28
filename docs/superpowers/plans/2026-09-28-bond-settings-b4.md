# Slice B4 — Settings (`ETag` / `If-Match`): Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A member can change a bond's settings without silently overwriting a change the other member made a second earlier, and can change their own reminder settings without either of them seeing the other's.

**Architecture:** Three endpoints on B1's guard. `PATCH /bonds/{bondId}` is conditional: the `ETag` every bond response already carries is the row `@Version`, and `If-Match` is compared against it before the write and enforced again by Hibernate at flush — so a lost update is a `412`, never a silent overwrite. The two member-settings endpoints are per-row, unconditional, and expose only the caller's own five fields.

**Tech Stack:** Kotlin 2.4, Spring Boot 4.1, Spring Data JPA, Postgres 18 + Valkey via Testcontainers, kotest, Konsist, springdoc.

**Spec:** `docs/superpowers/specs/2026-09-24-bonds-and-invites-design.md` — §5.2 rows #10–#12, §5.3, §6.5, §9 (B4 row), §11 decisions 3 and 10. Doc 06 §1 ("`ETag` on mutable resources; `If-Match` required for updates to Bond settings"), `states.md` §8.

**Depends on:** B1 (#37) and B2 (#38), merged; **B3 (#39), open and unmerged** — this branch is stacked on `feat/bond-ending` because it uses `Bond.isOpen` for the archived rule and `Member.applyTo` as the settings write path. Rebase onto `main` once #39 merges.

## Global Constraints

Everything in B1–B3's plans still binds. Added or sharpened for this slice:

- **`If-Match` is required on `PATCH /bonds/{bondId}`** (doc 06 §1). Absent → `428 PRECONDITION_REQUIRED`. Present and not equal to the current version → `412 PRECONDITION_FAILED`. Present and equal → the write proceeds and the response carries the **new** `ETag` (the B2 lesson: never return a version the client cannot use).
- **Both new codes are new `ErrorCode` values**, so **B4 is a breaking change and carries the `breaking-api-change` label** (ADR-0024's 2026-09-24 amendment). Do not suppress the oasdiff check.
- **`type` is patchable unilaterally** (ADR-0013 §8, spec §11 decision 3, `states.md` §8: "Changeable unilaterally, no consent ceremony"). It does **not** touch `max_members`: that column is the bond's own property (FR-021), every v1 type seats two, and a future type with a different number needs a rule of its own.
- **The anchor timezone is NOT patchable here.** `PATCH /bonds/{bondId}/timezone` is B5's, two-party, once per 30 days (FR-027). A `PATCH /bonds/{bondId}` body naming `anchorTimezone` must be refused, not silently ignored.
- **Member settings are the caller's own, always** (`states.md` §8: *"Never show the partner's notification settings or quiet hours"*). The route is `/members/me/settings` with no member id in it — there is no way to name another member, which is the structural half of that rule.
- **`PUT` is a full replacement** of all five fields (spec §5.2 row 12). An absent field is `null`, which means cleared; that is what `PUT` means, and it is why this is not a second `PATCH`.
- **Every write is refused on an archived bond** with `409 BOND_ARCHIVED`, from `Bond.isOpen` — B3's `EndBond` and `RevokeInvite` are the precedent (ADR-0028 decision 4).
- **Three new routes go in `BondCrossTenantTest.fixtures`** — and the `PATCH` fixture needs a body *and* an `If-Match` header, or it is refused at `428` before the guard is the thing under test. **Let the suite fail once first.**
- Commit trailer:
  ```
  Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01GPjbihrTNz2YwDjuwUmysH
  ```
- Work from `/Users/danzucker/Desktop/Learning/project_base_learning/Gratitude/moyi-backend/.worktrees/feat-bond-settings` (branch `feat/bond-settings`, based on `feat/bond-ending`).
- **Not in this slice:** the timezone proposal and the deletion request (B5); `reminderTimezone`'s effect on actual notifications (Phase 4 — this slice stores it).

---

## File structure

**common:web** — `ErrorCode.kt` (+2), `Preconditions.kt` (new: `IfMatch` parsing plus `PreconditionRequiredException` and `PreconditionFailedException` — protocol concerns, not bond ones, and B5 and Phase 3 will reuse them).

**bond domain** — `Bond.kt`: `update(changes: BondSettings): Bond`. `Member.kt`: `withSettings(settings: MemberSettings): Member`, plus the quiet-hours invariant.

**bond infra** — `BondStore.kt`: `update(bond)` (saving with a **flush**, so the optimistic-lock failure is thrown where it can be caught) and `updateMember(member)`. `BondRepositories.kt`: `saveAndFlush`.

**bond service** — `UpdateBond.kt` (new), `MemberSettingsService.kt` (new, `of` + `replace`), `BondErrors.kt` unchanged (the two precondition failures live in `common:web`).

**bond web** — `PatchBondRequest.kt` (new), `MemberSettingsRequest.kt` + `MemberSettingsResponse.kt` (new), `BondsController.kt` (+`patchBond`), `BondMemberSettingsController.kt` (new).

**bond test** — `BondTest.kt` (domain), `BondPersistenceTest.kt` (version bump, stale write), `BondSettingsEndpointTest.kt` (new — every §5.2 status for #10–#12), `BondSettingsRaceTest.kt` (new — two concurrent patches on one `ETag`), `IfMatchTest.kt` in `common:web` (parsing), `BondCrossTenantTest.kt` (+3 fixtures).

**contracts / app** — `OpenApiConfiguration.kt` (the `409`/`412`/`428` rules), `OpenApiContractTest.kt` (+3 paths, +1 test), `contracts/openapi.json`.

**scripts / docs** — `scripts/smoke.sh` (a B4 section), `adr/0029-optimistic-concurrency-over-http.md`, `docs/learning-log.md`, `.claude/HANDOVER.md`; corpus amendments in `../documents` on branch `docs/bonds-phase-2`.

---

### Task 1: The two error codes, and `If-Match` parsed properly

**Files:**
- Modify: `common/web/src/main/kotlin/com/moyi/common/web/ErrorCode.kt`
- Create: `common/web/src/main/kotlin/com/moyi/common/web/Preconditions.kt`
- Test: `common/web/src/test/kotlin/com/moyi/common/web/IfMatchTest.kt`

**Interfaces:**
- Produces: `ErrorCode.PRECONDITION_REQUIRED`, `ErrorCode.PRECONDITION_FAILED`; `IfMatch.parse(header: String?): IfMatch` throwing `PreconditionRequiredException` when absent or `*`; `IfMatch.matches(version: Int): Boolean`; `PreconditionRequiredException`, `PreconditionFailedException`.

- [ ] **Step 1: Write the failing test.** New file `common/web/src/test/kotlin/com/moyi/common/web/IfMatchTest.kt`:

```kotlin
package com.moyi.common.web

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * RFC 9110 §13.1.1 is short and every clause of it is a decision this file
 * makes deliberately — see `Preconditions.kt` for the two places we knowingly
 * differ from the letter of it, and why.
 */
internal class IfMatchTest {
    @Test
    fun `a quoted version matches that version and no other`() {
        val ifMatch = IfMatch.parse("\"3\"")

        ifMatch.matches(3) shouldBe true
        ifMatch.matches(4) shouldBe false
        ifMatch.matches(0) shouldBe false
    }

    @Test
    fun `a list matches if any member matches`() {
        // RFC 9110: "If-Match: "1", "3"" is a list, and any match is a match.
        val ifMatch = IfMatch.parse("\"1\", \"3\"")

        ifMatch.matches(1) shouldBe true
        ifMatch.matches(3) shouldBe true
        ifMatch.matches(2) shouldBe false
    }

    @Test
    fun `a weak validator never matches`() {
        // RFC 9110 §13.1.1: If-Match uses the strong comparison function, and a
        // weak validator can never be strongly compared. 412, not 428 — the
        // client did send a precondition, it just cannot be honoured.
        IfMatch.parse("W/\"3\"").matches(3) shouldBe false
    }

    @Test
    fun `an unparseable value matches nothing rather than everything`() {
        // The failure mode that matters: anything that makes this return "yes"
        // by accident is a lost update.
        listOf("3", "\"\"", "\"abc\"", "\"3", "3\"", "  ", "\"-1\"").forEach {
            IfMatch.parse(it).matches(3) shouldBe false
        }
    }

    @Test
    fun `an absent header is a 428`() {
        shouldThrow<PreconditionRequiredException> { IfMatch.parse(null) }
    }

    @Test
    fun `a star is a 428, deliberately`() {
        // RFC 9110 says `*` matches any current representation, so a compliant
        // server would let the write through. We refuse it: doc 06 §1 requires
        // the condition in order to prevent a lost update, and `*` is a request
        // to skip exactly that. ADR-0029 records the deviation.
        shouldThrow<PreconditionRequiredException> { IfMatch.parse("*") }
    }
}
```

- [ ] **Step 2: Run it and watch it fail.** `./gradlew :common:web:test --tests '*IfMatchTest'` — expected FAIL: unresolved reference `IfMatch`.

- [ ] **Step 3: Add the two codes** to `ErrorCode`, each with its KDoc in the style of the ones around it:

```kotlin
    /**
     * A conditional request's condition is missing: `PATCH /bonds/{id}` with no
     * `If-Match` (doc 06 §1). 428.
     *
     * The client's move is to `GET` the resource, keep its `ETag`, and send it
     * back. A `400` would say the request was malformed, which it is not — it is
     * well-formed and unsafe, and 428 exists for exactly that difference.
     */
    PRECONDITION_REQUIRED,

    /**
     * The condition was sent and does not hold: the caller's `If-Match` names a
     * version that is no longer current, so somebody changed the resource since
     * they read it. 412.
     *
     * Also the answer when two writers pass the check and one loses the race at
     * commit — from the loser's side the precondition had stopped being true,
     * which is the same fact arriving a moment later (ADR-0029).
     */
    PRECONDITION_FAILED,
```

- [ ] **Step 4: Write `Preconditions.kt`:**

```kotlin
package com.moyi.common.web

import org.springframework.http.HttpStatus

/**
 * An `If-Match` header, parsed (RFC 9110 §13.1.1).
 *
 * Doc 06 §1 requires one on every bond-settings update, and the reason is a
 * lost update: two members open the settings screen, both change the name, and
 * without a condition the second write silently erases the first — with no
 * error for either of them to see. The `ETag` this compares against is the row
 * `@Version`, so the check is against the same number the database enforces.
 *
 * **Two deliberate deviations from the RFC, both recorded in ADR-0029:**
 *
 * - **`*` is refused** with `428` rather than matching. The RFC says `*` matches
 *   any current representation, so a compliant server would perform the write.
 *   But the *point* of requiring the condition is to prevent a lost update, and
 *   `If-Match: *` is a request to skip that check — so honouring it would make
 *   the requirement decorative. A client that genuinely wants to overwrite
 *   re-reads and sends the version it saw.
 * - **An unparseable value matches nothing** and produces `412` rather than a
 *   `400`. The dangerous direction is the other one: anything that returns
 *   "matches" by accident is a silently lost update, so every doubt resolves to
 *   "does not match".
 *
 * A weak validator (`W/"3"`) never matches, which *is* the RFC: `If-Match` uses
 * strong comparison.
 */
@JvmInline
value class IfMatch private constructor(private val versions: List<Int>) {
    /** True when [version] is one of the versions the caller said it would accept. */
    fun matches(version: Int): Boolean = versions.contains(version)

    companion object {
        /**
         * @throws PreconditionRequiredException the header is absent, blank, or `*`
         */
        fun parse(header: String?): IfMatch {
            val raw = header?.trim()
            if (raw.isNullOrBlank() || raw == "*") throw PreconditionRequiredException()
            return IfMatch(raw.split(',').mapNotNull { it.trim().toVersionOrNull() })
        }

        /**
         * `"3"` → `3`. Anything else — a weak validator, an unquoted number,
         * an empty tag, a non-number, a negative — is `null`, and a `null`
         * cannot match anything.
         */
        private fun String.toVersionOrNull(): Int? =
            takeIf { it.length >= 2 && it.startsWith('"') && it.endsWith('"') }
                ?.substring(1, length - 1)
                ?.toIntOrNull()
                ?.takeIf { it >= 0 }
    }
}

/** 428: doc 06 §1 requires the update to be conditional and this one was not. */
class PreconditionRequiredException :
    ApiException(
        HttpStatus.PRECONDITION_REQUIRED,
        ErrorCode.PRECONDITION_REQUIRED,
        "Read this first, then send its version back as If-Match.",
    )

/**
 * 412: the version the caller holds is not the current one.
 *
 * The detail says what happened in the terms a person experiences — somebody
 * else changed it — because that is the fact the client has to explain, and it
 * is also what `states.md` would draw if it drew this state.
 */
class PreconditionFailedException :
    ApiException(
        HttpStatus.PRECONDITION_FAILED,
        ErrorCode.PRECONDITION_FAILED,
        "This has changed since you last read it. Open it again and retry.",
    )
```

- [ ] **Step 5: Run the tests.** `./gradlew :common:web:check` — expected PASS. The `ProblemDetail` schema test in `app` will now fail until the contract is regenerated in Task 7; that is expected and is the `ErrorCode` enum doing its job.

- [ ] **Step 6: Commit.**

```bash
git add common/web
git commit -m "feat(web): If-Match parsed, and the two precondition codes" -m "The ETag a bond response carries is its row @Version, so a conditional update compares against the same number the database enforces. Two deliberate deviations from RFC 9110, both in the KDoc and ADR-0029: \`*\` is a 428 rather than a match, because it asks to skip the very check doc 06 §1 requires; and an unparseable validator matches nothing, because the dangerous direction is the one that silently loses a write." -m "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GPjbihrTNz2YwDjuwUmysH"
```

---

### Task 2: The domain — a settings change, and a quiet-hours invariant

**Files:**
- Modify: `modules/bond/src/main/kotlin/com/moyi/bond/domain/Bond.kt`, `Member.kt`
- Test: `modules/bond/src/test/kotlin/com/moyi/bond/domain/BondTest.kt`

**Interfaces:**
- Produces: `BondSettings(name: String?, type: BondType?, revealTimeLocal: Change<LocalTime>?, strictMode: Boolean?)`; `Bond.update(settings: BondSettings): Bond`; `MemberSettings(nicknameForOther, reminderTimeLocal, reminderTimezone, quietHoursStart, quietHoursEnd)`; `Member.withSettings(settings: MemberSettings): Member`; `Change<T>` (a present-but-possibly-null value).

- [ ] **Step 1: Write the failing tests** in `BondTest`:

```kotlin
    @Test
    fun `updating changes only the fields the caller named`() {
        val bond = create().copy(revealTimeLocal = LocalTime.of(21, 0), strictMode = true)

        val renamed = bond.update(BondSettings(name = "Us two"))

        renamed.name shouldBe "Us two"
        renamed.type shouldBe bond.type
        renamed.revealTimeLocal shouldBe LocalTime.of(21, 0)
        renamed.strictMode shouldBe true
        renamed.version shouldBe bond.version
    }

    @Test
    fun `a named null clears the reveal time, and an absent one leaves it`() {
        // PATCH's one genuine ambiguity: `"revealTimeLocal": null` means clear
        // it, and omitting the field means leave it alone. `Change` is what
        // keeps those two apart all the way down from the wire.
        val bond = create().copy(revealTimeLocal = LocalTime.of(21, 0))

        bond.update(BondSettings(revealTimeLocal = Change(null))).revealTimeLocal.shouldBeNull()
        bond.update(BondSettings(revealTimeLocal = null)).revealTimeLocal shouldBe LocalTime.of(21, 0)
        bond.update(BondSettings(revealTimeLocal = Change(LocalTime.of(7, 30)))).revealTimeLocal shouldBe LocalTime.of(7, 30)
    }

    @Test
    fun `updating a bond that has ended is refused`() {
        val archived = create().let { it.leave(it.members.single().id, now) }

        shouldThrow<IllegalStateException> { archived.update(BondSettings(name = "Us two")) }
    }

    @Test
    fun `changing the type leaves the seats alone`() {
        // FR-021 puts the member limit on the row, and every v1 type seats two.
        // A type change is not the place to move it — a future type with a
        // different number needs its own rule, and ADR-0029 says so.
        val bond = create()

        val friends = bond.update(BondSettings(type = BondType.FRIENDS))

        friends.type shouldBe BondType.FRIENDS
        friends.maxMembers shouldBe bond.maxMembers
    }

    @Test
    fun `a member's settings are replaced wholesale, and quiet hours come in pairs`() {
        val member = create().members.single()

        val updated =
            member.withSettings(
                MemberSettings(
                    nicknameForOther = "Ada",
                    reminderTimeLocal = LocalTime.of(7, 0),
                    reminderTimezone = RegionZone.of("Europe/London"),
                    quietHoursStart = LocalTime.of(22, 0),
                    quietHoursEnd = LocalTime.of(7, 0),
                ),
            )

        updated.nicknameForOther shouldBe "Ada"
        updated.reminderTimeLocal shouldBe LocalTime.of(7, 0)
        updated.reminderTimezone shouldBe RegionZone.of("Europe/London")
        updated.quietHoursStart shouldBe LocalTime.of(22, 0)
        // Everything the caller did not send is cleared, because PUT replaces.
        updated.withSettings(MemberSettings(reminderTimeLocal = LocalTime.of(20, 0))).nicknameForOther.shouldBeNull()
        // Identity is not the caller's to change.
        updated.id shouldBe member.id
        updated.userId shouldBe member.userId
        updated.joinedAt shouldBe member.joinedAt
    }

    @Test
    fun `one quiet hour without the other is not a state a member can be in`() {
        // A start with no end is not a window, and a notification scheduler
        // (Phase 4) would have to invent the other half. Refused in the domain
        // so every caller gets the same answer.
        val member = create().members.single()
        val settings = MemberSettings(reminderTimeLocal = LocalTime.of(20, 0), quietHoursStart = LocalTime.of(22, 0))

        shouldThrow<IllegalArgumentException> { member.withSettings(settings) }
        shouldThrow<IllegalArgumentException> { member.copy(quietHoursEnd = LocalTime.of(7, 0)) }
        // A window that wraps midnight is ordinary and must stay legal.
        member
            .withSettings(settings.copy(quietHoursEnd = LocalTime.of(7, 0)))
            .quietHoursStart shouldBe LocalTime.of(22, 0)
    }
```

- [ ] **Step 2: Run them and watch them fail.** `./gradlew :modules:bond:test --tests '*BondTest'` — expected FAIL: unresolved `update`, `BondSettings`, `Change`, `withSettings`, `MemberSettings`.

- [ ] **Step 3: Add `Change` and `BondSettings`** to `Bond.kt`, above the aggregate:

```kotlin
/**
 * A value the caller **named**, which may be `null`.
 *
 * `PATCH` has one genuine ambiguity and this is the answer to it: a field the
 * client omitted must keep its current value, and a field the client sent as
 * `null` must be cleared. A plain nullable parameter cannot tell those apart,
 * and guessing is how a client's untouched setting gets wiped by a request
 * about something else.
 *
 * Only nullable settings need it, so only `revealTimeLocal` has one today.
 */
internal data class Change<T : Any>(
    val value: T?,
)

/**
 * The fields `PATCH /bonds/{bondId}` may change (doc 06 §3.3, ADR-0013 §8).
 *
 * `null` means "not named" for every property here; `revealTimeLocal` is a
 * [Change] because it is the one that can also be set *to* null.
 *
 * **`anchorTimezone` is deliberately absent.** FR-027 makes it two-party and
 * at most once per 30 days, which is slice B5's `PATCH /bonds/{id}/timezone`.
 * The web layer refuses a body that names it rather than ignoring it, because a
 * silently dropped setting is worse than a refusal.
 */
internal data class BondSettings(
    val name: String? = null,
    val type: BondType? = null,
    val revealTimeLocal: Change<LocalTime>? = null,
    val strictMode: Boolean? = null,
)
```

- [ ] **Step 4: Add `Bond.update`**, below `end`:

```kotlin
    /**
     * A settings change (FR-027's unilateral half, ADR-0013 §8).
     *
     * Every field is optional and an absent one is left alone. The
     * constructor's `require`s run on the result, so a name that is too long or
     * blank cannot produce an object — the edge validates too, and this is the
     * layer that cannot be bypassed.
     *
     * `check(isOpen)`: BR-9, and the same answer B3's `leave` gives, because it
     * is the same rule — an archived bond is a record (ADR-0028 decision 4).
     *
     * The seats do not move when the type does. FR-021 puts the limit on the
     * row, every v1 type seats two, and a type that ever seats a different
     * number needs a rule about existing members rather than an arithmetic
     * side effect here.
     */
    fun update(settings: BondSettings): Bond {
        check(isOpen) { "a bond that has ended cannot be changed" }
        return copy(
            name = settings.name ?: name,
            type = settings.type ?: type,
            revealTimeLocal = settings.revealTimeLocal?.value ?: revealTimeLocal.takeIf { settings.revealTimeLocal == null },
            strictMode = settings.strictMode ?: strictMode,
        )
    }
```

  Read that `revealTimeLocal` line twice — it is the only clever line in the slice, and if it is wrong a client loses a setting. `Change(null)` → `?.value` is null and `settings.revealTimeLocal == null` is false, so the whole expression is null (cleared). Absent → the elvis falls through to the current value. If it reads badly to you, write it as a `when`; a test covers all three cases either way.

- [ ] **Step 5: Add `MemberSettings` and `Member.withSettings`** to `Member.kt`, and the invariant to its `init`:

```kotlin
/**
 * One member's own notification settings — the whole of what `PUT
 * /bonds/{bondId}/members/me/settings` replaces (doc 06 §3.3).
 *
 * All five fields together, because the endpoint is a `PUT`: it replaces, so an
 * absent field is a cleared field. That is a deliberate choice over a second
 * `PATCH` — these are five small fields on one screen, a client always has all
 * of them, and "replace what is there" needs no `Change` wrapper and no
 * ambiguity about what `null` meant.
 *
 * `reminderTimeLocal` has no default here on purpose: [Member.DEFAULT_REMINDER_TIME]
 * is the default for a *new* member, and a `PUT` that omits the one field a
 * member cannot be without should be a `422`, not a silent 20:00.
 */
internal data class MemberSettings(
    val nicknameForOther: String? = null,
    val reminderTimeLocal: LocalTime,
    val reminderTimezone: RegionZone? = null,
    val quietHoursStart: LocalTime? = null,
    val quietHoursEnd: LocalTime? = null,
)
```

  In `Member.init`, after the nickname `require`:

```kotlin
        // A start with no end is not a window. Phase 4's scheduler would have to
        // invent the other half, and V9 permits either column alone — so this is
        // the layer that says no. A window that wraps midnight (22:00 → 07:00)
        // is ordinary and stays legal.
        require((quietHoursStart == null) == (quietHoursEnd == null)) {
            "quiet hours need both a start and an end, or neither"
        }
```

  And the transition, after the `isActive` property:

```kotlin
    /**
     * Replaces this member's own settings (`PUT …/members/me/settings`).
     *
     * Identity, role, membership dates and `leftAt` are not the caller's to
     * change and are not in [MemberSettings] at all — which is stronger than
     * checking, because there is nothing to check.
     *
     * `reminderTimezone` absent means the caller did not say, and the member
     * keeps the zone they had rather than falling back to the bond's anchor:
     * losing a zone you deliberately set, because you edited a nickname, is the
     * kind of quiet damage doc 04 §6 warns about.
     */
    fun withSettings(settings: MemberSettings): Member =
        copy(
            nicknameForOther = settings.nicknameForOther,
            reminderTimeLocal = settings.reminderTimeLocal,
            reminderTimezone = settings.reminderTimezone ?: reminderTimezone,
            quietHoursStart = settings.quietHoursStart,
            quietHoursEnd = settings.quietHoursEnd,
        )
```

- [ ] **Step 6: Run the tests.** `./gradlew :modules:bond:test --tests '*BondTest'` — expected PASS. Then `./gradlew :modules:bond:ktlintCheck :modules:bond:detektMain`.

- [ ] **Step 7: Commit.**

```bash
git add modules/bond/src/main/kotlin/com/moyi/bond/domain modules/bond/src/test/kotlin/com/moyi/bond/domain
git commit -m "feat(bond): a settings change in the domain, and quiet hours in pairs" -m "PATCH's one real ambiguity gets a type: Change<T> is a value the caller named, which may be null, so an omitted field keeps its value and a sent null clears it. Member settings are replaced wholesale because the endpoint is a PUT, and a quiet-hours start with no end is now refused by the aggregate — V9 permits either column alone and Phase 4's scheduler would have to invent the other half." -m "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GPjbihrTNz2YwDjuwUmysH"
```

---

### Task 3: Persistence — the write that flushes, so a lost race is catchable

**Files:**
- Modify: `modules/bond/src/main/kotlin/com/moyi/bond/infra/database/BondStore.kt`, `BondRepositories.kt`
- Test: `modules/bond/src/test/kotlin/com/moyi/bond/infra/database/BondPersistenceTest.kt`

**Interfaces:**
- Produces: `BondStore.update(bond: Bond)` (flushes; throws `OptimisticLockingFailureException` on a stale version), `BondStore.updateMember(member: Member)`; `BondRepository.saveAndFlush`.
- Consumes: `Bond.update`, `Member.withSettings` (Task 2); `Bond.applyTo`, `Member.applyTo` (B2, B3).

- [ ] **Step 1: Write the failing tests** in `BondPersistenceTest`:

```kotlin
    @Test
    fun `updating a bond writes the change and moves the version`() {
        val (bond, invite) = newBond()
        transactions.executeWithoutResult { store.insert(bond).also { invites.insert(invite) } }

        transactions.executeWithoutResult { store.update(bond.update(BondSettings(name = "Us two", strictMode = true))) }

        val loaded = transactions.execute { store.findByMember(bond.id, bond.createdBy) }.shouldNotBeNull()
        loaded.name shouldBe "Us two"
        loaded.strictMode shouldBe true
        loaded.version shouldBe 1
    }

    @Test
    fun `a write against a stale version is refused by the database, not by luck`() {
        // The half of ADR-0029 that is not the If-Match check: even if two
        // callers both pass that check, Hibernate's `WHERE version = ?` lets
        // exactly one of them commit. `update` flushes, so the failure arrives
        // inside the caller's transaction where a service can turn it into a
        // 412 — without the flush it would surface at commit, past every catch.
        val (bond, invite) = newBond()
        transactions.executeWithoutResult { store.insert(bond).also { invites.insert(invite) } }
        transactions.executeWithoutResult { store.update(bond.update(BondSettings(name = "First"))) }

        shouldThrow<OptimisticLockingFailureException> {
            // `bond` is still version 0 in memory; the row is at 1.
            transactions.executeWithoutResult { store.update(bond.update(BondSettings(name = "Second"))) }
        }

        transactions.execute { store.findByMember(bond.id, bond.createdBy) }.shouldNotBeNull().name shouldBe "First"
    }

    @Test
    fun `a member's settings round-trip, and the bond's version does not move`() {
        // The member row is not the bond row: changing a reminder time is
        // nobody else's business and must not invalidate the other member's
        // ETag (doc 06 §1, states.md §8).
        val (bond, invite) = newBond()
        transactions.executeWithoutResult { store.insert(bond).also { invites.insert(invite) } }
        val member = bond.members.single()

        transactions.executeWithoutResult {
            store.updateMember(
                member.withSettings(
                    MemberSettings(
                        nicknameForOther = "Ada",
                        reminderTimeLocal = LocalTime.of(7, 30),
                        reminderTimezone = RegionZone.of("Europe/London"),
                        quietHoursStart = LocalTime.of(22, 0),
                        quietHoursEnd = LocalTime.of(7, 0),
                    ),
                ),
            )
        }

        val loaded = transactions.execute { store.findByMember(bond.id, bond.createdBy) }.shouldNotBeNull()
        val reloaded = loaded.memberOf(bond.createdBy).shouldNotBeNull()
        reloaded.nicknameForOther shouldBe "Ada"
        reloaded.reminderTimeLocal shouldBe LocalTime.of(7, 30)
        reloaded.reminderTimezone shouldBe RegionZone.of("Europe/London")
        reloaded.quietHoursStart shouldBe LocalTime.of(22, 0)
        reloaded.quietHoursEnd shouldBe LocalTime.of(7, 0)
        loaded.version shouldBe 0
    }
```

  Add the imports the file needs: `org.springframework.dao.OptimisticLockingFailureException`, `com.moyi.bond.domain.BondSettings`, `com.moyi.bond.domain.MemberSettings`.

- [ ] **Step 2: Run them and watch them fail.** `./gradlew :modules:bond:test --tests '*BondPersistenceTest'` — expected FAIL: unresolved `update`, `updateMember`.

- [ ] **Step 3: Declare `saveAndFlush`** on `BondRepository`, next to `save`:

```kotlin
    /**
     * Writes and flushes, so Hibernate's `WHERE version = ?` runs **inside** the
     * caller's transaction.
     *
     * With a plain `save` the UPDATE is deferred to commit, which happens after
     * the service method returns — so the `OptimisticLockingFailureException`
     * would fly past the service's own `catch` and reach the web layer as a 500
     * instead of the `412` ADR-0029 promises. The flush is the whole reason this
     * method exists; do not "simplify" it back to `save`.
     */
    fun saveAndFlush(bond: BondEntity): BondEntity
```

- [ ] **Step 4: Add the two store methods** to `BondStore`, below `archive`:

```kotlin
    /**
     * Writes a settings change to the bond row and flushes it.
     *
     * [bond] is the aggregate *after* `update`. The flush is load-bearing — see
     * [BondRepository.saveAndFlush] — and it is why `UpdateBond` can answer a
     * lost race with a `412` rather than a 500.
     *
     * @throws org.springframework.dao.OptimisticLockingFailureException somebody
     *   else changed the row since [bond] was read
     */
    fun update(bond: Bond) {
        val entity = bonds.findById(bond.id.value) ?: error("cannot update a bond that does not exist")
        bond.applyTo(entity)
        bonds.saveAndFlush(entity)
    }

    /**
     * Writes one member's own settings. The bond row is untouched, so the other
     * member's `ETag` stays valid: a reminder time is not part of the bond
     * (`states.md` §8).
     */
    fun updateMember(member: Member) {
        val entity =
            members.findAllByBondId(member.bondId.value).firstOrNull { it.getId() == member.id.value }
                ?: error("cannot update a member row that does not exist")
        member.applyTo(entity)
        members.saveAll(listOf(entity))
    }
```

- [ ] **Step 5: Run the tests.** `./gradlew :modules:bond:test --tests '*BondPersistenceTest'` — expected PASS.

- [ ] **Step 6: Commit.**

```bash
git add modules/bond/src/main/kotlin/com/moyi/bond/infra modules/bond/src/test/kotlin/com/moyi/bond/infra
git commit -m "feat(bond): the settings write flushes, so a lost race is a 412" -m "saveAndFlush rather than save: Hibernate's WHERE version = ? has to run inside the caller's transaction, or the optimistic-lock failure surfaces at commit — after the service method returned — and becomes a 500 instead of the 412 the contract promises. A member's settings write does not touch the bond row, so the other member's ETag stays valid." -m "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GPjbihrTNz2YwDjuwUmysH"
```

---

### Task 4: `PATCH /bonds/{bondId}`

**Files:**
- Create: `modules/bond/src/main/kotlin/com/moyi/bond/service/UpdateBond.kt`, `modules/bond/src/main/kotlin/com/moyi/bond/web/PatchBondRequest.kt`
- Modify: `modules/bond/src/main/kotlin/com/moyi/bond/web/BondsController.kt`
- Test: `modules/bond/src/test/kotlin/com/moyi/bond/web/BondSettingsEndpointTest.kt` (new)

**Interfaces:**
- Produces: `UpdateBond.patch(membership: Membership, settings: BondSettings, ifMatch: IfMatch): BondView`; route `PATCH /api/v1/bonds/{bondId}` → `200` + `ETag`; controller method `patchBond`.
- Consumes: `IfMatch`, `PreconditionFailedException` (Task 1); `Bond.update`, `BondSettings`, `Change` (Task 2); `BondStore.update` (Task 3); `BondViews.of`, `BondArchivedException`, `BondNotFoundException` (existing).

- [ ] **Step 1: Write the failing endpoint test.** New file `BondSettingsEndpointTest.kt` — the `PATCH` half now, the settings half added in Task 5. Model the helpers on `BondEndingEndpointTest`:

```kotlin
package com.moyi.bond.web

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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.util.UUID
import javax.sql.DataSource

/**
 * `PATCH /bonds/{bondId}` and the two member-settings endpoints — every status
 * the design's §5.2 rows #10–#12 list.
 *
 * The subject is doc 06 §1's one sentence: *"`ETag` on mutable resources;
 * `If-Match` required for updates to Bond settings."* Which means the
 * interesting tests here are not the happy path but the four ways a condition
 * can be wrong.
 */
@SpringBootTest(classes = [BondTestApplication::class])
@AutoConfigureMockMvc
internal class BondSettingsEndpointTest(
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
    fun `a patch with the current ETag is 200, and returns the new one`() {
        val ada = users.verified("Ada")
        val created = createBond(ada)
        val bondId = bondIdOf(created)
        created.getHeader(HttpHeaders.ETAG) shouldBe "\"0\""

        val response = patch(ada, bondId, """{"name":"Us two"}""", "\"0\"")

        response.status shouldBe 200
        response.contentAsString shouldContain "\"name\":\"Us two\""
        // The NEW version, not the one that was sent: a client told to keep a
        // stale value would be refused on its next write (the B2 ETag lesson).
        response.getHeader(HttpHeaders.ETAG) shouldBe "\"1\""
        getBond(ada, bondId).getHeader(HttpHeaders.ETAG) shouldBe "\"1\""
    }

    @Test
    fun `no If-Match is 428 PRECONDITION_REQUIRED and changes nothing`() {
        val ada = users.verified("Ada")
        val bondId = bondIdOf(createBond(ada))

        val response = patch(ada, bondId, """{"name":"Us two"}""", ifMatch = null)

        response.status shouldBe 428
        response.contentAsString shouldContain "\"code\":\"PRECONDITION_REQUIRED\""
        getBond(ada, bondId).contentAsString shouldContain "\"name\":\"Us\""
    }

    @Test
    fun `a stale If-Match is 412 and changes nothing`() {
        val ada = users.verified("Ada")
        val bondId = bondIdOf(createBond(ada))
        patch(ada, bondId, """{"name":"First"}""", "\"0\"").status shouldBe 200

        val response = patch(ada, bondId, """{"name":"Second"}""", "\"0\"")

        response.status shouldBe 412
        response.contentAsString shouldContain "\"code\":\"PRECONDITION_FAILED\""
        getBond(ada, bondId).contentAsString shouldContain "\"name\":\"First\""
    }

    @Test
    fun `a star If-Match is 428, and a weak one is 412`() {
        // ADR-0029's two deviations from RFC 9110, asserted so they are
        // decisions rather than accidents.
        val ada = users.verified("Ada")
        val bondId = bondIdOf(createBond(ada))

        patch(ada, bondId, """{"name":"Us two"}""", "*").status shouldBe 428
        patch(ada, bondId, """{"name":"Us two"}""", "W/\"0\"").status shouldBe 412
        getBond(ada, bondId).contentAsString shouldContain "\"name\":\"Us\""
    }

    @Test
    fun `the guard runs before the precondition, so a non-member never learns the bond is real`() {
        // The order matters: a 428 or a 412 to a stranger would confirm the
        // bond exists, which is precisely T-02's oracle. A non-member gets the
        // same 404 whatever their headers say.
        val ada = users.verified("Ada")
        val eve = users.verified("Eve")
        val bondId = bondIdOf(createBond(ada))

        patch(eve, bondId, """{"name":"Mine"}""", ifMatch = null).status shouldBe 404
        patch(eve, bondId, """{"name":"Mine"}""", "\"0\"").status shouldBe 404
        patch(eve, bondId, """{"name":"Mine"}""", "\"99\"").status shouldBe 404
    }

    @Test
    fun `patching an archived bond is 409 BOND_ARCHIVED`() {
        val ada = users.verified("Ada")
        val created = createBond(ada)
        val bondId = bondIdOf(created)
        leave(ada, bondId).status shouldBe 204

        val response = patch(ada, bondId, """{"name":"Us two"}""", "\"1\"")

        response.status shouldBe 409
        response.contentAsString shouldContain "\"code\":\"BOND_ARCHIVED\""
    }

    @Test
    fun `the type is patchable, and the seats do not move`() {
        val ada = users.verified("Ada")
        val bondId = bondIdOf(createBond(ada))

        val response = patch(ada, bondId, """{"type":"FRIENDS"}""", "\"0\"")

        response.status shouldBe 200
        response.contentAsString shouldContain "\"type\":\"FRIENDS\""
        response.contentAsString shouldContain "\"maxMembers\":2"
    }

    @Test
    fun `a named null clears the reveal time and an absent field is left alone`() {
        val ada = users.verified("Ada")
        val bondId = bondIdOf(createBond(ada))
        patch(ada, bondId, """{"revealTimeLocal":"21:00","strictMode":true}""", "\"0\"").status shouldBe 200

        val cleared = patch(ada, bondId, """{"revealTimeLocal":null}""", "\"1\"")

        cleared.status shouldBe 200
        cleared.contentAsString shouldContain "\"revealTimeLocal\":null"
        // strictMode was not named, so it is untouched — the whole reason
        // `Change` exists.
        cleared.contentAsString shouldContain "\"strictMode\":true"
    }

    @Test
    fun `the anchor timezone is refused here, not ignored`() {
        // FR-027 makes it two-party and once per 30 days (slice B5). Silently
        // dropping it would tell a client the change succeeded.
        val ada = users.verified("Ada")
        val bondId = bondIdOf(createBond(ada))

        val response = patch(ada, bondId, """{"anchorTimezone":"Europe/London"}""", "\"0\"")

        response.status shouldBe 422
        response.contentAsString shouldContain "\"field\":\"anchorTimezone\""
        getBond(ada, bondId).contentAsString shouldContain "\"anchorTimezone\":\"Africa/Lagos\""
    }

    @Test
    fun `an empty patch is 422 rather than a version bump for nothing`() {
        val ada = users.verified("Ada")
        val bondId = bondIdOf(createBond(ada))

        patch(ada, bondId, "{}", "\"0\"").status shouldBe 422
        getBond(ada, bondId).getHeader(HttpHeaders.ETAG) shouldBe "\"0\""
    }

    @Test
    fun `a name that is too long, blank, or the wrong type is 422 naming the field`() {
        val ada = users.verified("Ada")
        val bondId = bondIdOf(createBond(ada))

        patch(ada, bondId, """{"name":"${"x".repeat(61)}"}""", "\"0\"").let {
            it.status shouldBe 422
            it.contentAsString shouldContain "\"field\":\"name\""
            // Doc 18 §5: the failure response does not echo the input.
            it.contentAsString shouldNotContain "xxxxx"
        }
        patch(ada, bondId, """{"name":"  "}""", "\"0\"").status shouldBe 422
        patch(ada, bondId, """{"type":"THROUPLE"}""", "\"0\"").let {
            it.status shouldBe 422
            it.contentAsString shouldContain "\"field\":\"type\""
        }
        patch(ada, bondId, """{"revealTimeLocal":"9am"}""", "\"0\"").status shouldBe 422
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

    private fun patch(
        userId: UUID,
        bondId: String,
        body: String,
        ifMatch: String?,
    ): MockHttpServletResponse =
        mockMvc
            .patch("/api/v1/bonds/$bondId") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                ifMatch?.let { header(HttpHeaders.IF_MATCH, it) }
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    private fun getBond(
        userId: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc.get("/api/v1/bonds/$bondId") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun leave(
        userId: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc.post("/api/v1/bonds/$bondId/leave") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun bearer(userId: UUID): String = "Bearer ${tokens.issue(userId).token}"

    private fun bondIdOf(response: MockHttpServletResponse): String =
        Regex(""""id":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]
}
```

- [ ] **Step 2: Run it and watch it fail.** `./gradlew :modules:bond:test --tests '*BondSettingsEndpointTest'` — expected FAIL: 405, no such route.

- [ ] **Step 3: Write `UpdateBond`:**

```kotlin
package com.moyi.bond.service

import com.moyi.bond.domain.BondSettings
import com.moyi.bond.domain.Membership
import com.moyi.bond.infra.database.BondStore
import com.moyi.common.web.IfMatch
import com.moyi.common.web.PreconditionFailedException
import org.slf4j.LoggerFactory
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * `PATCH /bonds/{bondId}` (doc 06 §3.3, ADR-0029).
 *
 * **The interesting part is that there are two checks, not one**, and both are
 * needed:
 *
 * 1. `If-Match` against the version just read. This is what gives a client a
 *    `412` it can explain — "somebody changed this, open it again" — and it is
 *    checked before anything is written.
 * 2. Hibernate's `WHERE version = ?` at flush. This is what makes the promise
 *    true under concurrency, because two callers can pass check 1 within the
 *    same millisecond and only one row update can win.
 *
 * Check 1 without check 2 is a lost update with a reassuring `200`. Check 2
 * without check 1 turns an ordinary stale edit into a 500. `BondSettingsRaceTest`
 * is what proves both are present.
 */
@Service
internal class UpdateBond(
    private val bonds: BondStore,
    private val views: BondViews,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @throws BondNotFoundException the caller's membership went away between the guard and here
     * @throws BondArchivedException the bond has ended (BR-9, ADR-0028 decision 4)
     * @throws PreconditionFailedException the caller's `If-Match` is not the current version
     */
    @Transactional
    @Suppress("ThrowsCount")
    fun patch(
        membership: Membership,
        settings: BondSettings,
        ifMatch: IfMatch,
    ): BondView {
        val bond = bonds.findByMember(membership.bondId, membership.userId) ?: throw BondNotFoundException()
        // Archived before precondition: a member of an archived bond gets the
        // same `409` whatever version they hold, because "this has ended" is the
        // more useful answer and it is true regardless.
        if (!bond.isOpen) throw BondArchivedException()
        if (!ifMatch.matches(bond.version)) throw PreconditionFailedException()

        val updated = bond.update(settings)
        try {
            bonds.update(updated)
        } catch (lost: OptimisticLockingFailureException) {
            // Somebody committed between the check above and the flush. From
            // this caller's side the precondition stopped being true, which is
            // the same fact as a stale `If-Match` arriving a moment later.
            log.debug("Lost an optimistic lock updating bond {}", membership.bondId.value, lost)
            throw PreconditionFailedException()
        }
        log.info("Bond {} settings updated", membership.bondId.value)
        // Re-read, so the `ETag` carries the version the row now has rather
        // than the one this object was loaded with — the defect the review of
        // PR #38 found on accept.
        val persisted = bonds.findByMember(bond.id, membership.userId) ?: error("the bond just updated is gone")
        return views.of(persisted, membership.userId)
    }
}
```

- [ ] **Step 4: Write `PatchBondRequest`:**

```kotlin
package com.moyi.bond.web

import com.moyi.bond.domain.Bond
import com.moyi.bond.domain.BondSettings
import com.moyi.bond.domain.BondType
import com.moyi.bond.domain.Change
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.LocalTime
import java.util.Optional

/**
 * The wire format of `PATCH /bonds/{bondId}` (doc 06 §3.3).
 *
 * **`Optional` is how an omitted field is told from a field sent as `null`.**
 * Jackson leaves an absent property at its Kotlin default (`null` here) and
 * deserialises an explicit `null` into `Optional.empty()` — so `null` means "not
 * named" and `Optional.empty()` means "clear it". It is not pretty; the
 * alternative is guessing, and guessing means a client that edits the name wipes
 * a reveal time it never mentioned.
 *
 * Only `revealTimeLocal` needs it: the other three settings are not nullable, so
 * for them a plain nullable field already means "not named".
 *
 * `anchorTimezone` is here **only to refuse it**. FR-027 makes the anchor zone
 * two-party and at most once per 30 days, which is `PATCH
 * /bonds/{bondId}/timezone` in slice B5. A client that sends it to this endpoint
 * has misread the contract, and a `422` naming the field says so — whereas
 * ignoring the property would report success for a change that did not happen.
 */
internal data class PatchBondRequest(
    @field:Size(min = 1, max = Bond.MAX_NAME_LENGTH)
    val name: String? = null,
    @field:ValidBondType
    val type: String? = null,
    val revealTimeLocal: Optional<@Pattern(regexp = TIME_OF_DAY, message = "must be a time of day such as 21:00") String>? = null,
    val strictMode: Boolean? = null,
    val anchorTimezone: String? = null,
) {
    /** A name of spaces is not a name; `@Size` counts them. */
    @get:AssertTrue(message = "must not be blank")
    val nameIsNotBlank: Boolean get() = name == null || name.isNotBlank()

    /** FR-027: not here. Slice B5's `PATCH /bonds/{id}/timezone`, with the other member's consent. */
    @get:AssertTrue(message = "can only be changed through /bonds/{bondId}/timezone, with the other member's agreement")
    val anchorTimezoneIsNotSentHere: Boolean get() = anchorTimezone == null

    /**
     * An empty body would otherwise be a successful write that changes nothing
     * and still moves the version — invalidating the other member's `ETag` for
     * no reason. `422` is the honest answer to "change these zero things".
     */
    @get:AssertTrue(message = "must name at least one setting to change")
    val changesSomething: Boolean
        get() = name != null || type != null || revealTimeLocal != null || strictMode != null

    fun toSettings() =
        BondSettings(
            name = name?.trim(),
            type = type?.trim()?.let(BondType::valueOf),
            revealTimeLocal = revealTimeLocal?.let { Change(it.orElse(null)?.let(LocalTime::parse)) },
            strictMode = strictMode,
        )

    private companion object {
        const val TIME_OF_DAY = "^([01]\\d|2[0-3]):[0-5]\\d$"
    }
}
```

  **The `@get:AssertTrue` field names are the API's error fields**, so they read `nameIsNotBlank`, `anchorTimezoneIsNotSentHere`, `changesSomething` in the `errors` array — the test above asserts `"field":"anchorTimezone"`, so **name the property `anchorTimezone…` only if the violation reports that**; if Bean Validation reports the property name instead, either rename the assertions to match what the test expects or relax the test to the property name. Run it and see which; do not guess, and say in the commit which it was.

- [ ] **Step 5: Wire the route** into `BondsController` — add `private val updateBond: UpdateBond` to the constructor and the method after `getBond`:

```kotlin
    /**
     * `PATCH /bonds/{bondId}` (doc 06 §1, §3.3). Conditional: the `ETag` from any
     * bond response, sent back as `If-Match`.
     *
     * **The order of the first two statements is the security property.** The
     * guard runs before the header is even looked at, so a non-member gets the
     * same 404 they get everywhere — a `428` or a `412` would confirm that the
     * bond exists (T-02), and to the one caller who must not know.
     */
    @PatchMapping("/{bondId}")
    fun patchBond(
        caller: CurrentUser,
        @PathVariable bondId: String,
        @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
        @Valid @RequestBody request: PatchBondRequest,
    ): ResponseEntity<BondResponse> {
        val membership = guard.membershipOf(UserId(caller.id), bondIdOrNotFound(bondId))
        val view = updateBond.patch(membership, request.toSettings(), IfMatch.parse(ifMatch))
        return ResponseEntity.ok().eTag(BondResponse.etagOf(view)).body(BondResponse.from(view))
    }
```

  New imports: `com.moyi.bond.service.UpdateBond`, `com.moyi.common.web.IfMatch`, `org.springframework.http.HttpHeaders`, `org.springframework.web.bind.annotation.PatchMapping`, `org.springframework.web.bind.annotation.RequestHeader`.

  **Note the ordering trap:** `@Valid @RequestBody` is validated by Spring *before* the method body runs, so a stranger sending an invalid body would get a `422` — an oracle. Check this in the test above (`the guard runs before the precondition`): if a non-member with a bad body gets 422, move validation inside the method (validate manually after the guard) and write down that you found it. Spring resolves arguments before invoking, so **expect this to be real** and deal with it rather than hoping.

- [ ] **Step 6: Run the test.** `./gradlew :modules:bond:test --tests '*BondSettingsEndpointTest'` — expected PASS for the patch cases. Fix what fails, and if the `@Valid` ordering trap above is real, fix it properly (the guard must run first) rather than weakening the test.

- [ ] **Step 7: Commit.**

```bash
git add modules/bond common/web
git commit -m "feat(bond): PATCH /bonds/{bondId}, conditional on the ETag (doc 06 §1, FR-027)" -m "Two checks, not one: If-Match against the version just read, so an ordinary stale edit is a 412 a client can explain; and Hibernate's WHERE version = ? at flush, so two callers who both pass that check cannot both write. The guard runs before either, because a 412 to a non-member would confirm the bond exists." -m "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GPjbihrTNz2YwDjuwUmysH"
```

---

### Task 5: The member's own settings

**Files:**
- Create: `modules/bond/src/main/kotlin/com/moyi/bond/service/MemberSettingsService.kt`, `modules/bond/src/main/kotlin/com/moyi/bond/web/MemberSettingsRequest.kt`, `modules/bond/src/main/kotlin/com/moyi/bond/web/MemberSettingsResponse.kt`, `modules/bond/src/main/kotlin/com/moyi/bond/web/BondMemberSettingsController.kt`
- Modify: `modules/bond/src/test/kotlin/com/moyi/bond/web/BondSettingsEndpointTest.kt`

**Interfaces:**
- Produces: `MemberSettingsService.of(membership): Member`, `MemberSettingsService.replace(membership, settings: MemberSettings): Member`; routes `GET`/`PUT /api/v1/bonds/{bondId}/members/me/settings`; controller methods `getMemberSettings`, `replaceMemberSettings`.
- Consumes: `Member.withSettings`, `MemberSettings` (Task 2); `BondStore.updateMember` (Task 3).

- [ ] **Step 1: Add the failing tests** to `BondSettingsEndpointTest`:

```kotlin
    @Test
    fun `a member reads and replaces their own settings`() {
        val ada = users.verified("Ada")
        val bondId = bondIdOf(createBond(ada))

        val initial = getSettings(ada, bondId)
        initial.status shouldBe 200
        initial.contentAsString shouldContain "\"reminderTimeLocal\":\"20:00\""
        initial.contentAsString shouldContain "\"reminderTimezone\":\"Africa/Lagos\""
        initial.contentAsString shouldContain "\"nicknameForOther\":null"

        val replaced =
            putSettings(
                ada,
                bondId,
                """{"nicknameForOther":"Ada","reminderTimeLocal":"07:30","reminderTimezone":"Europe/London","quietHoursStart":"22:00","quietHoursEnd":"07:00"}""",
            )

        replaced.status shouldBe 200
        replaced.contentAsString shouldContain "\"nicknameForOther\":\"Ada\""
        replaced.contentAsString shouldContain "\"quietHoursEnd\":\"07:00\""
        getSettings(ada, bondId).contentAsString shouldBe replaced.contentAsString
    }

    @Test
    fun `PUT replaces, so an omitted field is cleared`() {
        val ada = users.verified("Ada")
        val bondId = bondIdOf(createBond(ada))
        putSettings(ada, bondId, """{"nicknameForOther":"Ada","reminderTimeLocal":"07:30"}""").status shouldBe 200

        val response = putSettings(ada, bondId, """{"reminderTimeLocal":"21:00"}""")

        response.status shouldBe 200
        response.contentAsString shouldContain "\"nicknameForOther\":null"
        // The zone is the one exception and it is deliberate: losing a zone you
        // set, because you edited a nickname, is silent damage (doc 04 §6).
        response.contentAsString shouldContain "\"reminderTimezone\":\"Africa/Lagos\""
    }

    @Test
    fun `settings are the caller's own, and the bond's version does not move`() {
        // states.md §8: never the other member's settings. There is no member id
        // in the route, so there is nothing to ask for — and a member's reminder
        // time is not a change to the bond, so the other member's ETag survives.
        val ada = users.verified("Ada")
        val bea = users.verified("Bea")
        val created = createBond(ada)
        val bondId = bondIdOf(created)
        accept(bea, codeOf(created)).status shouldBe 200
        val etagBefore = getBond(bea, bondId).getHeader(HttpHeaders.ETAG)

        putSettings(ada, bondId, """{"nicknameForOther":"My Ada","reminderTimeLocal":"07:30"}""").status shouldBe 200

        getBond(bea, bondId).getHeader(HttpHeaders.ETAG) shouldBe etagBefore
        val beaSettings = getSettings(bea, bondId)
        beaSettings.contentAsString shouldContain "\"reminderTimeLocal\":\"20:00\""
        beaSettings.contentAsString shouldNotContain "My Ada"
        getBond(bea, bondId).contentAsString shouldNotContain "My Ada"
    }

    @Test
    fun `settings take no If-Match, and are refused on an archived bond`() {
        val ada = users.verified("Ada")
        val bondId = bondIdOf(createBond(ada))
        // No condition needed: the row is the caller's own and nobody else can
        // write it, so there is no update to lose (ADR-0029).
        putSettings(ada, bondId, """{"reminderTimeLocal":"07:30"}""").status shouldBe 200
        leave(ada, bondId).status shouldBe 204

        // Archived: reads still work for both members, writes do not.
        getSettings(ada, bondId).status shouldBe 200
        val refused = putSettings(ada, bondId, """{"reminderTimeLocal":"08:00"}""")
        refused.status shouldBe 409
        refused.contentAsString shouldContain "\"code\":\"BOND_ARCHIVED\""
    }

    @Test
    fun `a bad settings body is 422 naming the field`() {
        val ada = users.verified("Ada")
        val bondId = bondIdOf(createBond(ada))

        putSettings(ada, bondId, "{}").let {
            it.status shouldBe 422
            it.contentAsString shouldContain "\"field\":\"reminderTimeLocal\""
        }
        putSettings(ada, bondId, """{"reminderTimeLocal":"7am"}""").status shouldBe 422
        putSettings(ada, bondId, """{"reminderTimeLocal":"07:30","reminderTimezone":"Etc/GMT+3"}""").let {
            it.status shouldBe 422
            it.contentAsString shouldContain "\"field\":\"reminderTimezone\""
        }
        putSettings(ada, bondId, """{"reminderTimeLocal":"07:30","nicknameForOther":"${"x".repeat(41)}"}""").let {
            it.status shouldBe 422
            it.contentAsString shouldContain "\"field\":\"nicknameForOther\""
        }
        // One quiet hour without the other is not a window (Task 2's invariant).
        putSettings(ada, bondId, """{"reminderTimeLocal":"07:30","quietHoursStart":"22:00"}""").status shouldBe 422
    }

    @Test
    fun `a non-member is refused the settings routes with the same 404`() {
        val ada = users.verified("Ada")
        val eve = users.verified("Eve")
        val bondId = bondIdOf(createBond(ada))

        getSettings(eve, bondId).status shouldBe 404
        putSettings(eve, bondId, """{"reminderTimeLocal":"07:30"}""").status shouldBe 404
    }
```

  And the helpers:

```kotlin
    private fun getSettings(
        userId: UUID,
        bondId: String,
    ): MockHttpServletResponse =
        mockMvc
            .get("/api/v1/bonds/$bondId/members/me/settings") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }
            .andReturn()
            .response

    private fun putSettings(
        userId: UUID,
        bondId: String,
        body: String,
    ): MockHttpServletResponse =
        mockMvc
            .put("/api/v1/bonds/$bondId/members/me/settings") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response

    private fun accept(
        userId: UUID,
        code: String,
    ): MockHttpServletResponse =
        mockMvc.post("/api/v1/invites/$code/accept") { header(HttpHeaders.AUTHORIZATION, bearer(userId)) }.andReturn().response

    private fun codeOf(response: MockHttpServletResponse): String =
        Regex(""""code":"([A-Z0-9]{6})"""").find(response.contentAsString)!!.groupValues[1]
```

- [ ] **Step 2: Run and watch it fail.** `./gradlew :modules:bond:test --tests '*BondSettingsEndpointTest'` — expected FAIL on the settings cases (404/405).

- [ ] **Step 3: Write the service:**

```kotlin
package com.moyi.bond.service

import com.moyi.bond.domain.Member
import com.moyi.bond.domain.MemberSettings
import com.moyi.bond.domain.Membership
import com.moyi.bond.infra.database.BondStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * `GET` and `PUT /bonds/{bondId}/members/me/settings` (doc 06 §3.3,
 * `states.md` §8).
 *
 * **No `If-Match` here, and that is a decision rather than an omission**
 * (ADR-0029): the row belongs to one member, nobody else can write it, so
 * there is no update for a concurrent writer to lose. Requiring a condition
 * where nothing can conflict is ceremony, and ceremony teaches clients to send
 * headers without meaning them.
 *
 * `me` is the only member this can name. There is no member id in the route, so
 * "never show the other member's settings" is not a check that could be
 * forgotten — it is the absence of a way to ask.
 */
@Service
internal class MemberSettingsService(
    private val bonds: BondStore,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** @throws BondNotFoundException the caller's membership went away between the guard and here */
    @Transactional(readOnly = true)
    fun of(membership: Membership): Member = load(membership)

    /**
     * Replaces all five fields (a `PUT`).
     *
     * @throws BondArchivedException the bond has ended — writes stop, reads do
     *   not (ADR-0028 decision 4)
     */
    @Transactional
    fun replace(
        membership: Membership,
        settings: MemberSettings,
    ): Member {
        val bond = bonds.findByMember(membership.bondId, membership.userId) ?: throw BondNotFoundException()
        if (!bond.isOpen) throw BondArchivedException()
        val updated = bond.memberOf(membership.userId)?.withSettings(settings) ?: throw BondNotFoundException()
        bonds.updateMember(updated)
        // No user id and no setting values: a reminder time is personal data
        // about when somebody is awake (doc 18 §5).
        log.info("Member settings updated in bond {}", membership.bondId.value)
        return updated
    }

    private fun load(membership: Membership): Member {
        val bond = bonds.findByMember(membership.bondId, membership.userId) ?: throw BondNotFoundException()
        return bond.memberOf(membership.userId) ?: throw BondNotFoundException()
    }
}
```

- [ ] **Step 4: Write the wire types.** `MemberSettingsResponse.kt`:

```kotlin
package com.moyi.bond.web

import com.moyi.bond.domain.Member
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * One member's own settings (doc 06 §3.3).
 *
 * The caller's, always — `states.md` §8 forbids showing the other member's, and
 * the route has no member id in it. Times are `HH:mm` like every other time of
 * day in this API (doc 06 §1), formatted rather than serialised because
 * `LocalTime`'s own form is `07:30:00`.
 */
internal data class MemberSettingsResponse(
    val nicknameForOther: String?,
    val reminderTimeLocal: String,
    val reminderTimezone: String,
    val quietHoursStart: String?,
    val quietHoursEnd: String?,
) {
    companion object {
        private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        fun from(member: Member) =
            MemberSettingsResponse(
                nicknameForOther = member.nicknameForOther,
                reminderTimeLocal = HH_MM.format(member.reminderTimeLocal),
                reminderTimezone = member.reminderTimezone.id,
                quietHoursStart = member.quietHoursStart?.let(HH_MM::format),
                quietHoursEnd = member.quietHoursEnd?.let(HH_MM::format),
            )
    }
}
```

  `MemberSettingsRequest.kt`:

```kotlin
package com.moyi.bond.web

import com.moyi.bond.domain.Member
import com.moyi.bond.domain.MemberSettings
import com.moyi.bond.domain.RegionZone
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.LocalTime

/**
 * The wire format of `PUT /bonds/{bondId}/members/me/settings`.
 *
 * A `PUT`, so this is the whole of the member's settings and an absent field is
 * a cleared one — except `reminderTimezone`, which keeps its current value when
 * absent, because silently resetting somebody's zone as a side effect of
 * editing a nickname is the kind of damage doc 04 §6 is about.
 *
 * `reminderTimeLocal` is required: it is the one field a member cannot be
 * without, and a `PUT` that omits it should be told so rather than quietly set
 * back to 20:00.
 */
internal data class MemberSettingsRequest(
    @field:Size(min = 1, max = Member.MAX_NICKNAME_LENGTH)
    val nicknameForOther: String? = null,
    @field:NotNull
    @field:Pattern(regexp = TIME_OF_DAY, message = "must be a time of day such as 21:00")
    val reminderTimeLocal: String? = null,
    @field:ValidRegionZone
    val reminderTimezone: String? = null,
    @field:Pattern(regexp = TIME_OF_DAY, message = "must be a time of day such as 22:00")
    val quietHoursStart: String? = null,
    @field:Pattern(regexp = TIME_OF_DAY, message = "must be a time of day such as 07:00")
    val quietHoursEnd: String? = null,
) {
    /**
     * Both quiet hours or neither — the aggregate's invariant, restated at the
     * edge so the answer is a `422` naming the pair rather than a 500 from a
     * `require` deeper down. `BondConstraints`' KDoc explains why the edge
     * *delegates* rather than restates wherever the domain can be asked; this
     * one cannot be asked without building a `Member`, so it is stated twice
     * and the domain keeps the last word.
     */
    @get:AssertTrue(message = "need both a start and an end, or neither")
    val quietHours: Boolean get() = (quietHoursStart == null) == (quietHoursEnd == null)

    fun toSettings() =
        MemberSettings(
            nicknameForOther = nicknameForOther?.trim(),
            reminderTimeLocal = LocalTime.parse(reminderTimeLocal),
            reminderTimezone = reminderTimezone?.takeIf { it.isNotBlank() }?.let { RegionZone.of(it.trim()) },
            quietHoursStart = quietHoursStart?.let(LocalTime::parse),
            quietHoursEnd = quietHoursEnd?.let(LocalTime::parse),
        )

    private companion object {
        const val TIME_OF_DAY = "^([01]\\d|2[0-3]):[0-5]\\d$"
    }
}
```

- [ ] **Step 5: Write the controller:**

```kotlin
package com.moyi.bond.web

import com.moyi.bond.domain.BondId
import com.moyi.bond.domain.UserId
import com.moyi.bond.service.BondAccessGuard
import com.moyi.bond.service.BondNotFoundException
import com.moyi.bond.service.MemberSettingsService
import com.moyi.common.security.CurrentUser
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * One member's own notification settings (doc 06 §3.3, `states.md` §8).
 *
 * Its own controller rather than two more methods on `BondsController`, because
 * the resource is different: that one is about the bond both members share, this
 * is about the row only the caller can see. The path says `me` and takes no
 * member id, so there is no way to ask for anybody else's — the structural half
 * of "never show the partner's settings".
 *
 * Method names are API names (springdoc builds `operationId` from the method
 * name alone; `OpenApiContractTest` fails on a collision).
 */
@RestController
@RequestMapping("/api/v1/bonds/{bondId}/members/me/settings")
internal class BondMemberSettingsController(
    private val guard: BondAccessGuard,
    private val settings: MemberSettingsService,
) {
    @GetMapping
    fun getMemberSettings(
        caller: CurrentUser,
        @PathVariable bondId: String,
    ): MemberSettingsResponse {
        val membership = guard.membershipOf(UserId(caller.id), bondIdOrNotFound(bondId))
        return MemberSettingsResponse.from(settings.of(membership))
    }

    @PutMapping
    fun replaceMemberSettings(
        caller: CurrentUser,
        @PathVariable bondId: String,
        @Valid @RequestBody request: MemberSettingsRequest,
    ): MemberSettingsResponse {
        val membership = guard.membershipOf(UserId(caller.id), bondIdOrNotFound(bondId))
        return MemberSettingsResponse.from(settings.replace(membership, request.toSettings()))
    }

    private fun bondIdOrNotFound(raw: String): BondId =
        BondId(runCatching { UUID.fromString(raw) }.getOrElse { throw BondNotFoundException() })
}
```

- [ ] **Step 6: Run the tests.** `./gradlew :modules:bond:test --tests '*BondSettingsEndpointTest'` — expected PASS. If the non-member `PUT` with a valid body returns `422` rather than `404`, that is the `@Valid`-before-guard trap again: fix it the same way as in Task 4.

- [ ] **Step 7: Commit.**

```bash
git add modules/bond
git commit -m "feat(bond): a member's own settings, read and replaced (FR-006, states.md §8)" -m "One controller for the row only the caller can see, at a path with no member id in it — so 'never show the other member's settings' is the absence of a way to ask rather than a check somebody could forget. A PUT because these five fields are one screen: it replaces, and an absent field is cleared. No If-Match, deliberately: nobody else can write this row, so there is no update to lose." -m "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GPjbihrTNz2YwDjuwUmysH"
```

---

### Task 6: The cross-tenant fixtures, and the race

**Files:**
- Modify: `modules/bond/src/test/kotlin/com/moyi/bond/web/BondCrossTenantTest.kt`
- Test: `modules/bond/src/test/kotlin/com/moyi/bond/web/BondSettingsRaceTest.kt` (new)

- [ ] **Step 1: Run the cross-tenant suite and let it fail.** `./gradlew :modules:bond:test --tests '*BondCrossTenantTest'` — expected FAIL naming the three new routes. Read the message before fixing it.

- [ ] **Step 2: Add the three fixtures:**

```kotlin
            // Slice B4. The PATCH needs a body *and* an `If-Match`, or it is
            // refused at 428 before the guard is what is being tested — which
            // would make this case pass for the wrong reason.
            "PATCH /api/v1/bonds/{bondId}" to
                Fixture(
                    body = """{"name":"Mine"}""",
                    headers = mapOf(HttpHeaders.IF_MATCH to "\"0\""),
                ),
            "GET /api/v1/bonds/{bondId}/members/me/settings" to Fixture(),
            "PUT /api/v1/bonds/{bondId}/members/me/settings" to Fixture(body = """{"reminderTimeLocal":"07:30"}"""),
```

  Run it again — expected PASS, with all three answering the same 404 as a stranger, a random id and a value that is not an id.

- [ ] **Step 3: Write the race test.** New file `BondSettingsRaceTest.kt`, using `InviteRaceTest`'s `inParallel` shape (copy it; it is six lines and a shared helper across test classes in different packages is not worth a module):

```kotlin
    @Test
    fun `two patches with the same ETag - exactly one wins`() {
        // ADR-0029's promise under concurrency. Both requests read version 0,
        // both pass the If-Match check, and only one row update can commit —
        // the loser gets the 412 it would have got had it been a second later,
        // which is the same fact.
        val ada = users.verified("Ada")
        val bondId = bondIdOf(createBond(ada))

        val statuses = inParallel(listOf({ patch(ada, bondId, """{"name":"First"}""", "\"0\"") }, { patch(ada, bondId, """{"name":"Second"}""", "\"0\"") })).map { it.status }

        statuses shouldContainExactlyInAnyOrder listOf(200, 412)
        jdbc.queryForObject("SELECT version FROM bonds", Int::class.java) shouldBe 1
        // And the winner's write is intact — not a mixture of the two.
        jdbc.queryForObject("SELECT name FROM bonds", String::class.java) shouldBeIn listOf("First", "Second")
    }

    @Test
    fun `a patch and a member settings write do not block each other`() {
        // Different rows, so neither has to wait and neither invalidates the
        // other. If this ever fails with a 412 or a timeout, something has
        // started locking the bond row for a member-settings write.
        val ada = users.verified("Ada")
        val bondId = bondIdOf(createBond(ada))

        val statuses =
            inParallel(
                listOf(
                    { patch(ada, bondId, """{"name":"First"}""", "\"0\"") },
                    { putSettings(ada, bondId, """{"reminderTimeLocal":"07:30"}""") },
                ),
            ).map { it.status }

        statuses shouldContainExactlyInAnyOrder listOf(200, 200)
    }
```

- [ ] **Step 4: Run it.** `./gradlew :modules:bond:test --tests '*BondSettingsRaceTest'` — expected PASS. If both patches return 200, `saveAndFlush` is not flushing or `@Version` is not mapped; fix the code, not the test.

- [ ] **Step 5: Commit.**

```bash
git add modules/bond/src/test
git commit -m "test(bond): the three routes in the cross-tenant suite, and two patches on one ETag" -m "The suite failed naming all three routes before the fixtures were added. The race test is the half of ADR-0029 the If-Match check cannot provide on its own: both callers pass it, and exactly one row update commits." -m "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GPjbihrTNz2YwDjuwUmysH"
```

---

### Task 7: The contract

**Files:**
- Modify: `contracts/src/main/kotlin/com/moyi/contracts/OpenApiConfiguration.kt`, `app/src/test/kotlin/com/moyi/app/OpenApiContractTest.kt`, `contracts/openapi.json`

- [ ] **Step 1: Add the failing expectations** to `OpenApiContractTest` — the three paths in the route list:

```kotlin
                "/api/v1/bonds/{bondId}/members/me/settings",
```

  (`PATCH /api/v1/bonds/{bondId}` is the same path as the `GET`, so only the settings path is new.)

  And a test:

```kotlin
    @Test
    fun `the conditional update documents its If-Match, its 428 and its 412`() {
        // Doc 06 §1. A generated client has to be able to *send* the condition
        // and to model both ways it can fail, or the concurrency control is
        // something the client author has to discover by reading prose.
        val patch = api.paths["/api/v1/bonds/{bondId}"]!!.patch

        patch.responses.keys shouldContainAll listOf("200", "404", "409", "412", "422", "428")
        patch.parameters.map { it.name } shouldContain HttpHeaders.IF_MATCH
        patch.parameters.first { it.name == HttpHeaders.IF_MATCH }.`in` shouldBe "header"
        patch.responses["200"]!!.headers.orEmpty() shouldContainKey "ETag"

        // The member-settings endpoints take no condition, and say so by not
        // documenting one (ADR-0029: nobody else can write that row).
        val settings = api.paths["/api/v1/bonds/{bondId}/members/me/settings"]!!
        settings.put.parameters.orEmpty().map { it.name } shouldNotContain HttpHeaders.IF_MATCH
        settings.put.responses.keys shouldContainAll listOf("200", "404", "409", "422")
        settings.get.responses shouldContainKey "404"
    }
```

- [ ] **Step 2: Run it and watch it fail.** `./gradlew :app:test --tests '*OpenApiContractTest'` — expected FAIL: the new statuses are not in the document, and the committed file is stale.

- [ ] **Step 3: Teach the customizer.** In `OpenApiConfiguration`:

```kotlin
            if (operation.operationId in CONFLICTING_OPERATIONS) add(HttpStatus.CONFLICT)
            if (operation.operationId in CONDITIONAL_OPERATIONS) {
                addAll(listOf(HttpStatus.PRECONDITION_FAILED, HttpStatus.PRECONDITION_REQUIRED))
            }
```

  and in the companion, alongside `CONFLICTING_OPERATIONS`:

```kotlin
        /**
         * Operations that require `If-Match` (doc 06 §1) and can therefore answer
         * `412` and `428`. By id for the same reason the conflict list is: it is
         * a property of the operation's rule, not of its shape — the member
         * settings `PUT` has a body and a path parameter exactly like the bond
         * `PATCH`, and takes no condition at all (ADR-0029).
         */
        private val CONDITIONAL_OPERATIONS = setOf("patchBond")
```

  Add `patchBond` and `replaceMemberSettings` to `CONFLICTING_OPERATIONS` (both answer `409 BOND_ARCHIVED`).

- [ ] **Step 4: Regenerate and read the diff.**

```bash
./gradlew :app:test --tests '*OpenApiContractTest'   # writes app/build/openapi/openapi.json, fails on the stale committed file
cp app/build/openapi/openapi.json contracts/openapi.json
./gradlew :app:test --tests '*OpenApiContractTest'   # expected PASS
git diff contracts/openapi.json | head -100
```

  Expected in the diff: a `patch` on `/api/v1/bonds/{bondId}` with an `If-Match` header parameter, `200` + `ETag`, `404`, `409`, `412`, `422`, `428`; the new settings path with `get` and `put`; `MemberSettingsRequest`/`MemberSettingsResponse` schemas; and **two new values in the `ProblemDetail` `code` enum** — which is the breaking part. Nothing else.

- [ ] **Step 5: Commit.**

```bash
git add contracts app/src/test/kotlin/com/moyi/app/OpenApiContractTest.kt
git commit -m "feat(contracts): the conditional update and the settings endpoints (ADR-0024)" -m "The PATCH documents its If-Match parameter, its 412 and its 428, and its 200 carries the ETag by the existing schema-keyed rule. The settings PUT documents no condition, which is the contract saying what ADR-0029 decided. Two new ErrorCode values, so this is a breaking change and the PR is labelled." -m "Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01GPjbihrTNz2YwDjuwUmysH"
```

---

### Task 8: Verify by running, then the ADR, the corpus and the PR

- [ ] **Step 1: The whole build.** `./gradlew build` — ktlint, detekt, every module's tests, JaCoCo 80%, Konsist. Expected PASS. Konsist will check that `UpdateBond.patch` and `MemberSettingsService`'s functions take a `Membership` and not a `BondId`; they do.

- [ ] **Step 2: Add the B4 smoke section** to `scripts/smoke.sh`, after the ending section. Reuse `verified_account` (added in B3) and `bond_body`:

```bash
echo; echo "settings — the conditional update (FR-027, doc 06 §1, ADR-0029)"
flush_buckets
verified_account "settler" "203.0.113.60"; SETTLER_ACCESS="$ACCOUNT_ACCESS"
expect "a bond to configure is 201" 201 '"name":"Us"' -- -X POST "$API/bonds" -H "Authorization: Bearer $SETTLER_ACCESS" -d "$(bond_body "Us")"
SET_BOND="$(printf '%s' "$LAST_BODY" | jget id)"
header_is "…with an ETag of 0" ETag '"0"'

expect "a PATCH with no If-Match is 428" 428 '"code":"PRECONDITION_REQUIRED"' -- -X PATCH "$API/bonds/$SET_BOND" -H "Authorization: Bearer $SETTLER_ACCESS" -d '{"name":"Us two"}'
expect "a PATCH with a stale If-Match is 412" 412 '"code":"PRECONDITION_FAILED"' -- -X PATCH "$API/bonds/$SET_BOND" -H "Authorization: Bearer $SETTLER_ACCESS" -H 'If-Match: "7"' -d '{"name":"Us two"}'
expect "If-Match: * is 428, deliberately (ADR-0029)" 428 '"code":"PRECONDITION_REQUIRED"' -- -X PATCH "$API/bonds/$SET_BOND" -H "Authorization: Bearer $SETTLER_ACCESS" -H 'If-Match: *' -d '{"name":"Us two"}'
expect "a weak validator is 412" 412 '"code":"PRECONDITION_FAILED"' -- -X PATCH "$API/bonds/$SET_BOND" -H "Authorization: Bearer $SETTLER_ACCESS" -H 'If-Match: W/"0"' -d '{"name":"Us two"}'
expect "the right If-Match is 200" 200 '"name":"Us two"' -- -X PATCH "$API/bonds/$SET_BOND" -H "Authorization: Bearer $SETTLER_ACCESS" -H 'If-Match: "0"' -d '{"name":"Us two","strictMode":true}'
header_is "…and the response carries the NEW ETag" ETag '"1"'
expect "the same If-Match again is 412 — it is spent" 412 '"code":"PRECONDITION_FAILED"' -- -X PATCH "$API/bonds/$SET_BOND" -H "Authorization: Bearer $SETTLER_ACCESS" -H 'If-Match: "0"' -d '{"name":"Us three"}'
expect "an empty patch is 422" 422 '"code":"VALIDATION_FAILED"' -- -X PATCH "$API/bonds/$SET_BOND" -H "Authorization: Bearer $SETTLER_ACCESS" -H 'If-Match: "1"' -d '{}'
expect "the anchor zone is refused here (FR-027 is B5's)" 422 '"field":"anchorTimezone"' -- -X PATCH "$API/bonds/$SET_BOND" -H "Authorization: Bearer $SETTLER_ACCESS" -H 'If-Match: "1"' -d '{"anchorTimezone":"Europe/London"}'
expect "a named null clears the reveal time" 200 '"revealTimeLocal":null' -- -X PATCH "$API/bonds/$SET_BOND" -H "Authorization: Bearer $SETTLER_ACCESS" -H 'If-Match: "1"' -d '{"revealTimeLocal":null}'
[[ "$LAST_BODY" == *'"strictMode":true'* ]] && pass "…and leaves the setting it did not name" || fail "patch isolation" "${LAST_BODY:0:250}"

expect "the member reads their own settings" 200 '"reminderTimeLocal":"20:00"' -- "$API/bonds/$SET_BOND/members/me/settings" -H "Authorization: Bearer $SETTLER_ACCESS"
expect "and replaces them" 200 '"quietHoursEnd":"07:00"' -- -X PUT "$API/bonds/$SET_BOND/members/me/settings" -H "Authorization: Bearer $SETTLER_ACCESS" -d '{"nicknameForOther":"Ada","reminderTimeLocal":"07:30","reminderTimezone":"Europe/London","quietHoursStart":"22:00","quietHoursEnd":"07:00"}'
expect "a PUT that omits a field clears it" 200 '"nicknameForOther":null' -- -X PUT "$API/bonds/$SET_BOND/members/me/settings" -H "Authorization: Bearer $SETTLER_ACCESS" -d '{"reminderTimeLocal":"21:00"}'
[[ "$LAST_BODY" == *'"reminderTimezone":"Europe/London"'* ]] && pass "…but keeps the zone, which is not the caller's to lose" || fail "zone reset" "${LAST_BODY:0:250}"
expect "one quiet hour without the other is 422" 422 '"code":"VALIDATION_FAILED"' -- -X PUT "$API/bonds/$SET_BOND/members/me/settings" -H "Authorization: Bearer $SETTLER_ACCESS" -d '{"reminderTimeLocal":"21:00","quietHoursStart":"22:00"}'
expect "the member settings write did not move the bond's ETag" 200 '"name":"Us two"' -- "$API/bonds/$SET_BOND" -H "Authorization: Bearer $SETTLER_ACCESS"
header_is "…still the version the last PATCH produced" ETag '"2"'
expect "a stranger patching it is 404, headers and all" 404 '"code":"NOT_FOUND"' -- -X PATCH "$API/bonds/$SET_BOND" -H "Authorization: Bearer $STRANGER_ACCESS" -H 'If-Match: "2"' -d '{"name":"Mine"}'
expect "…and cannot read its settings either" 404 '"code":"NOT_FOUND"' -- "$API/bonds/$SET_BOND/members/me/settings" -H "Authorization: Bearer $STRANGER_ACCESS"
expect "an archived bond takes no settings write" 204 "" -- -X POST "$API/bonds/$SET_BOND/leave" -H "Authorization: Bearer $SETTLER_ACCESS"
expect "…the PATCH is 409 BOND_ARCHIVED" 409 '"code":"BOND_ARCHIVED"' -- -X PATCH "$API/bonds/$SET_BOND" -H "Authorization: Bearer $SETTLER_ACCESS" -H 'If-Match: "3"' -d '{"name":"Us four"}'
expect "…the settings PUT is too" 409 '"code":"BOND_ARCHIVED"' -- -X PUT "$API/bonds/$SET_BOND/members/me/settings" -H "Authorization: Bearer $SETTLER_ACCESS" -d '{"reminderTimeLocal":"21:00"}'
expect "…and the settings are still readable" 200 '"reminderTimeLocal":"21:00"' -- "$API/bonds/$SET_BOND/members/me/settings" -H "Authorization: Bearer $SETTLER_ACCESS"
```

  The `ETag` values in the later probes depend on how many successful patches ran; count them as you go and fix the literals rather than guessing.

- [ ] **Step 3: Run the smoke script against the jar.**

```bash
docker compose up -d
./gradlew :app:bootJar
PORT=18086 scripts/smoke.sh --no-build
```

  Expected 0 failures, and a probe count above B3's 192. Record it.

- [ ] **Step 4: Mutation-check the four load-bearing pieces.** Break, run the named test, confirm it fails for the right reason, revert. Record all four in the learning log.

  1. **The `If-Match` check.** Delete `if (!ifMatch.matches(bond.version)) throw PreconditionFailedException()` → `BondSettingsEndpointTest.a stale If-Match is 412 and changes nothing` must fail.
  2. **The flush.** Change `saveAndFlush` back to `save` in `BondStore.update` → `BondSettingsRaceTest.two patches with the same ETag` must fail (expect a 500 rather than a 412, which is the point).
  3. **The guard-before-precondition order.** Move `IfMatch.parse(ifMatch)` above `guard.membershipOf(...)` in `patchBond` → `the guard runs before the precondition` must fail with a 428 for a non-member.
  4. **`Change`.** Replace `settings.revealTimeLocal?.value ?: revealTimeLocal.takeIf { settings.revealTimeLocal == null }` with a plain `settings.revealTimeLocal?.value ?: revealTimeLocal` → `a named null clears the reveal time` must fail, because a sent `null` would no longer clear.

- [ ] **Step 5: Write ADR-0029** at `adr/0029-optimistic-concurrency-over-http.md`, in ADR-0026–0028's shape:
  - **Context** — doc 06 §1's one sentence, the lost-update problem it exists to prevent, `states.md` §8, and the fact that `@Version` was added in B1 for exactly this slice.
  - **Decision** — the `ETag` is the row version; `If-Match` required on `PATCH /bonds/{id}`; `428` absent, `412` stale; **two checks** (header, then the database at flush) and why each alone is insufficient; the flush is what makes the second catchable; member settings take no condition, because nobody else can write that row; `type` patchable and the seats unmoved; `anchorTimezone` refused rather than ignored; an empty patch is `422`; `PUT` replaces but keeps the reminder zone.
  - **The RFC deviations** — `*` is `428`, an unparseable validator is `412`, and a weak validator never matches (that last one *is* RFC 9110). State plainly that the first is non-compliant and why we prefer it to a decorative requirement.
  - **Consequences** — two new `ErrorCode` values, so a breaking change; B5's timezone and deletion endpoints inherit the pattern; Phase 3's entries will need the same treatment and `IfMatch` is already in `common:web` for them; the client must keep the `ETag` from *every* bond response, which is why the header is declared by schema rather than by path.
  - **Alternatives** — a `version` field in the body (rejected: HTTP has a place for this, and a body field is one more thing to get out of sync); last-write-wins (rejected: it is the bug); a `PATCH` on member settings (rejected: five fields on one screen, and `PUT` needs no `Change` wrapper); requiring `If-Match` on member settings too (rejected as ceremony).

- [ ] **Step 6: Copy the ADR to the corpus and amend the docs** on the Gratitude repo's `docs/bonds-phase-2` branch:

```bash
cp adr/0029-optimistic-concurrency-over-http.md ../documents/adr/
```

  Then in `../documents`: doc 06 §3.3's rows 10–12 (the statuses, the `If-Match` requirement, `type` patchable, `anchorTimezone` refused here, the settings `PUT` shape) and §1's concurrency line (the `ETag` *is* the row version; `*` refused); doc 00's amendment log; `states.md` §8 (what the API now enforces, and that a settings write does not invalidate the other member's `ETag`). Commit there with the same trailer.

- [ ] **Step 7: The learning-log entry** in `docs/learning-log.md` — Expected / Reality / Wrong about. Name the four mutation results, whether the `@Valid`-before-guard trap was real, and what `Optional` in a Kotlin data class actually did.

- [ ] **Step 8: Update `.claude/HANDOVER.md`** — the B4 row in §3's table, §4's next step rewritten for **B5 — consent** (V10, `bond_proposals`, #13–#17, and ADR-0028's obligation that ending a bond cancels a live proposal), the new counts, and any gotcha this slice found.

- [ ] **Step 9: Final verification and the PR.**

```bash
./gradlew build
git log --oneline feat/bond-ending..HEAD
git push -u origin feat/bond-settings
```

  PR body from `.github/PULL_REQUEST_TEMPLATE.md`, with PR #39 as the model: what and why; the note that it is **stacked on #39** and rebases once that merges; the **concept brief** (optimistic concurrency over HTTP — `ETag`/`If-Match`, the difference between application-level and database-level checks, and the market gaps: no `ETag`s on collections, no `If-Unmodified-Since`, no CRDT/merge strategy); the **Figma alignment** table (bond name editor, relationship type, reveal time picker, rest days toggle, *Your reminder*, and the **nickname gap** `states.md` §11 records); the ten hostile-reviewer questions answered honestly; the DoD; and the **`breaking-api-change` label with the two new codes named**. Then leave it for Daniel — **do not merge.**

---

## Done when

1. `./gradlew build` green; `scripts/smoke.sh` green with the B4 section and its probe count recorded.
2. All eleven `PATCH`/settings statuses from §5.2 rows #10–#12 have a test, including `428`, `412`, the `*` and weak-validator decisions, and the `409` on an archived bond.
3. A race test proves two patches on one `ETag` produce exactly one `200` and one `412`.
4. The three new routes are in `BondCrossTenantTest.fixtures`, added *after* watching the suite fail without them — and a non-member gets `404` regardless of headers or body.
5. Each of the four guards verified by breaking it, with the result in the learning log.
6. ADR-0029 here and in `../documents/adr/`; doc 06 §1 and §3.3, doc 00's log and `states.md` §8 amended on `docs/bonds-phase-2`; a learning-log entry; `.claude/HANDOVER.md` current.
7. PR opened, labelled `breaking-api-change`, with the template, the concept brief and the Figma table — and **not merged**.
