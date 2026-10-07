package com.moyi.gratitude.service

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.web.idempotency.IdempotencyInterceptor
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * What the withdrawal tests share: real requests against real bonds, and a
 * way to say that two bonds ended up **the same**.
 *
 * **Why "the same" is a snapshot and not a handful of assertions.** A
 * withdrawal has to leave what a run of single deletes leaves (ADR-0035
 * decision 13), and has to leave it whichever of the close job and the
 * consumer reached a day first. A test that named the columns it cared about
 * would pass on the column nobody thought of. So [Snapshot] is every row of a
 * bond's entries and days, its streak, the events it published and what both
 * members are then answered, with the bond's own ids replaced by names so
 * that two bonds can be compared with `shouldBe`.
 *
 * **What a snapshot leaves out, and why:** `updated_at` and the value of
 * `deleted_at` (when an entry was erased is the one thing the two routes are
 * allowed to differ in: a delete happens when it is asked for, an erasure
 * when the delivery runs; whether it was erased is kept), the days'
 * `created_at`, and the `EntriesWithdrawn` event itself, which only one of
 * the two routes has.
 */
internal class WithdrawalRig(
    private val mockMvc: MockMvc,
    private val tokens: AccessTokenIssuer,
    val jdbc: JdbcTemplate,
) {
    /**
     * Before a test as well as after it: the close job and the dispatcher
     * both work on everything in a database that other test classes share.
     * The consumer's registration (`outbox_consumers`, `outbox_subscriptions`)
     * is made once, when the context starts, and is not touched.
     */
    fun clear() {
        jdbc.execute("TRUNCATE TABLE outbox_deliveries, outbox_events, streak_events, streak_states")
        jdbc.execute(
            "TRUNCATE TABLE idempotency_keys, entries, bond_days, blocks, bond_entry_withdrawals, " +
                "bond_invites, bond_members, bonds CASCADE",
        )
    }

    /** A bond [creator] made and [joiner] accepted, and its id. */
    fun pair(
        creator: UUID,
        joiner: UUID,
    ): String {
        val created = create(creator)
        accept(joiner, codeOf(created))
        return idOf(created)
    }

    fun create(creator: UUID): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds") {
                header(HttpHeaders.AUTHORIZATION, bearer(creator))
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Us","type":"COUPLE","anchorTimezone":"Africa/Lagos"}"""
            }.andReturn()
            .response
            .also { it.status shouldBe 201 }

    fun accept(
        joiner: UUID,
        code: String,
    ) {
        mockMvc
            .post("/api/v1/invites/$code/accept") { header(HttpHeaders.AUTHORIZATION, bearer(joiner)) }
            .andReturn()
            .response.status shouldBe 200
    }

    /** The new entry's id. */
    fun submit(
        user: UUID,
        bond: String,
        words: String,
    ): String =
        idOf(
            mockMvc
                .post("/api/v1/bonds/$bond/entries") {
                    header(HttpHeaders.AUTHORIZATION, bearer(user))
                    header(IdempotencyInterceptor.HEADER, UUID.randomUUID().toString())
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"text":"$words"}"""
                }.andReturn()
                .response
                .also { it.status shouldBe 201 },
        )

    fun delete(
        user: UUID,
        entryId: String,
    ) {
        mockMvc
            .delete("/api/v1/entries/$entryId") { header(HttpHeaders.AUTHORIZATION, bearer(user)) }
            .andReturn()
            .response.status shouldBe 204
    }

    /** With no [withdraw], no body: a block withdraws unless it is declined (FR-029a). */
    fun block(
        user: UUID,
        bond: String,
        withdraw: Boolean? = null,
    ) {
        end("block", user, bond, withdraw)
    }

    /** With no [withdraw], no body: a leave keeps the entries unless it is asked to take them. */
    fun leave(
        user: UUID,
        bond: String,
        withdraw: Boolean? = null,
    ) {
        end("leave", user, bond, withdraw)
    }

    private fun end(
        how: String,
        user: UUID,
        bond: String,
        withdraw: Boolean?,
    ) {
        mockMvc
            .post("/api/v1/bonds/$bond/$how") {
                header(HttpHeaders.AUTHORIZATION, bearer(user))
                if (withdraw != null) {
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"withdrawEntries": $withdraw}"""
                }
            }.andReturn()
            .response.status shouldBe 204
    }

    fun get(
        user: UUID,
        path: String,
    ): String =
        mockMvc
            .get(path) { header(HttpHeaders.AUTHORIZATION, bearer(user)) }
            .andReturn()
            .response
            .also { it.status shouldBe 200 }
            .contentAsString

    fun today(
        user: UUID,
        bond: String,
    ): String = get(user, "/api/v1/bonds/$bond/today")

    fun memberId(
        bond: String,
        user: UUID,
    ): UUID = jdbc.queryForObject("SELECT id FROM bond_members WHERE bond_id = ?::uuid AND user_id = ?", UUID::class.java, bond, user)!!

    /** The delivery of [bond]'s withdrawal event to the consumer, as it stands. */
    fun delivery(bond: String): Map<String, Any?> =
        jdbc.queryForMap(
            """
            SELECT d.processed_at, d.attempts, d.last_error, d.next_attempt_at
            FROM outbox_deliveries d JOIN outbox_events e ON e.id = d.event_id
            WHERE e.event_type = 'EntriesWithdrawn' AND e.aggregate_id = ?::uuid AND d.consumer_id = 'gratitude.withdrawal'
            """.trimIndent(),
            bond,
        )

    /** Puts [bond]'s withdrawal delivery off until [until], as a failure backing off would: the erasure has not happened yet. */
    fun postpone(
        bond: String,
        until: Instant,
    ) {
        jdbc.update(
            """
            UPDATE outbox_deliveries d SET next_attempt_at = ?
            FROM outbox_events e
            WHERE e.id = d.event_id AND e.event_type = 'EntriesWithdrawn' AND e.aggregate_id = ?::uuid
            """.trimIndent(),
            Timestamp.from(until),
            bond,
        ) shouldBe 1
    }

    /** Every column of every entry in [bond], oldest day first: for "nothing at all changed". */
    fun wholeEntries(bond: String): List<Map<String, Any?>> =
        jdbc.queryForList(
            """
            SELECT e.* FROM entries e JOIN bond_days d ON d.id = e.bond_day_id
            WHERE e.bond_id = ?::uuid ORDER BY d.date, e.created_at, e.id
            """.trimIndent(),
            bond,
        )

    /** Every column of every day of [bond], oldest first. */
    fun wholeDays(bond: String): List<Map<String, Any?>> =
        jdbc.queryForList("SELECT * FROM bond_days WHERE bond_id = ?::uuid ORDER BY date", bond)

    /** See the class KDoc. [ada] and [bea] are the bond's two users, in the order they should be named. */
    fun snapshot(
        bond: String,
        ada: UUID,
        bea: UUID,
    ): Snapshot {
        val names = names(bond, ada, bea)

        fun String.named(): String = names.entries.fold(this) { text, (id, name) -> text.replace(id, name) }
        return Snapshot(
            entries =
                jdbc
                    .queryForList(
                        """
                        SELECT d.date, e.author_member_id::text AS author, e.text, e.text_search, e.image_media_id, e.voice_media_id,
                               e.voice_duration_ms, e.prompt_id, e.status, e.author_deleted_account, e.created_at, e.intended_at,
                               e.revealed_at, e.deleted_at IS NOT NULL AS erased
                        FROM entries e JOIN bond_days d ON d.id = e.bond_day_id
                        WHERE e.bond_id = ?::uuid
                        """.trimIndent(),
                        bond,
                    ).map { it.toString().named() }
                    // By date, then by author: two entries of one instant have no order of their own.
                    .sorted(),
            days =
                jdbc
                    .queryForList(
                        """
                        SELECT date, status, anchor_timezone, starts_at, ends_at, entry_count, revealed_at, closed_at,
                               evaluated_at IS NOT NULL AS evaluated, evaluated_as, evaluated_strict, freeze_applied
                        FROM bond_days WHERE bond_id = ?::uuid ORDER BY date
                        """.trimIndent(),
                        bond,
                    ).map { it.toString() },
            streak =
                jdbc
                    .queryForList(
                        """
                        SELECT current_streak, longest_streak, last_complete_date, freezes_available, freeze_progress,
                               freezes_consumed, total_complete_days
                        FROM streak_states WHERE bond_id = ?::uuid
                        """.trimIndent(),
                        bond,
                    ).map { it.toString() } +
                    jdbc
                        .queryForList(
                            "SELECT date, event, streak_before, streak_after FROM streak_events " +
                                "WHERE bond_id = ?::uuid ORDER BY date, event",
                            bond,
                        ).map { it.toString() },
            events =
                jdbc
                    .queryForList(
                        """
                        SELECT event_type, count(*) AS times FROM outbox_events
                        WHERE payload ->> 'bondId' = ? AND event_type <> 'EntriesWithdrawn'
                        GROUP BY event_type ORDER BY event_type
                        """.trimIndent(),
                        bond,
                    ).map { it.toString() },
            read =
                listOf(ada, bea).flatMap { reader ->
                    listOf(today(reader, bond).named(), get(reader, "/api/v1/bonds/$bond/streak").named())
                },
        )
    }

    /** Each id that belongs to [bond] alone, and the name it is compared under. */
    private fun names(
        bond: String,
        ada: UUID,
        bea: UUID,
    ): Map<String, String> {
        val members = mapOf(memberId(bond, ada).toString() to "MEMBER_A", memberId(bond, bea).toString() to "MEMBER_B")
        val days =
            jdbc
                .queryForList("SELECT id::text AS id, date FROM bond_days WHERE bond_id = ?::uuid", bond)
                .associate { it["id"] as String to "DAY_${it["date"]}" }
        val entries =
            jdbc
                .queryForList(
                    """
                    SELECT e.id::text AS id, d.date, e.author_member_id::text AS author,
                           row_number() OVER (PARTITION BY e.bond_day_id, e.author_member_id ORDER BY e.created_at, e.id) AS nth
                    FROM entries e JOIN bond_days d ON d.id = e.bond_day_id WHERE e.bond_id = ?::uuid
                    """.trimIndent(),
                    bond,
                ).associate { it["id"] as String to "ENTRY_${it["date"]}_${members[it["author"]]}_${it["nth"]}" }
        return entries + days + members + (bond to "BOND")
    }

    private fun bearer(user: UUID): String = "Bearer ${tokens.issue(user).token}"

    fun codeOf(response: MockHttpServletResponse): String = Regex(""""code":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    fun idOf(response: MockHttpServletResponse): String = Regex(""""id":"([^"]+)"""").find(response.contentAsString)!!.groupValues[1]

    /** A bond as it stands, with its own ids named: two of these are equal when the bonds cannot be told apart. */
    data class Snapshot(
        val entries: List<String>,
        val days: List<String>,
        val streak: List<String>,
        val events: List<String>,
        val read: List<String>,
    )
}
