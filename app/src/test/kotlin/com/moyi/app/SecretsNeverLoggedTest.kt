package com.moyi.app

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.AppenderBase
import com.moyi.common.events.EventConsumer
import com.moyi.common.events.EventPublisher
import com.moyi.common.events.OutboxDispatcher
import com.moyi.common.events.OutboxEvent
import com.moyi.common.events.ReceivedEvent
import com.moyi.common.events.StartFrom
import com.moyi.common.testing.IntegrationTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.LoggerFactory
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.logging.LogLevel
import org.springframework.boot.logging.LoggingSystem
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import javax.sql.DataSource

/**
 * CLAUDE.md: entry text, passwords, tokens and invite codes never reach a
 * log. This holds the half of that rule that no `toString` can keep: the
 * libraries underneath print bound and fetched values, entity fields and raw
 * request bytes of their own accord once their level is lowered, and
 * `LOGGING_LEVEL_ROOT=DEBUG` is what gets set during an incident.
 *
 * **The configuration that ships is what is tested.** The context loads the
 * real `application.yml` (the `test` profile adds no logging level), and the
 * levels are raised the way an operator would raise them: by property, before
 * the context starts, for the root logger, for every parent of a pinned logger
 * and for Spring Boot's `sql` and `web` groups. Only the pins in
 * `application.yml` stand between those properties and the words.
 *
 * **Over a real socket**, not MockMvc: one of the loggers is Tomcat's, which
 * prints the bytes it reads, and MockMvc has no Tomcat.
 *
 * **What is scanned for:** the text of three entries (as submitted, as
 * edited, and the partner's), a password, two access tokens and two refresh
 * tokens, across register, sign-in, pairing, submit, read, edit, the reveal,
 * delete, an ending that withdraws, the withdrawal's erasure by the
 * dispatcher, a failing transactional consumer, and a refresh.
 *
 * **An invite code is held to less**, because it cannot be held to more: see
 * [PRINTS_THE_REQUEST_PATH]. **Not covered at all:** the email verification
 * and password reset tokens. The `test` and `local` profiles print those on
 * purpose (`LoggingEmailSender`), and the accounts here are verified in the
 * database.
 *
 * The console is held at INFO (`logging.threshold.console`) so that a build
 * does not print a start-up at TRACE; the appender this test attaches to the
 * root logger has no threshold and sees every event. What reaches the console
 * by any other road is scanned too.
 *
 * **What a pin cannot do:** it holds against a level set on any ancestor, and
 * gives way to a level set on that exact logger. This test does not and
 * cannot guard against `LOGGING_LEVEL_ORG_HIBERNATE_ORM_JDBC_BIND=TRACE`, nor
 * against `spring.mvc.log-request-details=true`, which was run once here and
 * put the bearer token in the dispatcher servlet's TRACE line.
 *
 * Each of the original six pins was removed in turn and this test failed each time,
 * naming the logger that line holds. The transactional interceptor regression
 * was also seen failing before its pin was added.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "logging.level.root=TRACE",
        "logging.level.org=TRACE",
        "logging.level.org.hibernate=TRACE",
        "logging.level.org.hibernate.orm=TRACE",
        "logging.level.org.hibernate.orm.jdbc=TRACE",
        "logging.level.org.hibernate.orm.resource=TRACE",
        "logging.level.org.apache=TRACE",
        "logging.level.org.apache.coyote=TRACE",
        "logging.level.org.apache.coyote.http11=TRACE",
        "logging.level.org.apache.tomcat=TRACE",
        "logging.level.org.springframework=TRACE",
        "logging.level.sql=TRACE",
        "logging.level.web=TRACE",
        "logging.threshold.console=INFO",
        // Each account registers from its own address: registration is three
        // an hour per address, and other classes in this JVM share the Redis.
        "moyi.security.client-address.trusted-proxies=127.0.0.0/8,::1/128",
    ],
)
@Import(SecretsNeverLoggedTest.ConsumerConfiguration::class)
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Suppress("LongParameterList") // The real dispatcher and its proxied failing consumer are both exercised.
class SecretsNeverLoggedTest(
    @LocalServerPort private val port: Int,
    @Autowired private val dispatcher: OutboxDispatcher,
    @Autowired private val logging: LoggingSystem,
    @Autowired dataSource: DataSource,
    @Autowired private val publisher: EventPublisher,
    @Autowired private val transactions: TransactionTemplate,
    @Autowired private val failingConsumer: FailingConsumer,
) : IntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val http = HttpClient.newHttpClient()
    private val json = JsonMapper.builder().build()
    private val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
    private val scan = SecretScan()

    @BeforeEach
    fun attach() {
        scan.start()
        root.addAppender(scan)
    }

    /**
     * The logging configuration belongs to the JVM, not to this context, and a
     * context cached by another class does not set it again: left at TRACE,
     * every later test in this JVM would run at TRACE.
     */
    @AfterEach
    fun detach() {
        root.detachAppender(scan)
        scan.stop()
        RAISED.forEach { logging.setLogLevel(it, null) }
        logging.setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.INFO)
    }

    @Test
    @Suppress("LongMethod")
    fun `with every level raised to TRACE no entry, password, token or invite code is in any log line`(output: CapturedOutput) {
        root.level shouldBe Level.TRACE
        val run = UUID.randomUUID().toString().take(RUN_ID_LENGTH)

        scan.watch("password", PASSWORD)
        val ada = account("ada-$run", "203.0.113.201")
        val bea = account("bea-$run", "203.0.113.202")

        val created = post("/bonds", ada, """{"name":"Us","type":"COUPLE","anchorTimezone":"Africa/Lagos"}""")
        created.status shouldBe 201
        val bond = created.json["id"].asString()
        val code = created.json["invite"]["code"].asString()
        scan.watch("invite code", code, wholeWord = true, exceptFrom = PRINTS_THE_REQUEST_PATH)
        get("/invites/$code", bea).status shouldBe 200
        post("/invites/$code/accept", bea, null).status shouldBe 200

        // Submit, read, edit, read.
        val first = post("/bonds/$bond/entries", ada, """{"text":"$ADA_TEXT"}""", idempotent = true)
        first.status shouldBe 201
        val adaEntry = first.json["id"].asString()
        get("/bonds/$bond/today", ada).body.contains(ADA_TEXT) shouldBe true
        get("/bonds/$bond/today", bea).status shouldBe 200
        send("PATCH", "/entries/$adaEntry", ada, """{"text":"$ADA_EDITED"}""").status shouldBe 200
        get("/bonds/$bond/today", ada).body.contains(ADA_EDITED) shouldBe true

        // The second submission reveals: each now reads the other's words.
        val second = post("/bonds/$bond/entries", bea, """{"text":"$BEA_TEXT"}""", idempotent = true)
        second.status shouldBe 201
        val beaEntry = second.json["id"].asString()
        get("/bonds/$bond/today", bea).body.contains(ADA_EDITED) shouldBe true
        get("/bonds/$bond/today", ada).body.contains(BEA_TEXT) shouldBe true
        get("/bonds/$bond/streak", ada).status shouldBe 200

        // Delete, then an ending that withdraws, then the erasure itself.
        send("DELETE", "/entries/$adaEntry", ada, null).status shouldBe 204
        get("/bonds/$bond/today", bea).status shouldBe 200
        post("/bonds/$bond/block", bea, """{"withdrawEntries": true}""").status shouldBe 204
        get("/bonds/$bond/today", ada).body.contains(BEA_TEXT) shouldBe false
        jdbc.queryForObject("SELECT text IS NOT NULL FROM entries WHERE id = ?::uuid", Boolean::class.java, beaEntry) shouldBe true
        dispatcher.dispatchDue(Instant.now(), DISPATCH_BUDGET).delivered shouldBe 1
        jdbc.queryForObject("SELECT text IS NULL FROM entries WHERE id = ?::uuid", Boolean::class.java, beaEntry) shouldBe true
        get("/bonds/$bond/today", bea).status shouldBe 200

        failingConsumerDoesNotLogItsException()

        // A refresh sends the refresh token back; the pair it returns is watched too.
        val refreshed = send("POST", "/auth/refresh", null, """{"refreshToken":"${ada.refresh}"}""")
        refreshed.status shouldBe 200
        scan.watch("access token", refreshed.json["accessToken"].asString())
        scan.watch("refresh token", refreshed.json["refreshToken"].asString())

        // The capture is known to see: a line this test writes at TRACE is
        // found, and the libraries whose pins are under test did log below INFO.
        LoggerFactory.getLogger("com.moyi.app.SecretsNeverLoggedTest.control").trace("a line carrying {}", CONTROL)
        root.detachAppender(scan)

        withClue("the capture did not see a TRACE line written through an unpinned logger") {
            scan.sawControl shouldBe true
        }
        RAISED_EXPECTED_FROM.forEach { prefix ->
            withClue("nothing below INFO from $prefix: the levels were not raised, and this test proved nothing") {
                scan.raisedLoggers.any { it.startsWith(prefix) } shouldBe true
            }
        }
        withClue("these loggers printed a secret (logger, level, which secret); ${scan.events} events were read") {
            scan.offenders().shouldBeEmpty()
        }
        withClue("a secret reached the console by a road that is not a logger") {
            scan
                .watched()
                .filter { (_, watched) -> watched.exceptFrom.isEmpty() && watched.pattern.containsMatchIn(output.all) }
                .map { it.first }
                .shouldBeEmpty()
        }
    }

    /** The catch in the dispatcher runs after this consumer's transactional interceptor. */
    private fun failingConsumerDoesNotLogItsException() {
        AopUtils.isAopProxy(failingConsumer) shouldBe true
        scan.watch("handler exception", HANDLER_SECRET)
        val aggregate = UUID.randomUUID()
        transactions.executeWithoutResult {
            publisher.publish(OutboxEvent("SecretProbe", aggregate, FailingConsumer.EVENT, emptyMap(), Instant.now()))
        }
        dispatcher.dispatchDue(Instant.now(), DISPATCH_BUDGET).failed shouldBe 1
        // The handler reached the database, but its write was rolled back with the delivery.
        jdbc.queryForObject(
            "SELECT aggregate_type FROM outbox_events WHERE aggregate_id = ?",
            String::class.java,
            aggregate,
        ) shouldBe "SecretProbe"
        jdbc.queryForObject(
            """
            SELECT d.last_error FROM outbox_deliveries d JOIN outbox_events e ON e.id = d.event_id
            WHERE e.aggregate_id = ? AND d.consumer_id = ? AND d.processed_at IS NULL AND d.attempts = 1
            """.trimIndent(),
            String::class.java,
            aggregate,
            FailingConsumer.ID,
        ) shouldBe IllegalStateException::class.java.name
    }

    /** Same transactional proxy as WithdrawEntries, with a synthetic secret in its failure. */
    open class FailingConsumer(
        private val jdbc: JdbcTemplate,
    ) : EventConsumer {
        override val id = ID
        override val eventTypes = setOf(EVENT)
        override val startFrom = StartFrom.NOW

        @Transactional(propagation = Propagation.MANDATORY)
        override fun handle(event: ReceivedEvent) {
            jdbc.update("UPDATE outbox_events SET aggregate_type = 'MustRollBack' WHERE id = ?", event.id) shouldBe 1
            throw IllegalStateException(HANDLER_SECRET)
        }

        companion object {
            const val ID = "test.secret-probe"
            const val EVENT = "SecretProbeFailed"
        }
    }

    @TestConfiguration
    class ConsumerConfiguration {
        @Bean
        fun failingConsumer(jdbc: JdbcTemplate): FailingConsumer = FailingConsumer(jdbc)
    }

    /** Registers, verifies in the database, signs in. The tokens it is given are watched from then on. */
    private fun account(
        name: String,
        address: String,
    ): Session {
        val email = "$name@example.com"
        val registered =
            send(
                "POST",
                "/auth/register",
                null,
                """{"email":"$email","password":"$PASSWORD","displayName":"${name.take(3)}","locale":"en",""" +
                    """"acceptedTermsVersion":"2026-09-01","over18":true}""",
                forwardedFor = address,
            )
        registered.status shouldBe 201
        jdbc.update("UPDATE users SET email_verified_at = now() WHERE email = ?::citext", email) shouldBe 1
        val login = send("POST", "/auth/login", null, """{"email":"$email","password":"$PASSWORD"}""", forwardedFor = address)
        login.status shouldBe 200
        val session = Session(login.json["accessToken"].asString(), login.json["refreshToken"].asString())
        scan.watch("access token", session.access)
        scan.watch("refresh token", session.refresh)
        return session
    }

    private class Session(
        val access: String,
        val refresh: String,
    )

    private class Answer(
        val status: Int,
        val body: String,
        mapper: JsonMapper,
    ) {
        val json: JsonNode by lazy { mapper.readTree(body) }
    }

    private fun get(
        path: String,
        session: Session,
    ) = send("GET", path, session, null)

    private fun post(
        path: String,
        session: Session,
        body: String?,
        idempotent: Boolean = false,
    ) = send("POST", path, session, body, idempotent = idempotent)

    @Suppress("LongParameterList")
    private fun send(
        method: String,
        path: String,
        session: Session?,
        body: String?,
        idempotent: Boolean = false,
        forwardedFor: String? = null,
    ): Answer {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port/api/v1$path"))
        request.method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
        if (body != null) request.header("Content-Type", "application/json")
        if (session != null) request.header("Authorization", "Bearer ${session.access}")
        if (idempotent) request.header("Idempotency-Key", UUID.randomUUID().toString())
        if (forwardedFor != null) request.header("X-Forwarded-For", forwardedFor)
        val response = http.send(request.build(), HttpResponse.BodyHandlers.ofString())
        return Answer(response.statusCode(), response.body(), json)
    }

    /**
     * Keeps every event that reaches the root logger and searches them all at
     * the end, because a token is only known once the response that carries it
     * has arrived, and the lines written while it was being made count too.
     * What it reports is the logger's name, the level and the secret's label,
     * never the line: a failure of this test is printed by the build.
     */
    private class SecretScan : AppenderBase<ILoggingEvent>() {
        private val secrets = ConcurrentHashMap<String, Watched>()
        private val lines = ConcurrentLinkedQueue<Line>()
        val raisedLoggers: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val events: Int get() = lines.size
        val sawControl: Boolean get() = lines.any { CONTROL in it.text }

        /**
         * [wholeWord] is for a secret short enough to be spelt by chance inside
         * a longer run of letters and digits. A logger whose name starts with
         * one of [exceptFrom] may print this secret; nothing else may.
         */
        fun watch(
            label: String,
            secret: String,
            wholeWord: Boolean = false,
            exceptFrom: List<String> = emptyList(),
        ) {
            val quoted = Regex.escape(secret)
            val pattern = Regex(if (wholeWord) "(?<![A-Za-z0-9])$quoted(?![A-Za-z0-9])" else quoted)
            secrets["$label ${secrets.size}"] = Watched(pattern, exceptFrom)
        }

        fun watched(): List<Pair<String, Watched>> = secrets.toList() + ENTRY_MARKERS

        fun offenders(): List<String> =
            watched()
                .flatMap { (label, watched) ->
                    lines
                        .filter { line -> watched.exceptFrom.none(line.logger::startsWith) && watched.pattern.containsMatchIn(line.text) }
                        .map { "${it.logger} ${it.level}: ${label.substringBeforeLast(' ')}" }
                }.distinct()
                .sorted()

        override fun append(event: ILoggingEvent) {
            if (!event.level.isGreaterOrEqual(Level.INFO)) raisedLoggers += event.loggerName
            val text =
                buildString {
                    append(event.formattedMessage)
                    event.throwableProxy?.let { append('\n').append(ThrowableProxyUtil.asString(it)) }
                    event.mdcPropertyMap?.values?.forEach { append('\n').append(it) }
                    event.keyValuePairs?.forEach { append('\n').append(it.value) }
                }
            lines += Line(event.loggerName, event.level, text)
        }

        private class Line(
            val logger: String,
            val level: Level,
            val text: String,
        )
    }

    private class Watched(
        val pattern: Regex,
        val exceptFrom: List<String> = emptyList(),
    )

    private companion object {
        // `~` is in no UUID, no SQL the application writes and no base64url
        // token, so a match is the entry and nothing else.
        const val ADA_TEXT = "Ada~wrote~this first"
        const val ADA_EDITED = "Ada~edited~it to this"
        const val BEA_TEXT = "Bea~wrote~hers after"
        const val PASSWORD = "seven~green~kettles~at~dawn"
        const val HANDLER_SECRET = "private~handler~exception"
        const val CONTROL = "control~marker~"
        const val RUN_ID_LENGTH = 8
        const val DISPATCH_BUDGET = 10

        val ENTRY_MARKERS =
            listOf("entry text 0" to Watched(Regex("~wrote~")), "entry text 1" to Watched(Regex("~edited~")))

        /**
         * An invite code travels in the path (`/invites/{code}/accept`), so a
         * logger that prints a request line prints the code: Spring Security's
         * filter chain at DEBUG, the dispatcher servlet and the handler's
         * arguments at TRACE, Tomcat's authenticator valve at DEBUG. Those are
         * the lines an operator lowers the level to read, and they are not
         * pinned. **So an invite code does reach the log at DEBUG**, by these
         * loggers; what is held here is that it reaches it by no other, the
         * ones that print stored and bound values in particular.
         */
        val PRINTS_THE_REQUEST_PATH =
            listOf("org.springframework.security.web.", "org.springframework.web.", "org.apache.catalina.")

        /** Every logger name a property above raised, so that it can be put back. */
        val RAISED =
            listOf(
                "org",
                "org.hibernate",
                "org.hibernate.orm",
                "org.hibernate.orm.jdbc",
                "org.hibernate.orm.resource",
                "org.apache",
                "org.apache.coyote",
                "org.apache.coyote.http11",
                "org.apache.tomcat",
                "org.springframework",
                "sql",
                "web",
            )

        /** Libraries that must be seen logging below INFO for the test to mean anything. */
        val RAISED_EXPECTED_FROM = listOf("org.hibernate.", "org.springframework.", "org.apache.")
    }
}
