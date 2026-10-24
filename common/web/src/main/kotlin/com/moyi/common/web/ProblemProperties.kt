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
 *
 * The default is the API's own host, on a domain the project owns
 * (getmoyi.app, registered 2026-10-23); until then it named `api.moyi.app`,
 * which belongs to someone else. A client compares a `type` as an identifier,
 * so changing this after launch breaks every error a client recognises.
 */
@Validated
@ConfigurationProperties(prefix = "moyi.problems")
data class ProblemProperties(
    @field:NotNull
    val baseUri: URI = URI.create("https://api.getmoyi.app/problems"),
)
