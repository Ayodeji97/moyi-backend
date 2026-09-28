package com.moyi.gratitude.web

/**
 * At least one character the *domain* does not consider whitespace —
 * `bond.web.NOT_ONLY_SPACE`'s own value, declared again here because that
 * one is `internal` to `bond` and this module cannot see it.
 *
 * `@NotBlank` is not this rule. Bean Validation blanks a string with Java's
 * `String.trim`, which removes only characters at or below `U+0020`, while
 * Kotlin's `trim`/`isBlank` (what [com.moyi.gratitude.domain.EntryText.of]
 * uses) also removes every `isSpaceChar` — `U+00A0` among them. A body of
 * one non-breaking space would pass `@NotBlank`, reach `EntryText.of`, and
 * fail there as an `IllegalArgumentException` nothing at the edge turns
 * into a `422` naming the field — ADR-0029 §13's lesson, found first in
 * `bond`.
 *
 * `(?U)` is what makes `\S` agree with Kotlin's `isSpaceChar` here, and
 * `(?s)` lets `.` cross a newline so a multi-line entry is judged by its
 * content rather than its shape.
 */
internal const val NOT_ONLY_SPACE = "(?sU).*\\S.*"
