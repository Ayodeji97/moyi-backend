package com.moyi.common.web

import jakarta.validation.constraints.NotNull
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.net.URI

/**
 * Where the `type` URIs in error responses point (doc 06 §2).
 *
 * `@ConfigurationProperties` with validation rather than scattered `@Value`
 * (doc 18 §4): the binding fails at startup with a message naming the
 * property, instead of a null appearing inside a URI at the first error a
 * client sees — which is exactly the moment nobody wants a second bug.
 */
@Validated
@ConfigurationProperties(prefix = "moyi.problems")
data class ProblemProperties(
    @field:NotNull
    val baseUri: URI = URI.create("https://api.moyi.app/problems"),
)
