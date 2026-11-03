package com.moyi.gratitude.infra.database

import com.moyi.ai.api.EmbeddingSpace
import com.moyi.gratitude.domain.VectorTable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * An entry's `text_search` as vectors, one table per embedding model (V41, ADR-0054, spec §3.2).
 *
 * **A second copy of what an entry says**, in meaning if not in words, and the documented
 * exception beside `text_search` itself: so it lives exactly as long as the projection does.
 * [EntryStore.update] is the one place `text_search` changes (an edit, the author's delete, the
 * withdrawal's consumer, the close job's and a read's pre-step), and it calls [deleteForEntry]
 * in the same transaction whenever the projection changes. A deleted entry row takes its
 * vectors with it (`ON DELETE CASCADE`).
 *
 * Plain SQL on [JdbcTemplate], as [ArchiveDays] is. Not transactional itself: the caller's
 * transaction is the boundary, so a delete commits or rolls back with the write that caused it.
 * The table name is only ever [tableOf]'s, never anything a caller passed in.
 */
@Component
internal class EntryEmbeddings(
    private val jdbc: JdbcTemplate,
) {
    /** An entry the sweep may embed: its words are `text_search` as read. Never prints them. */
    data class Pending(
        val entryId: UUID,
        val bondId: UUID,
        val authorMemberId: UUID,
        val text: String,
    ) {
        override fun toString() = "Pending($entryId)"
    }

    /**
     * Up to [limit] live entries of [bondId] with words and no row in [space]'s table, oldest
     * first, **none written by a member in [withdrawnMemberIds]**. Whether the bond's setting
     * allows embedding at all is the caller's to ask, and so is who has withdrawn: both come
     * from `BondAccess.closingViewOf`, read in the caller's transaction.
     *
     * The withdrawn are left out because their words are unreadable from the withdrawal's
     * commit, while the consumer that erases them runs later (ADR-0035): a sweep in between must
     * not send them (final review I1).
     */
    fun missing(
        space: EmbeddingSpace,
        bondId: UUID,
        limit: Int,
        withdrawnMemberIds: Set<UUID> = emptySet(),
    ): List<Pending> {
        val excluding =
            withdrawnMemberIds
                .takeIf { it.isNotEmpty() }
                ?.let { ids -> "AND e.author_member_id NOT IN (${ids.joinToString { "?" }})" }
                .orEmpty()
        return jdbc.query(
            """
            SELECT e.id, e.bond_id, e.author_member_id, e.text_search FROM entries e
            WHERE e.bond_id = ? AND e.text_search IS NOT NULL AND ${EntryRows.LIVE_ROW} $excluding
              AND NOT EXISTS (SELECT 1 FROM ${tableOf(space)} x WHERE x.entry_id = e.id)
            ORDER BY e.created_at LIMIT ?
            """.trimIndent(),
            PreparedStatementSetter { statement ->
                (listOf<Any>(bondId) + withdrawnMemberIds + limit).forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            },
            RowMapper { row, _ ->
                Pending(
                    row.getObject("id", UUID::class.java),
                    row.getObject("bond_id", UUID::class.java),
                    row.getObject("author_member_id", UUID::class.java),
                    row.getString("text_search"),
                )
            },
        )
    }

    /**
     * Writes [vector] for [entryId] in [space]'s table **only if the entry, in [bondId], still
     * has words and they hash to [textHash]** ([hashOf] of what was embedded); returns whether a
     * row was written. A vector of words the entry no longer holds is refused, so an edit or an
     * erasure that landed while the provider was being called is never undone by its answer.
     *
     * Safe against a concurrent edit **because** every writer of `text_search` holds the bond's
     * lock first and the sweep writes under the same lock (Task 6), not because of this
     * statement alone: without the lock, an edit could commit between this statement's read of
     * the entry and its insert, and its [deleteForEntry] would find nothing to delete yet.
     */
    @Suppress("LongParameterList") // The row's own columns, each a separate fact.
    fun upsert(
        space: EmbeddingSpace,
        entryId: UUID,
        bondId: UUID,
        vector: FloatArray,
        textHash: ByteArray,
        at: Instant,
    ): Boolean {
        require(vector.size == space.dimensions) { "a $space vector has ${space.dimensions} dimensions, not ${vector.size}" }
        val written =
            jdbc.update(
                """
                INSERT INTO ${tableOf(space)} (entry_id, bond_id, embedding, text_hash, created_at)
                SELECT e.id, e.bond_id, ?::vector, ?, ? FROM entries e
                WHERE e.id = ? AND e.bond_id = ? AND e.text_search IS NOT NULL
                  AND sha256(convert_to(e.text_search, 'UTF8')) = ?
                ON CONFLICT (entry_id) DO UPDATE
                SET embedding = EXCLUDED.embedding, text_hash = EXCLUDED.text_hash, created_at = EXCLUDED.created_at
                """.trimIndent(),
                vectorLiteral(vector),
                textHash,
                at.atOffset(ZoneOffset.UTC),
                entryId,
                bondId,
                textHash,
            )
        return written == 1
    }

    /** The entry's rows in every table: its words have changed or gone. */
    fun deleteForEntry(entryId: UUID) {
        VectorTable.entries.forEach { table ->
            jdbc.update("DELETE FROM ${nameOf(table)} WHERE entry_id = ?", entryId)
        }
    }

    /** The bond's rows in every table, and how many there were. */
    fun deleteForBond(bondId: UUID): Int =
        VectorTable.entries.sumOf { table ->
            jdbc.update("DELETE FROM ${nameOf(table)} WHERE bond_id = ?", bondId)
        }

    /** The bond's rows across every table. */
    fun countFor(bondId: UUID): Int =
        VectorTable.entries.sumOf { table ->
            jdbc.queryForObject("SELECT count(*) FROM ${nameOf(table)} WHERE bond_id = ?", Int::class.java, bondId)!!
        }

    internal companion object {
        /**
         * The table [space]'s vectors live in, as gratitude names it. An exhaustive `when`, so a
         * new space does not compile until it has a table. Here, in `infra`, and not in
         * `service`: both read it, and `infra` may not depend on `service` (`ArchitectureTest`).
         */
        fun vectorTableOf(space: EmbeddingSpace): VectorTable =
            when (space) {
                EmbeddingSpace.OPENAI_TEXT_EMBEDDING_3_SMALL -> VectorTable.OPENAI_1536
                EmbeddingSpace.VOYAGE_3_5 -> VectorTable.VOYAGE_1024
            }

        /** [table]'s name in SQL; static, so `ArchiveDays`' companion can name it. */
        fun nameOf(table: VectorTable): String =
            when (table) {
                VectorTable.OPENAI_1536 -> "entry_embeddings_openai_1536"
                VectorTable.VOYAGE_1024 -> "entry_embeddings_voyage_1024"
            }

        /** The table [space]'s vectors live in: [nameOf] of [vectorTableOf]. */
        fun tableOf(space: EmbeddingSpace): String = nameOf(vectorTableOf(space))

        /** [vector] as pgvector's text form, `[1.0,0.5,…]`, for a `?::vector` parameter. */
        fun vectorLiteral(vector: FloatArray): String = vector.joinToString(",", prefix = "[", postfix = "]")

        /** SHA-256 of [text]'s UTF-8 bytes: what Postgres's `sha256(convert_to(text_search, 'UTF8'))` computes. */
        fun hashOf(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    }
}
