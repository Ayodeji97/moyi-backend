package com.moyi.contracts

import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Keeps the contract's nullability what it was before Jackson 2's Kotlin module reached the
 * classpath (ADR-0054).
 *
 * `spring-ai-openai` is built on the OpenAI Java SDK, which needs
 * `com.fasterxml.jackson.module:jackson-module-kotlin` at runtime, so the artifact cannot be
 * excluded. Springdoc's `SpringDocJacksonKotlinModuleConfiguration` is conditional on that class
 * alone: once it is present, springdoc registers the module on swagger-core's global
 * `Json.mapper()`, and every non-null Kotlin property becomes `required`. That added 33
 * `required` arrays to `contracts/openapi.json` with no change to any route.
 *
 * This removes that one bean definition before anything is instantiated, so springdoc's own
 * `springdocObjectMapperProvider` (a plain mapper) is the one used and the global mapper is never
 * touched. Adopting Kotlin nullability in the contract is a deliberate follow-up, under the
 * `breaking-api-change` label, never a side effect of a dependency. `OpenApiContractTest` fails
 * if this stops working, for instance if a springdoc upgrade renames the bean.
 */
@Suppress("UtilityClassWithPublicConstructor") // A class: Spring instantiates configurations, and the bean must be static.
@Configuration(proxyBeanMethods = false)
internal class SchemaNullabilityPin {
    companion object {
        /** springdoc 3.1.1's name for the provider that registers the Jackson 2 Kotlin module. */
        const val KOTLIN_MODULE_PROVIDER = "springdocKotlinObjectMapperProvider"

        /** Static, as a registry post-processor must be: it runs before this class's instance exists. */
        @Bean
        @JvmStatic
        fun dropSpringdocKotlinModuleProvider(): BeanDefinitionRegistryPostProcessor =
            BeanDefinitionRegistryPostProcessor { registry: BeanDefinitionRegistry ->
                if (registry.containsBeanDefinition(KOTLIN_MODULE_PROVIDER)) {
                    registry.removeBeanDefinition(KOTLIN_MODULE_PROVIDER)
                }
            }
    }
}
