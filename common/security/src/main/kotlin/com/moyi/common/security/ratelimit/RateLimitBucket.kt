package com.moyi.common.security.ratelimit

import java.time.Duration

/**
 * Doc 06 §4's table, as code. Every bucket the application enforces is an
 * entry here, so the set is closed and reviewable in one place, and a bucket
 * that exists in a document but not in this enum is visibly not enforced.
 *
 * **Refill is greedy**: a five-per-fifteen-minutes bucket hands one token
 * back every three minutes rather than five at once on the quarter hour. The
 * rate is the same; the difference is what a person who hit the limit is
 * told — "try again in three minutes" rather than "in fourteen" — and
 * `states.md` §1b asks for the wait to be announced, so the shorter honest
 * number is the better one.
 *
 * Two shapes not in doc 06's table, recorded in ADR-0023: `auth:resend` per
 * email, the one-a-minute cooldown `states.md` §1 promised the resend button
 * and the table omitted; and a per-IP bucket on the two endpoints that send
 * email to whoever asks, because a per-address limit alone bounds nothing an
 * attacker cannot vary.
 */
@Suppress("MagicNumber") // The numbers are the table in doc 06 §4; naming each one would hide the transcription.
enum class RateLimitBucket(
    /** The middle of the Redis key, `rl:{bucket}:{key}` (doc 07 §5). */
    val id: String,
    val subject: Subject,
    val capacity: Long,
    val period: Duration,
) {
    /** FR-012: 5 attempts / 15 min / account. Counts unknown addresses too, so it is not an oracle (T-18). */
    AUTH_LOGIN_EMAIL("auth:login:email", Subject.EMAIL, 5, Duration.ofMinutes(15)),

    /** FR-012: 20 / hour / IP, "regardless of whether the address exists" (T-18). */
    AUTH_LOGIN_IP("auth:login:ip", Subject.IP, 20, Duration.ofHours(1)),

    /** FR-012: 3 registrations / hour / IP. */
    AUTH_REGISTER_IP("auth:register:ip", Subject.IP, 3, Duration.ofHours(1)),

    /** FR-012: 3 password-reset requests / hour / email. */
    AUTH_RESET_EMAIL("auth:reset:email", Subject.EMAIL, 3, Duration.ofHours(1)),

    /** ADR-0023 addition: the reset endpoint sends email, so the sender's address is bounded as login's is. */
    AUTH_RESET_IP("auth:reset:ip", Subject.IP, 20, Duration.ofHours(1)),

    /** ADR-0023 addition: the one-a-minute cooldown `states.md` §1 promises the resend button. */
    AUTH_RESEND_EMAIL("auth:resend:email", Subject.EMAIL, 1, Duration.ofMinutes(1)),

    /** ADR-0023 addition, same reasoning as [AUTH_RESET_IP]. */
    AUTH_RESEND_IP("auth:resend:ip", Subject.IP, 20, Duration.ofHours(1)),

    /**
     * #135, ADR-0023 amended 2026-11-08: ten typed codes a day per address, on `verify-email`
     * and `reset-password` together. A code is one of 10^6 and five wrong ones burn it, but
     * codes can be asked for again; this is what bounds the guesses at one account to ten a
     * day whatever is re-requested: at most 10 / 10^6 = 1 in 100,000 a day. Counted for every
     * address alike, so the 429 is no oracle.
     */
    AUTH_CODE_EMAIL("auth:code:email", Subject.EMAIL, 10, Duration.ofDays(1)),

    /**
     * #135, ADR-0023 amended 2026-11-08: twenty presentations an hour per client address,
     * shared by `verify-email` and `reset-password`, links and codes alike, as the invite
     * endpoints share theirs. Bounds one address spraying codes across many accounts.
     */
    AUTH_CODE_IP("auth:code:ip", Subject.IP, 20, Duration.ofHours(1)),

    /** Doc 06 §4: "`invite:create` (per user) 10 / day". Creating one revokes the outstanding one, so this bounds churn. */
    INVITE_CREATE_USER("invite:create:user", Subject.USER, 10, Duration.ofDays(1)),

    /**
     * Ten resolves an hour per user, above the per-IP bucket both code
     * endpoints share. Added by ADR-0027: doc 06 §3.3 specifies "10/hour/user"
     * on `GET /invites/{code}` in prose and §4's table carries only the per-IP
     * half, so this is the table catching up with the endpoint.
     */
    INVITE_LOOKUP_USER("invite:lookup:user", Subject.USER, 10, Duration.ofHours(1)),

    /**
     * Doc 06 §4: "`invite:lookup` + `invite:accept` (per IP) 20 / hour
     * **combined** — brute-forcing 6-char codes". One bucket named by both
     * endpoints, which is what "combined" means: 30^6 is about 7.3 x 10^8
     * codes, and doc 06 says plainly that "the limit is what makes it fine in
     * practice" (T-06).
     */
    INVITE_CODE_IP("invite:code:ip", Subject.IP, 20, Duration.ofHours(1)),

    /** Spec §5.5, corpus doc 06 §4: 20 entry submission attempts per day per user. */
    ENTRIES_CREATE_USER("entries:create:user", Subject.USER, 20, Duration.ofDays(1)),

    /**
     * Doc 06 §4: "`media:intent` (per user) 30 / hour". Each intent is an upload
     * URL for up to 10 MB, so this is what bounds the bytes one account can
     * make the storage take (T-16, spec §6 R-C).
     */
    MEDIA_INTENT_USER("media:intent:user", Subject.USER, 30, Duration.ofHours(1)),

    /**
     * ADR-0053 addition (doc 26 OQ-26c asked whether reporting is limited like
     * support's 5 a day; doc 06 §4 names no bucket for it): 10 reports a day
     * per user. Each report holds media back for a person and puts a row in
     * their queue, so this bounds what one account can make a person read; ten
     * leaves room for someone reporting several days of one partner's entries.
     */
    REPORTS_CREATE_USER("reports:create:user", Subject.USER, 10, Duration.ofDays(1)),

    /**
     * ADR-0054 addition (spec §3.6: "its own Bucket4j bucket, because each call
     * spends a provider request"; doc 06 §4 names none): 30 "Remember when"
     * calls an hour per user. Each call may embed `q` with a paid provider, so
     * this bounds what one account can spend; the search box needs no bucket of
     * its own because a person types into it, and this is asked a question.
     */
    REMEMBER_USER("remember:user", Subject.USER, 30, Duration.ofHours(1)),

    /** Doc 06 §4: "global authenticated 120 / min", per user, on every request that carries a valid token. */
    AUTHENTICATED("authenticated:user", Subject.USER, 120, Duration.ofMinutes(1)),
    ;

    /**
     * What the key identifies, which decides *where* the bucket is applied:
     * [IP] and [USER] are known before the controller runs and are consumed
     * by the interceptor; [EMAIL] is in the request body and is consumed by
     * the service that reads it.
     */
    enum class Subject { IP, EMAIL, USER }
}
