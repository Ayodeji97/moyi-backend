package com.moyi.app.admin

import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar
import org.springframework.core.type.AnnotationMetadata
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Supplier

/**
 * The bytes behind a review copy (A2 spec §7.4) in the app's admin tests, without MinIO.
 *
 * The app context has one `ObjectStore`, media's `MediaStorageConfiguration.objectStore`, which
 * the test profile makes `UnavailableObjectStore` (`moyi.media.storage.provider: unavailable`).
 * Production wiring is not touched: this registers a **second**, `@Primary` store, which media's
 * beans then receive. It is the admin module's `FakeMediaStore` pattern (a JDK proxy of the
 * interface found by name, the one way to supply an `internal` type of another module), and like
 * the store it stands in for, it refuses every call but one: [open], which a review copy is the only
 * caller of, serves [BYTES] under every key except one that contains a [missing] fragment, and
 * records each key asked for. Everything else, `configured` included, answers as the unavailable
 * store does, so no other test in the shared admin context sees a difference.
 */
@TestConfiguration
@Import(ReviewObjectStore.Registrar::class)
class ReviewObjectStore {
    companion object {
        val BYTES: ByteArray = "review copy bytes, as the store holds them".toByteArray()

        /** Every key a review copy opened, in order. */
        val opened: MutableList<String> = CopyOnWriteArrayList()

        /** A key containing any of these is not in the store: a variant purged under a race, or never made. */
        val missing: MutableSet<String> =
            java.util.concurrent.ConcurrentHashMap
                .newKeySet()

        fun reset() {
            opened.clear()
            missing.clear()
        }

        private const val STORE = "com.moyi.media.infra.storage.ObjectStore"
        private const val UNAVAILABLE = "com.moyi.media.infra.storage.UnavailableObjectStore"

        private fun open(key: String): InputStream? {
            opened += key
            return if (missing.any { it in key }) null else ByteArrayInputStream(BYTES)
        }
    }

    class Registrar : ImportBeanDefinitionRegistrar {
        override fun registerBeanDefinitions(
            metadata: AnnotationMetadata,
            registry: BeanDefinitionRegistry,
        ) {
            @Suppress("UNCHECKED_CAST")
            val type = Class.forName(STORE) as Class<Any>
            val unavailable = Class.forName(UNAVAILABLE).getDeclaredConstructor().newInstance()
            // `open`'s key is a value class, so it arrives as its `String`; every other call is the unavailable store's.
            val handler =
                InvocationHandler { proxy, method, args ->
                    when {
                        method.name.startsWith("open") -> open(args!![0] as String)
                        method.name == "toString" -> "ReviewObjectStore"
                        method.name == "hashCode" -> System.identityHashCode(proxy)
                        method.name == "equals" -> proxy === args!![0]
                        else -> unavailable(method, unavailable, args)
                    }
                }
            val store = Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler)
            registry.registerBeanDefinition(
                "reviewObjectStore",
                RootBeanDefinition(type, Supplier { store }).apply { isPrimary = true },
            )
        }

        private fun unavailable(
            method: java.lang.reflect.Method,
            target: Any,
            args: Array<out Any?>?,
        ): Any? =
            try {
                method.isAccessible = true
                method.invoke(target, *(args ?: emptyArray()))
            } catch (thrown: InvocationTargetException) {
                throw thrown.targetException
            }
    }
}
