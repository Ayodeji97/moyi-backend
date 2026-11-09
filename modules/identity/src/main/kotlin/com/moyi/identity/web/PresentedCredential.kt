package com.moyi.identity.web

import com.moyi.identity.domain.Email
import com.moyi.identity.domain.VerificationCode
import com.moyi.identity.domain.VerificationSecret

/**
 * What `verify-email` and `reset-password` were handed (#135): the token from the link, or
 * the address and the six digits from the email. Exactly one of the two, which is the one
 * rule here and is stated once, in [formOf]: the requests' `@AssertTrue` asks it, and the
 * controllers dispatch on its answer.
 */
internal sealed interface PresentedCredential {
    data class Link(
        val secret: VerificationSecret,
    ) : PresentedCredential

    data class Code(
        val email: Email,
        val code: VerificationCode,
    ) : PresentedCredential

    /** Which form the body is in, judged by presence alone; the shape of each field is its own constraint's. */
    enum class Form { LINK, CODE }

    companion object {
        /**
         * [Form.LINK] for a token that is not blank and nothing else; [Form.CODE] for an address
         * that is not blank and a code, and no token; null for anything else (neither, both, a
         * blank token, an address without a code).
         */
        fun formOf(
            token: String?,
            email: String?,
            code: String?,
        ): Form? =
            when {
                token != null -> if (token.isNotBlank() && email == null && code == null) Form.LINK else null
                !email.isNullOrBlank() && code != null -> Form.CODE
                else -> null
            }

        /** Called only after validation passed, so the form is known and each field has its shape. */
        fun of(
            token: String?,
            email: String?,
            code: String?,
        ): PresentedCredential =
            when (checkNotNull(formOf(token, email, code)) { "validated before it is read" }) {
                Form.LINK -> Link(VerificationSecret(token!!.trim()))
                Form.CODE -> Code(Email(email!!.trim()), VerificationCode(code!!.trim()))
            }

        const val FORM_MESSAGE = "send either the token from the link, or the email address and the code"
    }
}
