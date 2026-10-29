package com.moyi.gratitude.infra

import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/**
 * A photo as `media.process` leaves one (Phase 4 D2), written straight into
 * `media_assets`: this module reaches `media` only through `media.api`, and its
 * tests need a photo in a given state, not the pipeline that makes one (that is
 * `modules/media`'s to test). The id is a fresh v4, as `media`'s are.
 */
internal class ProcessedPhotos(
    private val jdbc: JdbcTemplate,
) {
    fun ready(
        owner: UUID,
        status: String = "READY",
        kind: String = "IMAGE",
        verdict: String? = null,
    ): UUID {
        val id = UUID.randomUUID()
        val random = UUID.randomUUID()
        val variants =
            """[{"variant":"FULL","key":"v/$random/full.webp","width":1536,"height":2048,"bytes":200000},""" +
                """{"variant":"DISPLAY","key":"v/$random/display.webp","width":960,"height":1280,"bytes":90000}]"""
        jdbc.update(
            """
            INSERT INTO media_assets (id, owner_user_id, kind, storage_key, content_type, size_bytes, checksum, status, variants,
                                      moderation_verdict, created_at, deleted_at)
            VALUES (?, ?, ?, ?, 'image/jpeg', 1000, 'Zm9vYmFyZm9vYmFyZm9vYmFyZm9vYmFyZm9vYmFyMDA=', ?, ?::jsonb, ?, now(),
                    CASE WHEN ? = 'DELETED' THEN now() END)
            """.trimIndent(),
            id,
            owner,
            kind,
            "in/$random",
            status,
            variants,
            verdict,
            status,
        )
        return id
    }

    fun attachedTo(photo: UUID): UUID? =
        jdbc.queryForObject("SELECT attached_entry_id FROM media_assets WHERE id = ?", UUID::class.java, photo)
}
