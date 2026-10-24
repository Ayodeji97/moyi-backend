package com.moyi.app

import com.moyi.common.testing.IntegrationTest
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.core.io.ClassPathResource
import org.springframework.test.context.ActiveProfiles
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

// The actuator lives on its own port, which kamal-proxy does not route, so the
// Prometheus scrape — public to anything that can reach that port, since
// Prometheus holds no token — is never public to the internet. The one health
// verdict the proxy needs is left on the application's port as /healthz.
//
// Real servers on two random ports, not MockMvc: MockMvc has no port, and a
// path served on the wrong one is exactly the failure this guards. The port
// is 0 here so that runs never collide; that application.yml says 8081 is the
// first test. Seen failing, one at a time, with `management.server.port`
// removed from application.yml (the first test), with the `additional-path`
// removed (/healthz answered 404) and with micrometer-registry-prometheus
// removed from the build (no scrape on either port).
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["management.server.port=0"],
)
@ActiveProfiles("test")
class ManagementPortTest : IntegrationTest() {
    @LocalServerPort
    private var applicationPort: Int = 0

    @LocalManagementPort
    private var managementPort: Int = 0

    private val http = HttpClient.newHttpClient()

    @Test
    fun `production puts the actuator on port 8081`() {
        val yaml = YamlPropertySourceLoader().load("application", ClassPathResource("application.yml"))
        yaml.firstNotNullOf { it.getProperty("management.server.port") }.toString() shouldBe "8081"
    }

    @Test
    fun `the Prometheus scrape answers on the management port, with the application's own meters`() {
        val response = get(managementPort, "/actuator/prometheus")

        response.statusCode() shouldBe 200
        response.body() shouldContain "moyi_rate_limit_backend_unavailable_total"
    }

    @Test
    fun `the Prometheus scrape is not served on the application's port`() {
        get(applicationPort, "/actuator/prometheus").statusCode() shouldBe 404
    }

    @Test
    fun `the health verdict the proxy routes on is served on the application's port`() {
        val response = get(applicationPort, "/healthz")

        response.statusCode() shouldBe 200
        response.body() shouldContain "\"status\":\"UP\""
        // The group inherits show-details: never. Its components would name
        // the database and the disk to anyone on the internet.
        response.body() shouldNotContain "components"
    }

    private fun get(
        port: Int,
        path: String,
    ): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
}
