package com.moyi.common.web

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.http.MockHttpOutputMessage
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.util.Base64

/**
 * A [Representation] reaches the wire as its bytes or not at all.
 *
 * Found by review of 5e27c3a: the converter claimed `application/json`
 * only, so for `Accept: application/problem+json` the framework chose the
 * JSON converter instead, which wrote the holder: a `200` whose body was
 * `{"entityTag":…,"bytes…":"<base64 of the real body>"}` under an `ETag`
 * that was the digest of something else.
 */
internal class RepresentationTest {
    private val json = JsonMapper.builder().build()
    private val representations = Representations(json) { body -> "digest-of-${body.size}" }
    private val converter = RepresentationConverter()
    private val representation = representations.of(mapOf("word" to "zqthanks"))

    @Test
    fun `the converter claims a representation whatever media type it is asked about, and nothing else`() {
        val asked =
            listOf(
                null,
                MediaType.APPLICATION_JSON,
                MediaType.APPLICATION_PROBLEM_JSON,
                MediaType.parseMediaType("application/vnd.moyi+json"),
                MediaType.parseMediaType("application/json;charset=ISO-8859-1"),
                MediaType.TEXT_PLAIN,
                MediaType.APPLICATION_XML,
                MediaType.ALL,
            )

        asked.forEach { converter.canWrite(Representation::class.java, it) shouldBe true }
        asked.forEach { converter.canWrite(Map::class.java, it) shouldBe false }
        asked.forEach { converter.canRead(Representation::class.java, it) shouldBe false }
    }

    @Test
    fun `whatever media type was negotiated, what is written is the bytes, as application-json, with their length`() {
        listOf(null, MediaType.APPLICATION_PROBLEM_JSON, MediaType.parseMediaType("application/json;charset=ISO-8859-1"), MediaType.ALL)
            .forEach { negotiated ->
                val out = MockHttpOutputMessage()

                converter.write(representation, negotiated, out)

                out.bodyAsBytes.toList() shouldBe """{"word":"zqthanks"}""".toByteArray().toList()
                out.headers[HttpHeaders.CONTENT_TYPE].orEmpty() shouldContainExactly listOf("application/json")
                out.headers.contentLength shouldBe out.bodyAsBytes.size.toLong()
            }
    }

    /**
     * The belt. A route that came to return one where the converter is not
     * (a context without it, a converter list somebody reordered) must fail,
     * loudly, as a `500`: the alternative was a `200` nobody noticed.
     */
    @Test
    fun `the holder cannot be serialised by the JSON mapper - not as a value, not as a property`() {
        shouldThrow<JacksonException> { json.writeValueAsString(representation) }
        shouldThrow<JacksonException> { json.writeValueAsBytes(mapOf("held" to representation)) }
        val leaked = runCatching { json.writeValueAsString(representation) }.getOrDefault("")
        leaked shouldNotContain "entityTag"
        leaked shouldNotContain Base64.getEncoder().encodeToString("""{"word":"zqthanks"}""".toByteArray())
    }

    /**
     * Spring MVC prints the value a handler returned at TRACE (`Writing
     * [com.moyi.common.web.Representation@…]`), and this holder is a day's
     * entries as bytes. It has no `toString` of its own, which is what keeps
     * the words out of that line; this fails the day somebody gives it one,
     * or makes it a class whose generated `toString` prints a field.
     */
    @Test
    fun `the holder's toString says nothing of the body it holds`() {
        val printed = representation.toString()

        printed shouldNotContain "zqthanks"
        printed shouldNotContain "word"
        printed shouldNotContain Base64.getEncoder().encodeToString("""{"word":"zqthanks"}""".toByteArray())
    }
}
