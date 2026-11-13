package com.moyi.gratitude.infra

import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.common.testing.MutableClock
import com.moyi.gratitude.api.ReportModeration
import com.moyi.gratitude.api.ReportedEntryRead
import com.moyi.gratitude.api.ReportedEntryText
import com.moyi.gratitude.api.ReportedWords
import com.moyi.gratitude.api.WordsRefusal
import com.moyi.gratitude.web.SubmitEntryBondLockTest
import com.moyi.identity.api.UserDirectory
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * `gratitude.api.ReportedEntryText` (A5 spec §4, §5 step 4, §7; plan Task 1): the one door to a
 * reported entry's words, from reports filed through the real routes. Every outcome, the
 * `WORDS_READ` row written for each read that reached the entry and for no refusal, and the
 * page's question ([ReportedEntryText.whyNot]) answering as the read does.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
@Import(SubmitEntryBondLockTest.TimeConfiguration::class)
@Suppress("LongParameterList") // What Spring hands the test.
internal class ReportedEntryTextTest(
    @Autowired mockMvc: MockMvc,
    @Autowired tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired dataSource: DataSource,
    @Autowired private val clock: MutableClock,
    @Autowired private val port: ReportedEntryText,
    @Autowired private val moderation: ReportModeration,
    @Autowired private val transactions: TransactionTemplate,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val jdbc = JdbcTemplate(dataSource)
    private val rig = ReportedEntryRig(mockMvc, tokens, jdbc)

    private lateinit var ada: UUID
    private lateinit var bea: UUID
    private lateinit var bond: UUID

    @BeforeEach
    fun setUp() {
        clock.set(NOW.minusSeconds(86_400))
        ada = users.verified("Ada")
        bea = users.verified("Bea")
        bond = rig.pair(ada, bea)
        clock.set(NOW)
    }

    @AfterEach
    fun clear() {
        rig.clear()
        users.clear()
    }

    @Test
    fun `a revealed entry reported for harassment is shown with its date, and the read is logged once`() {
        val entry = rig.revealed(bond, bea, ada, """{"text":"$MARKER"}""")
        val report = rig.reportEntry(ada, entry, "HARASSMENT")

        val read = tx { port.read(report, "HARASSMENT", ACTOR) }

        val shown = read.shouldBeInstanceOf<ReportedEntryRead.Shown>()
        shown.entryId shouldBe entry
        shown.date shouldBe rig.dateOf(entry)
        shown.words.value shouldBe MARKER
        rig.log(report) shouldContainExactly listOf("WORDS_READ|$ACTOR|null")
        tx { port.whyNot(report) } shouldBe null
    }

    @Test
    fun `an entry its author erased is erased, and the read is still logged`() {
        val entry = rig.revealed(bond, bea, ada, """{"text":"$MARKER"}""")
        val report = rig.reportEntry(ada, entry, "VIOLENCE_OR_SELF_HARM")
        rig.delete(bea, entry)

        tx { port.read(report, "VIOLENCE_OR_SELF_HARM", ACTOR) } shouldBe ReportedEntryRead.Erased(entry)

        rig.log(report) shouldContainExactly listOf("WORDS_READ|$ACTOR|null")
    }

    /** Spec §5 step 4 and §15 question 1: the outbox erases a withdrawn author's words later; the admin must not read them meanwhile. */
    @Test
    fun `a withdrawn author's entry is erased even while its words are still in the row`() {
        val entry = rig.revealed(bond, bea, ada, """{"text":"$MARKER"}""")
        val report = rig.reportEntry(ada, entry, "HARASSMENT")
        rig.blockAndWithdraw(bea, bond)

        tx { port.read(report, "HARASSMENT", ACTOR) } shouldBe ReportedEntryRead.Erased(entry)

        jdbc.queryForObject("SELECT text FROM entries WHERE id = ?", String::class.java, entry) shouldBe MARKER
        rig.log(report) shouldContainExactly listOf("WORDS_READ|$ACTOR|null")
    }

    /**
     * The domain forbids a live entry with no text (`Entry.init`), and no route can make one; the
     * row is written by hand. The store refuses to show it, writes nothing, and says why without a word.
     */
    @Test
    fun `a live entry with no text is a broken invariant - it throws and logs nothing`() {
        val entry = rig.revealed(bond, bea, ada, """{"text":"$MARKER"}""")
        val report = rig.reportEntry(ada, entry, "OTHER")
        jdbc.update("UPDATE entries SET text = NULL WHERE id = ?", entry) shouldBe 1

        val thrown = assertThrows<IllegalStateException> { tx { port.read(report, "OTHER", ACTOR) } }

        thrown.message shouldBe "entry $entry is live and has no text"
        rig.log(report).shouldBeEmpty()
    }

    @Test
    fun `a report decided first is not open, and nothing is logged`() {
        val entry = rig.revealed(bond, bea, ada, """{"text":"$MARKER"}""")
        val report = rig.reportEntry(ada, entry, "HARASSMENT")
        jdbc.queryForList("SELECT entry_report_dismiss(?, 'op', 'decided')", report)
        val before = rig.log(report)

        tx { port.read(report, "HARASSMENT", ACTOR) } shouldBe ReportedEntryRead.NotOpen(entry)

        rig.log(report) shouldBe before
        noWordsRead()
    }

    @Test
    fun `a reason changed since the page was loaded is stale, and nothing is logged`() {
        val entry = rig.revealed(bond, bea, ada, """{"text":"$MARKER"}""")
        val report = rig.reportEntry(ada, entry, "HARASSMENT")

        tx { port.read(report, "OTHER", ACTOR) } shouldBe ReportedEntryRead.Stale(entry)

        noWordsRead()
    }

    @Test
    fun `a member report names no entry and is not about words, and nothing is logged`() {
        val report = rig.reportMember(ada, bond, "HARASSMENT")

        tx { port.read(report, "HARASSMENT", ACTOR) } shouldBe ReportedEntryRead.NotAboutWords(null, "HARASSMENT", imageReason = false)

        noWordsRead()
        tx { port.whyNot(report) } shouldBe WordsRefusal.MEMBER_REPORT
    }

    @Test
    fun `an image reason is not about words, and nothing is logged`() {
        val entry = rig.revealed(bond, bea, ada, """{"text":"$MARKER"}""")
        val report = rig.reportEntry(ada, entry, "NON_CONSENSUAL")

        tx { port.read(report, "NON_CONSENSUAL", ACTOR) } shouldBe
            ReportedEntryRead.NotAboutWords(entry, "NON_CONSENSUAL", imageReason = true)

        noWordsRead()
        tx { port.whyNot(report) } shouldBe WordsRefusal.IMAGE_REASON
    }

    @Test
    fun `an unknown report is not open with no entry, and nothing is logged`() {
        val unknown = UUID.randomUUID()

        tx { port.read(unknown, "HARASSMENT", ACTOR) } shouldBe ReportedEntryRead.NotOpen(null)

        noWordsRead()
        tx { port.whyNot(unknown) } shouldBe WordsRefusal.NOT_OPEN
    }

    /** Spec §4: an image-reason report whose detail is about words is reclassified first, then read. */
    @Test
    fun `an image reason reclassified to one about words is then shown`() {
        val entry = rig.revealed(bond, bea, ada, """{"text":"$MARKER"}""")
        val report = rig.reportEntry(ada, entry, "NON_CONSENSUAL")
        tx { port.read(report, "NON_CONSENSUAL", ACTOR) }.shouldBeInstanceOf<ReportedEntryRead.NotAboutWords>()

        tx { moderation.reclassify(report, "NON_CONSENSUAL", "HARASSMENT", ACTOR, "the detail is about words") }
        val read = tx { port.read(report, "HARASSMENT", ACTOR) }

        read.shouldBeInstanceOf<ReportedEntryRead.Shown>().words.value shouldBe MARKER
        // The function stamps the database's now(), the port the application's clock: compared as a set.
        rig.log(report) shouldContainExactlyInAnyOrder
            listOf("RECLASSIFIED|$ACTOR|NON_CONSENSUAL -> HARASSMENT: the detail is about words", "WORDS_READ|$ACTOR|null")
    }

    /** The page asks the rule the read applies (brief: "never a copy"): for every reason, target and status, the two agree. */
    @Test
    fun `whyNot agrees with read for every reason, target and status`() {
        val entry = rig.revealed(bond, bea, ada, """{"text":"$MARKER"}""")
        val cases =
            REASONS.flatMap { r ->
                listOf("ENTRY", "MEMBER").flatMap { t -> listOf("OPEN", "DISMISSED", "UPHELD").map { Triple(r, t, it) } }
            }
        for ((reason, target, status) in cases) {
            val report = if (target == "ENTRY") rig.reportEntry(ada, entry, reason) else rig.reportMember(ada, bond, reason)
            when (status) {
                "DISMISSED" -> jdbc.queryForList("SELECT entry_report_dismiss(?, 'op', 'decided')", report)
                "UPHELD" -> jdbc.queryForList("SELECT * FROM entry_report_uphold(?, 'BLOCK', 'op', 'decided')", report)
            }
            val asked = tx { port.whyNot(report) }
            val read = tx { port.read(report, reason, ACTOR) }
            withClue("$reason $target $status: whyNot $asked, read $read") { asked shouldBe refusalOf(read) }
        }
    }

    /** What [ReportedEntryText.whyNot] must answer for a report whose read answered [read]. */
    private fun refusalOf(read: ReportedEntryRead): WordsRefusal? =
        when (read) {
            is ReportedEntryRead.Shown, is ReportedEntryRead.Erased -> null
            is ReportedEntryRead.NotOpen -> WordsRefusal.NOT_OPEN
            is ReportedEntryRead.NotAboutWords -> if (read.imageReason) WordsRefusal.IMAGE_REASON else WordsRefusal.MEMBER_REPORT
            is ReportedEntryRead.Stale -> error("read with the report's own reason cannot be stale")
        }

    @Test
    fun `whyNot writes nothing`() {
        val entry = rig.revealed(bond, bea, ada, """{"text":"$MARKER"}""")
        val report = rig.reportEntry(ada, entry, "HARASSMENT")

        tx { port.whyNot(report) } shouldBe null

        rig.log(report).shouldBeEmpty()
    }

    @Test
    fun `the words' string never has the words, and two of the same words are not equal`() {
        val words = ReportedWords(MARKER)

        words.toString() shouldNotContain MARKER
        words.toString() shouldBe "ReportedWords(redacted)"
        (words == ReportedWords(MARKER)) shouldBe false
        val entry = rig.revealed(bond, bea, ada, """{"text":"$MARKER"}""")
        val read = tx { port.read(rig.reportEntry(ada, entry, "OTHER"), "OTHER", ACTOR) }
        read.toString() shouldNotContain MARKER
    }

    @Test
    fun `outside a transaction neither method runs`() {
        val entry = rig.revealed(bond, bea, ada, """{"text":"$MARKER"}""")
        val report = rig.reportEntry(ada, entry, "HARASSMENT")

        assertThrows<IllegalTransactionStateException> { port.read(report, "HARASSMENT", ACTOR) }
        assertThrows<IllegalTransactionStateException> { port.whyNot(report) }
        noWordsRead()
    }

    private fun noWordsRead() {
        jdbc.queryForObject("SELECT count(*) FROM entry_report_log WHERE action = 'WORDS_READ'", Int::class.java) shouldBe 0
    }

    /** [block] in a transaction of its own, committed: its answer, null or not. */
    private fun <T> tx(block: () -> T): T {
        var answer: Result<T>? = null
        transactions.executeWithoutResult { answer = Result.success(block()) }
        return answer!!.getOrThrow()
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-15T10:00:00Z")
        const val ACTOR = "admin:0f0f0f0f-0000-4000-8000-0000000000ad"
        const val MARKER = "MARKER-a5-words-7f3c"
        val REASONS = listOf("NON_CONSENSUAL", "MINOR_SEXUAL_CONTENT", "VIOLENCE_OR_SELF_HARM", "HARASSMENT", "OTHER")
    }
}
