package com.moyi.gratitude.web

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.gratitude.service.WithdrawalRig
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.UUID

/**
 * What the archive's tests share: the feed asked over HTTP, a walk of it by
 * `nextCursor`, and rows put down by hand for the states no request reaches.
 *
 * [bonds] is `WithdrawalRig`, for everything a request can do to a bond.
 */
internal class ArchiveRig(
    private val mockMvc: MockMvc,
    private val tokens: AccessTokenIssuer,
    val jdbc: JdbcTemplate,
    private val json: ObjectMapper,
) {
    val bonds = WithdrawalRig(mockMvc, tokens, jdbc)

    /** `GET /bonds/{bond}/days`, whatever it answers. Parameters go through the request builder, so nothing here encodes a URL. */
    fun days(
        user: UUID,
        bond: String,
        parameters: Map<String, String> = emptyMap(),
    ): MockHttpServletResponse =
        mockMvc
            .get("/api/v1/bonds/$bond/days") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}")
                parameters.forEach { (name, value) -> param(name, value) }
            }.andReturn()
            .response

    /** One page, which must be a `200`. */
    fun page(
        user: UUID,
        bond: String,
        parameters: Map<String, String> = emptyMap(),
    ): JsonNode = json.readTree(days(user, bond, parameters).also { it.status shouldBe 200 }.getContentAsString(Charsets.UTF_8))

    /**
     * Every page from the first to the one whose `nextCursor` is null, in the
     * order they were given. Each page is asked for with [parameters] and the
     * cursor the page before it handed back, and nothing else.
     */
    fun pages(
        user: UUID,
        bond: String,
        parameters: Map<String, String> = emptyMap(),
    ): List<JsonNode> {
        val pages = mutableListOf<JsonNode>()
        var cursor: String? = null
        do {
            val body = page(user, bond, parameters + listOfNotNull(cursor?.let { "cursor" to it }))
            pages.add(body)
            cursor = body["nextCursor"].takeUnless { it.isNull }?.asString()
            check(pages.size < MAX_PAGES) { "the walk did not end: a cursor that does not advance" }
        } while (cursor != null)
        return pages
    }

    /** Every day of every page of [pages], in order. */
    fun walk(
        user: UUID,
        bond: String,
        parameters: Map<String, String> = emptyMap(),
    ): List<JsonNode> = pages(user, bond, parameters).flatMap { it["items"].toList() }

    fun datesOf(days: List<JsonNode>): List<String> = days.map { it["date"].asString() }

    fun favourite(
        user: UUID,
        entryId: String,
    ) {
        mockMvc
            .put("/api/v1/entries/$entryId/favourite") { header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}") }
            .andReturn()
            .response.status shouldBe 204
    }

    /** A cursor as the API writes one, made here from its documented form and not by the code under test. */
    fun cursorBefore(date: String): String = encoded("v1:$date")

    fun encoded(plain: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(plain.toByteArray(Charsets.US_ASCII))

    fun dayId(
        bond: String,
        date: String,
    ): UUID = jdbc.queryForObject("SELECT id FROM bond_days WHERE bond_id = ?::uuid AND date = ?::date", UUID::class.java, bond, date)!!

    fun dayStatus(
        bond: String,
        date: String,
    ): String =
        jdbc.queryForObject(
            "SELECT status FROM bond_days WHERE bond_id = ?::uuid AND date = ?::date",
            String::class.java,
            bond,
            date,
        )!!

    // ---- rows by hand ----

    /** A day's row, by hand: a whole UTC day, closed exactly when [status] is a closed one. */
    fun insertDay(
        bond: String,
        date: LocalDate,
        status: String,
    ): UUID {
        val id = UUID.randomUUID()
        val start = Timestamp.from(date.atStartOfDay().toInstant(java.time.ZoneOffset.UTC))
        val end = Timestamp.from(date.plusDays(1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC))
        jdbc.update(
            """
            INSERT INTO bond_days (id, bond_id, date, status, anchor_timezone, starts_at, ends_at, entry_count, created_at,
                                   closed_at, version)
            VALUES (?, ?::uuid, ?, ?, 'UTC', ?, ?, 0, ?, CASE WHEN ? THEN ?::timestamptz ELSE NULL END, 0)
            """.trimIndent(),
            id,
            bond,
            date,
            status,
            start,
            end,
            start,
            status in CLOSED,
            end,
        ) shouldBe 1
        return id
    }

    /** An entry's row, by hand, in one of the five states an entry can be in ([EntryState]); `null` for [EntryState.NONE]. */
    @Suppress("LongParameterList") // A row's own columns.
    fun insertEntry(
        day: UUID,
        bond: String,
        author: UUID,
        state: EntryState,
        words: String,
        at: Instant,
    ): UUID? {
        if (state == EntryState.NONE) return null
        val id = UUID.randomUUID()
        val written = Timestamp.from(at)
        val later = Timestamp.from(at.plusSeconds(60))
        jdbc.update(
            """
            INSERT INTO entries (id, bond_day_id, bond_id, author_member_id, text, status, created_at, intended_at, updated_at,
                                 revealed_at, deleted_at)
            VALUES (?, ?, ?::uuid, ?, ?, ?, ?, ?, ?, CASE WHEN ? THEN ?::timestamptz ELSE NULL END,
                    CASE WHEN ? THEN ?::timestamptz ELSE NULL END)
            """.trimIndent(),
            id,
            day,
            bond,
            author,
            words.takeUnless { state.erased },
            when {
                state.erased -> "DELETED"
                state.revealed -> "REVEALED"
                else -> "SUBMITTED"
            },
            written,
            written,
            written,
            state.revealed,
            later,
            state.erased,
            later,
        ) shouldBe 1
        return id
    }

    companion object {
        private const val MAX_PAGES = 500
        private val CLOSED = setOf("REVEALED", "SOLO", "EMPTY", "FROZEN")
    }
}

/** What one member's entry on a day can be. The words are gone exactly when [erased]. */
internal enum class EntryState(
    val revealed: Boolean,
    val erased: Boolean,
) {
    NONE(revealed = false, erased = false),
    LIVE_UNREVEALED(revealed = false, erased = false),
    REVEALED(revealed = true, erased = false),
    ERASED_BEFORE_REVEAL(revealed = false, erased = true),
    ERASED_AFTER_REVEAL(revealed = true, erased = true),
}
