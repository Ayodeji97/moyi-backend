package com.moyi.media.service

import com.moyi.media.api.MediaErasure
import com.moyi.media.infra.database.MediaAssetStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * [MediaErasure] (spec D-9): one conditional update, keyed by the attachment,
 * and one `MediaReleased` for each asset it deleted, all in the erasure's
 * transaction (`MANDATORY`: outside one, Spring refuses the call).
 *
 * It touches no storage. The objects go after the commit, in `media.purge`'s
 * delivery, which is retried until it succeeds; deleting them here would lose
 * the bytes of a photo whose erasure then rolled back.
 */
@Service
internal class ReleaseErasedMedia(
    private val assets: MediaAssetStore,
    private val releases: MediaReleases,
    private val clock: Clock,
) : MediaErasure {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun releaseAllOf(entryId: UUID): Int {
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val released = assets.releaseAllOf(entryId, now)
        released.forEach { releases.released(it, now) }
        return released.size
    }
}
