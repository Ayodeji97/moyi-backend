package com.moyi.media.service

import com.moyi.common.security.VerifiedEmails
import com.moyi.media.api.AttachableKind
import com.moyi.media.api.MediaAttachments
import com.moyi.media.domain.MediaKind
import com.moyi.media.infra.database.MediaAssetStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * [MediaAttachments]: FR-044a as one conditional update ([MediaAssetStore.claim]),
 * in the caller's transaction (`MANDATORY`: outside one, Spring refuses the call
 * rather than committing a claim apart from its entry).
 *
 * An owner whose address is not verified is refused before the update, `403
 * EMAIL_NOT_VERIFIED` (ADR-0049), and the caller's transaction rolls back with
 * it. Not reachable through the API today: FR-002 keeps an unverified account
 * out of every bond, so it never reaches an entry, and it cannot make an asset
 * to claim. It is here so that the rule holds for whatever later lets one in,
 * rather than resting on two other modules' rules.
 */
@Service
internal class AttachMedia(
    private val assets: MediaAssetStore,
    private val clock: Clock,
    private val verifiedEmails: VerifiedEmails,
) : MediaAttachments {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun claim(
        owner: UUID,
        mediaId: UUID,
        kind: AttachableKind,
        entryId: UUID,
    ): Boolean {
        verifiedEmails.requireVerified(owner)
        return assets.claim(owner, mediaId, kind.asMediaKind(), entryId)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun release(
        owner: UUID,
        mediaId: UUID,
        entryId: UUID,
    ) {
        // Stamped, so the reaper gives the photo a day from now rather than from when it was made (D5).
        assets.release(owner, mediaId, entryId, clock.instant().truncatedTo(ChronoUnit.MICROS))
    }

    private fun AttachableKind.asMediaKind(): MediaKind =
        when (this) {
            AttachableKind.IMAGE -> MediaKind.IMAGE
            AttachableKind.VOICE -> MediaKind.VOICE
        }
}
