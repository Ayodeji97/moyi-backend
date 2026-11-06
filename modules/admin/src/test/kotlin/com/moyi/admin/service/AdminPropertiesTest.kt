package com.moyi.admin.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.util.Base64

class AdminPropertiesTest {
    private val goodKey = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })

    @Test
    fun `disabled with nothing set validates`() {
        assertDoesNotThrow { AdminProperties().validate() }
    }

    @Test
    fun `enabled without networks names the property`() {
        val e = assertThrows<IllegalStateException> { AdminProperties(enabled = true, totpKey = goodKey).validate() }
        assertTrue(e.message!!.contains("moyi.admin.allowed-networks"))
    }

    @Test
    fun `enabled without a key names the property`() {
        val e =
            assertThrows<IllegalStateException> {
                AdminProperties(enabled = true, allowedNetworks = listOf("10.0.0.0/8")).validate()
            }
        assertTrue(e.message!!.contains("moyi.admin.totp-key"))
    }

    @Test
    fun `a 16 byte key fails without echoing it`() {
        val short = Base64.getEncoder().encodeToString(ByteArray(16) { 9 })
        val props = AdminProperties(enabled = true, allowedNetworks = listOf("10.0.0.0/8"), totpKey = short)
        val e = assertThrows<IllegalStateException> { props.validate() }
        assertTrue(e.message!!.contains("moyi.admin.totp-key"))
        assertFalse(e.message!!.contains(short))
        assertFalse(props.toString().contains(short))
    }

    @Test
    fun `a good enabled configuration validates`() {
        assertDoesNotThrow { AdminProperties(enabled = true, allowedNetworks = listOf("10.0.0.0/8"), totpKey = goodKey).validate() }
    }
}
