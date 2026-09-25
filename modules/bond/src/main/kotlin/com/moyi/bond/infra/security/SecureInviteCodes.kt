package com.moyi.bond.infra.security

import com.moyi.bond.domain.InviteCode
import com.moyi.bond.domain.InviteCodes
import org.springframework.stereotype.Component
import java.security.SecureRandom

/**
 * The production adapter for [InviteCodes]: a CSPRNG.
 *
 * Not `Random`, and not `Math.random()`. A code is the only thing standing
 * between a stranger and someone's bond until it is spent (T-06), so a
 * predictable sequence would let an attacker who has seen one code compute
 * the next — which no rate limit can help with.
 */
@Component
internal class SecureInviteCodes : InviteCodes {
    private val random = SecureRandom()

    override fun next(): InviteCode = InviteCode.random(random)
}
