package com.moyi.media.domain

/** The fixtures under `src/test/resources/images`, made by hand and described where they are used. */
internal object TestResources {
    fun bytes(name: String): ByteArray = checkNotNull(TestResources::class.java.getResourceAsStream("/images/$name")).use { it.readBytes() }
}
