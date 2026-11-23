package com.moyi.gratitude.web

import com.moyi.ai.api.EmbeddingPort
import com.moyi.common.core.FeatureFlag
import com.moyi.common.core.FeatureFlags
import com.moyi.common.security.AccessTokenIssuer
import com.moyi.common.testing.IntegrationTest
import com.moyi.gratitude.infra.FakeEmbeddingPort
import com.moyi.gratitude.infra.FakeUserDirectory
import com.moyi.gratitude.infra.GratitudeTestApplication
import com.moyi.gratitude.infra.SwitchableFeatureFlags
import com.moyi.gratitude.service.WithdrawalRig
import com.moyi.identity.api.UserDirectory
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import javax.sql.DataSource

/**
 * `BondResponse.semanticSearchActive` with the real wiring (ADR-0070): `bond`'s view asks the port
 * `gratitude`'s `SemanticSearchReadiness` implements, the same readiness search and "Remember when"
 * decide from. Here the `AI_EMBEDDINGS` flag is switched and the provider taken away, as an `ADMIN`
 * and a deploy would, and the bond's answer, and its `ETag`, follow; `bond`'s own test plays the
 * port with a fake, so only this one proves the real bean is the one asked.
 */
@SpringBootTest(classes = [GratitudeTestApplication::class])
@AutoConfigureMockMvc
internal class SemanticSearchActiveTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: AccessTokenIssuer,
    @Autowired directory: UserDirectory,
    @Autowired port: EmbeddingPort,
    @Autowired flags: FeatureFlags,
    @Autowired dataSource: DataSource,
) : IntegrationTest() {
    private val users = directory as FakeUserDirectory
    private val port = port as FakeEmbeddingPort
    private val flags = flags as SwitchableFeatureFlags
    private val rig = WithdrawalRig(mockMvc, tokens, JdbcTemplate(dataSource))
    private val json = JsonMapper.builder().build()

    @AfterEach
    fun clear() {
        flags.reset()
        port.clear()
        rig.clear()
        users.clear()
    }

    @Test
    fun `with the flag on, a provider and a threshold, a bond with the setting on is active`() {
        val ada = users.verified("Ada")
        val bond = rig.idOf(rig.create(ada))
        active(bond(ada, bond)) shouldBe true
    }

    @Test
    fun `switching AI_EMBEDDINGS off makes it inactive and changes the ETag, so the old tag earns no 304`() {
        val ada = users.verified("Ada")
        val bond = rig.idOf(rig.create(ada))
        val before = bond(ada, bond).getHeader(HttpHeaders.ETAG)!!
        bond(ada, bond, ifNoneMatch = before).status shouldBe 304

        flags.off(FeatureFlag.AI_EMBEDDINGS)

        val after = bond(ada, bond, ifNoneMatch = before)
        after.status shouldBe 200
        after.getHeader(HttpHeaders.ETAG) shouldNotBe before
        active(after) shouldBe false
    }

    @Test
    fun `no provider makes it inactive`() {
        val ada = users.verified("Ada")
        val bond = rig.idOf(rig.create(ada))
        port.space = null
        active(bond(ada, bond)) shouldBe false
    }

    @Test
    fun `the setting off makes it inactive, with everything else ready`() {
        val ada = users.verified("Ada")
        val bond = rig.idOf(rig.create(ada))
        rig.turnOffSemanticSearch(ada, bond)
        active(bond(ada, bond)) shouldBe false
    }

    private fun active(response: MockHttpServletResponse): Boolean =
        json.readTree(response.contentAsString)["semanticSearchActive"].asBoolean()

    private fun bond(
        user: UUID,
        bond: String,
        ifNoneMatch: String? = null,
    ): MockHttpServletResponse =
        mockMvc
            .get("/api/v1/bonds/$bond") {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.issue(user).token}")
                if (ifNoneMatch != null) header(HttpHeaders.IF_NONE_MATCH, ifNoneMatch)
            }.andReturn()
            .response
}
