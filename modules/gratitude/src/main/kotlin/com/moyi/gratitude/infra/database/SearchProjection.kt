package com.moyi.gratitude.infra.database

import com.moyi.gratitude.domain.EntryText

/**
 * What `entries.text_search` holds for an entry whose words are [text] (ADR-0039 decision 5).
 *
 * **Today, the words as stored**, and `null` when there are none: an erased entry has no
 * words, so it has no projection, and its generated `search_vector` is empty. Every write of
 * an entry row goes through [EntryStore] (`insert` through `Entry.toEntity`, everything else
 * through `update`), and both ask this, so an erasure, which writes `text = null`, empties the
 * projection in the same statement, whoever erases: the author, the withdrawal's consumer or
 * the close job's pre-step.
 *
 * **Phase 5 (D-13) changes this function and nothing else.** When `text` becomes
 * ciphertext, this becomes the lossy projection doc 07 §2 describes, computed from the
 * plaintext before it is encrypted.
 *
 * Not on the domain [com.moyi.gratitude.domain.Entry]: no rule of an entry reads it.
 */
internal fun searchProjectionOf(text: EntryText?): String? = text?.value
