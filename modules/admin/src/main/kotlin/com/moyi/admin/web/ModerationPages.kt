package com.moyi.admin.web

import com.moyi.admin.service.AdminSignIn
import com.moyi.admin.service.ConditionalOnAdminConsole
import com.moyi.admin.service.FreshStepUp
import com.moyi.admin.service.ModerationNote
import com.moyi.admin.service.StepUp
import com.moyi.admin.service.TotpStep
import com.moyi.common.security.ClientAddress
import jakarta.servlet.http.HttpSession
import org.springframework.http.HttpStatus
import org.springframework.security.access.hierarchicalroles.RoleHierarchy
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.springframework.web.servlet.ModelAndView
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * What the moderation pages share (A2 spec §5): the one `404` page every refused read answers
 * with, the id parsing that leads to it, the step-up's session half, and the decision form.
 */
internal object ModerationPages {
    /**
     * The admin `404` page, the same bytes for an unknown id, a value that is not a UUID and a
     * refused look (spec §5.1, §7.4), so that a forged id learns nothing. It is the page
     * [AdminErrorResolver] renders for a path with no page, from the same view and model.
     */
    fun notFound(): ModelAndView = ModelAndView(AdminErrorPage.VIEW, AdminErrorPage.model(HttpStatus.NOT_FOUND), HttpStatus.NOT_FOUND)

    /** [raw] as a UUID, or null for anything else: a page's id is parsed before anything is read (spec §10.3). */
    fun uuidOrNull(raw: String?): UUID? = raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    /** The signed-in admin's id: the chain admits only a two-factor session, whose principal it is. */
    fun adminId(authentication: Authentication): UUID = authentication.principal as UUID

    /** Whole days since [since], for the lists' age column (spec §5: an age, never an "overdue" flag). */
    fun ageInDays(
        since: Instant?,
        now: Instant,
    ): Long? = since?.let { Duration.between(it, now).toDays() }
}

/**
 * The session's half of the step-up (A2 spec §10.2). The rule is `StepUp.isFresh`, which the
 * console applies to what this hands it; this proves a typed code and records when, and nothing
 * else ever writes [AdminAuthorities.SECOND_FACTOR_AT] after sign-in.
 */
@Component
@ConditionalOnAdminConsole
internal class SecondFactor(
    private val signIn: AdminSignIn,
    private val clock: Clock,
    private val roles: RoleHierarchy,
) {
    /**
     * The proof to hand the console. With no code typed, the session's own, as it is. With one,
     * `AdminSignIn.stepUp` decides it (the sign-in's lockout and replay check): accepted, the
     * window starts now and that is the proof; refused, nothing is written and the decision is not
     * attempted.
     */
    fun prove(
        session: HttpSession,
        adminId: UUID,
        code: String,
        from: ClientAddress?,
    ): Proof {
        if (code.isBlank()) return Proof.Held(session.getAttribute(AdminAuthorities.SECOND_FACTOR_AT) as? Instant)
        return when (signIn.stepUp(adminId, code.trim(), from)) {
            is TotpStep.Accepted -> {
                val now = clock.instant()
                session.setAttribute(AdminAuthorities.SECOND_FACTOR_AT, now)
                Proof.Held(now)
            }

            TotpStep.Refused -> {
                Proof.Refused
            }
        }
    }

    /**
     * A code proved for one read of an entry's words (A5 spec §5 step 3), with no window. A blank
     * code is [Fresh.Missing], whatever the session's window says: the session is not read. Any
     * other is `AdminSignIn.stepUp`'s to decide (its row lock, lockout, replay check and its own
     * `ADMIN_STEP_UP` row, committed before the read begins): accepted, it is [Fresh.Proved], and
     * [AdminAuthorities.SECOND_FACTOR_AT] is **not** written, so the read opens no window for the
     * acts [prove] gates; refused, [Fresh.Refused]. The one place a [FreshStepUp] is made.
     */
    fun proveNow(
        adminId: UUID,
        code: String,
        from: ClientAddress?,
    ): Fresh {
        if (code.isBlank()) return Fresh.Missing
        return when (signIn.stepUp(adminId, code.trim(), from)) {
            is TotpStep.Accepted -> Fresh.Proved(FreshStepUp(adminId, clock.instant()))
            TotpStep.Refused -> Fresh.Refused
        }
    }

    /** Whether a gated form needs its code field shown up front. Display only: the console decides. */
    fun asksForCode(session: HttpSession?): Boolean =
        !StepUp.isFresh(session?.getAttribute(AdminAuthorities.SECOND_FACTOR_AT) as? Instant, clock.instant())

    /** Whether the session's role reaches `MODERATOR` through the hierarchy: the navigation and the home counts. */
    fun moderates(authentication: Authentication): Boolean =
        roles.getReachableGrantedAuthorities(authentication.authorities).any { it.authority == "ROLE_MODERATOR" }

    /** Whether the session's role is `ADMIN`: whether the report page offers the read form (A5 spec §5). Display only. */
    fun isAdmin(authentication: Authentication): Boolean =
        roles.getReachableGrantedAuthorities(authentication.authorities).any { it.authority == "ROLE_ADMIN" }

    sealed interface Proof {
        data class Held(
            val secondFactorAt: Instant?,
        ) : Proof

        data object Refused : Proof
    }

    /** What [proveNow] answers: no code typed, a code refused, or a code proved in this request. */
    sealed interface Fresh {
        data object Missing : Fresh

        data object Refused : Fresh

        class Proved(
            val stepUp: FreshStepUp,
        ) : Fresh
    }
}

/**
 * One decision form (spec §5.1, §5.2; the `admin/fragments/decision` fragment): the page it is
 * on ([back], where its POST returns), where it posts,
 * the hidden fields it carries (the reason the page showed, the takedown), the note as typed, and
 * whether it asks for a code. [toString] leaves the note out.
 *
 * [wording] is how the note and the code are named on the form: A2's decisions post a `note` and
 * ask for a code because the act cannot be undone; A3's suspension posts a `justification` and asks
 * for a new code every time.
 */
@Suppress("LongParameterList") // A form's parts, each shown by the fragment.
internal class DecisionForm(
    val id: String,
    val back: String,
    val action: String,
    val label: String,
    val hidden: Map<String, String> = emptyMap(),
    val reasons: List<String> = emptyList(),
    val gated: Boolean = false,
    val askCode: Boolean = false,
    val note: String = "",
    val error: String? = null,
    val warning: String? = null,
    val wording: Wording = Wording.DECISION,
) {
    val noteHint: String = NOTE_HINT

    /** This form again, after a POST that decided nothing: the note kept, the code field asked for if [askCode]. */
    fun again(
        note: String,
        askCode: Boolean,
        error: String?,
    ): DecisionForm = DecisionForm(id, back, action, label, hidden, reasons, gated, askCode, note, error, warning, wording)

    /** The note's field name and label, the code field's hint, and the line a refused note gets, in the label's word. */
    enum class Wording(
        val noteField: String,
        val noteLabel: String,
        val codeHint: String,
        val noteRefused: String,
    ) {
        DECISION(
            "note",
            "Note",
            "This cannot be undone, so it needs a code proven in the last five minutes.",
            "Write a note of 1 to ${ModerationNote.MAX_CODE_POINTS} characters saying why.",
        ),
        SUSPENSION(
            "justification",
            "Justification",
            "Each suspension, and each lift, needs a new code.",
            "Write a justification of 1 to ${ModerationNote.MAX_CODE_POINTS} characters saying why.",
        ),
    }

    override fun toString(): String = "DecisionForm($action)"

    companion object {
        val NOTE_HINT = "Required: 1 to ${ModerationNote.MAX_CODE_POINTS} characters. It is kept in the audit log as your reason."

        /** A2's line, unchanged; a form with other [Wording] says its own ([Wording.noteRefused]). */
        val NOTE_REFUSED = Wording.DECISION.noteRefused
        const val CODE_NEEDED = "This cannot be undone. Enter a code from your authenticator app to confirm it."
        const val DECIDE_VIEW = "admin/decide"
    }
}
