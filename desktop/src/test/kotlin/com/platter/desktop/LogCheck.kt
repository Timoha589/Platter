package com.platter.desktop

import com.platter.desktop.log.AppLog
import java.nio.file.Paths

/** Run in a JVM of its own by [AppLogTest]: installs the log in the folder given, then goes wrong in three ways. */
fun main(args: Array<String>) {
    AppLog.install(Paths.get(args[0]))
    AppLog.error("a handled failure", IllegalStateException("boom"))
    System.err.println("something printed to stderr, from http://host/rest/stream?u=tim&t=0123abcd&s=pepper&id=7")
    val thread = Thread({ throw IllegalArgumentException("nobody catches this") }, "worker-x")
    thread.start()
    thread.join()
}
