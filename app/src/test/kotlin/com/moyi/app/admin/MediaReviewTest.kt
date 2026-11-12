package com.moyi.app.admin

import com.moyi.app.admin.ModerationRig.Companion.NOTE
import com.moyi.common.testing.MutableClock
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockHttpSession
import org.springframework.test.web.servlet.MockMvc
import java.util.UUID
import javax.sql.DataSource

/**
 * The review copy through HTTP (A2 spec §5.3, §7.4, §9.2, §10.3; plan Task 6): the bytes with
 * the served variant's content type and the spec's headers; every refusal the same `404` bytes;
 * the page that shows the copy names no URL outside `/admin`; and the origin a link names is
 * recorded only when it is true.
 */
class MediaReviewTest(
    @Autowired mockMvc: MockMvc,
    @Autowired private val clock: MutableClock,
    @Autowired dataSource: DataSource,
) : AdminEnabledTest() {
    private val rig = ModerationRig(mockMvc, JdbcTemplate(dataSource), clock)

    @BeforeEach
    fun reset() = ReviewObjectStore.reset()

    @Test
    fun `a held photo is served with its bytes, the variant's type and the spec's headers`() {
        val photo = rig.quarantined()
        val session = rig.session()

        val copy = review(session, photo, "via" to "QUARANTINE")

        copy.status shouldBe 200
        copy.contentAsByteArray shouldBe ReviewObjectStore.BYTES
        copy.contentType shouldBe "image/webp"
        copy.getHeaders(HttpHeaders.CACHE_CONTROL) shouldBe listOf("no-store")
        copy.getHeaders("X-Content-Type-Options") shouldBe listOf("nosniff")
        copy.getHeaders(HttpHeaders.CONTENT_DISPOSITION) shouldBe listOf("inline")
        copy.getHeaders("Cross-Origin-Resource-Policy") shouldBe listOf("same-origin")
        copy.getHeader(HttpHeaders.LOCATION) shouldBe null
        ReviewObjectStore.opened shouldContainExactly listOf("v/${rig.keyOf(photo)}/display.webp")
        rig.audited(rig.adminOf(session)).map { it.head } shouldContainExactly listOf("MEDIA_VIEWED|SUCCEEDED|$photo")
    }

    @Test
    fun `a photo uploaded as HEIC is served as the webp variant it is, not as what the phone declared`() {
        val photo = rig.quarantined(contentType = "image/heic")

        val copy = review(rig.session(), photo, "via" to "QUARANTINE")

        copy.status shouldBe 200
        copy.contentType shouldBe "image/webp"
    }

    @Test
    fun `every refusal is the same 404 bytes as a path with no page, and each look is audited with its reason`() {
        val session = rig.session()
        val noPage = rig.get(session, "/admin/no-such-page")
        val blocked = rig.quarantined()
        rig.post(session, "/admin/quarantine/$blocked/block", mapOf("note" to NOTE))
        val (minorEntry, minorPhoto) = rig.entryWithPhoto()
        val minor = rig.report(minorEntry, "MINOR_SEXUAL_CONTENT")
        val (consentEntry, consentPhoto) = rig.entryWithPhoto()
        val consent = rig.report(consentEntry, "NON_CONSENSUAL")
        val gone = rig.quarantined().also { ReviewObjectStore.missing += rig.keyOf(it) }
        val released = rig.entryWithPhoto().second
        val unknown = UUID.randomUUID()
        val admin = rig.adminOf(session)
        val before = rig.audited(admin).size

        val refusals =
            mapOf(
                "unknown" to review(session, unknown, "via" to "QUARANTINE"),
                "not a uuid" to rig.get(session, "/admin/media/nope/review", "via" to "QUARANTINE"),
                "no origin" to review(session, rig.quarantined()),
                "an origin not a UUID" to review(session, minorPhoto, "via" to "REPORT", "report" to "nope"),
                "blocked" to review(session, blocked, "via" to "QUARANTINE"),
                "priority" to review(session, minorPhoto, "via" to "REPORT", "report" to minor.toString()),
                "consent" to review(session, consentPhoto, "via" to "REPORT", "report" to consent.toString()),
                "missing" to review(session, gone, "via" to "QUARANTINE"),
                "never held" to review(session, released, "via" to "QUARANTINE"),
            )

        refusals.forEach { (case, response) ->
            withClue(case) {
                response.status shouldBe 404
                response.contentAsString shouldBe noPage.contentAsString
                response.contentType shouldStartWith "text/html"
            }
        }
        rig.audited(admin).drop(before).map {
            it.head + "|" + Regex(""""reason": "([a-z_]+)"""").find(it.metadata)?.groupValues?.get(1)
        } shouldContainExactly
            // A value that is not a UUID, or a link with no readable origin, is refused before the console: no row.
            listOf(
                "MEDIA_VIEWED|REFUSED|$unknown|not_held",
                "MEDIA_VIEWED|REFUSED|$blocked|blocked",
                "MEDIA_VIEWED|REFUSED|$minorPhoto|priority_report",
                "MEDIA_VIEWED|REFUSED|$consentPhoto|consent_report",
                "MEDIA_VIEWED|REFUSED|$gone|missing",
                "MEDIA_VIEWED|REFUSED|$released|not_held",
            )
        ReviewObjectStore.opened shouldContainExactly listOf("v/${rig.keyOf(gone)}/display.webp")
    }

    @Test
    fun `a link is believed only from a page that offers it, and a forged origin is never written`() {
        val (entry, photo) = rig.entryWithPhoto()
        val report = rig.report(entry, "VIOLENCE_OR_SELF_HARM")
        val other = rig.report(rig.entryWithPhoto().first, "VIOLENCE_OR_SELF_HARM")
        val session = rig.session()
        val admin = rig.adminOf(session)
        val noPage = rig.get(session, "/admin/no-such-page")

        val honest = review(session, photo, "via" to "REPORT", "report" to report.toString())
        val forged =
            mapOf(
                "another report's page" to review(session, photo, "via" to "REPORT", "report" to other.toString()),
                // The quarantine page offers no View while an open report holds the asset.
                "the quarantine page" to review(session, photo, "via" to "QUARANTINE"),
            )

        honest.status shouldBe 200
        forged.forEach { (case, response) ->
            withClue(case) {
                response.status shouldBe 404
                response.contentAsString shouldBe noPage.contentAsString
                response.contentType shouldStartWith "text/html"
            }
        }
        val (served, refusedReport, refusedQuarantine) = rig.audited(admin)
        served.head shouldBe "MEDIA_VIEWED|SUCCEEDED|$photo"
        served.metadata shouldContain """"via": "REPORT""""
        served.metadata shouldContain """"reportId": "$report""""
        listOf(refusedReport, refusedQuarantine).forEach { refused ->
            withClue(refused.metadata) {
                refused.head shouldBe "MEDIA_VIEWED|REFUSED|$photo"
                refused.metadata shouldContain """"reason": "wrong_origin""""
                refused.metadata shouldContain """"reportDriven": true"""
                refused.metadata shouldNotContain "via"
                refused.metadata shouldNotContain "reportId"
                refused.metadata shouldNotContain other.toString()
            }
        }
        // Only the honest look opened the store.
        ReviewObjectStore.opened shouldContainExactly listOf("v/${rig.keyOf(photo)}/display.webp")
    }

    @Test
    fun `the pages that show a copy name no URL outside the console, and the report's View link carries its own id`() {
        val (entry, photo) = rig.entryWithPhoto()
        val report = rig.report(entry, "VIOLENCE_OR_SELF_HARM")
        val held = rig.quarantined()
        val session = rig.session()

        val reportPage = rig.get(session, "/admin/reports/$report", "view" to photo.toString()).contentAsString
        val heldPage = rig.get(session, "/admin/quarantine/$held", "view" to "1").contentAsString

        reportPage shouldContain """<img src="/admin/media/$photo/review?via=REPORT&amp;report=$report""""
        heldPage shouldContain """<img src="/admin/media/$held/review?via=QUARANTINE""""
        listOf(reportPage, heldPage).forEach { page ->
            page shouldNotContain "http:"
            page shouldNotContain "https:"
            page shouldNotContain rig.keyOf(photo)
            page shouldNotContain rig.keyOf(held)
            ModerationRig.links(page).shouldNotBeEmpty().forEach { withClue(it) { it shouldStartWith "/admin" } }
        }
        // Rendering a page opens nothing: the look is the browser's request for the copy, and it is audited there.
        ReviewObjectStore.opened.shouldBeEmpty()
    }

    private fun review(
        session: MockHttpSession,
        media: UUID,
        vararg params: Pair<String, String>,
    ): MockHttpServletResponse = rig.get(session, "/admin/media/$media/review", *params)
}
