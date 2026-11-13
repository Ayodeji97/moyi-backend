package com.moyi.bond.web

import com.moyi.bond.domain.InviteCode
import com.moyi.bond.infra.BondTestApplication
import com.moyi.bond.infra.FakeAccountStanding
import com.moyi.bond.infra.FakeUserDirectory
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.identity.api.AccountStanding
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * A3 at the HTTP edge: `paused` on the one bond shape, in its `ETag`, and the invite routes'
 * one answer for a bond that is paused (FR-024: it must look like a revoked code, or it names
 * the suspension to a stranger).
 */
@SpringBootTest(classes = [BondTestApplication::class])
@AutoConfigureMockMvc
@Suppress("TooManyFunctions") // One test per surface, and the helpers they share.
internal class PausedBondEndpointTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired standing: AccountStanding,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val spans = standing as FakeAccountStanding
    private val jdbc = JdbcTemplate(dataSource)

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE blocks, bond_invites, bond_members, bonds CASCADE")
        users.clear()
        spans.reset()
    }

    @Test
    fun `get one and get all carry paused, true then false`() {
        val (bond, ada, bea) = pairedBond()
        spans.suspend(bea, NOW)

        get(ada, "/api/v1/bonds/$bond").contentAsString shouldContain "\"paused\":true"
        get(bea, "/api/v1/bonds").contentAsString shouldContain "\"paused\":true"

        spans.lift(bea, NOW.plusSeconds(60))

        get(ada, "/api/v1/bonds/$bond").contentAsString shouldContain "\"paused\":false"
        get(bea, "/api/v1/bonds").contentAsString shouldContain "\"paused\":false"
    }

    @Test
    fun `the ETag differs across a pause, and the old tag is answered 200 and not 304`() {
        val (bond, ada, bea) = pairedBond()
        val before = get(ada, "/api/v1/bonds/$bond")
        val oldTag = before.getHeader(HttpHeaders.ETAG)!!
        get(ada, "/api/v1/bonds/$bond", oldTag).status shouldBe 304

        spans.suspend(bea, NOW)

        val during = get(ada, "/api/v1/bonds/$bond", oldTag)
        during.status shouldBe 200
        during.contentAsString shouldContain "\"paused\":true"
        val pausedTag = during.getHeader(HttpHeaders.ETAG)!!
        pausedTag shouldNotBe oldTag

        spans.lift(bea, NOW.plusSeconds(60))

        val after = get(ada, "/api/v1/bonds/$bond", pausedTag)
        after.status shouldBe 200
        after.contentAsString shouldContain "\"paused\":false"
    }

    @Test
    fun `resolving and accepting an invite of a bond whose creator is suspended is a revoked invite's answer`() {
        val creator = users.verified("Ada")
        val code = codeOf(createBond(creator))
        val revoked = codeOf(createBond(users.verified("Bea")))
        jdbc.update("UPDATE bond_invites SET revoked_at = now() WHERE code = ?", revoked)
        val joiner = users.verified("Joiner")
        val reference = get(joiner, "/api/v1/invites/$revoked")
        val referenceAccept = accept(joiner, revoked)
        reference.status shouldBe 404

        spans.suspend(creator, NOW)

        get(joiner, "/api/v1/invites/$code").let {
            it.status shouldBe 404
            it.contentAsString.withoutInstance() shouldBe reference.contentAsString.withoutInstance()
        }
        accept(joiner, code).let {
            it.status shouldBe 404
            it.contentAsString.withoutInstance() shouldBe referenceAccept.contentAsString.withoutInstance()
        }

        spans.lift(creator, NOW.plusSeconds(60))

        get(joiner, "/api/v1/invites/$code").status shouldBe 200
        accept(joiner, code).status shouldBe 200
    }

    @Test
    fun `leaving and blocking a paused bond still work`() {
        val (left, ada, bea) = pairedBond()
        spans.suspend(bea, NOW)
        post(bea, "/api/v1/bonds/$left/leave", """{"withdrawEntries":false}""").status shouldBe 204

        val (blocked, cal, dee) = pairedBond("Cal" to "Dee")
        spans.suspend(dee, NOW)
        post(cal, "/api/v1/bonds/$blocked/block", """{"withdrawEntries":false}""").status shouldBe 204
        ada shouldNotBe cal
    }

    // ---- helpers ------------------------------------------------------------

    private fun bearer(userId: UUID) = "Bearer ${tokens.issue(userId).token}"

    private fun get(
        userId: UUID,
        path: String,
        ifNoneMatch: String? = null,
    ): MockHttpServletResponse =
        mockMvc
            .get(path) {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                if (ifNoneMatch != null) header(HttpHeaders.IF_NONE_MATCH, ifNoneMatch)
            }.andReturn()
            .response

    private fun post(
        userId: UUID,
        path: String,
        body: String? = null,
    ): MockHttpServletResponse =
        mockMvc
            .post(path) {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                if (body != null) {
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                }
            }.andReturn()
            .response

    private fun accept(
        userId: UUID,
        code: String,
    ) = post(userId, "/api/v1/invites/$code/accept")

    private fun createBond(userId: UUID): MockHttpServletResponse =
        mockMvc
            .post("/api/v1/bonds") {
                header(HttpHeaders.AUTHORIZATION, bearer(userId))
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Us","type":"COUPLE","anchorTimezone":"Africa/Lagos"}"""
            }.andReturn()
            .response

    private fun pairedBond(names: Pair<String, String> = "Ada" to "Bea"): Triple<String, UUID, UUID> {
        val creator = users.verified(names.first)
        val joiner = users.verified(names.second)
        val created = createBond(creator)
        created.status shouldBe 201
        accept(joiner, codeOf(created)).status shouldBe 200
        return Triple(Regex(""""id":"([^"]+)"""").find(created.contentAsString)!!.groupValues[1], creator, joiner)
    }

    private fun codeOf(response: MockHttpServletResponse): String =
        Regex(""""code":"([A-Z0-9]{${InviteCode.LENGTH}})"""").find(response.contentAsString)!!.groupValues[1]

    private fun String.withoutInstance(): String = replace(Regex(""""instance":"[^"]*""""), "\"instance\":\"-\"")

    private companion object {
        val NOW: Instant = Instant.parse("2026-11-13T12:00:00Z")
    }
}
