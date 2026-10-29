package com.moyi.media.infra.database

import com.moyi.media.domain.ImageVariant
import com.moyi.media.domain.MediaAsset
import com.moyi.media.domain.MediaKind
import com.moyi.media.domain.MediaStatus
import com.moyi.media.domain.StorageKey
import com.moyi.media.domain.StoredVariant
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * `media_assets` (V32), through plain JDBC. Every read a person's request makes
 * is by id **and owner**: an asset another person owns is not found, by
 * construction, never found and then refused (CLAUDE.md: authorisation before
 * any read; doc 06:184). The reads by id alone ([findAny], [lockAny],
 * [findAll]) are for the work no person asks for: the pipeline, and an entry's
 * rendering, which `gratitude` has authorised before it asks (spec §4 D2).
 *
 * Instants arrive truncated to microseconds (the service's clock is), so what
 * is written is what comes back (CLAUDE.md).
 */
@Repository
@Suppress("TooManyFunctions") // One table, every statement on it; each is one rule's backstop.
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

    /** The asset, whoever owns it: for the pipeline, which acts for nobody. */
    fun findAny(id: UUID): MediaAsset? = jdbc.query("$SELECT WHERE id = ?", ROW, id).firstOrNull()

    /** [findAny] under the row lock: the pipeline's last word is said under it, after any deletion has committed. */
    fun lockAny(id: UUID): MediaAsset? = jdbc.query("$SELECT WHERE id = ? FOR UPDATE", ROW, id).firstOrNull()

    /** Each of [ids] that exists, whoever owns it: for rendering entries their reader may already read. */
    fun findAll(ids: Collection<UUID>): List<MediaAsset> {
        if (ids.isEmpty()) return emptyList()
        return jdbc.query("$SELECT WHERE id = ANY (?::uuid[])", ROW, ids.map(UUID::toString).toTypedArray())
    }

    /** PENDING_UPLOAD to UPLOADED, only from PENDING_UPLOAD. Returns whether it moved. */
    fun markUploaded(id: UUID): Boolean =
        jdbc.update("UPDATE media_assets SET status = 'UPLOADED' WHERE id = ? AND status = 'PENDING_UPLOAD'", id) == 1

    /**
     * An attempt begins: a photo UPLOADED, or PROCESSING after an attempt that
     * did not finish, becomes PROCESSING. Nothing is counted yet: an attempt
     * the storage stops before the decode spends none ([countDecode]). Returns
     * whether it moved; a photo deleted, finished or given up since is left alone.
     */
    fun startProcessing(id: UUID): Boolean =
        jdbc.update(
            "UPDATE media_assets SET status = 'PROCESSING' " +
                "WHERE id = ? AND kind = 'IMAGE' AND status IN ('UPLOADED', 'PROCESSING') AND processing_attempts < ?",
            id,
            MediaAsset.MAX_ATTEMPTS,
        ) == 1

    /**
     * An attempt reaches the decode: its bytes are fetched and verified, and
     * libvips is about to read them. Counted before it runs, so a decode that
     * never returns (the process killed with it) is counted too.
     */
    fun countDecode(id: UUID) {
        jdbc.update(
            "UPDATE media_assets SET processing_attempts = processing_attempts + 1 WHERE id = ? AND status = 'PROCESSING'",
            id,
        )
    }

    /**
     * A photo whose attempts have all begun and none finished becomes FAILED:
     * only one still waiting or left PROCESSING, and only once
     * [MediaAsset.MAX_ATTEMPTS] have begun. Returns whether it moved.
     */
    fun giveUp(id: UUID): Boolean =
        jdbc.update(
            "UPDATE media_assets SET status = 'FAILED' " +
                "WHERE id = ? AND kind = 'IMAGE' AND status IN ('UPLOADED', 'PROCESSING') AND processing_attempts >= ?",
            id,
            MediaAsset.MAX_ATTEMPTS,
        ) == 1

    /** PROCESSING to READY, with what is kept of the photo. Only from PROCESSING. */
    fun markReady(
        id: UUID,
        variants: List<StoredVariant>,
        at: Instant,
    ): Boolean {
        val largest = variants.maxBy { it.width.toLong() * it.height }
        return jdbc.update(
            "UPDATE media_assets SET status = 'READY', width = ?, height = ?, variants = ?::jsonb, ready_at = ? " +
                "WHERE id = ? AND status = 'PROCESSING'",
            largest.width,
            largest.height,
            variantsJson(variants),
            Timestamp.from(at),
            id,
        ) == 1
    }

    /** PROCESSING to FAILED. Only from PROCESSING. */
    fun markFailed(id: UUID): Boolean =
        jdbc.update("UPDATE media_assets SET status = 'FAILED' WHERE id = ? AND status = 'PROCESSING'", id) == 1

    /**
     * FR-044a, in one statement (spec §4 D2): the asset is attached to
     * [entryId] only if it is [owner]'s, of [kind], `READY`, and attached to
     * nothing. Two entries racing for one asset are serialised on its row, and
     * the second finds `attached_entry_id` set and matches nothing: one winner.
     * Returns whether this call attached it.
     */
    fun claim(
        owner: UUID,
        id: UUID,
        kind: MediaKind,
        entryId: UUID,
    ): Boolean =
        jdbc.update(
            "UPDATE media_assets SET attached_entry_id = ? " +
                "WHERE id = ? AND owner_user_id = ? AND kind = ? AND status = 'READY' AND attached_entry_id IS NULL",
            entryId,
            id,
            owner,
            kind.name,
        ) == 1

    /** Detaches [id] from [entryId], only if it is [owner]'s and attached there. */
    fun release(
        owner: UUID,
        id: UUID,
        entryId: UUID,
    ): Boolean =
        jdbc.update(
            "UPDATE media_assets SET attached_entry_id = NULL WHERE id = ? AND owner_user_id = ? AND attached_entry_id = ?",
            id,
            owner,
            entryId,
        ) == 1

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
                "attached_entry_id, created_at, expires_at, variants::text AS variants, moderation_verdict, processing_attempts " +
                "FROM media_assets"

        val JSON: JsonMapper = JsonMapper.builder().build()

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
                    variants = variantsOf(rs.getString("variants")),
                    moderationVerdict = rs.getString("moderation_verdict"),
                    processingAttempts = rs.getInt("processing_attempts"),
                )
            }

        /** `[{"variant":"FULL","key":"v/…","width":2048,"height":1536,"bytes":180766}, …]`. */
        fun variantsJson(variants: List<StoredVariant>): String =
            JSON.writeValueAsString(
                variants.map {
                    mapOf(
                        "variant" to it.variant.name,
                        "key" to it.key.value,
                        "width" to it.width,
                        "height" to it.height,
                        "bytes" to it.bytes,
                    )
                },
            )

        fun variantsOf(json: String): List<StoredVariant> {
            val array = JSON.readTree(json)
            return (0 until array.size()).map { index ->
                val node = array[index]
                StoredVariant(
                    variant = ImageVariant.valueOf(node["variant"].asString()),
                    key = StorageKey(node["key"].asString()),
                    width = node["width"].asInt(),
                    height = node["height"].asInt(),
                    bytes = node["bytes"].asLong(),
                )
            }
        }
    }
}
