package com.moyi.admin.infra

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO

class QrCodeTest {
    @Test
    fun `renders a decodable png data uri`() {
        val uri = QrCode.pngDataUri("otpauth://totp/Moyi:op?secret=ABC")
        val prefix = "data:image/png;base64,"
        assertTrue(uri.startsWith(prefix))
        val image = ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(uri.removePrefix(prefix))))
        assertNotNull(image)
        assertTrue(image.width >= 200)
    }
}
