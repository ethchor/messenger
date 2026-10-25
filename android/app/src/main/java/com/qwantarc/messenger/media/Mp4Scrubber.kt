package com.qwantarc.messenger.media

import java.io.File
import java.io.RandomAccessFile

/**
 * Blanks what an MP4 says about where, when and on what it was made, in
 * place. `udta` and `meta` boxes, where phones put the location and the
 * camera, and `uuid` boxes, where XMP goes, become `free` boxes with their
 * contents zeroed; the creation and modification times in `mvhd`, `tkhd`
 * and `mdhd` become 0. No size or offset changes, so the file still plays,
 * and it works on any MP4, re-encoded or not.
 */
object Mp4Scrubber {
    private val containers = setOf("moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "mvex", "moof", "traf")
    private val blanked = setOf("udta", "meta", "uuid")
    private val timed = setOf("mvhd", "tkhd", "mdhd")

    /** False if the file isn't an MP4 this can read; it may be partly scrubbed then. */
    fun scrub(file: File): Boolean = RandomAccessFile(file, "rw").use { mp4 ->
        if (mp4.length() < 8) return@use false
        mp4.seek(4)
        if (type(mp4) != "ftyp") return@use false
        walk(mp4, 0, mp4.length())
    }

    private fun walk(mp4: RandomAccessFile, start: Long, end: Long): Boolean {
        var at = start
        while (at + 8 <= end) {
            mp4.seek(at)
            var size = mp4.readInt().toLong() and 0xFFFFFFFFL
            val type = type(mp4)
            var header = 8L
            when (size) {
                1L -> {
                    size = mp4.readLong()
                    header = 16
                }
                0L -> size = end - at
            }
            if (size < header || at + size > end) return false
            when (type) {
                in blanked -> blank(mp4, at, header, size)
                in timed -> zeroTimes(mp4, at + header)
                in containers -> if (!walk(mp4, at + header, at + size)) return false
            }
            at += size
        }
        return true
    }

    private fun type(mp4: RandomAccessFile): String =
        ByteArray(4).also { mp4.readFully(it) }.toString(Charsets.ISO_8859_1)

    private fun blank(mp4: RandomAccessFile, at: Long, header: Long, size: Long) {
        mp4.seek(at + 4)
        mp4.write("free".toByteArray(Charsets.ISO_8859_1))
        mp4.seek(at + header)
        val zeros = ByteArray(16 * 1024)
        var left = size - header
        while (left > 0) {
            val count = minOf(left, zeros.size.toLong()).toInt()
            mp4.write(zeros, 0, count)
            left -= count
        }
    }

    /** Version 1 boxes have 64-bit times, version 0 boxes 32-bit ones, after the version and flags. */
    private fun zeroTimes(mp4: RandomAccessFile, body: Long) {
        mp4.seek(body)
        val version = mp4.read()
        mp4.seek(body + 4)
        if (version == 1) {
            mp4.writeLong(0)
            mp4.writeLong(0)
        } else {
            mp4.writeInt(0)
            mp4.writeInt(0)
        }
    }
}
