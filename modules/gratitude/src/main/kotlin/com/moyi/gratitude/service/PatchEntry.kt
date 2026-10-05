package com.moyi.gratitude.service

import com.moyi.common.web.idempotency.IdempotentExecution
import com.moyi.common.web.idempotency.IdempotentRequest
import com.moyi.common.web.idempotency.IdempotentResult
import com.moyi.common.web.idempotency.ResultKind
import com.moyi.gratitude.domain.EntryId
import com.moyi.gratitude.domain.EntryText
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/** A supplied PATCH key uses C1's reservation transaction; an omitted key makes an ordinary edit. */
@Service
internal class PatchEntry(
    private val changes: ChangeEntry,
    private val execution: IdempotentExecution,
    private val transactions: TransactionTemplate,
) {
    fun patch(
        userId: UUID,
        id: EntryId,
        text: EntryText,
        media: Boolean,
        request: IdempotentRequest?,
    ): EntryChangeResult {
        if (request == null) return EntryChangeResult(changes.change(userId, id, text, media), false)
        require(request.userId == userId) { "the key belongs to a different caller" }
        return checkNotNull(
            transactions.execute {
                val outcome =
                    execution.once(request) {
                        IdempotentResult(changes.change(userId, id, text, media), id.value, ResultKind.ENTRY, OK)
                    }
                val view = outcome.value ?: changes.read(userId, EntryId(outcome.resultId))
                EntryChangeResult(view, outcome.wasReplayed)
            },
        )
    }

    private companion object {
        const val OK = 200
    }
}

/** The rendered resource is current, even when the request is a replay. */
internal data class EntryChangeResult(
    val view: EntryView,
    val replayed: Boolean,
)
