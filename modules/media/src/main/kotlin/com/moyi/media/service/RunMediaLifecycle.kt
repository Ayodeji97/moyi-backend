package com.moyi.media.service

import com.moyi.media.api.LifecycleRun
import com.moyi.media.api.MediaLifecycle
import com.moyi.media.infra.database.MediaAssetStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * [MediaLifecycle]: the reaper and the demotion (spec §4 D5), each one
 * statement that picks and changes under the row locks, and one event per
 * asset it changed, in one transaction. The rules are the statements'
 * ([MediaAssetStore.reap], [MediaAssetStore.demote]); this adds the
 * transaction and the events.
 *
 * **Nothing here reaches the storage**, so a run cannot be slowed or failed by
 * it, and runs the same whether a bucket is configured or not (production's
 * `unavailable` included): the objects are `media.purge`'s, which subscribes
 * only where there is a storage to delete from.
 *
 * The transaction joins a caller's if there is one: the attach-and-reap race
 * test holds a reaper's row locks open that way.
 */
@Service
internal class RunMediaLifecycle(
    private val assets: MediaAssetStore,
    private val releases: MediaReleases,
    private val transactions: TransactionTemplate,
) : MediaLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun reap(
        now: Instant,
        limit: Int,
    ): LifecycleRun {
        val run = run(now, limit, assets::reap, releases::released)
        // Counts only: which assets is nobody's business but their owners'.
        if (run.changed > 0) log.info("Media reaper: {} deleted", run.changed)
        return run
    }

    override fun demote(
        now: Instant,
        limit: Int,
    ): LifecycleRun {
        val run = run(now, limit, assets::demote, releases::demoted)
        if (run.changed > 0) log.info("Media demotion: {} demoted to tier 2", run.changed)
        return run
    }

    private fun run(
        now: Instant,
        limit: Int,
        change: (Instant, Int) -> List<UUID>,
        publish: (UUID, Instant) -> Unit,
    ): LifecycleRun {
        require(limit > 0) { "a run changes at least one asset" }
        val at = now.truncatedTo(ChronoUnit.MICROS)
        val changed =
            checkNotNull(
                transactions.execute {
                    change(at, limit).onEach { publish(it, at) }
                },
            ) { "the transaction returns what it changed" }
        return LifecycleRun(changed = changed.size, more = changed.size == limit)
    }
}
