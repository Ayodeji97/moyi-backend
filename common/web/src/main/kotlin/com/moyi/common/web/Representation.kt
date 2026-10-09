package com.moyi.common.web

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpInputMessage
import org.springframework.http.HttpOutputMessage
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.stereotype.Component
import tools.jackson.core.JsonGenerator
import tools.jackson.databind.DatabindException
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ValueSerializer
import tools.jackson.databind.annotation.JsonSerialize

/**
 * A response body **as the bytes that will be sent**, and the validator made
 * from exactly those bytes.
 *
 * [T] is what was serialised. Nothing reads it at run time; it is there so
 * that a handler's declared type still says what the body is, which is what
 * the generated contract is read from (`contracts` tells springdoc to look
 * through this type as it looks through `ResponseEntity`).
 *
 * **Why the bytes are kept, and the value is not.** An `ETag` that promises
 * "the body has not changed" has to be made from the body. Made from the
 * object, with the object then handed to the framework to serialise, it is a
 * digest of one serialisation attached to another, and the two agree only
 * for as long as nobody configures one of them differently. Here there is
 * one serialisation: [Representations.of] makes the bytes, [entityTag] is
 * their digest, and [RepresentationConverter] writes them and nothing else.
 *
 * Built only by [Representations], so a tag cannot be attached to bytes it
 * was not made from.
 *
 * **Not a JSON value.** [RepresentationIsNotJson] makes the mapper refuse
 * one, so the only road to the wire is the converter.
 */
@JsonSerialize(using = RepresentationIsNotJson::class)
class Representation<T : Any> internal constructor(
    internal val bytes: ByteArray,
    /**
     * A **strong** entity tag (RFC 9110 §8.8.3): [RepresentationDigest]'s
     * digest of [bytes], quoted. Strong because it is true: under one
     * secret, two responses with this tag are the same octets.
     */
    val entityTag: String,
)

/**
 * The digest an `ETag` is made of: of the bytes of a response body, **keyed
 * by a secret**, in lower-case hexadecimal.
 *
 * **Keyed, because a tag travels further than the body does.** A response
 * header is the kind of thing a proxy or an access log records. A plain
 * SHA-256 of a body is then a guess away from the body: for a day of the
 * archive every field but the entry's words is known to the other member,
 * so anyone holding the tag could hash "thank you" in its place and compare.
 * It is the hazard ADR-0031 decision 8 closed for `request_hash`, met again
 * in a header. Under a MAC a guess cannot be checked without the secret, and
 * the tag is still everything a validator has to be: the same bytes give the
 * same tag, and different bytes a different one.
 *
 * **A port, for [idempotency.RequestFingerprint]'s reason**: the secret is
 * `common:security`'s, which depends on this module and cannot be depended
 * on from it. `common:security` provides the bean
 * (`HmacRepresentationDigest`, under the personal-data secret), and a
 * context without one fails at startup naming this type.
 *
 * **What a keyed tag costs.** It validates only while the secret is
 * unchanged. After a rotation, or a restart with an `EPHEMERAL` secret
 * (local development), a client's stored tag matches nothing and it is sent
 * the whole `200` once: the safe direction, and the only cost. Every
 * instance of a deployment holds the one configured secret, so a tag made by
 * one is good at another.
 *
 * Hexadecimal and not base64, so that no word can be spelt in one.
 */
fun interface RepresentationDigest {
    fun of(body: ByteArray): String
}

/**
 * Makes a [Representation] of a response body with the application's own
 * JSON mapper, and the `200` that carries it for a **conditional read**.
 *
 * **What [revalidated] sends.** The `ETag`, and `Cache-Control: private,
 * no-cache`: no shared cache may store the response (it is one caller's
 * view, and may hold what somebody wrote to them), and the caller's own
 * cache must ask before it reuses it. Asking is cheap, because of the tag.
 * Setting `Cache-Control` here is also what stops Spring Security adding its
 * default (`no-cache, no-store, max-age=0, must-revalidate`, with `Pragma`
 * and `Expires`): it writes those only where a response has none of the
 * three, and `no-store` beside an `ETag` would forbid the very copy the tag
 * exists to revalidate.
 *
 * **No `Vary: Authorization`.** `private` already keeps the response out of
 * any cache that serves more than one person. The tag is a digest of this
 * caller's body, so a tag kept from another account cannot earn a `304`
 * unless the two bodies are the same octets, in which case nothing was
 * mistaken. And access tokens are short-lived: a client cache that honoured
 * `Vary: Authorization` would discard its copy at every refresh, which is
 * most of the times it would have been of use.
 *
 * **`If-None-Match` is not read here, or anywhere in this codebase.** Spring
 * MVC compares it for any `GET` or `HEAD` that returns a `ResponseEntity`
 * with an `ETag` (`ServletWebRequest.checkNotModified`), and answers `304`
 * with the entity's headers and no body. That is how `GET /bonds/{bondId}`
 * has answered one since slice B4, so this is the same dialect and not a
 * second one: **weak comparison, over a list of tags** (RFC 9110 §13.1.2);
 * a value that is not a tag matches nothing and the whole `200` is sent.
 * **`If-None-Match: *` is not a match on a read**, here as there: the
 * framework keeps `*` for writes, where it means "only if nothing is there
 * yet", and answers a `GET` that sends it in full. The RFC would allow a
 * `304`; a client has no use for one it cannot name a tag for, and what it
 * gets instead is never wrong.
 *
 * **The comparison happens after the handler has returned**, so a `304` has
 * passed every check the `200` would have: the guard, the read and the
 * rendering have all run, and the only thing saved is the transfer. A
 * refusal never reaches this class and so never carries a tag.
 */
@Component
class Representations(
    private val json: ObjectMapper,
    private val digest: RepresentationDigest,
) {
    /** [body] serialised, once, and tagged with the digest of those bytes. */
    fun <T : Any> of(body: T): Representation<T> {
        val bytes = json.writeValueAsBytes(body)
        return Representation(bytes, "\"${digest.of(bytes)}\"")
    }

    /** `200` with [body] serialised once, its `ETag`, and `Cache-Control: private, no-cache`. */
    fun <T : Any> revalidated(body: T): ResponseEntity<Representation<T>> {
        val representation = of(body)
        return ResponseEntity
            .ok()
            .eTag(representation.entityTag)
            // Written out, not built: the header is asserted to the character, and it is two words.
            .header(HttpHeaders.CACHE_CONTROL, PRIVATE_REVALIDATE)
            .body(representation)
    }

    private companion object {
        const val PRIVATE_REVALIDATE = "private, no-cache"
    }
}

/**
 * Writes a [Representation]: its bytes, as `application/json`, untouched.
 *
 * A bean of this type is placed ahead of the framework's own converters, so
 * a [Representation] is never handed to the JSON converter, which would
 * serialise the holder and not send what it holds.
 *
 * **It claims a [Representation] for every media type it is asked about.**
 * The framework chooses a media type from the request's `Accept` first and
 * only then asks each converter whether it writes that type. This one used
 * to say yes to `application/json` alone, so for `Accept:
 * application/problem+json`, or any `application/<x>+json`, the question
 * passed to the JSON converter, which says yes to all of those and to any
 * class: a `200` whose body was the holder, its bytes in base64, under a
 * tag that was the digest of something else (found by review, by asking).
 * What a [Representation] is does not depend on what the caller would have
 * liked, so the answer no longer does either. Which requests get one at all
 * is the mapping's business (`produces`, on the routes that return one).
 *
 * **And it names the bytes itself**: `application/json`, which is UTF-8 by
 * definition (RFC 8259 §8.1), whatever was negotiated. Left to the
 * framework, `Accept: application/json;charset=ISO-8859-1` was answered
 * with that charset in the `Content-Type` over the same UTF-8 bytes.
 *
 * `gratitude`'s `DaysConditionalTest` holds all of it over HTTP: for each
 * `Accept`, the body received must be the JSON and the tag its digest.
 *
 * It reads nothing: a [Representation] is only ever a response.
 */
@Component
class RepresentationConverter : HttpMessageConverter<Representation<*>> {
    override fun canRead(
        clazz: Class<*>,
        mediaType: MediaType?,
    ): Boolean = false

    override fun canWrite(
        clazz: Class<*>,
        mediaType: MediaType?,
    ): Boolean = Representation::class.java.isAssignableFrom(clazz)

    override fun getSupportedMediaTypes(): List<MediaType> = listOf(MediaType.APPLICATION_JSON)

    override fun write(
        representation: Representation<*>,
        contentType: MediaType?,
        outputMessage: HttpOutputMessage,
    ) {
        // Set, not defaulted: [contentType] is what was negotiated, and it is not what these bytes are.
        outputMessage.headers.contentType = MediaType.APPLICATION_JSON
        outputMessage.headers.contentLength = representation.bytes.size.toLong()
        outputMessage.body.write(representation.bytes)
        outputMessage.body.flush()
    }

    override fun read(
        clazz: Class<out Representation<*>>,
        inputMessage: HttpInputMessage,
    ): Representation<*> = throw HttpMessageNotReadableException("A representation is written, never read.", inputMessage)
}

/**
 * Why Jackson cannot write a [Representation]: asked to, it fails.
 *
 * [RepresentationConverter] is what writes one. Should a response ever
 * reach the JSON converter instead (a context without that bean, a list of
 * converters somebody reordered, a holder nested in another body), the
 * result must be a failure somebody sees, a `500`, and not a `200` carrying
 * the holder's fields. That happened once, and looked like success.
 */
internal class RepresentationIsNotJson : ValueSerializer<Representation<*>>() {
    override fun serialize(
        value: Representation<*>,
        gen: JsonGenerator,
        ctxt: SerializationContext,
    ): Unit = throw DatabindException.from(gen, NOT_JSON)

    private companion object {
        // Says what happened and names no content: the bytes held are a response body.
        const val NOT_JSON = "A Representation is written by RepresentationConverter, as its bytes; it is not a JSON value."
    }
}
