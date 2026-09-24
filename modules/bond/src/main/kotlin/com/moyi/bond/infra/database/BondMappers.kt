package com.moyi.bond.infra.database

import com.moyi.bond.domain.Block
import com.moyi.bond.domain.Bond
import com.moyi.bond.domain.BondId
import com.moyi.bond.domain.Invite
import com.moyi.bond.domain.InviteCode
import com.moyi.bond.domain.InviteId
import com.moyi.bond.domain.Member
import com.moyi.bond.domain.MemberId
import com.moyi.bond.domain.RegionZone
import com.moyi.bond.domain.UserId

/**
 * The translation between the domain model and the persistence model, as
 * plain functions — the `IdentityMappers` precedent, whose KDoc gives the
 * reason: MapStruct does not see Kotlin's value classes, and a reflective
 * mapper turns a rename into a runtime surprise instead of a compile error.
 *
 * The direction matters. [toDomain] is total — anything in the table can be
 * expressed. [toEntity] is **insert-only**: it builds a brand-new object whose
 * `isNew` flag is true, so handing its result to `save()` asks for an
 * `INSERT`. The update path arrives with slice B3 as `applyTo` functions,
 * which carry a changed domain object onto the managed entity it came from.
 *
 * `RegionZone.of` on the way **out** of the database re-validates the stored
 * id. A zone the JDK's tzdb has since dropped fails here, loudly, rather than
 * silently filing somebody's day under the wrong date (doc 04 §6).
 */
internal fun BondEntity.toDomain(members: List<BondMemberEntity>): Bond =
    Bond(
        id = BondId(getId()),
        type = type,
        name = name,
        anchorTimezone = RegionZone.of(anchorTimezone),
        timezoneChangedAt = timezoneChangedAt,
        revealTimeLocal = revealTimeLocal,
        strictMode = strictMode,
        status = status,
        maxMembers = maxMembers.toInt(),
        createdBy = UserId(createdBy),
        createdAt = createdAt,
        archivedAt = archivedAt,
        deletionRequestedAt = deletionRequestedAt,
        version = version,
        // Ordered by when people joined, so the owner is first and a response
        // does not reshuffle itself between requests.
        members = members.map { it.toDomain() }.sortedBy { it.joinedAt },
    )

/** Builds a new row. See the note above on why this is not the update path. */
internal fun Bond.toEntity(): BondEntity =
    BondEntity(
        id = id.value,
        type = type,
        name = name,
        anchorTimezone = anchorTimezone.id,
        timezoneChangedAt = timezoneChangedAt,
        revealTimeLocal = revealTimeLocal,
        strictMode = strictMode,
        status = status,
        maxMembers = maxMembers.toShort(),
        createdBy = createdBy.value,
        createdAt = createdAt,
        archivedAt = archivedAt,
        deletionRequestedAt = deletionRequestedAt,
        version = version,
    )

/**
 * Carries a changed [Bond] onto the managed entity it came from — the update
 * path, added in slice B2 for the status change a join makes.
 *
 * `id`, `createdBy` and `createdAt` are not copied: they are not the caller's
 * to change, and the columns are `updatable = false` so the database would
 * refuse anyway. Neither is `version`, which is Hibernate's to increment —
 * assigning it here would fight the optimistic lock rather than use it.
 *
 * The alternative, `save(bond.toEntity())`, is wrong in a way that does not
 * fail: it hands Hibernate a *detached* object claiming to be new, and the
 * `isNew` flag means it is persisted rather than merged. `IdentityMappers`
 * carries the same warning for the same reason.
 */
internal fun Bond.applyTo(entity: BondEntity) {
    require(entity.getId() == id.value) { "cannot apply a bond onto a different bond's row" }
    entity.type = type
    entity.name = name
    entity.anchorTimezone = anchorTimezone.id
    entity.timezoneChangedAt = timezoneChangedAt
    entity.revealTimeLocal = revealTimeLocal
    entity.strictMode = strictMode
    entity.status = status
    entity.maxMembers = maxMembers.toShort()
    entity.archivedAt = archivedAt
    entity.deletionRequestedAt = deletionRequestedAt
}

internal fun BondMemberEntity.toDomain(): Member =
    Member(
        id = MemberId(getId()),
        bondId = BondId(bondId),
        userId = UserId(userId),
        role = role,
        joinedAt = joinedAt,
        leftAt = leftAt,
        reminderTimeLocal = reminderTimeLocal,
        reminderTimezone = RegionZone.of(reminderTimezone),
        quietHoursStart = quietHoursStart,
        quietHoursEnd = quietHoursEnd,
        nicknameForOther = nicknameForOther,
    )

/** Insert-only, for the same reason as [Bond.toEntity]. */
internal fun Member.toEntity(): BondMemberEntity =
    BondMemberEntity(
        id = id.value,
        bondId = bondId.value,
        userId = userId.value,
        role = role,
        joinedAt = joinedAt,
        leftAt = leftAt,
        reminderTimeLocal = reminderTimeLocal,
        reminderTimezone = reminderTimezone.id,
        quietHoursStart = quietHoursStart,
        quietHoursEnd = quietHoursEnd,
        nicknameForOther = nicknameForOther,
    )

internal fun BondInviteEntity.toDomain(): Invite =
    Invite(
        id = InviteId(getId()),
        bondId = BondId(bondId),
        code = InviteCode(code),
        createdByMemberId = MemberId(createdByMemberId),
        expiresAt = expiresAt,
        usedAt = usedAt,
        usedByUserId = usedByUserId?.let(::UserId),
        revokedAt = revokedAt,
    )

/**
 * Insert-only. An invite's one state change — being spent — is a conditional
 * `UPDATE` in slice B2, never an `applyTo`, because it has to be atomic with
 * the check that it is still live.
 */
internal fun Invite.toEntity(): BondInviteEntity =
    BondInviteEntity(
        id = id.value,
        bondId = bondId.value,
        code = code.value,
        createdByMemberId = createdByMemberId.value,
        expiresAt = expiresAt,
        usedAt = usedAt,
        usedByUserId = usedByUserId?.value,
        revokedAt = revokedAt,
    )

internal fun BlockEntity.toDomain(): Block =
    Block(
        blockerUserId = UserId(blockerUserId),
        blockedUserId = UserId(blockedUserId),
        bondId = BondId(bondId),
        createdAt = createdAt,
    )

/** Insert-only. A block is never edited; if it is ever lifted, that is a delete. */
internal fun Block.toEntity(id: java.util.UUID): BlockEntity =
    BlockEntity(
        id = id,
        blockerUserId = blockerUserId.value,
        blockedUserId = blockedUserId.value,
        bondId = bondId.value,
        createdAt = createdAt,
    )
