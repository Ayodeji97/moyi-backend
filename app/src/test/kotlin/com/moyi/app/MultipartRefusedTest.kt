package com.moyi.app

import com.moyi.common.testing.IntegrationTest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.string.shouldStartWith
import jakarta.servlet.ServletContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import java.io.File
import java.net.Socket

/**
 * **No route of this API takes multipart, so nothing parses it** (`spring.servlet.multipart.enabled: false`,
 * task 2 re-review m3). With Spring Boot's default, the dispatcher servlet parses every part of a multipart body
 * before any interceptor or handler runs, and with a threshold of 0 writes each to Tomcat's temporary directory:
 * on a public route such as `/auth/login`, a stranger's upload of up to 10 MB reached the disk before the `415`.
 *
 * **What is asked, over a real socket:** half of a declared multipart body is sent, and the answer is read
 * while the other half is still owed. If anything parsed the parts, Tomcat would be waiting for the rest and no
 * answer would come (the read times out, which is the red). The answer is the `415` any body that is not JSON
 * gets, and at that moment no upload file is in Tomcat's temporary directory.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["moyi.security.client-address.trusted-proxies=127.0.0.0/8,::1/128"],
)
@ActiveProfiles("test")
class MultipartRefusedTest(
    @LocalServerPort private val port: Int,
    @Autowired private val servletContext: ServletContext,
) : IntegrationTest() {
    @Test
    fun `a multipart POST to a public route is refused before its body is read, and nothing is written to disk`() {
        val temporary = servletContext.getAttribute(ServletContext.TEMPDIR) as File
        val part =
            "--$BOUNDARY\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.bin\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n"
        val declared = part.length + FILE_BYTES + "\r\n--$BOUNDARY--\r\n".length

        val statusLine =
            Socket("localhost", port).use { socket ->
                socket.soTimeout = SOCKET_TIMEOUT_MILLIS
                val out = socket.getOutputStream()
                out.write(
                    (
                        "POST /api/v1/auth/login HTTP/1.1\r\nHost: localhost\r\nX-Forwarded-For: 203.0.113.231\r\n" +
                            "Content-Type: multipart/form-data; boundary=$BOUNDARY\r\nContent-Length: $declared\r\n\r\n$part"
                    ).toByteArray(),
                )
                // Half the file; the rest is never sent.
                out.write(ByteArray(FILE_BYTES / 2) { 'a'.code.toByte() })
                out.flush()
                val line = socket.getInputStream().bufferedReader().readLine()
                val uploads = temporary.listFiles { file -> file.name.startsWith("upload_") }.orEmpty()
                uploads.toList().shouldBeEmpty()
                line
            }

        statusLine shouldStartWith "HTTP/1.1 415"
    }

    private companion object {
        const val BOUNDARY = "moyiboundary"

        /** Under Spring Boot's 1 MB per-file default, so that only parsing, never a size limit, could refuse it. */
        const val FILE_BYTES = 900_000
        const val SOCKET_TIMEOUT_MILLIS = 15_000
    }
}
