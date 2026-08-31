package com.moyi.app

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class MoyiApplication

// Spring's own idiomatic Kotlin bootstrap — the spread operator here is
// unavoidable (Array<String> into a vararg) and this is the only call site.
@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    runApplication<MoyiApplication>(*args)
}
val x=1
