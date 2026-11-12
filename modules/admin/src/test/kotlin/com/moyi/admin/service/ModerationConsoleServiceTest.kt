package com.moyi.admin.service

import com.moyi.admin.domain.AdminRole
import com.moyi.admin.domain.AuditAction
import com.moyi.admin.domain.AuditOutcome
import com.moyi.admin.domain.Disclosure
import com.moyi.admin.domain.LookupPurpose
import com.moyi.admin.infra.AdminTestApplication
import com.moyi.admin.infra.FakeMediaStore
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.gratitude.api.DismissOutcome
import com.moyi.gratitude.api.ReclassifyOutcome
import com.moyi.gratitude.api.ReportModeration
import com.moyi.gratitude.api.Takedown
import com.moyi.gratitude.api.TakedownOutcome
import com.moyi.gratitude.api.UpholdResult
import com.moyi.media.api.BlockOutcome
import com.moyi.media.api.DeleteOutcome
import com.moyi.media.api.QuarantineModeration
import com.moyi.media.api.ReleaseOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.transaction.support.TransactionTemplate
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * `ModerationConsole` (A2 spec §4, §6, §7.4, §9.2, §10.2, §12; plan Task 5) against the real
 * schema and the two real ports, with only media's object store faked ([FakeMediaStore]).
 *
 * Here, not in `app`: the console is `internal` to this module. Both ports are wrapped by
 * [Recorder], which notes every call, so that a refusal can be shown to have reached neither
 * port, and which lets a test run a statement inside a port call's transaction.
 */
@SpringBootTest(
    classes = [AdminTestApplication::class],
    properties = [
        "moyi.admin.enabled=true",
        "moyi.admin.allowed-networks=10.9.0.0/16",
        "moyi.admin.totp-key=${AdminConsoleServiceTest.KEY}",
        // The lock tests hold one connection, run the console on another, and poll on a third.
        "spring.datasource.hikari.maximum-pool-size=8",
    ],
)
@Import(AdminConsoleServiceTest.TimeConfiguration::class, ModerationConsoleServiceTest.Recording::class)
@Suppress("LargeClass") // Every promise of one service, over one rig; split, each half would need the other's rig.
internal class ModerationConsoleServiceTest(
    @Autowired private val console: ModerationConsole,
    @Autowired private val clock: MutableClock,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val dataSource: DataSource,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val admin = UUID.randomUUID()

    @BeforeEach
    fun clean() {
        Recorder.reset()
        FakeMediaStore.reset()
    }

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    // --- roles -------------------------------------------------------------------------------------------------------

    @Test
    fun `SUPPORT is refused on every method before any port is called or any row written`() {
        val report = report(entryWithPhoto().first, "OTHER")
        val photo = quarantined()
        val proven = clock.instant()
        val reportLog = count("SELECT count(*) FROM entry_report_log")
        val mediaLog = count("SELECT count(*) FROM media_moderation_log")

        val calls: List<() -> Any?> =
            listOf(
                { console.counts() },
                { console.reportQueue(admin, null) },
                { console.report(report, admin, null) },
                { console.dismiss(report, "OTHER", NOTE, admin, null) },
                { console.reclassify(report, "OTHER", "HARASSMENT", NOTE, admin, null) },
                { console.uphold(report, "OTHER", Takedown.DELETE, NOTE, proven, admin, null) },
                { console.quarantineQueue(admin, null) },
                { console.quarantined(photo, admin, null) },
                { console.release(photo, NOTE, admin, null) },
                { console.block(photo, NOTE, proven, admin, null) },
                { console.delete(photo, NOTE, proven, admin, null) },
                { console.openForReview(photo, ReviewOrigin.Quarantine, admin, null) },
            )
        acting(AdminRole.SUPPORT) { calls.forEach { call -> shouldThrow<AccessDeniedException> { call() } } }

        Recorder.calls.shouldBeEmpty()
        audited().shouldBeEmpty()
        count("SELECT count(*) FROM entry_report_log") shouldBe reportLog
        count("SELECT count(*) FROM media_moderation_log") shouldBe mediaLog
        reportStatus(report) shouldBe "OPEN|OTHER"
        assetState(photo) shouldBe "QUARANTINED|QUARANTINED|false"
    }

    @Test
    fun `ADMIN passes the MODERATOR check through the role hierarchy`() {
        acting(AdminRole.ADMIN) { console.reportQueue(admin, null) }
        audited().map { it.action } shouldContainExactly listOf("REPORT_QUEUE_VIEWED")
    }

    // --- each decision's row -----------------------------------------------------------------------------------------

    @Test
    fun `dismiss writes one row with the note as justification and the function names the admin`() {
        val (entry, photo) = entryWithPhoto()
        val report = report(entry, "OTHER", detail = DETAIL)

        acting(AdminRole.MODERATOR) { console.dismiss(report, "OTHER", NOTE, admin, null) } shouldBe DismissOutcome.DISMISSED

        val row = audited().single()
        row.matches(AuditAction.REPORT_DISMISSED, AuditOutcome.SUCCEEDED, "REPORT", report, setOf("result"))
        row.metadata["result"] shouldBe "DISMISSED"
        reportActors(report) shouldContainExactly listOf("DISMISSED|admin:$admin")
        mediaActors(photo).last() shouldBe "RELEASED|admin:$admin"
        assetState(photo) shouldBe "READY|PASSED|false"
    }

    @Test
    fun `a second dismiss is refused not open, audited REFUSED, and a stale one is refused stale`() {
        val report = report(entryWithPhoto().first, "OTHER")
        val other = report(entryWithPhoto().first, "OTHER")

        acting(AdminRole.MODERATOR) {
            console.dismiss(report, "OTHER", NOTE, admin, null)
            console.dismiss(report, "OTHER", NOTE, admin, null) shouldBe DismissOutcome.NOT_OPEN
            console.dismiss(other, "HARASSMENT", NOTE, admin, null) shouldBe DismissOutcome.STALE
        }

        audited().map { "${it.outcome}|${it.metadata["result"]}" } shouldContainExactly
            listOf("SUCCEEDED|DISMISSED", "REFUSED|NOT_OPEN", "REFUSED|STALE")
        reportStatus(other) shouldBe "OPEN|OTHER"
    }

    @Test
    fun `reclassify records from and to only when the function vouched for them`() {
        val report = report(entryWithPhoto().first, "OTHER")
        val stale = report(entryWithPhoto().first, "OTHER")

        acting(AdminRole.MODERATOR) {
            console.reclassify(report, "OTHER", "HARASSMENT", NOTE, admin, null) shouldBe ReclassifyOutcome.RECLASSIFIED
            console.reclassify(stale, "HARASSMENT", "VIOLENCE_OR_SELF_HARM", NOTE, admin, null) shouldBe ReclassifyOutcome.STALE
        }

        val (done, refused) = audited()
        done.matches(AuditAction.REPORT_RECLASSIFIED, AuditOutcome.SUCCEEDED, "REPORT", report, setOf("result", "from", "to"))
        done.metadata shouldBe mapOf("result" to "RECLASSIFIED", "from" to "OTHER", "to" to "HARASSMENT")
        refused.matches(AuditAction.REPORT_RECLASSIFIED, AuditOutcome.REFUSED, "REPORT", stale, setOf("result"))
        reportActors(report) shouldContainExactly listOf("RECLASSIFIED|admin:$admin")
    }

    @Test
    fun `reclassify to a reason that is not one of the five commits its REFUSED row`() {
        val report = report(entryWithPhoto().first, "OTHER")

        acting(AdminRole.MODERATOR) {
            console.reclassify(report, "OTHER", "NOT_A_REASON", NOTE, admin, null) shouldBe ReclassifyOutcome.UNKNOWN_REASON
        }

        val row = audited().single()
        row.matches(AuditAction.REPORT_RECLASSIFIED, AuditOutcome.REFUSED, "REPORT", report, setOf("result"))
        row.metadata["result"] shouldBe "UNKNOWN_REASON"
        reportStatus(report) shouldBe "OPEN|OTHER"
    }

    @Test
    fun `an uphold by delete writes one REPORT_UPHELD row, its metadata field by field`() {
        val (entry, photo) = entryWithPhoto()
        val report = report(entry, "VIOLENCE_OR_SELF_HARM", detail = DETAIL)

        val result =
            acting(AdminRole.MODERATOR) { console.uphold(report, "VIOLENCE_OR_SELF_HARM", Takedown.DELETE, NOTE, fresh(), admin, null) }

        result shouldBe UpholdResult.Done(listOf(photo to TakedownOutcome.DELETED))
        val row = audited().single()
        row.matches(AuditAction.REPORT_UPHELD, AuditOutcome.SUCCEEDED, "REPORT", report, setOf("result", "takedown", "media"))
        row.metadata["result"] shouldBe "UPHELD"
        row.metadata["takedown"] shouldBe "DELETE"
        upheldMedia(report) shouldContainExactly listOf("id,outcome|$photo|DELETED")
        reportActors(report) shouldContainExactly listOf("UPHELD|admin:$admin")
    }

    @Test
    fun `an uphold by block writes one REPORT_UPHELD row, its metadata field by field`() {
        val (entry, photo) = entryWithPhoto()
        val report = report(entry, "MINOR_SEXUAL_CONTENT")

        val result =
            acting(AdminRole.MODERATOR) { console.uphold(report, "MINOR_SEXUAL_CONTENT", Takedown.BLOCK, NOTE, fresh(), admin, null) }

        result shouldBe UpholdResult.Done(listOf(photo to TakedownOutcome.BLOCKED))
        val row = audited().single()
        row.matches(AuditAction.REPORT_UPHELD, AuditOutcome.SUCCEEDED, "REPORT", report, setOf("result", "takedown", "media"))
        row.metadata["result"] shouldBe "UPHELD"
        row.metadata["takedown"] shouldBe "BLOCK"
        upheldMedia(report) shouldContainExactly listOf("id,outcome|$photo|BLOCKED")
        reportActors(report) shouldContainExactly listOf("UPHELD|admin:$admin")
    }

    @Test
    fun `an uphold by delete or by block writes no MEDIA row, one REPORT_UPHELD row, and the export shows none of it`() {
        // A moderation quarantine with no report-driven reason of its own, taken down by a report;
        // and a reported photo blocked as evidence.
        val entry = UUID.randomUUID()
        val photo = quarantined(attachedTo = entry)
        val report = report(entry, "HARASSMENT")
        val blockReport = report(entryWithPhoto().first, "MINOR_SEXUAL_CONTENT")

        acting(AdminRole.MODERATOR) {
            console.uphold(report, "HARASSMENT", Takedown.DELETE, NOTE, fresh(), admin, null)
            console.uphold(blockReport, "MINOR_SEXUAL_CONTENT", Takedown.BLOCK, NOTE, fresh(), admin, null)
        }

        val rows = audited()
        rows.filter { it.action.startsWith("MEDIA_") }.shouldBeEmpty()
        rows.map { "${it.action}|${it.outcome}|${it.targetId}" } shouldContainExactly
            listOf("REPORT_UPHELD|SUCCEEDED|$report", "REPORT_UPHELD|SUCCEEDED|$blockReport")
        rows.forEach { row ->
            // Read as the export reads it: the row's own fields, from the database, and nothing else.
            Disclosure
                .shows(
                    AuditAction.valueOf(row.action),
                    AuditOutcome.valueOf(row.outcome),
                    row.metadata["purpose"]?.let(LookupPurpose::valueOf),
                    row.metadata["reportDriven"]?.toBooleanStrictOrNull(),
                ).shouldBeNull()
        }
        assetState(photo) shouldBe "DELETED|QUARANTINED|false"
    }

    @Test
    fun `release, block and delete each write one MEDIA row with result and reportDriven`() {
        val released = quarantined()
        val blocked = quarantined()
        val deleted = quarantined()

        acting(AdminRole.MODERATOR) {
            console.release(released, NOTE, admin, null).outcome shouldBe ReleaseOutcome.RELEASED
            console.block(blocked, NOTE, fresh(), admin, null).outcome shouldBe BlockOutcome.BLOCKED
            console.delete(deleted, NOTE, fresh(), admin, null).outcome shouldBe DeleteOutcome.DELETED
        }

        val rows = audited()
        val keys = setOf("result", "reportDriven")
        rows[0].matches(AuditAction.MEDIA_RELEASED, AuditOutcome.SUCCEEDED, "MEDIA", released, keys)
        rows[1].matches(AuditAction.MEDIA_BLOCKED, AuditOutcome.SUCCEEDED, "MEDIA", blocked, keys)
        rows[2].matches(AuditAction.MEDIA_DELETED, AuditOutcome.SUCCEEDED, "MEDIA", deleted, keys)
        rows.forEach { it.metadata["reportDriven"] shouldBe "false" }
        assetState(blocked) shouldBe "QUARANTINED|BLOCKED|true"
        mediaActors(deleted).last() shouldBe "DELETED|admin:$admin"
    }

    @Test
    fun `a delete of a blocked asset is the port's EVIDENCE_SERVER_ONLY, the asset untouched, audited REFUSED`() {
        val photo = quarantined()
        acting(AdminRole.MODERATOR) { console.block(photo, NOTE, fresh(), admin, null) }
        val before = assetState(photo)

        val decision = acting(AdminRole.MODERATOR) { console.delete(photo, NOTE, fresh(), admin, null) }

        decision.outcome shouldBe DeleteOutcome.EVIDENCE_SERVER_ONLY
        assetState(photo) shouldBe before
        val row = audited().last()
        row.matches(AuditAction.MEDIA_DELETED, AuditOutcome.REFUSED, "MEDIA", photo, setOf("result", "reportDriven"))
        row.metadata["result"] shouldBe "EVIDENCE_SERVER_ONLY"
        // The service read nothing first: the one call it made for the delete was the port's own.
        Recorder.calls.filter { it.startsWith("media.") } shouldContainExactly listOf("media.block", "media.delete")
    }

    @Test
    fun `no note, a blank one, or one of 401 code points is refused before any port, with nothing written`() {
        val report = report(entryWithPhoto().first, "OTHER")
        val photo = quarantined()
        val tooLong = "x".repeat(ModerationNote.MAX_CODE_POINTS + 1)

        val decisions: List<(String) -> Any> =
            listOf(
                { console.dismiss(report, "OTHER", it, admin, null) },
                { console.reclassify(report, "OTHER", "HARASSMENT", it, admin, null) },
                { console.uphold(report, "OTHER", Takedown.DELETE, it, fresh(), admin, null) },
                { console.release(photo, it, admin, null) },
                { console.block(photo, it, fresh(), admin, null) },
                { console.delete(photo, it, fresh(), admin, null) },
            )
        acting(AdminRole.MODERATOR) {
            decisions.forEach { decide ->
                listOf("", "   ", tooLong, "nul\u0000here").forEach { note ->
                    val refused = shouldThrow<InvalidModerationNote> { decide(note) }
                    refused.message!! shouldNotContain "xxxx"
                }
            }
        }

        Recorder.calls.shouldBeEmpty()
        audited().shouldBeEmpty()
        reportStatus(report) shouldBe "OPEN|OTHER"
        assetState(photo) shouldBe "QUARANTINED|QUARANTINED|false"
    }

    @Test
    fun `a note of 400 code points is kept whole, though it is 800 UTF-16 characters, and is trimmed`() {
        val report = report(entryWithPhoto().first, "OTHER")
        val note = "🌿".repeat(ModerationNote.MAX_CODE_POINTS)

        acting(AdminRole.MODERATOR) { console.dismiss(report, "OTHER", "  $note\n", admin, null) } shouldBe DismissOutcome.DISMISSED

        audited().single().justification shouldBe note
        jdbc.queryForObject("SELECT note FROM entry_report_log WHERE report_id = ?", String::class.java, report) shouldBe note
    }

    // --- atomicity and isolation -------------------------------------------------------------------------------------

    @Test
    fun `a dismiss whose audit row fails is rolled back, the report open, the media held, no DISMISSED`() {
        val (entry, photo) = entryWithPhoto()
        val report = report(entry, "OTHER")
        val held = assetState(photo)
        jdbc.execute(
            "CREATE OR REPLACE FUNCTION moderation_console_test_fail() RETURNS trigger LANGUAGE plpgsql AS " +
                "\$\$ BEGIN IF NEW.justification = '$FAILING_NOTE' THEN RAISE EXCEPTION 'audit refused (test)'; END IF; " +
                "RETURN NEW; END \$\$",
        )
        jdbc.execute(
            "CREATE TRIGGER moderation_console_test_fail BEFORE INSERT ON audit_log " +
                "FOR EACH ROW EXECUTE FUNCTION moderation_console_test_fail()",
        )
        try {
            acting(AdminRole.MODERATOR) {
                shouldThrow<RuntimeException> { console.dismiss(report, "OTHER", FAILING_NOTE, admin, null) }
            }
        } finally {
            jdbc.execute("DROP TRIGGER moderation_console_test_fail ON audit_log")
            jdbc.execute("DROP FUNCTION moderation_console_test_fail()")
        }

        Recorder.calls shouldContainExactly listOf("reports.dismiss")
        reportStatus(report) shouldBe "OPEN|OTHER"
        assetState(photo) shouldBe held
        reportActors(report).shouldBeEmpty()
        audited().shouldBeEmpty()
    }

    @Test
    fun `a decision runs at READ COMMITTED, which media_quarantine_delete requires, and the delete succeeds`() {
        val photo = quarantined()
        var isolation: String? = null
        Recorder.before["media.delete"] = { isolation = jdbc.queryForObject("SHOW transaction_isolation", String::class.java) }

        val decision = acting(AdminRole.MODERATOR) { console.delete(photo, NOTE, fresh(), admin, null) }

        isolation shouldBe "read committed"
        decision.outcome shouldBe DeleteOutcome.DELETED
    }

    // --- step-up -----------------------------------------------------------------------------------------------------

    @Test
    fun `uphold, block and delete without a second factor in the last five minutes decide nothing`() {
        val report = report(entryWithPhoto().first, "OTHER")
        val photo = quarantined()
        val stale = clock.instant().minus(Duration.ofMinutes(5).plusSeconds(1))

        acting(AdminRole.MODERATOR) {
            listOf(null, stale).forEach { proof ->
                shouldThrow<StepUpRequired> { console.uphold(report, "OTHER", Takedown.DELETE, NOTE, proof, admin, null) }
                shouldThrow<StepUpRequired> { console.block(photo, NOTE, proof, admin, null) }
                shouldThrow<StepUpRequired> { console.delete(photo, NOTE, proof, admin, null) }
            }
        }

        Recorder.calls.shouldBeEmpty()
        audited().shouldBeEmpty()
        reportStatus(report) shouldBe "OPEN|OTHER"
        assetState(photo) shouldBe "QUARANTINED|QUARANTINED|false"
    }

    @Test
    fun `at 4 min 59 s the same three decide`() {
        val report = report(entryWithPhoto().first, "OTHER")
        val blocked = quarantined()
        val deleted = quarantined()
        val proof = clock.instant().minus(Duration.ofMinutes(4).plusSeconds(59))

        acting(AdminRole.MODERATOR) {
            console.uphold(report, "OTHER", Takedown.DELETE, NOTE, proof, admin, null).shouldBeInstanceOf<UpholdResult.Done>()
            console.block(blocked, NOTE, proof, admin, null).outcome shouldBe BlockOutcome.BLOCKED
            console.delete(deleted, NOTE, proof, admin, null).outcome shouldBe DeleteOutcome.DELETED
        }
    }

    // --- the view (spec §7.4) ----------------------------------------------------------------------------------------

    @Test
    fun `a moderation quarantine is served, its row written as a quarantine look that no report drove`() {
        val photo = quarantined()

        val copy = acting(AdminRole.MODERATOR) { console.openForReview(photo, ReviewOrigin.Quarantine, admin, null) }.shouldNotBeNull()

        FakeMediaStore.closed.get() shouldBe 0 // A committed look hands the copy, open, to the caller.
        copy.use { it.content.readAllBytes() shouldBe FakeMediaStore.BYTES }
        copy.contentType shouldBe "image/webp"
        val row = audited().single()
        row.matches(AuditAction.MEDIA_VIEWED, AuditOutcome.SUCCEEDED, "MEDIA", photo, setOf("via", "reportDriven"), justification = null)
        row.metadata shouldBe mapOf("via" to "QUARANTINE", "reportDriven" to "false")
        Recorder.calls shouldContainExactly listOf("media.reportsHolding", "reports.reviewableReasons", "media.reviewCopy")
    }

    @Test
    fun `a photo under a VIOLENCE_OR_SELF_HARM report is served, its row naming the report`() {
        val (entry, photo) = entryWithPhoto()
        val report = report(entry, "VIOLENCE_OR_SELF_HARM")

        acting(AdminRole.MODERATOR) { console.openForReview(photo, ReviewOrigin.Report(report), admin, null) }.shouldNotBeNull().close()

        audited().single().metadata shouldBe mapOf("via" to "REPORT", "reportId" to report.toString(), "reportDriven" to "true")
    }

    @Test
    fun `a look from a page that never offered it is refused before any lock, the forged origin written nowhere`() {
        val (entry, photo) = entryWithPhoto()
        report(entry, "VIOLENCE_OR_SELF_HARM")
        val other = report(entryWithPhoto().first, "VIOLENCE_OR_SELF_HARM")
        val unknown = UUID.randomUUID()
        val held = quarantined()
        Recorder.reset()

        acting(AdminRole.MODERATOR) {
            console.openForReview(photo, ReviewOrigin.Report(other), admin, null).shouldBeNull()
            console.openForReview(held, ReviewOrigin.Report(unknown), admin, null).shouldBeNull()
            // The quarantine page offers no View while an open report holds the asset.
            console.openForReview(photo, ReviewOrigin.Quarantine, admin, null).shouldBeNull()
        }

        // Refused on the unlocked read alone: no report lock, no asset lock, no store. The second
        // reads the asset's unlocked row only to say whether a report drove it.
        Recorder.calls shouldContainExactly
            listOf("media.reportsHolding", "media.reportsHolding", "media.asset", "media.reportsHolding")
        FakeMediaStore.opened.shouldBeEmpty()
        val (forged, unheld, forgedQuarantine) = audited()
        forged.matches(AuditAction.MEDIA_VIEWED, AuditOutcome.REFUSED, "MEDIA", photo, setOf("reason", "reportDriven"), null)
        forged.metadata shouldBe mapOf("reason" to "wrong_origin", "reportDriven" to "true")
        unheld.metadata shouldBe mapOf("reason" to "wrong_origin", "reportDriven" to "false")
        forgedQuarantine.matches(AuditAction.MEDIA_VIEWED, AuditOutcome.REFUSED, "MEDIA", photo, setOf("reason", "reportDriven"), null)
        forgedQuarantine.metadata shouldBe mapOf("reason" to "wrong_origin", "reportDriven" to "true")
        audited().forEach {
            it.metadataText shouldNotContain other.toString()
            it.metadataText shouldNotContain unknown.toString()
        }
    }

    @Test
    fun `a priority or consent report refuses the look before the asset or the store is touched`() {
        val (minorEntry, minorPhoto) = entryWithPhoto()
        val minor = report(minorEntry, "MINOR_SEXUAL_CONTENT")
        val (consentEntry, consentPhoto) = entryWithPhoto()
        val consent = report(consentEntry, "NON_CONSENSUAL")

        acting(AdminRole.MODERATOR) {
            console.openForReview(minorPhoto, ReviewOrigin.Report(minor), admin, null).shouldBeNull()
            console.openForReview(consentPhoto, ReviewOrigin.Report(consent), admin, null).shouldBeNull()
        }

        Recorder.calls shouldNotContain "media.reviewCopy"
        FakeMediaStore.opened.shouldBeEmpty()
        audited().map { "${it.outcome}|${it.metadata["reason"]}|${it.metadata["reportDriven"]}" } shouldContainExactly
            listOf("REFUSED|priority_report|true", "REFUSED|consent_report|true")
    }

    @Test
    fun `blocked, purged and released assets are refused by media's half without the store`() {
        val blocked = quarantined()
        acting(AdminRole.MODERATOR) { console.block(blocked, NOTE, fresh(), admin, null) }
        val purged = purged()
        val released = quarantined()
        acting(AdminRole.MODERATOR) { console.release(released, NOTE, admin, null) }

        acting(AdminRole.MODERATOR) {
            listOf(blocked, purged, released).forEach { console.openForReview(it, ReviewOrigin.Quarantine, admin, null).shouldBeNull() }
        }

        FakeMediaStore.opened.shouldBeEmpty()
        audited().filter { it.action == "MEDIA_VIEWED" }.map { "${it.outcome}|${it.metadata["reason"]}" } shouldContainExactly
            listOf("REFUSED|blocked", "REFUSED|purged", "REFUSED|not_held")
    }

    @Test
    fun `a storage that is down refuses the look as unavailable, and the REFUSED row commits`() {
        val photo = quarantined()
        FakeMediaStore.unavailable = true

        acting(AdminRole.MODERATOR) { console.openForReview(photo, ReviewOrigin.Quarantine, admin, null) }.shouldBeNull()

        val row = audited().single()
        row.matches(AuditAction.MEDIA_VIEWED, AuditOutcome.REFUSED, "MEDIA", photo, setOf("via", "reportDriven", "reason"), null)
        row.metadata["reason"] shouldBe "unavailable"
    }

    @Test
    fun `a look whose MEDIA_VIEWED row fails throws, and the copy it opened is closed`() {
        val photo = quarantined()
        jdbc.execute(
            "CREATE OR REPLACE FUNCTION moderation_console_test_view_fail() RETURNS trigger LANGUAGE plpgsql AS " +
                "\$\$ BEGIN IF NEW.action = 'MEDIA_VIEWED' THEN RAISE EXCEPTION 'audit refused (test)'; END IF; " +
                "RETURN NEW; END \$\$",
        )
        jdbc.execute(
            "CREATE TRIGGER moderation_console_test_view_fail BEFORE INSERT ON audit_log " +
                "FOR EACH ROW EXECUTE FUNCTION moderation_console_test_view_fail()",
        )
        // Inside a caller's transaction, so that the close is seen as the exception arrives, before
        // any rollback could close it: the look itself closes what it opened.
        val closedWhenThrown =
            try {
                acting(AdminRole.MODERATOR) {
                    transactions.execute { status ->
                        shouldThrow<RuntimeException> { console.openForReview(photo, ReviewOrigin.Quarantine, admin, null) }
                        status.setRollbackOnly()
                        FakeMediaStore.closed.get()
                    }
                }
            } finally {
                jdbc.execute("DROP TRIGGER moderation_console_test_view_fail ON audit_log")
                jdbc.execute("DROP FUNCTION moderation_console_test_view_fail()")
            }

        closedWhenThrown shouldBe 1
        FakeMediaStore.opened shouldHaveSize 1
        FakeMediaStore.closed.get() shouldBe 1
        audited().shouldBeEmpty()
    }

    @Test
    fun `a copy handed to a transaction that then rolls back is closed when it rolls back`() {
        val photo = quarantined()
        var closedBeforeRollback = -1

        val copy =
            acting(AdminRole.MODERATOR) {
                transactions.execute { status ->
                    console.openForReview(photo, ReviewOrigin.Quarantine, admin, null).also {
                        closedBeforeRollback = FakeMediaStore.closed.get()
                        status.setRollbackOnly()
                    }
                }
            }

        copy.shouldNotBeNull()
        closedBeforeRollback shouldBe 0
        FakeMediaStore.closed.get() shouldBe 1
        audited().shouldBeEmpty()
    }

    // --- reads -------------------------------------------------------------------------------------------------------

    @Test
    fun `counts write no LISTED row in either module log and no audit row`() {
        val before = acting(AdminRole.MODERATOR) { console.counts() }
        report(entryWithPhoto().first, "OTHER")
        quarantined()
        val listed = count("SELECT count(*) FROM entry_report_log WHERE action = 'LISTED'")
        val mediaListed = count("SELECT count(*) FROM media_moderation_log WHERE action = 'LISTED'")

        val after = acting(AdminRole.MODERATOR) { console.counts() }

        // The report's photo is held back too, so the quarantine count rises by two.
        after shouldBe QueueCounts(reports = before.reports + 1, quarantine = before.quarantine + 2)
        count("SELECT count(*) FROM entry_report_log WHERE action = 'LISTED'") shouldBe listed
        count("SELECT count(*) FROM media_moderation_log WHERE action = 'LISTED'") shouldBe mediaListed
        audited().shouldBeEmpty()
    }

    @Test
    fun `the queues and pages are audited as looks, and the report page carries the detail to the caller only`() {
        val (entry, photo) = entryWithPhoto()
        val report = report(entry, "OTHER", detail = DETAIL)

        val page =
            acting(AdminRole.MODERATOR) {
                console.reportQueue(admin, null)
                console.quarantineQueue(admin, null)
                console.quarantined(photo, admin, null).shouldNotBeNull()
                console.report(UUID.randomUUID(), admin, null).shouldBeNull()
                console.report(report, admin, null).shouldNotBeNull()
            }

        page.case.detail shouldBe DETAIL
        page.toString() shouldNotContain DETAIL
        audited().map { "${it.action}|${it.outcome}|${it.targetType}" } shouldContainExactly
            listOf(
                "REPORT_QUEUE_VIEWED|SUCCEEDED|null",
                "QUARANTINE_QUEUE_VIEWED|SUCCEEDED|null",
                "QUARANTINE_QUEUE_VIEWED|SUCCEEDED|MEDIA",
                "REPORT_READ|REFUSED|REPORT",
                "REPORT_READ|SUCCEEDED|REPORT",
            )
        audited().forEach { it.metadataText shouldNotContain DETAIL }
    }

    @Test
    fun `the quarantine page refuses a READY photo that was never held, NOT_FOUND as for an unknown id`() {
        val photo = entryWithPhoto().second
        val unknown = UUID.randomUUID()

        acting(AdminRole.MODERATOR) {
            console.quarantined(photo, admin, null).shouldBeNull()
            console.quarantined(unknown, admin, null).shouldBeNull()
        }

        val (ready, missing) = audited()
        ready.matches(AuditAction.QUARANTINE_QUEUE_VIEWED, AuditOutcome.REFUSED, "MEDIA", photo, setOf("result"), justification = null)
        missing.matches(AuditAction.QUARANTINE_QUEUE_VIEWED, AuditOutcome.REFUSED, "MEDIA", unknown, setOf("result"), justification = null)
        ready.metadata shouldBe missing.metadata
        ready.metadata["result"] shouldBe "NOT_FOUND"
    }

    // --- dismiss against view: no deadlock (spec §12) ----------------------------------------------------------------

    /**
     * A dismiss holds the report's row (its first lock, the port's stale check) while the view
     * runs. The view's share lock on the report waits; the dismiss then takes the asset and
     * commits; the view goes on and refuses. Had the view locked the asset before the report,
     * the dismiss would wait on the view's asset lock while the view waits on the report: a cycle.
     */
    @Test
    fun `a view that starts while a dismiss holds the report waits, and neither deadlocks`() {
        val (entry, photo) = entryWithPhoto()
        val report = report(entry, "OTHER")

        val copy =
            dataSource.connection.use { dismiss ->
                dismiss.autoCommit = false
                try {
                    run(dismiss, "SELECT reason FROM entry_reports WHERE id = ? AND status = 'OPEN' FOR UPDATE", report)
                    val view =
                        CompletableFuture.supplyAsync {
                            acting(AdminRole.MODERATOR) { console.openForReview(photo, ReviewOrigin.Report(report), admin, null) }
                        }
                    awaitLockWait(VIEW_REPORT_LOCK)
                    run(dismiss, "SELECT entry_report_dismiss(?, 'op', 'dismissed while viewed')", report)
                    dismiss.commit()
                    view.get(DEADLINE_SECONDS, TimeUnit.SECONDS)
                } finally {
                    dismiss.rollback()
                }
            }

        copy.shouldBeNull()
        reportStatus(report) shouldBe "DISMISSED|OTHER"
        audited().single().metadata["reason"] shouldBe "not_held"
    }

    /** The reverse: the view holds its share locks, uncommitted, while a dismiss runs. The dismiss waits, then dismisses. */
    @Test
    fun `a dismiss that starts while a view holds its locks waits, and neither deadlocks`() {
        val (entry, photo) = entryWithPhoto()
        val report = report(entry, "OTHER")
        val viewed = CountDownLatch(1)
        val release = CountDownLatch(1)

        val view =
            CompletableFuture.supplyAsync {
                acting(AdminRole.MODERATOR) {
                    transactions.execute {
                        console.openForReview(photo, ReviewOrigin.Report(report), admin, null)?.use { it.content.readAllBytes() }.also {
                            viewed.countDown()
                            release.await(DEADLINE_SECONDS, TimeUnit.SECONDS)
                        }
                    }
                }
            }
        try {
            viewed.await(DEADLINE_SECONDS, TimeUnit.SECONDS) shouldBe true
            val dismiss =
                CompletableFuture.supplyAsync { acting(AdminRole.MODERATOR) { console.dismiss(report, "OTHER", NOTE, admin, null) } }
            awaitLockWait(DISMISS_REPORT_LOCK)
            release.countDown()

            view.get(DEADLINE_SECONDS, TimeUnit.SECONDS) shouldBe FakeMediaStore.BYTES
            dismiss.get(DEADLINE_SECONDS, TimeUnit.SECONDS) shouldBe DismissOutcome.DISMISSED
        } finally {
            release.countDown()
        }
        audited().map { "${it.action}|${it.outcome}" } shouldContainExactly listOf("MEDIA_VIEWED|SUCCEEDED", "REPORT_DISMISSED|SUCCEEDED")
    }

    // --- rig ---------------------------------------------------------------------------------------------------------

    private fun <T> acting(
        role: AdminRole,
        block: () -> T,
    ): T {
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication =
            UsernamePasswordAuthenticationToken.authenticated(admin, null, listOf(SimpleGrantedAuthority("ROLE_${role.name}")))
        SecurityContextHolder.setContext(context)
        try {
            return block()
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun fresh(): Instant = clock.instant()

    /** An entry and its photo, `READY` and `PASSED` as moderation left it. */
    private fun entryWithPhoto(): Pair<UUID, UUID> {
        val entry = UUID.randomUUID()
        return entry to asset(status = "READY", verdict = "PASSED", attachedTo = entry)
    }

    /** A photo held back by moderation (`EXTREME_VIOLENCE`), attached to [attachedTo] if given. */
    private fun quarantined(attachedTo: UUID? = null): UUID =
        asset(status = "QUARANTINED", verdict = "QUARANTINED", attachedTo = attachedTo, reason = "EXTREME_VIOLENCE")

    /** A photo deleted from a quarantine whose objects are gone. */
    private fun purged(): UUID = asset(status = "DELETED", verdict = "QUARANTINED", reason = "EXTREME_VIOLENCE", purged = true)

    private fun asset(
        status: String,
        verdict: String,
        attachedTo: UUID? = null,
        reason: String? = null,
        purged: Boolean = false,
    ): UUID {
        val id = UUID.randomUUID()
        val random = UUID.randomUUID()
        val ready = status == "READY"
        val now = Timestamp.from(clock.instant())
        jdbc.update(
            """
            INSERT INTO media_assets (id, owner_user_id, kind, storage_key, content_type, size_bytes, checksum, status, variants,
                                      width, height, attached_entry_id, tier, created_at, ready_at, expires_at, deleted_at, purged_at,
                                      moderation_verdict, moderated_at, moderation_scores)
            VALUES (?, ?, 'IMAGE', ?, 'image/jpeg', 1000, 'Zm9vYmFyZm9vYmFyZm9vYmFyZm9vYmFyZm9vYmFyMDA=', ?, ?::jsonb,
                    ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            """.trimIndent(),
            id,
            UUID.randomUUID(),
            "in/$random",
            status,
            if (ready) {
                """[{"variant":"FULL","key":"v/$random/full.webp","width":2048,"height":1536,"bytes":180000},""" +
                    """{"variant":"DISPLAY","key":"v/$random/display.webp","width":1280,"height":960,"bytes":75000}]"""
            } else {
                "[]"
            },
            if (ready) 2048 else null,
            if (ready) 1536 else null,
            attachedTo,
            now,
            if (ready) now else null,
            Timestamp.from(clock.instant().plusSeconds(UPLOAD_URL_SECONDS)),
            if (status == "DELETED") now else null,
            if (purged) now else null,
            verdict,
            now,
            reason?.let { """{"reason":"$it"}""" },
        )
        return id
    }

    /** A report of [entry], filed as the route files one: the row, then `media_report_hold`. */
    private fun report(
        entry: UUID,
        reason: String,
        detail: String? = null,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO entry_reports (id, target, entry_id, bond_id, reporter_member_id, reason, detail, status, created_at) " +
                "VALUES (?, 'ENTRY', ?, ?, ?, ?, ?, 'OPEN', ?)",
            id,
            entry,
            UUID.randomUUID(),
            UUID.randomUUID(),
            reason,
            detail,
            Timestamp.from(clock.instant()),
        )
        jdbc.queryForObject("SELECT media_report_hold(?, ?, 'member-report')", Int::class.java, entry, id)
        return id
    }

    private fun count(sql: String): Int = jdbc.queryForObject(sql, Int::class.java)!!

    private fun reportStatus(id: UUID): String =
        jdbc.queryForObject("SELECT status || '|' || reason FROM entry_reports WHERE id = ?", String::class.java, id)!!

    private fun assetState(id: UUID): String =
        jdbc.queryForObject(
            "SELECT status || '|' || coalesce(moderation_verdict, '') || '|' || evidence_held FROM media_assets WHERE id = ?",
            String::class.java,
            id,
        )!!

    private fun reportActors(report: UUID): List<String> =
        jdbc
            .queryForList(
                "SELECT action || '|' || actor FROM entry_report_log WHERE report_id = ? AND action <> 'READ' ORDER BY at, id",
                String::class.java,
                report,
            ).filterNotNull()

    private fun mediaActors(media: UUID): List<String> =
        jdbc
            .queryForList(
                "SELECT action || '|' || actor FROM media_moderation_log WHERE media_id = ? ORDER BY at, id",
                String::class.java,
                media,
            ).filterNotNull()

    /** The `media` array of [report]'s `REPORT_UPHELD` row, one `keys|id|outcome` per element, in its order. */
    private fun upheldMedia(report: UUID): List<String> =
        jdbc
            .queryForList(
                "SELECT (SELECT string_agg(k, ',' ORDER BY k) FROM jsonb_object_keys(m.e) k) || '|' || (m.e ->> 'id') || '|' || " +
                    "(m.e ->> 'outcome') FROM audit_log a CROSS JOIN LATERAL jsonb_array_elements(a.metadata -> 'media') " +
                    "WITH ORDINALITY AS m(e, n) WHERE a.actor_id = ? AND a.action = 'REPORT_UPHELD' AND a.target_id = ? " +
                    "ORDER BY a.id, m.n",
                String::class.java,
                admin,
                report.toString(),
            ).filterNotNull()

    /** This test's admin's audit rows, in the order written. */
    private fun audited(): List<Row> =
        jdbc.query(
            "SELECT action, outcome, target_type, target_id, justification, metadata::text AS text, " +
                "ARRAY(SELECT k FROM jsonb_object_keys(metadata) k ORDER BY k)::text[] AS keys, " +
                "ARRAY(SELECT k || '=' || v FROM jsonb_each_text(metadata) AS e(k, v) WHERE jsonb_typeof(metadata -> k) <> 'array' " +
                "ORDER BY k)::text[] AS pairs " +
                "FROM audit_log WHERE actor_id = ? ORDER BY id",
            { rs, _ ->
                @Suppress("UNCHECKED_CAST")
                val pairs = (rs.getArray("pairs").array as Array<String>).associate { it.substringBefore('=') to it.substringAfter('=') }
                @Suppress("UNCHECKED_CAST")
                Row(
                    action = rs.getString("action"),
                    outcome = rs.getString("outcome"),
                    targetType = rs.getString("target_type"),
                    targetId = rs.getString("target_id"),
                    justification = rs.getString("justification"),
                    metadataText = rs.getString("text"),
                    keys = (rs.getArray("keys").array as Array<String>).toSet(),
                    metadata = pairs,
                )
            },
            admin,
        )

    @Suppress("LongParameterList") // One audit row's columns, and its metadata read two ways.
    private class Row(
        val action: String,
        val outcome: String,
        val targetType: String?,
        val targetId: String?,
        val justification: String?,
        val metadataText: String,
        val keys: Set<String>,
        /** The scalar values, as text. */
        val metadata: Map<String, String>,
    ) {
        /** The row's columns and its metadata keys exactly; a decision's note only in `justification`, never in the metadata. */
        @Suppress("LongParameterList") // The row's five facts, and the justification when it is not the note.
        fun matches(
            action: AuditAction,
            outcome: AuditOutcome,
            targetType: String,
            target: UUID,
            keys: Set<String>,
            justification: String? = NOTE,
        ) {
            this.action shouldBe action.name
            this.outcome shouldBe outcome.name
            this.targetType shouldBe targetType
            this.targetId shouldBe target.toString()
            this.justification shouldBe justification
            this.keys shouldBe keys
            metadataText shouldNotContain NOTE
            metadataText shouldNotContain DETAIL
        }
    }

    private fun run(
        connection: Connection,
        sql: String,
        vararg args: Any,
    ) {
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, arg -> statement.setObject(index + 1, arg) }
            statement.executeQuery().close()
        }
    }

    /** Until another backend of this database waits for a lock in a statement like [statement], or the deadline fails the test. */
    private fun awaitLockWait(statement: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEADLINE_SECONDS)
        while (System.nanoTime() < deadline) {
            val waiting =
                jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' " +
                        "AND datname = current_database() AND pid <> pg_backend_pid() AND query LIKE ?",
                    Int::class.java,
                    statement,
                )!!
            if (waiting > 0) return
            Thread.onSpinWait()
        }
        error("no statement like '$statement' was seen waiting for a lock within $DEADLINE_SECONDS s")
    }

    /**
     * Wraps both ports so a test can see which of their methods were called, in order, and run a
     * statement inside one before it goes on. Runs after the transaction proxies are made, so the
     * real ports keep their `MANDATORY` boundary.
     */
    @TestConfiguration
    @Suppress("UtilityClassWithPublicConstructor") // Spring instantiates a configuration; an object it cannot.
    class Recording {
        companion object {
            @Bean
            @JvmStatic
            fun recordingPorts(): BeanPostProcessor = Recorder
        }
    }

    internal object Recorder : BeanPostProcessor {
        val calls: MutableList<String> = CopyOnWriteArrayList()
        val before: MutableMap<String, () -> Unit> = java.util.concurrent.ConcurrentHashMap()

        fun reset() {
            calls.clear()
            before.clear()
        }

        override fun postProcessAfterInitialization(
            bean: Any,
            beanName: String,
        ): Any =
            when (bean) {
                is ReportModeration -> wrap(ReportModeration::class.java, "reports", bean)
                is QuarantineModeration -> wrap(QuarantineModeration::class.java, "media", bean)
                else -> bean
            }

        private fun <T : Any> wrap(
            type: Class<T>,
            prefix: String,
            target: T,
        ): T =
            type.cast(
                Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
                    val name = "$prefix.${method.name}"
                    if (method.declaringClass == type) calls += name
                    before[name]?.invoke()
                    try {
                        method.invoke(target, *(args ?: emptyArray()))
                    } catch (thrown: InvocationTargetException) {
                        throw thrown.targetException
                    }
                },
            )
    }

    private companion object {
        val NOTE = "looked at it, decided ${UUID.randomUUID()}"
        const val DETAIL = "a detail only the moderator reads 5d2b"
        const val FAILING_NOTE = "the audit write fails here"
        const val UPLOAD_URL_SECONDS = 15 * 60L
        const val DEADLINE_SECONDS = 10L

        /** The view's share lock on the reports holding the asset (spec §7.4 step 2). */
        const val VIEW_REPORT_LOCK = "%FROM entry\\_reports WHERE id = ANY%FOR SHARE%"

        /** The dismiss's stale check, the report's row `FOR UPDATE` (spec §8). */
        const val DISMISS_REPORT_LOCK = "%FROM entry\\_reports WHERE id = %FOR UPDATE%"
    }
}
