package com.nuvio.app.features.cast

import java.io.RandomAccessFile

/**
 * Picks the audio and subtitle a TV plays from a Matroska (MKV) file by rewriting the tracks'
 * "default" and "forced" flags as the relay sends the file. DLNA has no command for choosing
 * tracks, but TVs start with the tracks the file marks as default.
 *
 * Every edit keeps the file the same length, so byte ranges and seeking are unaffected: a flag
 * that's already in the file has its value changed, and a flag that isn't is written over an
 * element that can go without changing playback (a lacing flag, padding and the like).
 */
internal object MkvTrackFlags {
    /** Replace [bytes] at [offset] in the file. */
    data class Patch(val offset: Long, val bytes: ByteArray)

    data class Track(
        val type: Int,
        val language: String?,
        val name: String?,
        val defaultFlag: Element?,
        val forcedFlag: Element?,
        /** Elements in the track entry that can be overwritten to add a missing flag. */
        val spare: List<Element>,
        val number: Long = 0,
        val codecId: String? = null,
        /** How blocks are compressed: 0 zlib, 3 header stripping ([compressionSettings] is the stripped header). */
        val compression: Int? = null,
        val compressionSettings: ByteArray? = null,
    )

    /** An element's position: [start] of its ID, [dataStart] of its value, total [length]. */
    data class Element(val start: Long, val dataStart: Long, val length: Long) {
        val dataSize get() = length - (dataStart - start)
    }

    const val TYPE_AUDIO = 2
    const val TYPE_SUBTITLE = 17

    private const val ID_EBML = 0x1A45DFA3L
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_SEEK_HEAD = 0x114D9B74L
    private const val ID_SEEK = 0x4DBBL
    private const val ID_SEEK_ID = 0x53ABL
    private const val ID_SEEK_POSITION = 0x53ACL
    private const val ID_TRACKS = 0x1654AE6BL
    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_TRACK_ENTRY = 0xAEL
    private const val ID_TRACK_TYPE = 0x83L
    private const val ID_FLAG_DEFAULT = 0x88L
    private const val ID_FLAG_FORCED = 0x55AAL
    private const val ID_LANGUAGE = 0x22B59CL
    private const val ID_LANGUAGE_BCP47 = 0x22B59DL
    private const val ID_NAME = 0x536EL
    private const val ID_VOID = 0xECL
    private const val ID_TRACK_NUMBER = 0xD7L
    private const val ID_CODEC_ID = 0x86L
    private const val ID_CONTENT_ENCODINGS = 0x6D80L
    private const val ID_CONTENT_ENCODING = 0x6240L
    private const val ID_CONTENT_COMPRESSION = 0x5034L
    private const val ID_CONTENT_COMP_ALGO = 0x4254L
    private const val ID_CONTENT_COMP_SETTINGS = 0x4255L
    private const val ID_INFO = 0x1549A966L
    private const val ID_TIMESTAMP_SCALE = 0x2AD7B1L
    private const val ID_CLUSTER_TIMESTAMP = 0xE7L
    private const val ID_SIMPLE_BLOCK = 0xA3L
    private const val ID_BLOCK_GROUP = 0xA0L
    private const val ID_BLOCK = 0xA1L
    private const val ID_BLOCK_DURATION = 0x9BL

    /** Elements that sit directly in the segment; one of these ends a cluster of unknown size. */
    private val TOP_LEVEL_IDS = setOf(
        ID_CLUSTER, 0x1C53BB6BL, 0x1254C367L, 0x1043A770L, 0x1941A469L, ID_TRACKS, ID_INFO, ID_SEEK_HEAD,
    )

    /** Elements whose loss doesn't change playback: they repeat a default or only hint at buffering. */
    private val SPARE_IDS = setOf(
        0x9CL, // FlagLacing: blocks say themselves whether they're laced
        0xAAL, // CodecDecodeAll
        0x6DE7L, // MinCache
        0x6DF8L, // MaxCache
        0x55EEL, // MaxBlockAdditionID
        ID_VOID,
    )

    /** The file's tracks in order, or null when it isn't a readable Matroska file. */
    fun readTracks(file: RandomAccessFile): List<Track>? = runCatching {
        val reader = Reader(file)
        val ebml = reader.element(0) ?: return null
        if (ebml.id != ID_EBML) return null
        val segment = reader.element(ebml.end) ?: return null
        if (segment.id != ID_SEGMENT) return null
        val segmentEnd = if (segment.unknownSize) file.length() else minOf(segment.end, file.length())
        var tracks: Header? = null
        var tracksFromSeekHead: Long? = null
        var position = segment.dataStart
        // Tracks normally come before the first cluster; the seek head says where they are if not.
        while (position < segmentEnd && tracks == null) {
            val child = reader.element(position) ?: break
            when (child.id) {
                ID_TRACKS -> tracks = child
                ID_SEEK_HEAD -> if (tracksFromSeekHead == null) {
                    tracksFromSeekHead = reader.seekPosition(child, ID_TRACKS)?.let { segment.dataStart + it }
                }
                ID_CLUSTER -> break
            }
            if (child.unknownSize) break
            position = child.end
        }
        if (tracks == null) {
            tracks = tracksFromSeekHead?.let { reader.element(it) }?.takeIf { it.id == ID_TRACKS } ?: return null
        }
        reader.children(tracks).filter { it.id == ID_TRACK_ENTRY }.map { entry -> reader.track(entry) }
    }.getOrNull()

    /**
     * Edits that make the [audio]th audio track the default, and the [subtitle]th subtitle track
     * default and forced, or no subtitle track at all when [subtitle] is null. Null for [audio] leaves
     * audio as it is, and [touchSubtitles] false leaves subtitles as they are. Track numbers count from 0 within their type, in file order.
     */
    fun patches(tracks: List<Track>, audio: Int?, subtitle: Int?, touchSubtitles: Boolean = true): List<Patch> {
        val result = mutableListOf<Patch>()
        if (audio != null) {
            tracks.filter { it.type == TYPE_AUDIO }.forEachIndexed { index, track ->
                setFlag(track, ID_FLAG_DEFAULT, index == audio, result)
            }
        }
        if (!touchSubtitles) return result
        tracks.filter { it.type == TYPE_SUBTITLE }.forEachIndexed { index, track ->
            val chosen = index == subtitle
            setFlag(track, ID_FLAG_DEFAULT, chosen, result)
            setFlag(track, ID_FLAG_FORCED, chosen, result)
        }
        return result
    }

    private fun setFlag(track: Track, id: Long, on: Boolean, out: MutableList<Patch>) {
        val existing = if (id == ID_FLAG_DEFAULT) track.defaultFlag else track.forcedFlag
        if (existing != null) {
            val size = existing.dataSize.toInt()
            if (size in 1..8) out += Patch(existing.dataStart, ByteArray(size).also { if (on) it[size - 1] = 1 })
            return
        }
        // A missing default flag means "default", a missing forced flag "not forced".
        val implied = id == ID_FLAG_DEFAULT
        if (on == implied) return
        val idBytes = if (id == ID_FLAG_DEFAULT) byteArrayOf(0x88.toByte()) else byteArrayOf(0x55, 0xAA.toByte())
        val spare = track.spare.firstOrNull { it.length >= idBytes.size + 2 && out.none { p -> p.offset == it.start } } ?: return
        out += Patch(spare.start, flagElement(idBytes, on, spare.length.toInt()))
    }

    /**
     * The [subtitleIndex]th subtitle track as SRT, for TVs that ignore a file's default subtitle but
     * show a subtitle file sent alongside it. Text tracks only (SRT, ASS/SSA, WebVTT); picture-based
     * subtitles (PGS, VobSub) give null. Walks every cluster, reading block headers and only this
     * track's text.
     */
    fun extractSubtitle(file: RandomAccessFile, subtitleIndex: Int): String? = runCatching {
        val tracks = readTracks(file) ?: return null
        val track = tracks.filter { it.type == TYPE_SUBTITLE }.getOrNull(subtitleIndex) ?: return null
        val ass = when (track.codecId) {
            "S_TEXT/UTF8", "S_TEXT/WEBVTT" -> false
            "S_TEXT/ASS", "S_TEXT/SSA", "S_ASS", "S_SSA" -> true
            else -> return null
        }
        val reader = Reader(file)
        val ebml = reader.element(0) ?: return null
        val segment = reader.element(ebml.end)?.takeIf { it.id == ID_SEGMENT } ?: return null
        val segmentEnd = if (segment.unknownSize) file.length() else minOf(segment.end, file.length())
        var scaleNs = 1_000_000L
        val cues = mutableListOf<Cue>()
        var position = segment.dataStart
        while (position < segmentEnd) {
            val element = reader.element(position) ?: break
            when (element.id) {
                ID_INFO -> reader.children(element).firstOrNull { it.id == ID_TIMESTAMP_SCALE }
                    ?.let { scaleNs = reader.readUInt(it).takeIf { v -> v > 0 } ?: scaleNs }
                ID_CLUSTER -> {
                    position = readCluster(reader, element, segmentEnd, track, scaleNs, cues)
                    continue
                }
            }
            if (element.unknownSize) break
            position = element.end
        }
        if (cues.isEmpty()) return null
        toSrt(cues.map { if (ass) it.copy(text = assText(it.text)) else it })
    }.getOrNull()

    private data class Cue(val startMs: Long, val durationMs: Long?, val text: String)

    /** Reads one cluster's blocks for [track] into [cues]; returns where the next top-level element starts. */
    private fun readCluster(
        reader: Reader,
        cluster: Header,
        segmentEnd: Long,
        track: Track,
        scaleNs: Long,
        cues: MutableList<Cue>,
    ): Long {
        val end = if (cluster.unknownSize) segmentEnd else cluster.end
        var clusterTicks = 0L
        var position = cluster.dataStart
        while (position < end) {
            val child = reader.element(position) ?: return end
            // A cluster of unknown size ends where the next top-level element begins.
            if (cluster.unknownSize && child.id in TOP_LEVEL_IDS) return position
            when (child.id) {
                ID_CLUSTER_TIMESTAMP -> clusterTicks = reader.readUInt(child)
                ID_SIMPLE_BLOCK -> readBlock(reader, child, null, clusterTicks, track, scaleNs, cues)
                ID_BLOCK_GROUP -> {
                    val parts = reader.children(child)
                    val duration = parts.firstOrNull { it.id == ID_BLOCK_DURATION }?.let { reader.readUInt(it) }
                    parts.firstOrNull { it.id == ID_BLOCK }?.let {
                        readBlock(reader, it, duration, clusterTicks, track, scaleNs, cues)
                    }
                }
            }
            if (child.unknownSize) return end
            position = child.end
        }
        return end
    }

    private fun readBlock(
        reader: Reader,
        block: Header,
        durationTicks: Long?,
        clusterTicks: Long,
        track: Track,
        scaleNs: Long,
        cues: MutableList<Cue>,
    ) {
        val (number, width) = reader.vint(block.dataStart, keepMarker = false) ?: return
        if (number != track.number) return
        val timeStart = block.dataStart + width
        val relative = ((reader.u8(timeStart) shl 8) or reader.u8(timeStart + 1)).toShort().toLong()
        val flags = reader.u8(timeStart + 2)
        if (flags and 0x06 != 0) return // laced blocks don't occur in text tracks
        val payloadStart = timeStart + 3
        val payload = reader.bytes(payloadStart, (block.end - payloadStart).toInt()) ?: return
        val decoded = when (track.compression) {
            null -> payload
            0 -> inflate(payload) ?: return
            3 -> (track.compressionSettings ?: ByteArray(0)) + payload
            else -> return
        }
        val text = String(decoded, Charsets.UTF_8).trimEnd('\u0000').trim()
        if (text.isEmpty()) return
        cues += Cue(
            startMs = (clusterTicks + relative) * scaleNs / 1_000_000,
            durationMs = durationTicks?.let { it * scaleNs / 1_000_000 },
            text = text,
        )
    }

    private fun inflate(data: ByteArray): ByteArray? = runCatching {
        java.util.zip.InflaterInputStream(data.inputStream()).use { it.readBytes() }
    }.getOrNull()

    /** The dialogue of an ASS/SSA block ("ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text") without styling. */
    private fun assText(block: String): String {
        val text = block.split(',', limit = 9).getOrNull(8) ?: block
        return text.replace(Regex("\\{[^}]*\\}"), "")
            .replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
            .trim()
    }

    private fun toSrt(cues: List<Cue>): String {
        val sorted = cues.sortedBy { it.startMs }
        val out = StringBuilder()
        sorted.forEachIndexed { index, cue ->
            val next = sorted.getOrNull(index + 1)?.startMs
            var endMs = cue.durationMs?.let { cue.startMs + it } ?: next ?: (cue.startMs + 4000)
            if (endMs <= cue.startMs) endMs = cue.startMs + 2000
            out.append(index + 1).append('\n')
                .append(srtTime(cue.startMs)).append(" --> ").append(srtTime(endMs)).append('\n')
                .append(cue.text).append("\n\n")
        }
        return out.toString()
    }

    private fun srtTime(ms: Long): String {
        val total = ms.coerceAtLeast(0)
        return "%02d:%02d:%02d,%03d".format(total / 3_600_000, total / 60_000 % 60, total / 1000 % 60, total % 1000)
    }

    /** A flag element exactly [length] bytes long, padded with a Void element when it's longer than a flag can be. */
    private fun flagElement(idBytes: ByteArray, on: Boolean, length: Int): ByteArray {
        val dataSize = length - idBytes.size - 1
        if (dataSize in 1..8) {
            return idBytes + byteArrayOf((0x80 or dataSize).toByte()) +
                ByteArray(dataSize).also { if (on) it[dataSize - 1] = 1 }
        }
        val flag = idBytes + byteArrayOf(0x81.toByte(), if (on) 1 else 0)
        val voidLength = length - flag.size
        val voidData = voidLength - 2
        // Spare elements in a track entry are small; a one-byte size covers up to 126 bytes of padding.
        return flag + byteArrayOf(ID_VOID.toByte(), (0x80 or voidData).toByte()) + ByteArray(voidData)
    }

    private class Header(val id: Long, val start: Long, val dataStart: Long, val size: Long, val unknownSize: Boolean) {
        val end get() = dataStart + size
    }

    /**
     * Reads EBML from the file through a 256 KB window, so walking a whole episode's blocks touches
     * the file in large sequential reads rather than one system call per byte.
     */
    private class Reader(private val file: RandomAccessFile) {
        private val length = file.length()
        private val window = ByteArray(256 * 1024)
        private var windowStart = -1L
        private var windowSize = 0

        fun u8(position: Long): Int {
            if (position < 0 || position >= length) return -1
            if (windowStart < 0 || position < windowStart || position >= windowStart + windowSize) {
                file.seek(position)
                windowSize = maxOf(0, file.read(window, 0, window.size))
                windowStart = position
                if (windowSize == 0) return -1
            }
            return window[(position - windowStart).toInt()].toInt() and 0xFF
        }

        fun bytes(position: Long, count: Int): ByteArray? {
            if (count < 0 || position + count > length) return null
            return ByteArray(count) { i -> u8(position + i).toByte() }
        }

        fun element(position: Long): Header? {
            val (id, idWidth) = vint(position, keepMarker = true) ?: return null
            val (size, width) = vint(position + idWidth, keepMarker = false) ?: return null
            // All ones means "size unknown" (the element runs to the end of its parent).
            val unknown = size == (1L shl (7 * width)) - 1
            return Header(id, position, position + idWidth + width, size, unknown)
        }

        /** An EBML variable-length integer at [position]: (value, width). */
        fun vint(position: Long, keepMarker: Boolean): Pair<Long, Int>? {
            val first = u8(position)
            if (first <= 0) return null
            val width = Integer.numberOfLeadingZeros(first) - 23
            if (width !in 1..8) return null
            var value = if (keepMarker) first.toLong() else (first and (0xFF shr width)).toLong()
            for (i in 1 until width) {
                val b = u8(position + i)
                if (b < 0) return null
                value = (value shl 8) or b.toLong()
            }
            return value to width
        }

        fun children(parent: Header): List<Header> {
            val result = mutableListOf<Header>()
            var position = parent.dataStart
            while (position < parent.end) {
                val child = element(position) ?: break
                if (child.unknownSize || child.end > parent.end) break
                result += child
                position = child.end
            }
            return result
        }

        fun seekPosition(seekHead: Header, target: Long): Long? {
            for (seek in children(seekHead).filter { it.id == ID_SEEK }) {
                val parts = children(seek)
                val id = parts.firstOrNull { it.id == ID_SEEK_ID }?.let { readUInt(it) } ?: continue
                if (id == target) return parts.firstOrNull { it.id == ID_SEEK_POSITION }?.let { readUInt(it) }
            }
            return null
        }

        fun track(entry: Header): Track {
            var type = 0
            var language: String? = null
            var languageBcp47: String? = null
            var name: String? = null
            var defaultFlag: Element? = null
            var forcedFlag: Element? = null
            var number = 0L
            var codecId: String? = null
            var compression: Int? = null
            var compressionSettings: ByteArray? = null
            val spare = mutableListOf<Element>()
            for (child in children(entry)) {
                val element = Element(child.start, child.dataStart, child.end - child.start)
                when (child.id) {
                    ID_TRACK_NUMBER -> number = readUInt(child)
                    ID_CODEC_ID -> codecId = readString(child)
                    ID_CONTENT_ENCODINGS -> {
                        // Only compression is handled; an encrypted track has no usable text.
                        val compressionElement = children(child).filter { it.id == ID_CONTENT_ENCODING }
                            .flatMap { children(it) }
                            .firstOrNull { it.id == ID_CONTENT_COMPRESSION }
                        if (compressionElement != null) {
                            val parts = children(compressionElement)
                            compression = parts.firstOrNull { it.id == ID_CONTENT_COMP_ALGO }?.let { readUInt(it).toInt() } ?: 0
                            compressionSettings = parts.firstOrNull { it.id == ID_CONTENT_COMP_SETTINGS }
                                ?.let { bytes(it.dataStart, it.size.toInt()) }
                        }
                    }
                    ID_TRACK_TYPE -> type = readUInt(child).toInt()
                    ID_LANGUAGE -> language = readString(child)
                    ID_LANGUAGE_BCP47 -> languageBcp47 = readString(child)
                    ID_NAME -> name = readString(child)
                    ID_FLAG_DEFAULT -> defaultFlag = element
                    ID_FLAG_FORCED -> forcedFlag = element
                    in SPARE_IDS -> if (element.length <= 128) spare += element
                }
            }
            return Track(
                type, languageBcp47 ?: language, name, defaultFlag, forcedFlag, spare,
                number, codecId, compression, compressionSettings,
            )
        }

        fun readUInt(header: Header): Long {
            var value = 0L
            for (i in 0 until header.size.toInt().coerceAtMost(8)) value = (value shl 8) or u8(header.dataStart + i).toLong()
            return value
        }

        private fun readString(header: Header): String? {
            if (header.size !in 1..256) return null
            val bytes = bytes(header.dataStart, header.size.toInt()) ?: return null
            return String(bytes, Charsets.UTF_8).trimEnd('\u0000').ifBlank { null }
        }
    }
}
