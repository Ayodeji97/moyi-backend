package com.moyi.export.infra.database

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant

/** `export_archive_deletions` (V54): the keys of archives whose objects are owed a deletion. */
@Component
internal class ArchiveDeletions(
    private val jdbc: JdbcTemplate,
) {
    /** Writes [keys] down as owed, in the caller's transaction if there is one. Owing one twice is owing it once. */
    fun owe(
        keys: Collection<String>,
        at: Instant,
    ) {
        if (keys.isEmpty()) return
        jdbc.batchUpdate(
            "INSERT INTO export_archive_deletions (storage_key, owed_since) VALUES (?, ?) ON CONFLICT (storage_key) DO NOTHING",
            keys.map { arrayOf<Any>(it, Timestamp.from(at)) },
        )
    }

    /** Up to [limit] keys owed, oldest first. */
    fun owed(limit: Int): List<String> =
        jdbc
            .queryForList(
                "SELECT storage_key FROM export_archive_deletions ORDER BY owed_since, storage_key LIMIT ?",
                String::class.java,
                limit,
            ).filterNotNull()

    /** [key]'s object is gone: nothing is owed for it any more. */
    fun paid(key: String) {
        jdbc.update("DELETE FROM export_archive_deletions WHERE storage_key = ?", key)
    }
}
