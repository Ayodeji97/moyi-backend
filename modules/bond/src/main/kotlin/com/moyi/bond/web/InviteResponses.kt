package com.moyi.bond.web

import com.moyi.bond.domain.BondType
import com.moyi.bond.domain.Invite
import com.moyi.bond.service.InvitePreview
import java.net.URI
import java.time.Instant
import java.util.UUID

/**
 * `POST /bonds/{bondId}/invites` — the new code, its link and when it lapses.
 *
 * The same four fields as the `invite` inside a `BondResponse`, and
 * deliberately a separate type rather than a shared one: this is what a
 * *creation* returns, and if the two ever need to differ, they can without a
 * client noticing a field appear where it did not expect one.
 */
internal data class InviteDetailResponse(
    val id: UUID,
    val code: String,
    val link: URI,
    val expiresAt: Instant,
) {
    companion object {
        fun from(
            invite: Invite,
            link: URI,
        ): InviteDetailResponse =
            InviteDetailResponse(
                id = invite.id.value,
                code = invite.code.value,
                link = link,
                expiresAt = invite.expiresAt,
            )
    }
}

/**
 * `GET /invites/{code}` — the confirm screen (`states.md` §2).
 *
 * Three fields, and nothing else: no bond id, no member ids, no count, no
 * dates. The caller is not a member yet, and what they need in order to
 * decide is whose bond this is and what kind — everything further is theirs
 * to see once they have joined.
 */
internal data class InvitePreviewResponse(
    val bondName: String,
    val bondType: BondType,
    val inviterDisplayName: String,
) {
    companion object {
        fun from(preview: InvitePreview): InvitePreviewResponse =
            InvitePreviewResponse(
                bondName = preview.bondName,
                bondType = preview.bondType,
                inviterDisplayName = preview.inviterDisplayName,
            )
    }
}
