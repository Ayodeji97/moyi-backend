package com.moyi.contracts

import com.moyi.common.security.ClientContext
import com.moyi.common.security.CurrentUser
import com.moyi.common.security.SecurityConfiguration
import com.moyi.common.web.ErrorCode
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springdoc.core.utils.SpringDocUtils
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus

/**
 * What the generated document says beyond what springdoc can read off the
 * controllers (ADR-0024).
 *
 * Three things are added by rule rather than by annotation, because each is
 * a property of the whole API and an annotation per endpoint would be one
 * more place for it to drift:
 *
 * - **The bearer.** Everything is called with a token unless it is one of the
 *   public auth endpoints in `SecurityConfiguration` (doc 06 §1); those get an
 *   empty security requirement, which is OpenAPI's way of saying "none".
 * - **The error responses.** Doc 06 §2: one shape, `ProblemDetail`, whose
 *   `code` is `ErrorCode` enumerated — the client generates a sealed class
 *   from it, and an unhandled code is a compile error there. 429 and 500 can
 *   happen to anything; 400 and 422 to anything with a body; 401 and 403 to
 *   anything behind the bearer.
 * - **The two argument-resolver types.** `CurrentUser` comes from the token
 *   and `ClientContext` from the socket; documented as query parameters they
 *   would generate a client that sends them.
 *
 * Which codes a *particular* operation returns is doc 06 §3's table, not this
 * document: the contract a generated client is built from is the shape.
 */
@Configuration
class OpenApiConfiguration {
    init {
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(CurrentUser::class.java, ClientContext::class.java)
    }

    @Bean
    fun openApi(): OpenAPI =
        OpenAPI()
            .info(Info().title("Moyi API").version("v1").description("The contract the Moyi client is generated from (doc 06)."))
            .components(
                Components()
                    .addSecuritySchemes(BEARER, SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")),
            ).addSecurityItem(SecurityRequirement().addList(BEARER))

    /**
     * A customizer rather than part of the [openApi] bean: springdoc rebuilds
     * `components.schemas` from what it finds on the controllers and drops
     * anything that was there before, so the two schemas have to be added
     * after that pass.
     */
    @Bean
    fun problemResponsesAndPublicEndpoints(): OpenApiCustomizer =
        OpenApiCustomizer { api ->
            api.components.addSchemas(PROBLEM_DETAIL, problemDetail()).addSchemas(FIELD_VIOLATION, fieldViolation())
            api.paths.forEach { (path, item) ->
                item.readOperationsMap().forEach { (method, operation) ->
                    val public = method == PathItem.HttpMethod.POST && path in SecurityConfiguration.PUBLIC_AUTH_ENDPOINTS
                    if (public) operation.security = emptyList()
                    statusesFor(operation, public).forEach { status ->
                        operation.responses.addApiResponse(status.value().toString(), problemResponse(status))
                    }
                }
            }
        }

    private fun statusesFor(
        operation: Operation,
        public: Boolean,
    ): List<HttpStatus> =
        buildList {
            if (operation.requestBody != null) addAll(listOf(HttpStatus.BAD_REQUEST, HttpStatus.UNPROCESSABLE_ENTITY))
            if (!public) addAll(listOf(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN))
            add(HttpStatus.TOO_MANY_REQUESTS)
            add(HttpStatus.INTERNAL_SERVER_ERROR)
        }

    private fun problemResponse(status: HttpStatus): ApiResponse =
        ApiResponse()
            .description(status.reasonPhrase)
            .content(Content().addMediaType(PROBLEM_JSON, MediaType().schema(Schema<Any>().`$ref`(PROBLEM_DETAIL_REF))))

    /** RFC 9457 as doc 06 §2 specifies it, minus `traceId`, which does not exist until tracing does. */
    private fun problemDetail(): Schema<*> =
        ObjectSchema()
            .description("RFC 9457 problem details (doc 06 §2). Switch on `code`; `detail` is prose and may change.")
            .addProperty("type", StringSchema().format("uri"))
            .addProperty("title", StringSchema())
            .addProperty("status", IntegerSchema())
            .addProperty("detail", StringSchema())
            .addProperty("instance", StringSchema().format("uri"))
            .addProperty("code", StringSchema().apply { enum = ErrorCode.entries.map { it.name } })
            .addProperty("errors", ArraySchema().items(Schema<Any>().`$ref`(FIELD_VIOLATION_REF)))
            .required(listOf("type", "title", "status", "code"))

    private fun fieldViolation(): Schema<*> =
        ObjectSchema()
            .description("One rejected field. Never carries the rejected value.")
            .addProperty("field", StringSchema())
            .addProperty("code", StringSchema())
            .addProperty("message", StringSchema())
            .required(listOf("field", "code", "message"))

    companion object {
        const val BEARER = "bearerAuth"
        const val PROBLEM_DETAIL = "ProblemDetail"
        const val FIELD_VIOLATION = "FieldViolation"
        private const val PROBLEM_DETAIL_REF = "#/components/schemas/$PROBLEM_DETAIL"
        private const val FIELD_VIOLATION_REF = "#/components/schemas/$FIELD_VIOLATION"
        private const val PROBLEM_JSON = "application/problem+json"
    }
}
