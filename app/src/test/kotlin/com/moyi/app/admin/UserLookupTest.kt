package com.moyi.app.admin

import com.moyi.common.testing.MutableClock
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.FactorGrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource
import kotlin.random.Random

/**
 * FR-111 (spec §9): the lookup is role-gated through the hierarchy, shows metadata only,
 * and is audited in the transaction that returns it. Admin and user state is touched only
 * through HTTP and SQL (Ruling B).
 */
class UserLookupTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val clock: MutableClock,
    @Autowired private val meters: io.micrometer.core.instrument.MeterRegistry,
    @Autowired dataSource: DataSource,
) : AdminEnabledTest() {
    private val jdbc = JdbcTemplate(dataSource)

    @Test
    fun `support, moderator and admin all reach the page through the hierarchy`() {
        listOf("SUPPORT", "MODERATOR", "ADMIN").forEach { role ->
            withClue(role) { lookup(admin(role), "nobody@example.com").status shouldBe 200 }
        }
    }

    @Test
    fun `a two-factor session without any role is refused with the admin error page`() {
        val before = auditCount("USER_LOOKED_UP")
        val response = lookup(admin("SUPPORT", roles = emptyList()), "anything")
        response.status shouldBe 403
        response.contentAsString shouldContain "Error 403"
        auditCount("USER_LOOKED_UP") shouldBe before
    }

    @Test
    fun `a password-only session goes to the code page and nothing is audited`() {
        val before = auditCount("USER_LOOKED_UP")
        val response = lookup(admin("SUPPORT", factors = listOf(FactorGrantedAuthority.PASSWORD_AUTHORITY), roles = emptyList()), "x")
        response.status shouldBe 302
        response.redirectedUrl!! shouldContain "/admin/login/totp"
        auditCount("USER_LOOKED_UP") shouldBe before
    }

    @Test
    fun `an id and a padded mixed-case email both show the user, and the audit names the user id`() {
        val user = user()
        val session = admin("SUPPORT")
        val byId = lookup(session, user.id.toString())
        byId.status shouldBe 200
        byId.contentAsString shouldContain user.email
        byId.contentAsString shouldContain "Ada Test"

        val byEmail = lookup(session, "  ${user.email.uppercase()} ")
        byEmail.contentAsString shouldContain user.id.toString()

        val rows = audit(user.id.toString())
        rows.size shouldBe 2
        rows.forEach { it["outcome"] shouldBe "SUCCEEDED" }
        rows.forEach { (it["metadata"] as String) shouldNotContain user.email.substringBefore('@') }
    }

    @Test
    fun `the page holds no entry text and not the partner's email, and links the partner's lookup`() {
        val user = user()
        val partner = user()
        val marker = "UNIQUE-ENTRY-MARKER-${UUID.randomUUID()}"
        bond(user.id, partner.id)
        entry(user.id, marker)

        val page = lookup(admin("SUPPORT"), user.id.toString()).contentAsString
        page shouldNotContain marker
        page shouldNotContain partner.email
        page shouldContain "/admin/users?q=${partner.id}"
    }

    @Test
    fun `a query that matches nothing says so and records a refusal that does not hold the query`() {
        val query = "not-a-uuid-or-email"
        val before = refusals()
        val page = lookup(admin("SUPPORT"), query)
        page.contentAsString shouldContain "No account matches."
        val row =
            jdbc
                .queryForList(
                    "SELECT target_type, target_id, metadata::text AS metadata FROM audit_log " +
                        "WHERE outcome = 'REFUSED' AND action = 'USER_LOOKED_UP' ORDER BY occurred_at DESC LIMIT 1",
                ).single()
        refusals() shouldBe before + 1
        row["target_type"] shouldBe null
        row["target_id"] shouldBe null
        (row["metadata"] as String) shouldNotContain query
    }

    @Test
    fun `a UUID-looking query that is not a canonical UUID is looked up as an email, and finds nothing`() {
        // UUID.fromString takes "1-1-1-1-1" as 00000001-0001-0001-0001-000000000001; plan
        // Review Focus 5 says such a string is an email query, which matches nobody.
        val session = admin("SUPPORT")
        val page = lookup(session, "1-1-1-1-1")
        page.status shouldBe 200
        page.contentAsString shouldContain "No account matches."
        jdbc.queryForObject(
            "SELECT metadata->>'by' FROM audit_log WHERE action = 'USER_LOOKED_UP' AND outcome = 'REFUSED' AND actor_id = ?",
            String::class.java,
            actorOf(session),
        ) shouldBe "email"
    }

    @Test
    fun `every lookup writes exactly one audit row`() {
        val user = user()
        val session = admin("SUPPORT")
        val before = auditCount("USER_LOOKED_UP")
        val found = counter("found")
        val missed = counter("not_found")
        lookup(session, user.id.toString())
        lookup(session, "nobody-${UUID.randomUUID()}@example.com")
        lookup(session, "")
        auditCount("USER_LOOKED_UP") shouldBe before + 2
        counter("found") shouldBe found + 1
        counter("not_found") shouldBe missed + 1
    }

    @Test
    fun `when the audit insert fails the page is the error page and holds no user data`() {
        val user = user()
        val session = admin("SUPPORT")
        jdbc.execute(
            "CREATE FUNCTION fail_audit_insert() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN RAISE EXCEPTION 'audit down'; END \$\$",
        )
        jdbc.execute("CREATE TRIGGER fail_audit BEFORE INSERT ON audit_log FOR EACH ROW EXECUTE FUNCTION fail_audit_insert()")
        try {
            val page = lookup(session, user.id.toString())
            page.status shouldBe 500
            page.contentAsString shouldContain "Error 500"
            page.contentAsString shouldNotContain user.email
            page.contentAsString shouldNotContain "audit down"
        } finally {
            jdbc.execute("DROP TRIGGER fail_audit ON audit_log")
            jdbc.execute("DROP FUNCTION fail_audit_insert()")
        }
        audit(user.id.toString()).size shouldBe 0
    }

    // ---- helpers ----

    private class Account(
        val id: UUID,
        val email: String,
    )

    /**
     * A lookup as the form sends it (A2 spec §9.3): a POST with the session's CSRF token and a
     * disclosed purpose, which takes no note. The token is the session's, read off the first page
     * that session is shown a form on: the home page with both factors, the code page with only the
     * password, so that what the test sees is the role and factor checks, never a missing token.
     */
    private fun lookup(
        session: MockHttpSession,
        q: String,
    ): MockHttpServletResponse {
        val token =
            listOf("/admin", "/admin/login/totp").firstNotNullOfOrNull { path ->
                val page =
                    mockMvc
                        .get(path) {
                            with { it.apply { remoteAddr = AdminConsoleDriver.ALLOWED } }
                            this.session = session
                        }.andReturn()
                        .response
                runCatching { AdminConsoleDriver.csrfFrom(page) }.getOrNull()
            }
        checkNotNull(token) { "no page gave this session a CSRF token" }
        return mockMvc
            .post("/admin/users") {
                with { it.apply { remoteAddr = AdminConsoleDriver.ALLOWED } }
                this.session = session
                contentType = MediaType.APPLICATION_FORM_URLENCODED
                param("_csrf", token)
                param("q", q)
                param("purpose", "MEMBER_REQUEST")
            }.andReturn()
            .response
    }

    private fun actorOf(session: MockHttpSession): UUID =
        (session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) as SecurityContextImpl)
            .authentication!!
            .principal as UUID

    private fun counter(outcome: String): Double = meters.counter("moyi.admin.lookup", "outcome", outcome).count()

    private fun auditCount(action: String): Int =
        jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = ?", Int::class.java, action)!!

    private fun refusals(): Int =
        jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'USER_LOOKED_UP' AND outcome = 'REFUSED'", Int::class.java)!!

    private fun audit(targetId: String): List<Map<String, Any?>> =
        jdbc.queryForList(
            "SELECT outcome, metadata::text AS metadata FROM audit_log WHERE action = 'USER_LOOKED_UP' AND target_id = ?",
            targetId,
        )

    /** A signed-in admin session as the TOTP step builds it, for a freshly invited and enrolled account. */
    private fun admin(
        role: String,
        factors: List<String> = listOf(FactorGrantedAuthority.PASSWORD_AUTHORITY, "FACTOR_TOTP"),
        roles: List<String> = listOf(role),
    ): MockHttpSession {
        val id = UUID.randomUUID()
        val email = "ops-${id.toString().take(8)}@example.com"
        jdbc.queryForObject("SELECT admin_invite(?::citext, ?)", String::class.java, email, role)
        val adminId = jdbc.queryForObject("SELECT id FROM admin_accounts WHERE email = ?::citext", UUID::class.java, email)!!
        jdbc.update("UPDATE admin_accounts SET status = 'ACTIVE', password_hash = 'x' WHERE id = ?", adminId)
        val changed =
            jdbc.queryForObject(
                "SELECT credentials_changed_at FROM admin_accounts WHERE id = ?",
                OffsetDateTime::class.java,
                adminId,
            )!!
        val authorities: List<GrantedAuthority> =
            factors.map { FactorGrantedAuthority.fromAuthority(it) } + roles.map { SimpleGrantedAuthority("ROLE_$it") }
        return MockHttpSession().apply {
            val authentication = UsernamePasswordAuthenticationToken.authenticated(adminId, null, authorities)
            setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, SecurityContextImpl(authentication))
            setAttribute("admin.credentialsChangedAt", changed.toInstant())
            setAttribute("admin.absoluteDeadline", clock.instant().plus(Duration.ofHours(1)))
        }
    }

    private val members = mutableMapOf<UUID, UUID>()

    private fun bond(
        owner: UUID,
        other: UUID,
    ): UUID {
        val bondId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO bonds (id, name, anchor_timezone, status, created_by, created_at) " +
                "VALUES (?, 'Pair', 'Africa/Lagos', 'ACTIVE', ?, now())",
            bondId,
            owner,
        )
        listOf(owner to "OWNER", other to "MEMBER").forEach { (user, role) ->
            val member = UUID.randomUUID()
            members[user] = member
            jdbc.update(
                "INSERT INTO bond_members (id, bond_id, user_id, role, joined_at, reminder_timezone) " +
                    "VALUES (?, ?, ?, ?, now(), 'Africa/Lagos')",
                member,
                bondId,
                user,
                role,
            )
        }
        bondIds[owner] = bondId
        return bondId
    }

    private val bondIds = mutableMapOf<UUID, UUID>()

    private fun entry(
        author: UUID,
        text: String,
    ) {
        val bondId = bondIds.getValue(author)
        val day = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO bond_days (id, bond_id, date, status, anchor_timezone, starts_at, ends_at, created_at) " +
                "VALUES (?, ?, current_date, 'OPEN', 'Africa/Lagos', now(), now() + interval '1 day', now())",
            day,
            bondId,
        )
        jdbc.update(
            "INSERT INTO entries (id, bond_day_id, bond_id, author_member_id, text, status, created_at, intended_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, 'SUBMITTED', now(), now(), now())",
            UUID.randomUUID(),
            day,
            bondId,
            members.getValue(author),
            text,
        )
    }

    private fun user(): Account {
        val email = "ada-${UUID.randomUUID().toString().take(8)}@example.com"
        val address = "198.51.100.${Random.nextInt(1, 255)}"
        val response =
            mockMvc
                .post("/api/v1/auth/register") {
                    with { it.apply { remoteAddr = address } }
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        """{"email":"$email","password":"eleven~amber~lanterns~at~dusk","displayName":"Ada Test","locale":"en",""" +
                        """"acceptedTermsVersion":"2026-09-01","over18":true}"""
                }.andReturn()
                .response
        response.status shouldBe 201
        val id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?::citext", UUID::class.java, email)!!
        return Account(id, email)
    }
}
