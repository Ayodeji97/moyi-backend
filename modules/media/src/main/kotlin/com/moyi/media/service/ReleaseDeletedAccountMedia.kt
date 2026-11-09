package com.moyi.media.service

import com.moyi.common.events.EventConsumer
import com.moyi.common.events.ReceivedEvent
import com.moyi.common.events.StartFrom
import com.moyi.media.infra.database.MediaAssetStore
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * `media`'s share of an account deletion (FR-008, ADR-0058 decision 9): every photo and voice
 * note the person uploaded, on an entry or on none, is deleted and one `MediaReleased` is
 * published for each, so `media.purge` deletes the objects from the storage after this commits,
 * retrying until it can (ADR-0050 decision 2). An asset held as evidence or by an open report
 * keeps its objects until a person has decided, whoever deleted it (ADR-0051, ADR-0053): the
 * purge knows that rule, and this does not restate it.
 *
 * `gratitude`'s erasure of the person's entries releases the attached ones too, through
 * `MediaErasure.releaseAllOf`; whichever runs first deletes them and the other finds nothing,
 * because both change only what is not already `DELETED`.
 *
 * `media` is a leaf (ADR-0047) and depends on no other domain module, so the event's type is
 * spelled here rather than read from identity's `AccountDeletions`.
 */
@Component
internal class ReleaseDeletedAccountMedia(
    private val assets: MediaAssetStore,
    private val releases: MediaReleases,
    private val clock: Clock,
) : EventConsumer {
    /** Never to be changed: delivery state is kept under it. */
    override val id = "media.account-deletion"
    override val eventTypes = setOf(EVENT)
    override val startFrom = StartFrom.BEGINNING

    @Transactional(propagation = Propagation.MANDATORY)
    override fun handle(event: ReceivedEvent) {
        val owner = checkNotNull(event.references[USER_ID]) { "An $EVENT event carries no '$USER_ID'." }
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        assets.releaseAllOwnedBy(owner, now).forEach { releases.released(it, now) }
    }

    private companion object {
        /** identity's `AccountDeletions.EVENT` and `USER_ID`, which a leaf cannot import. */
        const val EVENT = "AccountDeleted"
        const val USER_ID = "userId"
    }
}
