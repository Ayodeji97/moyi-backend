package com.moyi.bond.web

import com.moyi.bond.domain.BondStatus
import com.moyi.bond.domain.BondType
import com.moyi.bond.domain.MemberRole
import com.moyi.bond.service.BondView
import com.moyi.bond.service.InviteView
import com.moyi.bond.service.MemberView
import java.net.URI
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * The one shape every bond endpoint returns — create, get, and each element of
 * the list — so a client parses one thing.
 *
 * What is **not** here is the interesting part. No `userId`: identity's ids
 * are identity's to hand out, and a member is named by their membership.
 * No reminder time, quiet hours or nickname: `states.md` §8 says never show
 * the other member's settings, and the simplest way to honour that is to have
 * no field that could carry them. No `version`: it travels as the `ETag`,
 * which is where HTTP already has a place for it.
 *
 * Doc 06 §3.3 also promises streak and today's status on a bond. Those arrive
 * with Phase 3 as additional fields, which is additive and not a breaking
 * change for a generated client.
 */
internal data class BondResponse(
    val id: UUID,
    val type: BondType,
    val name: String,
    val anchorTimezone: String,
    val revealTimeLocal: String?,
    val strictMode: Boolean,
    val status: BondStatus,
    val maxMembers: Int,
    val createdAt: Instant,
    val archivedAt: Instant?,
    val members: List<MemberResponse>,
    val me: MeResponse,
    val invite: InviteResponse?,
) {
    companion object {
        private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        fun from(view: BondView): BondResponse =
            BondResponse(
                id = view.bond.id.value,
                type = view.bond.type,
                name = view.bond.name,
                anchorTimezone = view.bond.anchorTimezone.id,
                // Formatted rather than serialised: LocalTime's default is
                // `21:00:00`, and the API takes and gives one shape (doc 06 §1).
                revealTimeLocal = view.bond.revealTimeLocal?.let(HH_MM::format),
                strictMode = view.bond.strictMode,
                status = view.bond.status,
                maxMembers = view.bond.maxMembers,
                createdAt = view.bond.createdAt,
                archivedAt = view.bond.archivedAt,
                members = view.members.map(MemberResponse::from),
                me = MeResponse(view.me.id.value, view.me.role),
                invite = view.invite?.let(InviteResponse::from),
            )

        /** The `ETag` for a bond: its row version, quoted, as RFC 9110 §8.8.3 requires. */
        fun etagOf(view: BondView): String = "\"${view.bond.version}\""
    }
}

/** One member, as the other is allowed to see them. */
internal data class MemberResponse(
    val id: UUID,
    val displayName: String,
    val role: MemberRole,
    val joinedAt: Instant,
    /** Non-null once they have left. The bond is archived by then, so this discloses nothing new. */
    val leftAt: Instant?,
) {
    companion object {
        fun from(view: MemberView): MemberResponse =
            MemberResponse(
                id = view.member.id.value,
                displayName = view.displayName,
                role = view.member.role,
                joinedAt = view.member.joinedAt,
                leftAt = view.member.leftAt,
            )
    }
}

/** Which of the members the caller is, so a client need not search the list for itself. */
internal data class MeResponse(
    val memberId: UUID,
    val role: MemberRole,
)

/** The live invite, present only while there is one (FR-022, FR-023). */
internal data class InviteResponse(
    val id: UUID,
    val code: String,
    val link: URI,
    val expiresAt: Instant,
) {
    companion object {
        fun from(view: InviteView): InviteResponse =
            InviteResponse(
                id = view.invite.id.value,
                code = view.invite.code.value,
                link = view.link,
                expiresAt = view.invite.expiresAt,
            )
    }
}

/** `GET /bonds`. An object rather than a bare array, so the response can grow a cursor if it ever needs one. */
internal data class BondsResponse(
    val bonds: List<BondResponse>,
)
