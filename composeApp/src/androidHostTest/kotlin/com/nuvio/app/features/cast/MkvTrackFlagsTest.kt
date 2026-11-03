package com.nuvio.app.features.cast

import java.io.File
import java.io.RandomAccessFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MkvTrackFlagsTest {
    // Made with ffmpeg: video, audio eng (default) + jpn, subtitles eng (default) + spa.
    private val source = File("src/androidHostTest/resources/cast/tracks.mkv")

    private fun patchedCopy(name: String, audio: Int?, subtitle: Int?): File {
        val tracks = RandomAccessFile(source, "r").use { MkvTrackFlags.readTracks(it) }
        assertNotNull(tracks)
        val bytes = source.readBytes()
        for (patch in MkvTrackFlags.patches(tracks, audio, subtitle)) {
            patch.bytes.copyInto(bytes, patch.offset.toInt())
        }
        // Left in build/ so the result can be checked with ffprobe too.
        return File("build/cast-test/$name.mkv").apply {
            parentFile.mkdirs()
            writeBytes(bytes)
        }
    }

    private fun flags(file: File): List<Pair<Boolean, Boolean>> =
        RandomAccessFile(file, "r").use { raf ->
            MkvTrackFlags.readTracks(raf)!!.filter { it.type != 1 }.map { track ->
                fun RandomAccessFile.flag(element: MkvTrackFlags.Element?, missing: Boolean): Boolean {
                    if (element == null) return missing
                    seek(element.dataStart)
                    var value = 0
                    repeat(element.dataSize.toInt()) { value = (value shl 8) or read() }
                    return value != 0
                }
                raf.flag(track.defaultFlag, missing = true) to raf.flag(track.forcedFlag, missing = false)
            }
        }

    @Test
    fun readsTracksInFileOrder() {
        val tracks = RandomAccessFile(source, "r").use { MkvTrackFlags.readTracks(it) }
        assertNotNull(tracks)
        assertEquals(listOf(1, 2, 2, 17, 17), tracks.map { it.type })
        assertEquals(listOf("eng", "jpn", "eng", "spa"), tracks.drop(1).map { it.language })
    }

    @Test
    fun picksSecondAudioAndSecondSubtitle() {
        val file = patchedCopy("audio1-sub1", audio = 1, subtitle = 1)
        // (default, forced) for audio eng, audio jpn, sub eng, sub spa
        assertEquals(listOf(false to false, true to false, false to false, true to true), flags(file))
        assertEquals(source.length(), file.length())
    }

    @Test
    fun turnsSubtitlesOffAndKeepsAudio() {
        val file = patchedCopy("subs-off", audio = null, subtitle = null)
        assertEquals(listOf(true to false, false to false, false to false, false to false), flags(file))
    }

    @Test
    fun rejectsFilesThatArentMatroska() {
        val notMkv = File("build/cast-test/not.mkv").apply { parentFile.mkdirs(); writeText("hello") }
        assertNull(RandomAccessFile(notMkv, "r").use { MkvTrackFlags.readTracks(it) })
    }

    @Test
    fun patchesStayInsideTheTracksElement() {
        val tracks = RandomAccessFile(source, "r").use { MkvTrackFlags.readTracks(it) }!!
        val patches = MkvTrackFlags.patches(tracks, audio = 1, subtitle = 0)
        assertTrue(patches.isNotEmpty())
        assertTrue(patches.all { it.offset > 0 && it.offset + it.bytes.size < source.length() })
    }
}
