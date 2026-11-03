package com.nuvio.app.features.cast

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.time.TimeSource

/**
 * The last few hundred casting events (commands sent, the TV's answers, what it fetched from the
 * relay), kept in memory so they can be copied from the casting screen when a TV misbehaves.
 * Links are shortened to their host and file name, so tokens in them aren't copied.
 */
object CastLog {
    private const val MAX_LINES = 400
    private val start = TimeSource.Monotonic.markNow()
    private val lock = SynchronizedObject()
    private val lines = ArrayDeque<String>()

    fun add(message: String) {
        val elapsed = start.elapsedNow().inWholeMilliseconds
        val line = "${elapsed / 1000}.${(elapsed % 1000).toString().padStart(3, '0')} $message"
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
    }

    fun dump(): String = synchronized(lock) { lines.joinToString("\n") }

    /** "host/…/file.mkv" for a link, without its query string. */
    fun shortUrl(url: String): String {
        val noQuery = url.substringBefore('?')
        val host = noQuery.substringAfter("://", "").substringBefore('/')
        val file = noQuery.substringAfterLast('/')
        return if (host.isEmpty()) file.ifEmpty { noQuery.takeLast(40) } else "$host/…/$file"
    }
}
