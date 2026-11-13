package com.moyi.admin.infra

import com.moyi.bond.api.BondAccess
import com.moyi.bond.api.BondClosingView
import io.mockk.every
import io.mockk.mockk
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar
import org.springframework.core.type.AnnotationMetadata
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

/**
 * A2's two moderation ports and A5's, real, in this module's test context: `ReportModerationStore`,
 * `QuarantineModerationStore` and `ReportedEntryTextStore`, found by name and nothing else of gratitude or media (their
 * other beans want a world this context does not build). All three are `internal` to their modules,
 * which is why they are named by pattern and never by type.
 *
 * The quarantine store reads bytes through media's `ObjectStore`, also `internal`. [FakeMediaStore]
 * stands in for it, made as a JDK proxy of the interface found by name: the one way to supply an
 * `internal` type of another module without widening it. It serves [FakeMediaStore.BYTES] under
 * every key, records each key it is asked for and each stream it handed out that was closed,
 * and can be made unreachable.
 */
@Configuration(proxyBeanMethods = false)
@ComponentScan(
    basePackages = ["com.moyi.gratitude.infra.database", "com.moyi.media.infra.database"],
    useDefaultFilters = false,
    includeFilters = [
        ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = [".*\\.ReportModerationStore", ".*\\.QuarantineModerationStore", ".*\\.ReportedEntryTextStore"],
        ),
    ],
)
@Import(FakeMediaStore.Registrar::class)
internal class ModerationPortsForTests {
    /**
     * A5's `ReportedEntryTextStore` asks bond for the withdrawn authors (`closingViewOf`), and
     * bond is not built here: every bond answers with a view in which nobody has withdrawn. A
     * withdrawn author's `ERASED` is gratitude's own test (`ReportedEntryTextTest`), on a real bond.
     */
    @Bean
    fun bondAccess(): BondAccess {
        val nobodyWithdrew = mockk<BondClosingView> { every { withdrawnMemberIds } returns emptySet() }
        return mockk { every { closingViewOf(any()) } returns nobodyWithdrew }
    }
}

/** The stand-in for media's `ObjectStore`; see [ModerationPortsForTests]. */
internal object FakeMediaStore {
    val BYTES: ByteArray = "review copy bytes".toByteArray()

    /** Every key the port opened, in order. */
    val opened: MutableList<String> = CopyOnWriteArrayList()

    /** How many of the streams [opened] handed out have been closed (a stream closed twice counts once). */
    val closed = AtomicInteger()

    /** When true, every open throws media's `ObjectStoreUnavailableException`, as a storage that is down does. */
    @Volatile var unavailable: Boolean = false

    fun reset() {
        opened.clear()
        closed.set(0)
        unavailable = false
    }

    private const val STORE = "com.moyi.media.infra.storage.ObjectStore"
    private const val UNAVAILABLE = "com.moyi.media.infra.storage.ObjectStoreUnavailableException"

    /** `open` is the only call the review copy makes; its key is a value class, so it arrives as its `String`. */
    private val handler =
        InvocationHandler { proxy, method, args ->
            when {
                method.name == "getConfigured" -> true
                method.name.startsWith("open") -> open(args!![0] as String)
                method.name == "toString" -> "FakeMediaStore"
                method.name == "hashCode" -> System.identityHashCode(proxy)
                method.name == "equals" -> proxy === args!![0]
                else -> throw UnsupportedOperationException("FakeMediaStore does not ${method.name}")
            }
        }

    private fun open(key: String): InputStream {
        opened += key
        if (unavailable) {
            val type = Class.forName(UNAVAILABLE)
            throw type.getConstructor(String::class.java, Throwable::class.java).newInstance("down (test)", null) as RuntimeException
        }
        return Closing(BYTES)
    }

    /** The bytes, counting its first [close] in [closed]. */
    private class Closing(
        bytes: ByteArray,
    ) : FilterInputStream(ByteArrayInputStream(bytes)) {
        private var done = false

        override fun close() {
            if (!done) {
                done = true
                closed.incrementAndGet()
            }
            super.close()
        }
    }

    class Registrar : ImportBeanDefinitionRegistrar {
        override fun registerBeanDefinitions(
            metadata: AnnotationMetadata,
            registry: BeanDefinitionRegistry,
        ) {
            @Suppress("UNCHECKED_CAST")
            val type = Class.forName(STORE) as Class<Any>
            val store = Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler)
            registry.registerBeanDefinition("objectStore", RootBeanDefinition(type, Supplier { store }))
        }
    }
}
