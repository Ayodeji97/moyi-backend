package com.moyi.notification.infra.email

import com.moyi.notification.api.EmailSender
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder
import org.springframework.boot.http.client.HttpClientSettings
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

/**
 * Chooses the [EmailSender] implementation from configuration.
 *
 * A single `@Bean` with an exhaustive `when` rather than two beans behind
 * `@ConditionalOnProperty`, for a reason that is easy to miss: adding a
 * provider to [EmailProperties.Provider] and forgetting to wire it is a
 * *compile* error here, and a silently missing bean there. It is a Factory
 * in the plain sense — one place that knows how each concrete sender is
 * built, so that nothing else does.
 *
 * `@EnableConfigurationProperties` is declared here as well as being covered
 * by the application's `@ConfigurationPropertiesScan`, so this module's
 * configuration is complete on its own — its tests boot it without the
 * application, and Boot registers the same class under the same name once.
 */
@Configuration
@EnableConfigurationProperties(EmailProperties::class)
internal class EmailSenderConfiguration {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun emailSender(
        properties: EmailProperties,
        restClientBuilder: RestClient.Builder,
    ): EmailSender {
        // The `from` address is ours, not a user's; naming it at startup is
        // how a misconfigured environment is spotted in the first log lines.
        log.info("Email provider: {} (from: {})", properties.provider, properties.from ?: "<unset>")
        return when (properties.provider) {
            EmailProperties.Provider.RESEND -> ResendEmailSender.create(properties, restClientBuilder.withTimeouts(properties.resend))
            EmailProperties.Provider.LOG -> LoggingEmailSender()
        }
    }

    /**
     * The transport is configured here, at the composition root, and not in
     * the sender. The sender's tests bind a mock server to the builder they
     * pass in; a sender that installed its own request factory would replace
     * that mock and quietly test the network instead.
     */
    private fun RestClient.Builder.withTimeouts(resend: EmailProperties.Resend): RestClient.Builder =
        requestFactory(
            ClientHttpRequestFactoryBuilder
                .jdk()
                .build(HttpClientSettings.defaults().withTimeouts(resend.connectTimeout, resend.readTimeout)),
        )
}
