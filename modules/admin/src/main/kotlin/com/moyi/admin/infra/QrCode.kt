package com.moyi.admin.infra

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/** Renders enrolment text as an inline PNG, so the secret never touches a URL or a file. */
internal object QrCode {
    private const val SIZE = 256
    private const val BLACK = 0x000000
    private const val WHITE = 0xFFFFFF

    fun pngDataUri(text: String): String {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, SIZE, SIZE, mapOf(EncodeHintType.MARGIN to 2))
        val image = BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) image.setRGB(x, y, if (matrix[x, y]) BLACK else WHITE)
        }
        val png = ByteArrayOutputStream().also { check(ImageIO.write(image, "png", it)) { "no PNG writer" } }
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(png.toByteArray())
    }
}
