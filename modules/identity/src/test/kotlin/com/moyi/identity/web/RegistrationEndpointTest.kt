package com.moyi.identity.web

import com.moyi.common.testing.PostgresIntegrationTest
import com.moyi.identity.domain.Password
import com.moyi.identity.domain.PasswordHash
import com.moyi.identity.domain.PasswordHashAlgorithm
import com.moyi.identity.domain.PasswordHasher
import com.moyi.identity.infra.IdentityTestApplication
import com.moyi.identity.infra.security.Argon2Properties
import com.moyi.identity.infra.security.Argon2idPasswordHasher
import com.moyi.identity.infra.security.TestBreachCorpus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * `POST /api/v1/auth/register`, against a real Postgres and the real Spring
 * context — the same beans `app` wires, not a slice with the interesting
 * parts stubbed out.
 *
 * The context is named explicitly rather than found by searching upwards:
 * the test application lives in `identity.infra`, which is not an ancestor
 * package of `identity.web`, and it cannot move to `com.moyi.identity`
 * without breaking the `com.moyi.<module>.<layer>` convention that every
 * architecture rule is keyed on.
 */
@SpringBootTest(classes = [IdentityTestApplication::class])
@AutoConfigureMockMvc
@Import(RegistrationEndpointTest.RecordingHasherConfiguration::class)
internal class RegistrationEndpointTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val hasher: RecordingPasswordHasher,
    @Autowired dataSource: DataSource,
) : PostgresIntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)

    @AfterEach
    fun clear() {
        jdbc.execute("TRUNCATE TABLE consent_records, credentials, users CASCADE")
        hasher.calls.set(0)
    }

    @Test
    fun `a valid registration creates the user, the credentials and the consent rows`() {
        val response = register()

        response.status shouldBe 201
        // Empty, and that is the security control rather than an omission —
        // see the note on RegistrationController and ADR-0015.
        response.contentAsString shouldBe ""

        jdbc.queryForObject(
            "SELECT status FROM users WHERE email = 'ada@example.com'",
            String::class.java,
            // FR-002: the account exists and can do almost nothing until the
            // address is confirmed.
        ) shouldBe "PENDING_VERIFICATION"

        jdbc.queryForObject(
            "SELECT algorithm FROM credentials c JOIN users u ON u.id = c.user_id WHERE u.email = 'ada@example.com'",
            String::class.java,
        ) shouldBe PasswordHashAlgorithm.ARGON2ID.name

        // FR-011: three documents, one moment, one version.
        jdbc.queryForList(
            "SELECT document FROM consent_records ORDER BY document",
            String::class.java,
        ) shouldBe listOf("AGE_18", "PRIVACY", "TERMS")
    }

    @Test
    fun `the stored hash is an argon2id digest and not the password`() {
        register()

        val stored =
            jdbc.queryForObject(
                "SELECT password_hash FROM credentials",
                String::class.java,
            )!!

        stored shouldContain "\$argon2id\$"
        // NFR-046's parameters, encoded into the digest by the encoder itself.
        // Reading them back from the stored value is the only check that
        // cannot be satisfied by a configuration that was never applied.
        stored shouldContain "m=19456,t=2,p=1"
        stored shouldNotContain PASSWORD
    }

    @Test
    fun `registering an address that already exists is indistinguishable from success`() {
        // ADR-0015. If this returned 409 — the obvious implementation, and
        // what the course does — the endpoint would answer "does this person
        // have an account here" for anyone who asks.
        val first = register()
        val second = register(email = "ADA@Example.COM")

        first.status shouldBe second.status
        first.contentAsString shouldBe second.contentAsString
        second.status shouldBe 201

        // The casing differs and citext still caught it: one account.
        jdbc.queryForObject("SELECT count(*) FROM users", Int::class.java) shouldBe 1
        jdbc.queryForObject("SELECT count(*) FROM consent_records", Int::class.java) shouldBe 3
    }

    @Test
    fun `the duplicate path still pays for a hash`() {
        // The other half of not being an oracle. An identical body means
        // nothing if the duplicate path returns in 2 ms and the real one
        // takes 150 — the difference is measurable from anywhere on the
        // internet (T-18). Asserting the call count rather than the elapsed
        // time, because a timing assertion that is not flaky is a timing
        // assertion that is not measuring anything.
        register()
        hasher.calls.set(0)

        register(email = "ADA@Example.COM")

        hasher.calls.get() shouldBe 1
    }

    @Test
    fun `a short password is rejected per field, and never echoed`() {
        val response = register(password = "short")

        response.status shouldBe 422
        response.contentAsString shouldContain "\"code\":\"VALIDATION_FAILED\""
        response.contentAsString shouldContain "\"field\":\"password\""
        response.contentAsString shouldContain "\"code\":\"VALID_PASSWORD\""
        // One violation, not two: the breach constraint declines to report a
        // password the shape constraint is already rejecting, so a single
        // mistake produces a single error.
        response.contentAsString shouldNotContain "NOT_BREACHED"
        response.contentAsString shouldNotContain "short"
        jdbc.queryForObject("SELECT count(*) FROM users", Int::class.java) shouldBe 0
    }

    @Test
    fun `an address the annotation accepts but the domain rejects is 422, not 500`() {
        // `@Email` deliberately does not require a dot, so `a@b` satisfied it
        // and then failed `Email`'s own shape check inside the service — a 500
        // on a request a user could plausibly send, after paying for an Argon2
        // hash. Confirmed against the running application before the fix.
        val response = register(email = "a@b")

        response.status shouldBe 422
        response.contentAsString shouldContain "\"field\":\"email\""
        jdbc.queryForObject("SELECT count(*) FROM users", Int::class.java) shouldBe 0
    }

    @Test
    fun `a password whose length changes under NFKC is 422 in both directions`() {
        // The edge's `@Size` measured the raw string and the domain measured
        // the normalised one, so the two disagreed wherever normalisation
        // changed the length — and the domain won, with a 500.
        //
        // Shrinking: "e" plus a combining acute is two characters that compose
        // into one, so eight characters become seven.
        //
        // These were twelve and eleven until ADR-0012's floor moved to 8. The
        // numbers are not decoration — the case only tests anything while it
        // straddles the minimum, and leaving them at twelve would have left a
        // test that passes by accepting the password, which is the opposite of
        // what it asserts.
        val composesShorter = "abcdef" + "e\u0301"
        composesShorter.length shouldBe 8

        // Growing: the "fi" ligature is one character that decomposes into
        // two, so 128 characters become 256.
        val composesLonger = "\uFB01".repeat(128)
        composesLonger.length shouldBe 128

        register(password = composesShorter).let { response ->
            response.status shouldBe 422
            response.contentAsString shouldContain "\"field\":\"password\""
        }
        register(email = "b@example.com", password = composesLonger).status shouldBe 422
        jdbc.queryForObject("SELECT count(*) FROM users", Int::class.java) shouldBe 0
    }

    @Test
    fun `a password already known to attackers is rejected, per field`() {
        // The half ADR-0012 makes the 8-character floor conditional on. It is
        // asserted here, through the real context and a real Postgres, rather
        // than only against the adapter — because the thing that can silently
        // break is the wiring, not the filter: a corpus bean nobody injects,
        // or a validator that catches its own exception, both leave every
        // other test in this file green.
        val response = register(password = "password")

        response.status shouldBe 422
        response.contentAsString shouldContain "\"code\":\"VALIDATION_FAILED\""
        response.contentAsString shouldContain "\"field\":\"password\""
        // Its own code, not the shape constraint's. A client cannot be asked to
        // tell "too short" from "already breached" by matching English, and
        // `states.md` §1c gives the breach case its own copy on this screen —
        // so it has to be recognisable without reading the sentence.
        response.contentAsString shouldContain "\"code\":\"NOT_BREACHED\""
        // "matches a list", not "has appeared in a breach": a Bloom filter is
        // one-sided, so roughly one rejection in a thousand is of a password
        // that was never breached, and the sentence has to be true then too.
        response.contentAsString shouldContain "matches a list"
        jdbc.queryForObject("SELECT count(*) FROM users", Int::class.java) shouldBe 0
    }

    @Test
    fun `the corpus check does not reject an ordinary password`() {
        // The other side of the one-sided error. A control that rejects
        // everything also passes every test that only checks rejections.
        register(password = TestBreachCorpus.SAFE).status shouldBe 201
    }

    @Test
    fun `eight characters is now accepted, which is the point of the corpus`() {
        // ADR-0012's two halves in one assertion: this password is exactly at
        // the new floor and would have been a 422 before the corpus landed.
        register(password = "tr0mbone").status shouldBe 201
        jdbc.queryForObject("SELECT count(*) FROM users", Int::class.java) shouldBe 1
    }

    @Test
    fun `a surrounding space in an address is a typing accident, not a rejection`() {
        // The trim existed before this fix but ran *after* validation, so
        // `@Email` rejected the request before it could ever apply — the code
        // comment described a behaviour the code did not have.
        val response = register(email = "  ada@example.com  ")

        response.status shouldBe 201
        jdbc.queryForObject("SELECT email FROM users", String::class.java) shouldBe "ada@example.com"
    }

    @Test
    fun `the byte limit is reported against the password field`() {
        // It used to be reported against `passwordWithinByteLimit`, the name
        // of the helper property that implemented it — an implementation
        // detail leaking into the API contract, and not a field any client
        // could map to an input.
        val fourBytesEach = "\uD83D\uDE00".repeat(Password.MAX_OCTETS / 4 + 1)

        val response = register(password = fourBytesEach)

        response.status shouldBe 422
        response.contentAsString shouldContain "\"field\":\"password\""
        response.contentAsString shouldNotContain "passwordWithinByteLimit"
    }

    @Test
    fun `an unconfirmed 18+ box is a validation failure, not a silent skip`() {
        // FR-011 makes the confirmation a condition of the account existing.
        // Accepting the registration and omitting the AGE_18 row would leave
        // the attestation missing from the export (FR-009) with nothing
        // recording that it was never given.
        val response = register(over18 = false)

        response.status shouldBe 422
        response.contentAsString shouldContain "\"field\":\"over18\""
        jdbc.queryForObject("SELECT count(*) FROM users", Int::class.java) shouldBe 0
    }

    @Test
    fun `a malformed body is 400, not 422`() {
        val response =
            mockMvc
                .post(PATH) {
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"email": """
                }.andReturn()
                .response

        response.status shouldBe 400
        response.contentAsString shouldContain "\"code\":\"MALFORMED_REQUEST\""
    }

    @Test
    fun `locale falls back to the default when the client omits it`() {
        val response =
            mockMvc
                .post(PATH) {
                    contentType = MediaType.APPLICATION_JSON
                    content =
                        """
                        {
                          "email": "ada@example.com",
                          "password": "$PASSWORD",
                          "displayName": "Ada",
                          "acceptedTermsVersion": "2026-09-01",
                          "over18": true
                        }
                        """.trimIndent()
                }.andReturn()
                .response

        response.status shouldBe 201
        // The Kotlin default on the data class, which only applies because
        // the Jackson 3 Kotlin module is present — without it this field
        // arrives as null in a non-null property.
        jdbc.queryForObject("SELECT locale FROM users", String::class.java) shouldBe "en"
    }

    private fun register(
        email: String = "ada@example.com",
        password: String = PASSWORD,
        over18: Boolean = true,
    ): MockHttpServletResponse =
        mockMvc
            .post(PATH) {
                contentType = MediaType.APPLICATION_JSON
                content =
                    """
                    {
                      "email": "$email",
                      "password": "$password",
                      "displayName": "Ada",
                      "locale": "en",
                      "acceptedTermsVersion": "2026-09-01",
                      "over18": $over18
                    }
                    """.trimIndent()
            }.andReturn()
            .response

    /**
     * Wraps the real hasher rather than replacing it: the test needs to know
     * how often hashing happened *and* still exercise the genuine Argon2id
     * path, because a stub would also stub away the ~150 ms that is the
     * entire point of the duplicate-path assertion.
     */
    @TestConfiguration
    class RecordingHasherConfiguration {
        @Bean
        @Primary
        fun recordingPasswordHasher(properties: Argon2Properties): RecordingPasswordHasher =
            RecordingPasswordHasher(Argon2idPasswordHasher(properties))
    }

    private companion object {
        /**
         * The real corpus is a 17 MB release asset the build downloads and
         * pins by digest; a test that waited for it would be testing the
         * network. This points the context at a dozen-entry fixture built from
         * a readable list, using the same `BloomFilter` the service loads — so
         * the format is exercised rather than stood in for.
         *
         * Every context-booting test needs this, because there is deliberately
         * no flag that switches the corpus off:
         * `BloomFilterBreachedPasswordCorpus` refuses to start without one
         * (ADR-0016), and a test context that could boot without it would not
         * be the context we deploy.
         */
        @JvmStatic
        @DynamicPropertySource
        fun breachCorpus(registry: DynamicPropertyRegistry) = TestBreachCorpus.register(registry)

        const val PATH = "/api/v1/auth/register"
        const val PASSWORD = "correct horse battery"
    }
}

internal class RecordingPasswordHasher(
    private val delegate: PasswordHasher,
) : PasswordHasher {
    val calls = AtomicInteger()

    override val algorithm: PasswordHashAlgorithm get() = delegate.algorithm

    override fun hash(password: Password): PasswordHash {
        calls.incrementAndGet()
        return delegate.hash(password)
    }

    override fun matches(
        raw: String,
        hash: PasswordHash,
    ): Boolean = delegate.matches(raw, hash)

    override fun matchesDummy(raw: String): Boolean = delegate.matchesDummy(raw)
}
