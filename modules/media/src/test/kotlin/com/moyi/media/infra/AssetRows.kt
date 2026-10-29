package com.moyi.media.infra

import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Assets in exactly the state a lifecycle test needs, written straight into
 * `media_assets`: the reaper and the demotion act on time and state, and
 * reaching a photo ninety days old through the pipeline would test the
 * pipeline. Ids are fresh v4s, as `media`'s are; keys are random, as an
 * upload's are, and a photo's variants are recorded under the keys derived from
 * its upload's, as `media.process` records them.
 */
internal class AssetRows(
    private val jdbc: JdbcTemplate,
) {
    @Suppress("LongParameterList") // Every column a lifecycle rule reads, each defaulted.
    fun insert(
        owner: UUID = UUID.randomUUID(),
        status: String = "READY",
        kind: String = "IMAGE",
        createdAt: Instant,
        readyAt: Instant? = if (status == "READY") createdAt else null,
        expiresAt: Instant? = createdAt.plusSeconds(UPLOAD_URL_SECONDS),
        attachedTo: UUID? = null,
        tier: Int = 1,
        detachedAt: Instant? = null,
        withDisplay: Boolean = true,
        withVariants: Boolean = status == "READY",
        upload: String = "in/${UUID.randomUUID()}",
        verdict: String? = if (status == "READY") "PASSED" else null,
    ): UUID {
        val id = UUID.randomUUID()
        val random = upload.removePrefix("in/")
        val full = """{"variant":"FULL","key":"v/$random/full.webp","width":2048,"height":1536,"bytes":180000}"""
        val display = """{"variant":"DISPLAY","key":"v/$random/display.webp","width":1280,"height":960,"bytes":75000}"""
        val variants =
            when {
                !withVariants -> "[]"
                tier == 2 -> "[$display]"
                withDisplay -> "[$full,$display]"
                else -> "[$full]"
            }
        jdbc.update(
            """
            INSERT INTO media_assets (id, owner_user_id, kind, storage_key, content_type, size_bytes, checksum, status, variants,
                                      width, height, attached_entry_id, tier, created_at, ready_at, expires_at, detached_at,
                                      deleted_at, moderation_verdict, moderated_at)
            VALUES (?, ?, ?, ?, 'image/jpeg', 1000, 'Zm9vYmFyZm9vYmFyZm9vYmFyZm9vYmFyZm9vYmFyMDA=', ?, ?::jsonb,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            owner,
            kind,
            upload,
            status,
            variants,
            if (withVariants) 2048 else null,
            if (withVariants) 1536 else null,
            attachedTo,
            tier,
            Timestamp.from(createdAt),
            readyAt?.let(Timestamp::from),
            expiresAt?.let(Timestamp::from),
            detachedAt?.let(Timestamp::from),
            if (status == "DELETED") Timestamp.from(createdAt) else null,
            verdict,
            verdict?.let { Timestamp.from(createdAt) },
        )
        return id
    }

    fun tierOf(id: UUID): Int = jdbc.queryForObject("SELECT tier FROM media_assets WHERE id = ?", Int::class.java, id)!!

    fun statusOf(id: UUID): String = jdbc.queryForObject("SELECT status FROM media_assets WHERE id = ?", String::class.java, id)!!

    fun row(id: UUID): Map<String, Any?> =
        jdbc.queryForMap(
            "SELECT status, tier, width, height, variants::text AS variants, attached_entry_id, deleted_at, purged_at, detached_at " +
                "FROM media_assets WHERE id = ?",
            id,
        )

    /** The events of [type] published for [id]. */
    fun events(
        id: UUID,
        type: String,
    ): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = ?",
            Int::class.java,
            id,
            type,
        )!!

    fun events(type: String): Int = jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = ?", Int::class.java, type)!!

    /** Everything a lifecycle test leaves: the rows and the outbox's history. */
    fun clear() {
        jdbc.execute("TRUNCATE TABLE media_assets, idempotency_keys, outbox_deliveries, outbox_events")
    }

    private companion object {
        /** An intent's upload URL lives fifteen minutes (spec §4 D1). */
        const val UPLOAD_URL_SECONDS = 15 * 60L
    }
}
