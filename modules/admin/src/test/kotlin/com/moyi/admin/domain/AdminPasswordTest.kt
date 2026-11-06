package com.moyi.admin.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AdminPasswordTest {
    @Test
    fun `14 code points are refused and 15 accepted`() {
        assertNull(AdminPassword.of("a".repeat(14)))
        assertNotNull(AdminPassword.of("a".repeat(15)))
    }

    @Test
    fun `128 is the last accepted length`() {
        assertNotNull(AdminPassword.of("a".repeat(128)))
        assertNull(AdminPassword.of("a".repeat(129)))
    }

    @Test
    fun `length counts code points not chars`() {
        val fifteenEmoji = "😀".repeat(15)
        assertEquals(30, fifteenEmoji.length)
        assertNotNull(AdminPassword.of(fifteenEmoji))
        // 140 chars but 70 code points: inside the upper bound only when counted as code points
        assertNotNull(AdminPassword.of("\uD83D\uDE00".repeat(70)))
        // 14 emoji are 28 chars but 14 code points: refused
        assertNull(AdminPassword.of("\uD83D\uDE00".repeat(14)))
    }

    @Test
    fun `toString hides the value`() {
        val text = AdminPassword.of("correct horse battery")!!.toString()
        assertEquals("AdminPassword(****)", text)
        assertFalse(text.contains("horse"))
    }
}
