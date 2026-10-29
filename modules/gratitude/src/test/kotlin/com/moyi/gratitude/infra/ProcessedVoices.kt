package com.moyi.gratitude.infra

import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/**
 * A voice note as `media.process` leaves one (Phase 4 D3), written straight
 * into `media_assets`, for the reason [ProcessedPhotos] gives: this module's
 * tests need a voice note in a given state, not the pipeline that makes one.
 * Its Opus is at the key derived from its upload's, so there is nothing more
 * to write. The id is a fresh v4, as `media`'s are.
 */
internal class ProcessedVoices(
    private val jdbc: JdbcTemplate,
) {
    fun ready(
        owner: UUID,
        status: String = "READY",
        durationMs: Int? = DURATION_MS,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO media_assets (id, owner_user_id, kind, storage_key, content_type, size_bytes, checksum, status, duration_ms,
                                      created_at, deleted_at)
            VALUES (?, ?, 'VOICE', ?, 'audio/mp4', 1000, 'Zm9vYmFyZm9vYmFyZm9vYmFyZm9vYmFyZm9vYmFyMDA=', ?, ?, now(),
                    CASE WHEN ? = 'DELETED' THEN now() END)
            """.trimIndent(),
            id,
            owner,
            "in/${UUID.randomUUID()}",
            status,
            durationMs.takeIf { status == "READY" },
            status,
        )
        return id
    }

    fun attachedTo(voice: UUID): UUID? =
        jdbc.queryForObject("SELECT attached_entry_id FROM media_assets WHERE id = ?", UUID::class.java, voice)

    companion object {
        /** A plainly made-up length: 4.2 seconds. */
        const val DURATION_MS = 4_200
    }
}
