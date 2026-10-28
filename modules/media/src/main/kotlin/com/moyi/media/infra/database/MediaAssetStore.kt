package com.moyi.media.infra.database

import com.moyi.media.domain.MediaAsset
import com.moyi.media.domain.MediaKind
import com.moyi.media.domain.MediaStatus
import com.moyi.media.domain.StorageKey
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * `media_assets` (V32), through plain JDBC. Every read is by id **and owner**:
 * an asset another person owns is not found, by construction, never found
 * and then refused (CLAUDE.md: authorisation before any read; doc 06:184).
 *
 * Instants arrive truncated to microseconds (the service's clock is), so what
 * is written is what comes back (CLAUDE.md).
 */
@Repository
internal class MediaAssetStore(
    private val jdbc: JdbcTemplate,
) {
    fun insert(asset: MediaAsset) {
        jdbc.update(
            """
            INSERT INTO media_assets (id, owner_user_id, kind, storage_key, content_type, size_bytes, checksum,
                                      status, created_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            asset.id,
            asset.ownerUserId,
            asset.kind.name,
            asset.storageKey.value,
            asset.contentType,
            asset.sizeBytes,
            asset.checksumSha256,
            asset.status.name,
            Timestamp.from(asset.createdAt),
            asset.expiresAt?.let(Timestamp::from),
        )
    }

    /** The owner's asset, as it stands. Null for nobody's, somebody else's, or none. */
    fun find(
        id: UUID,
        owner: UUID,
    ): MediaAsset? = jdbc.query("$SELECT WHERE id = ? AND owner_user_id = ?", ROW, id, owner).firstOrNull()

    /**
     * The owner's asset under its row lock, for a change: completion and
     * deletion of one asset are serialised, so the later one sees what the
     * earlier one committed.
     */
    fun lock(
        id: UUID,
        owner: UUID,
    ): MediaAsset? = jdbc.query("$SELECT WHERE id = ? AND owner_user_id = ? FOR UPDATE", ROW, id, owner).firstOrNull()

    /** PENDING_UPLOAD to UPLOADED, only from PENDING_UPLOAD. Returns whether it moved. */
    fun markUploaded(id: UUID): Boolean =
        jdbc.update("UPDATE media_assets SET status = 'UPLOADED' WHERE id = ? AND status = 'PENDING_UPLOAD'", id) == 1

    /**
     * To DELETED, only when unattached and not already deleted (doc 06:184).
     * The condition is the rule's backstop: the caller has already decided
     * under the row lock, and a row that no longer matches is not changed.
     */
    fun markDeleted(
        id: UUID,
        at: Instant,
    ): Boolean =
        jdbc.update(
            "UPDATE media_assets SET status = 'DELETED', deleted_at = ? " +
                "WHERE id = ? AND status <> 'DELETED' AND attached_entry_id IS NULL",
            Timestamp.from(at),
            id,
        ) == 1

    private companion object {
        const val SELECT =
            "SELECT id, owner_user_id, kind, storage_key, content_type, size_bytes, checksum, status, " +
                "attached_entry_id, created_at, expires_at FROM media_assets"

        val ROW =
            RowMapper { rs, _ ->
                MediaAsset(
                    id = rs.getObject("id", UUID::class.java),
                    ownerUserId = rs.getObject("owner_user_id", UUID::class.java),
                    kind = MediaKind.valueOf(rs.getString("kind")),
                    storageKey = StorageKey(rs.getString("storage_key")),
                    contentType = rs.getString("content_type"),
                    sizeBytes = rs.getLong("size_bytes"),
                    checksumSha256 = rs.getString("checksum"),
                    status = MediaStatus.valueOf(rs.getString("status")),
                    attachedEntryId = rs.getObject("attached_entry_id", UUID::class.java),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    expiresAt = rs.getTimestamp("expires_at")?.toInstant(),
                )
            }
    }
}
