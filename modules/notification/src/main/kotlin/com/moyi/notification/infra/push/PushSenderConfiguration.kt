package com.moyi.notification.infra.push

import com.moyi.notification.api.PushSender
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder
import org.springframework.boot.http.client.HttpClientSettings
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import java.time.Clock

/**
 * Chooses the [PushSender] from configuration: one `@Bean` with an exhaustive
 * `when`, as [com.moyi.notification.infra.email.EmailSenderConfiguration]
 * does and for its reason (ADR-0017 decision 3). The transport and its
 * timeouts are set here, at the composition root, and never in the sender,
 * whose tests bind a mock server to the builder they hand it.
 */
@Configuration
@EnableConfigurationProperties(PushProperties::class)
internal class PushSenderConfiguration {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun pushSender(
        properties: PushProperties,
        restClientBuilder: RestClient.Builder,
        clock: Clock,
    ): PushSender {
        // The project is ours, not a person's; naming it at start-up is how a
        // misconfigured environment is seen in the first lines of its log.
        log.info("Push provider: {} (project: {})", properties.provider, properties.fcm.projectId ?: "<unset>")
        return when (properties.provider) {
            PushProperties.Provider.FCM -> FcmPushSender.create(properties.fcm, restClientBuilder.withTimeouts(properties.fcm), clock)
            PushProperties.Provider.LOG -> LoggingPushSender()
        }
    }

    private fun RestClient.Builder.withTimeouts(fcm: PushProperties.Fcm): RestClient.Builder =
        requestFactory(
            ClientHttpRequestFactoryBuilder
                .jdk()
                .build(HttpClientSettings.defaults().withTimeouts(fcm.connectTimeout, fcm.readTimeout)),
        )
}
