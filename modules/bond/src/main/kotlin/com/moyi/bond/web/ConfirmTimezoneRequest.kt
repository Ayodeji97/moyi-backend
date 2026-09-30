package com.moyi.bond.web

import com.moyi.bond.domain.ProposalId
import jakarta.validation.constraints.NotNull
import java.util.UUID

/** Consent is for the proposal the member reviewed, never whichever proposal is current. */
internal data class ConfirmTimezoneRequest(
    @field:NotNull
    val proposalId: UUID,
) {
    fun toProposalId(): ProposalId = ProposalId(proposalId)
}
