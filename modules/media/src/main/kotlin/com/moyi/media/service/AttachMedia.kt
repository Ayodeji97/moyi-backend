package com.moyi.media.service

import com.moyi.media.api.AttachableKind
import com.moyi.media.api.MediaAttachments
import com.moyi.media.domain.MediaKind
import com.moyi.media.infra.database.MediaAssetStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * [MediaAttachments]: FR-044a as one conditional update ([MediaAssetStore.claim]),
 * in the caller's transaction (`MANDATORY`: outside one, Spring refuses the call
 * rather than committing a claim apart from its entry).
 */
@Service
internal class AttachMedia(
    private val assets: MediaAssetStore,
) : MediaAttachments {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun claim(
        owner: UUID,
        mediaId: UUID,
        kind: AttachableKind,
        entryId: UUID,
    ): Boolean = assets.claim(owner, mediaId, kind.asMediaKind(), entryId)

    @Transactional(propagation = Propagation.MANDATORY)
    override fun release(
        owner: UUID,
        mediaId: UUID,
        entryId: UUID,
    ) {
        assets.release(owner, mediaId, entryId)
    }

    private fun AttachableKind.asMediaKind(): MediaKind =
        when (this) {
            AttachableKind.IMAGE -> MediaKind.IMAGE
            AttachableKind.VOICE -> MediaKind.VOICE
        }
}
