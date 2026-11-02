package com.nuvio.app.features.cast

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Collections
import kotlin.concurrent.thread

/**
 * A small web server on the phone that the TV plays from, the way BubbleUPnP does it. It serves
 * downloads straight from the phone's storage, and passes on streams the TV can't open itself: local
 * torrent streams, links that need headers, and providers only the phone can reach (for example
 * through its VPN). Seeking works: the TV's byte-range requests are answered from the file or
 * forwarded to the provider.
 */
internal class CastRelay(
    private val http: OkHttpClient,
    private val address: InetAddress,
) : Closeable {
    sealed interface Source {
        val mimeType: String

        data class LocalFile(val file: File, override val mimeType: String) : Source
        data class Remote(val url: String, val headers: Map<String, String>, override val mimeType: String) : Source
    }

    sealed interface SubtitleSource {
        data class LocalFile(val file: File) : SubtitleSource
        data class Remote(val url: String, val headers: Map<String, String>) : SubtitleSource
    }

    private val server = ServerSocket(0, 16, address)

    /** A random path, so other devices on the network can't use the relay to read files or reach providers. */
    private val token = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    @Volatile private var source: Source? = null
    @Volatile private var generation = 0
    @Volatile private var subtitle: SubtitleSource? = null
    @Volatile private var subtitleGeneration = 0
    /** The converted subtitle, fetched once: TVs ask for it more than once. */
    @Volatile private var subtitleBytes: ByteArray? = null

    private val base get() = "http://${address.hostAddress}:${server.localPort}/$token"

    val videoMimeType: String? get() = source?.mimeType
    val hasSubtitle: Boolean get() = subtitle != null

    fun isVideoUrl(url: String): Boolean = source != null && url.startsWith("$base/$generation/")
    private val clients = Collections.synchronizedSet(HashSet<Socket>())

    init {
        thread(name = "cast-relay", isDaemon = true) {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                clients += socket
                thread(name = "cast-relay-client", isDaemon = true) {
                    try {
                        socket.use(::serve)
                    } catch (e: Exception) {
                        // The TV closed the connection (seek, stop) or the source ended.
                        Log.d(TAG, "Relay connection ended: ${e.message}")
                    } finally {
                        clients -= socket
                    }
                }
            }
        }
    }

    /** Points the relay at [newSource] and returns the address to give the TV. */
    fun serveVideo(newSource: Source): String {
        source = newSource
        generation++
        // Connections for the previous video would otherwise keep reading it.
        synchronized(clients) { clients.toList() }.forEach { runCatching { it.close() } }
        // A new path per video, so the TV doesn't treat a new video as the same one.
        return "$base/$generation/video.${extensionFor(newSource.mimeType)}"
    }

    /** Serves [newSource] as SRT and returns its address, or reuses it when it's the subtitle already served. */
    fun serveSubtitle(newSource: SubtitleSource): String {
        if (newSource != subtitle) {
            subtitle = newSource
            subtitleBytes = null
            subtitleGeneration++
        }
        // A new name per subtitle, so the TV doesn't reuse the one it cached.
        return "$base/sub$subtitleGeneration/subtitle.srt"
    }

    fun clearSubtitle() {
        subtitle = null
        subtitleBytes = null
    }

    override fun close() {
        source = null
        server.close()
        synchronized(clients) { clients.toList() }.forEach { runCatching { it.close() } }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 30_000
        val input = socket.getInputStream()
        val requestLine = input.readLine() ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0).orEmpty()
        val path = parts.getOrNull(1).orEmpty()
        val out = socket.getOutputStream()
        if (method != "GET" && method != "HEAD") return out.respond("405 Method Not Allowed")
        if (path.startsWith("/$token/sub$subtitleGeneration/")) return serveSubtitle(method == "HEAD", out)
        val current = source
        if (current == null || !path.startsWith("/$token/$generation/")) return out.respond("404 Not Found")
        val range = headers["range"]
        Log.d(TAG, "TV asked: $method range=$range")
        when (current) {
            is Source.LocalFile -> serveFile(current, method == "HEAD", range, out)
            is Source.Remote -> serveRemote(current, method == "HEAD", range, out)
        }
    }

    private fun serveFile(source: Source.LocalFile, headOnly: Boolean, range: String?, out: OutputStream) {
        val length = source.file.length()
        val requested = parseRange(range, length)
        if (range != null && requested == null) {
            return out.respond("416 Range Not Satisfiable", "Content-Range: bytes */$length\r\nContent-Length: 0\r\n")
        }
        val (start, end) = requested ?: (0L to length - 1)
        val count = end - start + 1
        val status = if (requested != null) "206 Partial Content" else "200 OK"
        val headers = buildString {
            append("Content-Type: ${source.mimeType}\r\n")
            append("Content-Length: $count\r\n")
            append("Accept-Ranges: bytes\r\n")
            if (requested != null) append("Content-Range: bytes $start-$end/$length\r\n")
            append(dlnaHeaders(seekable = true))
            append(captionHeader())
        }
        out.respond(status, headers, keepOpen = !headOnly)
        if (headOnly) return
        RandomAccessFile(source.file, "r").use { file ->
            file.seek(start)
            val buffer = ByteArray(BUFFER_SIZE)
            var remaining = count
            while (remaining > 0) {
                val read = file.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) break
                out.write(buffer, 0, read)
                remaining -= read
            }
        }
        out.flush()
    }

    private fun serveRemote(source: Source.Remote, headOnly: Boolean, range: String?, out: OutputStream) {
        val request = Request.Builder().url(source.url).apply {
            source.headers.forEach { (name, value) -> header(name, value) }
            if (range != null) header("Range", range)
            // Compressed bodies would break byte ranges.
            header("Accept-Encoding", "identity")
            if (headOnly) head()
        }.build()
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            Log.w(TAG, "Relay couldn't reach the source", e)
            return out.respond("502 Bad Gateway")
        }
        response.use {
            if (headOnly && !response.isSuccessful) {
                // Plenty of servers refuse HEAD but serve GET fine; tell the TV enough to go ahead.
                return out.respond("200 OK", "Content-Type: ${source.mimeType}\r\n" + dlnaHeaders(seekable = false))
            }
            if (!response.isSuccessful) {
                Log.w(TAG, "Relay: source answered ${response.code}")
                val status = if (response.code == 416) "416 Range Not Satisfiable" else "502 Bad Gateway"
                return out.respond(status)
            }
            val upstreamType = response.header("Content-Type")
            val type = upstreamType?.takeUnless { it.startsWith("application/octet-stream") || it.startsWith("text/") }
                ?: source.mimeType
            val seekable = response.header("Accept-Ranges")?.contains("bytes") == true || response.code == 206
            val headers = buildString {
                append("Content-Type: $type\r\n")
                response.header("Content-Length")?.let { append("Content-Length: $it\r\n") }
                response.header("Content-Range")?.let { append("Content-Range: $it\r\n") }
                if (seekable) append("Accept-Ranges: bytes\r\n")
                append(dlnaHeaders(seekable))
                append(captionHeader())
            }
            val status = if (response.code == 206) "206 Partial Content" else "200 OK"
            out.respond(status, headers, keepOpen = !headOnly)
            if (headOnly) return
            response.body.byteStream().copyTo(out, BUFFER_SIZE)
            out.flush()
        }
    }

    /** Samsung TVs find the subtitle through this header on the video. */
    private fun captionHeader(): String =
        if (subtitle != null) "CaptionInfo.sec: $base/sub$subtitleGeneration/subtitle.srt\r\n" else ""

    private fun serveSubtitle(headOnly: Boolean, out: OutputStream) {
        val bytes = subtitleBytes ?: loadSubtitle()?.also { subtitleBytes = it }
            ?: return out.respond("404 Not Found")
        out.respond(
            "200 OK",
            "Content-Type: text/srt; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
                "transferMode.dlna.org: Interactive\r\n",
            keepOpen = !headOnly,
        )
        if (!headOnly) {
            out.write(bytes)
            out.flush()
        }
    }

    private fun loadSubtitle(): ByteArray? {
        val text = when (val current = subtitle ?: return null) {
            is SubtitleSource.LocalFile -> runCatching { current.file.readText() }.getOrNull()
            is SubtitleSource.Remote -> try {
                val request = Request.Builder().url(current.url)
                    .apply { current.headers.forEach { (name, value) -> header(name, value) } }
                    .build()
                http.newCall(request).execute().use { if (it.isSuccessful) it.body.string() else null }
            } catch (e: IOException) {
                Log.w(TAG, "Couldn't fetch the subtitle", e)
                null
            }
        } ?: return null
        return toSrt(text).toByteArray(Charsets.UTF_8)
    }

    private fun OutputStream.respond(status: String, headers: String = "Content-Length: 0\r\n", keepOpen: Boolean = false) {
        write("HTTP/1.1 $status\r\n${headers}Connection: close\r\n\r\n".toByteArray())
        flush()
        if (!keepOpen) close()
    }

    /** Reads one header line; requests are plain ASCII, so a byte-at-a-time read is fine. */
    private fun InputStream.readLine(): String? {
        val line = StringBuilder()
        while (true) {
            val b = read()
            if (b == -1) return if (line.isEmpty()) null else line.toString()
            if (b == '\n'.code) return line.toString().trimEnd('\r')
            if (line.length > 8192) return null
            line.append(b.toChar())
        }
    }

    internal companion object {
        private const val TAG = "CastRelay"
        private const val BUFFER_SIZE = 64 * 1024

        /** Start and end (inclusive) of a "bytes=start-end" request, or null when absent or unusable. */
        fun parseRange(range: String?, length: Long): Pair<Long, Long>? {
            if (range == null || !range.startsWith("bytes=") || length <= 0) return null
            val spec = range.removePrefix("bytes=").substringBefore(',').trim()
            val dash = spec.indexOf('-')
            if (dash < 0) return null
            val startText = spec.substring(0, dash).trim()
            val endText = spec.substring(dash + 1).trim()
            val (start, end) = if (startText.isEmpty()) {
                // "bytes=-500": the last 500 bytes.
                val suffix = endText.toLongOrNull() ?: return null
                (length - suffix).coerceAtLeast(0) to length - 1
            } else {
                val start = startText.toLongOrNull() ?: return null
                start to (endText.toLongOrNull()?.coerceAtMost(length - 1) ?: (length - 1))
            }
            return if (start in 0..end && start < length) start to end else null
        }

        /**
         * What DLNA TVs (LG's among them) look for before they'll play: OP=01 says byte seeking
         * works, OP=00 that it doesn't.
         */
        fun dlnaHeaders(seekable: Boolean): String =
            "transferMode.dlna.org: Streaming\r\n" +
                "contentFeatures.dlna.org: ${contentFeatures(seekable)}\r\n"

        fun contentFeatures(seekable: Boolean): String =
            "DLNA.ORG_OP=${if (seekable) "01" else "00"};DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

        private val VTT_TIME = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{3})""")

        /**
         * TVs read SRT; online subtitles are often WebVTT. Converts VTT cues to numbered SRT cues with
         * comma milliseconds and no position settings or styling tags; SRT passes through unchanged.
         */
        fun toSrt(text: String): String {
            val clean = text.removePrefix("\uFEFF").replace("\r\n", "\n")
            if (!clean.trimStart().startsWith("WEBVTT")) return clean
            val out = StringBuilder()
            var number = 0
            for (block in clean.split(Regex("\n{2,}"))) {
                val lines = block.lines().filter { it.isNotBlank() }
                val timingIndex = lines.indexOfFirst { "-->" in it }
                if (timingIndex < 0) continue // header, NOTE or STYLE block
                val (from, to) = lines[timingIndex].split("-->").map { it.trim().substringBefore(' ') }
                    .takeIf { it.size == 2 } ?: continue
                val start = vttTime(from) ?: continue
                val end = vttTime(to) ?: continue
                val cue = lines.drop(timingIndex + 1).joinToString("\n") { it.replace(Regex("</?(c|v|lang|ruby|rt)[^>]*>"), "") }
                number++
                out.append(number).append('\n').append(start).append(" --> ").append(end).append('\n').append(cue).append("\n\n")
            }
            return out.toString()
        }

        private fun vttTime(value: String): String? {
            val match = VTT_TIME.matchEntire(value) ?: return null
            val (h, m, sec, ms) = match.destructured
            return "%02d:%02d:%02d,%s".format(h.ifEmpty { "0" }.toInt(), m.toInt(), sec.toInt(), ms)
        }

        fun mimeTypeFor(path: String): String {
            val lower = path.substringBefore('?').lowercase()
            return when {
                lower.endsWith(".mkv") -> "video/x-matroska"
                lower.endsWith(".webm") -> "video/webm"
                lower.endsWith(".avi") -> "video/x-msvideo"
                lower.endsWith(".ts") || lower.endsWith(".m2ts") -> "video/mp2t"
                lower.endsWith(".mov") -> "video/quicktime"
                else -> "video/mp4"
            }
        }

        fun extensionFor(mimeType: String): String = when (mimeType) {
            "video/x-matroska" -> "mkv"
            "video/webm" -> "webm"
            "video/x-msvideo" -> "avi"
            "video/mp2t" -> "ts"
            "video/quicktime" -> "mov"
            else -> "mp4"
        }
    }
}
