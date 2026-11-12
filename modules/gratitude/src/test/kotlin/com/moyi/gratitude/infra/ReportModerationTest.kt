package com.moyi.gratitude.infra

import com.moyi.common.testing.IntegrationTest
import com.moyi.gratitude.api.DismissOutcome
import com.moyi.gratitude.api.HeldMedia
import com.moyi.gratitude.api.ReasonsReview
import com.moyi.gratitude.api.ReclassifyOutcome
import com.moyi.gratitude.api.ReportModeration
import com.moyi.gratitude.api.Takedown
import com.moyi.gratitude.api.TakedownOutcome
import com.moyi.gratitude.api.UpholdResult
import com.moyi.gratitude.infra.database.ReportModerationStore
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * `gratitude.api.ReportModeration` (A2 spec §5.1, §6, §8; plan Task 2) against the real
 * functions on the migrated database: every outcome of every decision, the stale check taken
 * before the function, and what each leaves behind in `entry_reports`, `entry_report_log` and
 * the media the report held.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
internal class ReportModerationTest(
    @Autowired dataSource: DataSource,
    @Autowired private val port: ReportModeration,
    @Autowired private val transactions: TransactionTemplate,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val photos = ProcessedPhotos(jdbc)
    private val owner = UUID.randomUUID()

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE outbox_deliveries, outbox_events, media_report_holds, media_moderation_log, media_assets")
        jdbc.execute("TRUNCATE TABLE entry_report_log, entry_reports")
    }

    // --- reads -------------------------------------------------------------------------------------------------------

    @Test
    fun `the queue is the function's rows in its order, priority first, and the look is logged once`() {
        val (entry) = entryWithPhoto()
        val older = report(entry, "HARASSMENT", detail = DETAIL, at = T0)
        val priority = report(entry, "MINOR_SEXUAL_CONTENT", at = T0.plusSeconds(60))
        val member = memberReport("OTHER", at = T0.plusSeconds(30))
        val decided = report(entry, "OTHER", at = T0.plusSeconds(10))
        jdbc.queryForList("SELECT * FROM entry_report_dismiss(?, 'op', 'done')", decided)
        jdbc.update("DELETE FROM entry_report_log")

        val rows = tx { port.queue(ACTOR) }

        rows.map { it.reportId } shouldContainExactly listOf(priority, older, member)
        rows.map { it.priority } shouldContainExactly listOf(true, false, false)
        rows[1].reason shouldBe "HARASSMENT"
        rows[1].target shouldBe "ENTRY"
        rows[1].entryId shouldBe entry
        rows[1].held shouldBe 1
        rows[1].hasDetail shouldBe true
        rows[1].receivedAt shouldBe T0
        rows[2].target shouldBe "MEMBER"
        rows[2].entryId shouldBe null
        rows[2].held shouldBe 0
        rows.joinToString().let {
            it shouldNotContain DETAIL
            it shouldBe "ReportRow(reportId=$priority), ReportRow(reportId=$older), ReportRow(reportId=$member)"
        }
        log(null) shouldContainExactly listOf("LISTED|$ACTOR|")
    }

    @Test
    fun `the open count counts the open reports and writes nothing`() {
        val (entry) = entryWithPhoto()
        report(entry, "OTHER", at = T0)
        memberReport("HARASSMENT", at = T0)
        val decided = report(entry, "OTHER", at = T0)
        jdbc.queryForList("SELECT * FROM entry_report_dismiss(?, 'op', 'done')", decided)
        jdbc.update("DELETE FROM entry_report_log")

        tx { port.openCount() } shouldBe 2
        log(null).shouldBeEmpty()
        jdbc.queryForObject("SELECT count(*) FROM entry_report_log", Int::class.java) shouldBe 0
    }

    @Test
    fun `a report is read as one case with what it holds, the look is logged, and its string never has the detail`() {
        val (entry, photo) = entryWithPhoto()
        val report = report(entry, "VIOLENCE_OR_SELF_HARM", detail = DETAIL, at = T0)

        val case = transactions.execute { port.read(report, ACTOR) }!!

        case.reportId shouldBe report
        case.reason shouldBe "VIOLENCE_OR_SELF_HARM"
        case.priority shouldBe false
        case.mediaReviewable shouldBe true
        case.status shouldBe "OPEN"
        case.target shouldBe "ENTRY"
        case.entryId shouldBe entry
        case.reportedMemberId shouldBe null
        case.bondId shouldBe BOND
        case.receivedAt shouldBe T0
        case.detail shouldBe DETAIL
        case.media shouldContainExactly listOf(HeldMedia(photo, "IMAGE", "QUARANTINED", "QUARANTINED"))
        case.toString() shouldNotContain DETAIL
        case.toString() shouldBe "ReportCase(reportId=$report)"
        log(report) shouldContainExactly listOf("READ|$ACTOR|")
    }

    @Test
    fun `a priority report reads as priority and not reviewable, a consent report as not reviewable`() {
        val (entry) = entryWithPhoto()
        val minor = report(entry, "MINOR_SEXUAL_CONTENT", at = T0)
        val consent = report(entry, "NON_CONSENSUAL", at = T0)

        transactions.execute { port.read(minor, ACTOR) }!!.let {
            it.priority shouldBe true
            it.mediaReviewable shouldBe false
        }
        transactions.execute { port.read(consent, ACTOR) }!!.let {
            it.priority shouldBe false
            it.mediaReviewable shouldBe false
        }
    }

    @Test
    fun `a member report reads with its member and no media`() {
        val report = memberReport("HARASSMENT", at = T0)

        val case = transactions.execute { port.read(report, ACTOR) }!!

        case.target shouldBe "MEMBER"
        case.entryId shouldBe null
        case.reportedMemberId shouldBe REPORTED_MEMBER
        case.media.shouldBeEmpty()
    }

    @Test
    fun `an unknown report reads as nothing and writes no READ`() {
        transactions.execute { port.read(UUID.randomUUID(), ACTOR) } shouldBe null
        jdbc.queryForObject("SELECT count(*) FROM entry_report_log", Int::class.java) shouldBe 0
    }

    @Test
    fun `the history is the report's log, oldest first, and its strings never have the note`() {
        val (entry) = entryWithPhoto()
        val report = report(entry, "OTHER", at = T0)
        transactions.execute { port.read(report, ACTOR) }
        tx { port.dismiss(report, "OTHER", ACTOR, NOTE) }

        val history = tx { port.history(report) }

        history.map { "${it.action}|${it.actor}|${it.note}" } shouldContainExactly
            listOf("READ|$ACTOR|null", "DISMISSED|$ACTOR|$NOTE")
        history.joinToString() shouldNotContain NOTE
        tx { port.history(UUID.randomUUID()) }.shouldBeEmpty()
    }

    // --- dismiss -----------------------------------------------------------------------------------------------------

    @Test
    fun `dismiss closes the report and gives back the photo it held exactly as it was`() {
        val (entry, photo) = entryWithPhoto()
        val before = assetRow(photo)
        val report = report(entry, "OTHER", detail = DETAIL, at = T0)
        assetRow(photo) shouldNotBe before

        tx { port.dismiss(report, "OTHER", ACTOR, NOTE) } shouldBe DismissOutcome.DISMISSED

        reportRow(report) shouldBe "DISMISSED|true|null"
        assetRow(photo) shouldBe before
        holds(photo).shouldBeEmpty()
        log(report) shouldContainExactly listOf("DISMISSED|$ACTOR|$NOTE")
        mediaLog(photo).last() shouldBe "RELEASED|$ACTOR"
    }

    @Test
    fun `dismiss of a decided or unknown report is not open, and writes nothing`() {
        val (entry) = entryWithPhoto()
        val report = report(entry, "OTHER", at = T0)
        tx { port.dismiss(report, "OTHER", ACTOR, NOTE) }
        val logged = log(report)

        tx { port.dismiss(report, "OTHER", ACTOR, NOTE) } shouldBe DismissOutcome.NOT_OPEN
        tx { port.dismiss(UUID.randomUUID(), "OTHER", ACTOR, NOTE) } shouldBe DismissOutcome.NOT_OPEN
        log(report) shouldBe logged
    }

    @Test
    fun `dismiss on a page showing another reason is stale, and nothing is written or given back`() {
        val (entry, photo) = entryWithPhoto()
        val report = report(entry, "MINOR_SEXUAL_CONTENT", detail = DETAIL, at = T0)
        val held = assetRow(photo)

        tx { port.dismiss(report, "OTHER", ACTOR, NOTE) } shouldBe DismissOutcome.STALE

        reportRow(report) shouldBe "OPEN|false|$DETAIL"
        log(report).shouldBeEmpty()
        assetRow(photo) shouldBe held
        holds(photo) shouldContainExactly listOf(report)
    }

    // --- reclassify --------------------------------------------------------------------------------------------------

    @Test
    fun `reclassify changes the reason and logs both, and answers same reason, not open and stale`() {
        val (entry) = entryWithPhoto()
        val report = report(entry, "OTHER", at = T0)

        tx { port.reclassify(report, "OTHER", "HARASSMENT", ACTOR, NOTE) } shouldBe ReclassifyOutcome.RECLASSIFIED
        reason(report) shouldBe "HARASSMENT"
        log(report) shouldContainExactly listOf("RECLASSIFIED|$ACTOR|OTHER -> HARASSMENT: $NOTE")

        tx { port.reclassify(report, "HARASSMENT", "HARASSMENT", ACTOR, NOTE) } shouldBe ReclassifyOutcome.SAME_REASON
        tx { port.reclassify(report, "OTHER", "VIOLENCE_OR_SELF_HARM", ACTOR, NOTE) } shouldBe ReclassifyOutcome.STALE
        reason(report) shouldBe "HARASSMENT"
        log(report).size shouldBe 1

        tx { port.dismiss(report, "HARASSMENT", ACTOR, NOTE) }
        tx { port.reclassify(report, "HARASSMENT", "OTHER", ACTOR, NOTE) } shouldBe ReclassifyOutcome.NOT_OPEN
    }

    @Test
    fun `reclassify to a reason that is not one of the five is answered before any SQL, and the transaction goes on`() {
        val (entry) = entryWithPhoto()
        val report = report(entry, "OTHER", at = T0)

        val (answer, next) =
            tx {
                port.reclassify(report, "OTHER", "SPAM_BOT", ACTOR, NOTE) to
                    port.dismiss(report, "OTHER", ACTOR, NOTE)
            }

        answer shouldBe ReclassifyOutcome.UNKNOWN_REASON
        next shouldBe DismissOutcome.DISMISSED
        reason(report) shouldBe "OTHER"
        log(report) shouldContainExactly listOf("DISMISSED|$ACTOR|$NOTE")
    }

    // --- uphold ------------------------------------------------------------------------------------------------------

    @Test
    fun `uphold by delete deletes what the report held and says so per asset`() {
        val (entry, photo) = entryWithPhoto()
        val report = report(entry, "VIOLENCE_OR_SELF_HARM", detail = DETAIL, at = T0)

        val result = tx { port.uphold(report, "VIOLENCE_OR_SELF_HARM", Takedown.DELETE, ACTOR, NOTE) }

        result shouldBe UpholdResult.Done(listOf(photo to TakedownOutcome.DELETED))
        reportRow(report) shouldBe "UPHELD|true|null"
        status(photo) shouldBe "DELETED|QUARANTINED|false"
        log(report) shouldContainExactly listOf("UPHELD|$ACTOR|DELETE: $NOTE")
    }

    @Test
    fun `uphold by block blocks what the report held as evidence, and a blocked asset is kept by a delete`() {
        val (entry, photo) = entryWithPhoto()
        val first = report(entry, "MINOR_SEXUAL_CONTENT", at = T0)

        tx { port.uphold(first, "MINOR_SEXUAL_CONTENT", Takedown.BLOCK, ACTOR, NOTE) } shouldBe
            UpholdResult.Done(listOf(photo to TakedownOutcome.BLOCKED))
        status(photo) shouldBe "QUARANTINED|BLOCKED|true"

        val second = report(entry, "OTHER", at = T0.plusSeconds(1))
        tx { port.uphold(second, "OTHER", Takedown.DELETE, ACTOR, NOTE) } shouldBe
            UpholdResult.Done(listOf(photo to TakedownOutcome.BLOCKED_KEPT))
        status(photo) shouldBe "QUARANTINED|BLOCKED|true"
    }

    @Test
    fun `uphold by block of an asset a first upheld report already blocked answers already blocked`() {
        val (entry, photo) = entryWithPhoto()
        val first = report(entry, "VIOLENCE_OR_SELF_HARM", at = T0)
        val second = report(entry, "OTHER", at = T0.plusSeconds(1))

        tx { port.uphold(first, "VIOLENCE_OR_SELF_HARM", Takedown.BLOCK, ACTOR, NOTE) } shouldBe
            UpholdResult.Done(listOf(photo to TakedownOutcome.BLOCKED))
        tx { port.uphold(second, "OTHER", Takedown.BLOCK, ACTOR, NOTE) } shouldBe
            UpholdResult.Done(listOf(photo to TakedownOutcome.ALREADY_BLOCKED))
        status(photo) shouldBe "QUARANTINED|BLOCKED|true"
    }

    @Test
    fun `uphold by delete of a priority report is reclassify first, and writes nothing`() {
        val (entry, photo) = entryWithPhoto()
        val report = report(entry, "MINOR_SEXUAL_CONTENT", detail = DETAIL, at = T0)
        val held = assetRow(photo)

        tx { port.uphold(report, "MINOR_SEXUAL_CONTENT", Takedown.DELETE, ACTOR, NOTE) } shouldBe UpholdResult.ReclassifyFirst

        reportRow(report) shouldBe "OPEN|false|$DETAIL"
        log(report).shouldBeEmpty()
        assetRow(photo) shouldBe held
        holds(photo) shouldContainExactly listOf(report)
    }

    @Test
    fun `uphold of a report of words alone takes nothing down, and of a decided or unknown one is not open`() {
        val words = memberReport("HARASSMENT", at = T0)

        tx { port.uphold(words, "HARASSMENT", Takedown.DELETE, ACTOR, NOTE) } shouldBe UpholdResult.Done(emptyList())
        reportRow(words) shouldBe "UPHELD|true|null"

        tx { port.uphold(words, "HARASSMENT", Takedown.BLOCK, ACTOR, NOTE) } shouldBe UpholdResult.NotOpen
        tx { port.uphold(UUID.randomUUID(), "HARASSMENT", Takedown.BLOCK, ACTOR, NOTE) } shouldBe UpholdResult.NotOpen
        log(words).size shouldBe 1
    }

    /**
     * Spec §8's case: a priority report reclassified to `OTHER` under a page that still shows
     * `MINOR_SEXUAL_CONTENT`. The function would block; the port refuses, because the page
     * the moderator decided on is not the report.
     */
    @Test
    fun `uphold on a page showing another reason is stale, and nothing is taken down or written`() {
        val (entry, photo) = entryWithPhoto()
        val report = report(entry, "MINOR_SEXUAL_CONTENT", detail = DETAIL, at = T0)
        jdbc.queryForList("SELECT * FROM entry_report_reclassify(?, 'OTHER', 'op', 'wrong reason')", report)
        val logged = log(report)
        val held = assetRow(photo)

        tx { port.uphold(report, "MINOR_SEXUAL_CONTENT", Takedown.BLOCK, ACTOR, NOTE) } shouldBe UpholdResult.Stale

        reportRow(report) shouldBe "OPEN|false|$DETAIL"
        log(report) shouldBe logged
        assetRow(photo) shouldBe held
    }

    // --- the view rule's gratitude half ------------------------------------------------------------------------------

    @Test
    fun `reasons are reviewable only when every open report among the ids has a reviewable reason, and say which bars it`() {
        val (entry) = entryWithPhoto()
        val harassment = report(entry, "HARASSMENT", at = T0)
        val violence = report(entry, "VIOLENCE_OR_SELF_HARM", at = T0)
        val minor = report(entry, "MINOR_SEXUAL_CONTENT", at = T0)
        val consent = report(entry, "NON_CONSENSUAL", at = T0)
        val decidedMinor = report(entry, "MINOR_SEXUAL_CONTENT", at = T0)
        jdbc.queryForList("SELECT * FROM entry_report_uphold(?, 'BLOCK', 'op', 'done')", decidedMinor)

        tx { port.reviewableReasons(emptyList()) } shouldBe ReasonsReview.REVIEWABLE
        tx { port.reviewableReasons(listOf(harassment, violence)) } shouldBe ReasonsReview.REVIEWABLE
        tx { port.reviewableReasons(listOf(harassment, minor)) } shouldBe ReasonsReview.PRIORITY_REPORT
        tx { port.reviewableReasons(listOf(consent)) } shouldBe ReasonsReview.CONSENT_REPORT
        tx { port.reviewableReasons(listOf(consent, minor)) } shouldBe ReasonsReview.PRIORITY_REPORT
        tx { port.reviewableReasons(listOf(harassment, decidedMinor)) } shouldBe ReasonsReview.REVIEWABLE
        tx { port.reviewableReasons(listOf(UUID.randomUUID())) } shouldBe ReasonsReview.REVIEWABLE
    }

    // --- the transaction ---------------------------------------------------------------------------------------------

    @Test
    fun `every method refuses to run outside a transaction, and writes nothing`() {
        val (entry, photo) = entryWithPhoto()
        val report = report(entry, "OTHER", at = T0)
        val held = assetRow(photo)
        val calls: List<() -> Any?> =
            listOf(
                { port.queue(ACTOR) },
                { port.openCount() },
                { port.read(report, ACTOR) },
                { port.history(report) },
                { port.dismiss(report, "OTHER", ACTOR, NOTE) },
                { port.reclassify(report, "OTHER", "HARASSMENT", ACTOR, NOTE) },
                { port.uphold(report, "OTHER", Takedown.DELETE, ACTOR, NOTE) },
                { port.reviewableReasons(listOf(report)) },
            )

        calls.forEach { call -> assertThrows<IllegalTransactionStateException> { call() } }
        jdbc.queryForObject("SELECT count(*) FROM entry_report_log", Int::class.java) shouldBe 0
        reportRow(report) shouldBe "OPEN|false|null"
        assetRow(photo) shouldBe held
    }

    @Test
    fun `an answer the port does not know fails loudly, naming the function and not the report`() {
        val report = UUID.randomUUID()
        val failure =
            assertThrows<IllegalStateException> {
                ReportModerationStore.outcomeOf<DismissOutcome>("entry_report_dismiss", "SOMETHING_NEW")
            }
        failure.message!! shouldContain "entry_report_dismiss"
        failure.message!! shouldContain "SOMETHING_NEW"
        failure.message!! shouldNotContain report.toString()
    }

    // --- rig ---------------------------------------------------------------------------------------------------------

    private fun <T : Any> tx(block: () -> T): T = transactions.execute { block() }!!

    /** An entry and its photo, `READY` and `PASSED` as moderation left it, attached to the entry. */
    private fun entryWithPhoto(): Pair<UUID, UUID> {
        val entry = UUID.randomUUID()
        val photo = photos.ready(owner = owner, verdict = "PASSED")
        jdbc.update("UPDATE media_assets SET attached_entry_id = ? WHERE id = ?", entry, photo)
        return entry to photo
    }

    /** A report of [entry], filed as the route files one: the row, then `media_report_hold`. */
    private fun report(
        entry: UUID,
        reason: String,
        detail: String? = null,
        at: Instant,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO entry_reports (id, target, entry_id, bond_id, reporter_member_id, reason, detail, status, created_at) " +
                "VALUES (?, 'ENTRY', ?, ?, ?, ?, ?, 'OPEN', ?)",
            id,
            entry,
            BOND,
            REPORTER,
            reason,
            detail,
            java.sql.Timestamp.from(at),
        )
        jdbc.queryForObject("SELECT media_report_hold(?, ?, 'member-report')", Int::class.java, entry, id)
        return id
    }

    private fun memberReport(
        reason: String,
        at: Instant,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO entry_reports (id, target, reported_member_id, bond_id, reporter_member_id, reason, detail, status, created_at) " +
                "VALUES (?, 'MEMBER', ?, ?, ?, ?, NULL, 'OPEN', ?)",
            id,
            REPORTED_MEMBER,
            BOND,
            REPORTER,
            reason,
            java.sql.Timestamp.from(at),
        )
        return id
    }

    private fun reportRow(id: UUID): String =
        jdbc.queryForObject(
            "SELECT status || '|' || (resolved_at IS NOT NULL) || '|' || coalesce(detail, 'null') FROM entry_reports WHERE id = ?",
            String::class.java,
            id,
        )!!

    private fun reason(id: UUID): String = jdbc.queryForObject("SELECT reason FROM entry_reports WHERE id = ?", String::class.java, id)!!

    private fun log(report: UUID?): List<String> =
        jdbc
            .queryForList(
                "SELECT action || '|' || actor || '|' || coalesce(note, '') FROM entry_report_log " +
                    "WHERE report_id IS NOT DISTINCT FROM ? ORDER BY at, id",
                String::class.java,
                report,
            ).filterNotNull()

    private fun mediaLog(photo: UUID): List<String> =
        jdbc
            .queryForList(
                "SELECT action || '|' || actor FROM media_moderation_log WHERE media_id = ? ORDER BY at, id",
                String::class.java,
                photo,
            ).filterNotNull()

    private fun assetRow(photo: UUID): String =
        jdbc.queryForObject(
            "SELECT status || '|' || coalesce(moderation_verdict, '') || '|' || coalesce(moderated_at::text, '') || '|' || " +
                "coalesce(moderation_scores::text, '') || '|' || evidence_held FROM media_assets WHERE id = ?",
            String::class.java,
            photo,
        )!!

    private fun status(photo: UUID): String =
        jdbc.queryForObject(
            "SELECT status || '|' || coalesce(moderation_verdict, '') || '|' || evidence_held FROM media_assets WHERE id = ?",
            String::class.java,
            photo,
        )!!

    private fun holds(photo: UUID): List<UUID> =
        jdbc.queryForList("SELECT report_id FROM media_report_holds WHERE media_id = ?", UUID::class.java, photo).filterNotNull()

    private infix fun String.shouldNotBe(other: String) = (this == other) shouldBe false

    private companion object {
        const val ACTOR = "admin:0f0f0f0f-0000-4000-8000-0000000000ad"
        const val NOTE = "looked, decided"
        const val DETAIL = "a detail only the moderator reads 7c1e"
        val T0: Instant = Instant.parse("2026-11-01T09:00:00Z")
        val BOND: UUID = UUID.fromString("0f0f0f0f-0000-4000-8000-00000000b0d1")
        val REPORTER: UUID = UUID.fromString("0f0f0f0f-0000-4000-8000-00000000ae01")
        val REPORTED_MEMBER: UUID = UUID.fromString("0f0f0f0f-0000-4000-8000-00000000be02")
    }
}
