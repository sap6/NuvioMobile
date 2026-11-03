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

    private class Reader(private val file: RandomAccessFile) {
        fun element(position: Long): Header? {
            if (position >= file.length()) return null
            file.seek(position)
            val (id, _) = vint(keepMarker = true) ?: return null
            val (size, width) = vint(keepMarker = false) ?: return null
            // All ones means "size unknown" (the element runs to the end of its parent).
            val unknown = size == (1L shl (7 * width)) - 1
            return Header(id, position, file.filePointer, size, unknown)
        }

        /** An EBML variable-length integer: (value, width). */
        private fun vint(keepMarker: Boolean): Pair<Long, Int>? {
            val first = file.read()
            if (first <= 0) return null
            val width = Integer.numberOfLeadingZeros(first) - 23
            if (width !in 1..8) return null
            var value = if (keepMarker) first.toLong() else (first and (0xFF shr width)).toLong()
            repeat(width - 1) {
                val b = file.read()
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
            val spare = mutableListOf<Element>()
            for (child in children(entry)) {
                val element = Element(child.start, child.dataStart, child.end - child.start)
                when (child.id) {
                    ID_TRACK_TYPE -> type = readUInt(child).toInt()
                    ID_LANGUAGE -> language = readString(child)
                    ID_LANGUAGE_BCP47 -> languageBcp47 = readString(child)
                    ID_NAME -> name = readString(child)
                    ID_FLAG_DEFAULT -> defaultFlag = element
                    ID_FLAG_FORCED -> forcedFlag = element
                    in SPARE_IDS -> if (element.length <= 128) spare += element
                }
            }
            return Track(type, languageBcp47 ?: language, name, defaultFlag, forcedFlag, spare)
        }

        private fun readUInt(header: Header): Long {
            file.seek(header.dataStart)
            var value = 0L
            repeat(header.size.toInt().coerceAtMost(8)) { value = (value shl 8) or file.read().toLong() }
            return value
        }

        private fun readString(header: Header): String? {
            if (header.size !in 1..256) return null
            file.seek(header.dataStart)
            val bytes = ByteArray(header.size.toInt())
            file.readFully(bytes)
            return String(bytes, Charsets.UTF_8).trimEnd('\u0000').ifBlank { null }
        }
    }
}
