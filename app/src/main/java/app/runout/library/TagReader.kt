package app.runout.library

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.charset.Charset

/** Everything the shelf needs from one audio file. Unknown values stay null. */
data class AudioInfo(
    val codec: String,
    val title: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    val track: Int? = null,
    val disc: Int? = null,
    val year: Int? = null,
    val durationMs: Long = 0,
    val sampleRate: Int? = null,
    val bits: Int? = null,
    val channels: Int? = null,
    val bitrateKbps: Int? = null,
    val license: String? = null,
    val albumGainDb: Float? = null,
    val trackGainDb: Float? = null,
    val picture: ByteArray? = null,
) {
    override fun equals(other: Any?) = other is AudioInfo && toString() == other.toString()
    override fun hashCode() = toString().hashCode()
    override fun toString() =
        "AudioInfo($codec, $title, $artist, $albumArtist, $album, $track, $disc, $year, $durationMs, $sampleRate, $bits, $channels, $bitrateKbps, $license, $albumGainDb, $trackGainDb, pic=${picture?.size})"
}

/**
 * Reads tags, duration and stream format straight from FLAC, MP3 and WAV files.
 *
 * The scanner used to rely on Android's MediaMetadataRetriever alone. On some phones and storage
 * providers it returns nothing for files opened through the folder picker, and every track was then
 * dropped for having no duration — so no albums appeared. These readers parse the files directly.
 */
object TagReader {

    private const val MAX_BLOCK = 24 * 1024 * 1024

    fun read(input: InputStream, ext: String, fileSize: Long, wantPicture: Boolean): AudioInfo? = runCatching {
        val s = Stream(BufferedInputStream(input, 64 * 1024))
        when (ext.lowercase()) {
            "flac" -> readFlac(s, fileSize, wantPicture)
            "mp3" -> readMp3(s, fileSize, wantPicture)
            "wav" -> readWav(s)
            else -> null
        }
    }.onFailure { lastError = it }.getOrNull()

    /** The last parse failure, for diagnostics. */
    @Volatile
    var lastError: Throwable? = null
        private set

    // ---------------------------------------------------------------- FLAC

    private fun readFlac(s: Stream, fileSize: Long, wantPicture: Boolean): AudioInfo? {
        var magic = s.bytes(4)
        if (String(magic, 0, 3, Charsets.US_ASCII) == "ID3") {
            // Rare: an ID3 tag in front of the FLAC stream.
            val rest = s.bytes(6)
            if (rest[2].toInt() and 0x80 != 0 || rest[3].toInt() and 0x80 != 0 ||
                rest[4].toInt() and 0x80 != 0 || rest[5].toInt() and 0x80 != 0) return null
            val size = synchsafe(rest, 2)
            if (size > MAX_BLOCK) return null
            s.skip(size.toLong())
            if (rest[1].toInt() and 0x10 != 0) s.skip(10) // ID3v2.4 footer
            magic = s.bytes(4)
        }
        if (String(magic, Charsets.US_ASCII) != "fLaC") return null

        var info = AudioInfo(codec = "FLAC")
        val tags = mutableMapOf<String, String>()
        var picture: ByteArray? = null
        var pictureType = -1
        var streamInfoSeen = false
        var blocks = 0
        var minFrameBytes = 0
        while (true) {
            val header = s.u8()
            val last = header and 0x80 != 0
            val type = header and 0x7F
            val length = s.u24be()
            if (length > MAX_BLOCK) return null
            if (++blocks > 1024 || (blocks == 1 && type != 0) || type == 127) return null
            when (type) {
                0 -> {
                    if (streamInfoSeen || length != 34) return null
                    streamInfoSeen = true
                    val si = s.bytes(length)
                    fun u(i: Int) = si[i].toInt() and 0xFF
                    val minBlock = (u(0) shl 8) or u(1)
                    val maxBlock = (u(2) shl 8) or u(3)
                    val minFrame = (u(4) shl 16) or (u(5) shl 8) or u(6)
                    val maxFrame = (u(7) shl 16) or (u(8) shl 8) or u(9)
                    if (minBlock < 16 || maxBlock < minBlock || (maxFrame != 0 && minFrame > maxFrame)) return null
                    minFrameBytes = minFrame
                    val rate = (u(10) shl 12) or (u(11) shl 4) or (u(12) shr 4)
                    val channels = ((u(12) shr 1) and 0x7) + 1
                    val bits = (((u(12) and 0x1) shl 4) or (u(13) shr 4)) + 1
                    if (rate !in 1..655350 || bits !in 4..32) return null
                    val total = ((u(13) and 0x0F).toLong() shl 32) or
                        (u(14).toLong() shl 24) or (u(15).toLong() shl 16) or (u(16).toLong() shl 8) or u(17).toLong()
                    val ms = if (rate > 0) total * 1000 / rate else 0
                    info = info.copy(sampleRate = rate, channels = channels, bits = bits, durationMs = ms)
                }
                4 -> {
                    val block = s.bytes(length)
                    parseVorbisComments(block, tags)
                }
                6 -> {
                    if (wantPicture) {
                        val block = s.bytes(length)
                        val (ptype, data) = parseFlacPicture(block) ?: (-1 to null)
                        if (data != null && (picture == null || (ptype == 3 && pictureType != 3))) {
                            picture = data
                            pictureType = ptype
                        }
                    } else {
                        s.skip(length.toLong())
                    }
                }
                else -> s.skip(length.toLong())
            }
            if (last) break
        }
        // STREAMINFO without any encoded audio is a truncated download, not playable music.
        if (!streamInfoSeen || !readFlacFrameHeader(s, minFrameBytes)) return null
        val kbps = if (info.durationMs > 0 && fileSize > 0) (fileSize * 8 / info.durationMs).toInt() else null
        return info.copy(
            title = tags["TITLE"],
            artist = tags["ARTIST"],
            albumArtist = tags["ALBUMARTIST"] ?: tags["ALBUM ARTIST"] ?: tags["ALBUM_ARTIST"],
            album = tags["ALBUM"],
            track = leadingInt(tags["TRACKNUMBER"]),
            disc = leadingInt(tags["DISCNUMBER"]),
            year = year(tags["DATE"] ?: tags["YEAR"] ?: tags["ORIGINALDATE"]),
            license = tags["LICENSE"] ?: tags["COPYRIGHT"]?.takeIf { it.contains("creativecommons", ignoreCase = true) },
            albumGainDb = gain(tags["REPLAYGAIN_ALBUM_GAIN"]),
            trackGainDb = gain(tags["REPLAYGAIN_TRACK_GAIN"]),
            bitrateKbps = kbps,
            picture = picture,
        )
    }

    /** Validate the first frame header and its CRC, rather than accepting only the FLAC magic. */
    private fun readFlacFrameHeader(s: Stream, minFrameBytes: Int): Boolean {
        val header = mutableListOf<Int>()
        fun next(): Int = s.u8().also { header += it }
        val b0 = next(); val b1 = next(); val b2 = next(); val b3 = next()
        val blockCode = b2 ushr 4
        val rateCode = b2 and 15
        val widthCode = b3 ushr 1 and 7
        if (b0 != 0xFF || b1 and 0xFE != 0xF8 || blockCode == 0 || rateCode == 15 ||
            b3 and 1 != 0 || b3 ushr 4 > 10 || widthCode == 3) return false
        val number = next()
        val extra = when {
            number and 0x80 == 0 -> 0
            number and 0xE0 == 0xC0 -> 1
            number and 0xF0 == 0xE0 -> 2
            number and 0xF8 == 0xF0 -> 3
            number and 0xFC == 0xF8 -> 4
            number and 0xFE == 0xFC -> 5
            number == 0xFE && b1 and 1 != 0 -> 6
            else -> return false
        }
        repeat(extra) { if (next() and 0xC0 != 0x80) return false }
        if (blockCode == 6) next()
        if (blockCode == 7) { next(); next() }
        if (rateCode == 12) next()
        if (rateCode == 13 || rateCode == 14) { next(); next() }
        var crc = 0
        header.forEach { byte ->
            crc = crc xor byte
            repeat(8) { crc = if (crc and 0x80 != 0) (crc shl 1 xor 7) and 0xFF else crc shl 1 and 0xFF }
        }
        if (s.u8() != crc) return false
        // Include at least a subframe and the frame CRC; use STREAMINFO's stronger bound when set.
        s.skip((minFrameBytes - header.size - 1).coerceAtLeast(3).toLong())
        return true
    }

    private fun parseVorbisComments(b: ByteArray, out: MutableMap<String, String>) {
        var p = 0
        fun le32(): Int {
            val v = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
                ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24)
            p += 4
            return v
        }
        val vendor = le32()
        p += vendor
        val count = le32()
        repeat(count) {
            if (p + 4 > b.size) return
            val len = le32()
            if (len < 0 || p + len > b.size) return
            val entry = String(b, p, len, Charsets.UTF_8)
            p += len
            val eq = entry.indexOf('=')
            if (eq > 0) {
                val key = entry.substring(0, eq).uppercase()
                if (key !in out) out[key] = entry.substring(eq + 1).trim()
            }
        }
    }

    /** A broken picture block only loses the picture, never the rest of the tags. */
    private fun parseFlacPicture(b: ByteArray): Pair<Int, ByteArray>? = runCatching {
        var p = 0
        fun be32(): Int {
            val v = ((b[p].toInt() and 0xFF) shl 24) or ((b[p + 1].toInt() and 0xFF) shl 16) or
                ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
            p += 4
            return v
        }
        val type = be32()
        // Read each length first: `p += be32()` would add to p's value from before the read.
        val mimeLen = be32()
        p += mimeLen
        val descLen = be32()
        p += descLen
        p += 16 // width, height, depth, colours
        val len = be32()
        if (len <= 0 || p + len > b.size) null else type to b.copyOfRange(p, p + len)
    }.getOrNull()

    // ---------------------------------------------------------------- MP3

    private val MPEG1_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val MPEG2_L3 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)

    private fun readMp3(s: Stream, fileSize: Long, wantPicture: Boolean): AudioInfo? {
        val tags = mutableMapOf<String, String>()
        var picture: ByteArray? = null
        var audioStart = 0L

        val head = s.bytes(10)
        var pending: ByteArray? = null
        if (String(head, 0, 3, Charsets.ISO_8859_1) == "ID3") {
            val major = head[3].toInt()
            val flags = head[5].toInt() and 0xFF
            val size = synchsafe(head, 6)
            if (size in 1..MAX_BLOCK) {
                var body = s.bytes(size)
                if (flags and 0x80 != 0 && major < 4) body = deUnsync(body)
                picture = parseId3(body, major, flags, tags, wantPicture)
            } else {
                s.skip(size.toLong())
            }
            audioStart = 10L + size + if (flags and 0x10 != 0) 10 else 0
            if (flags and 0x10 != 0) s.skip(10)
        } else {
            pending = head
        }

        // Find the first MPEG frame: a plausible Layer III header followed, exactly one frame
        // later, by another header of the same stream. A lone 0xFFE sync pattern in junk, a
        // cover or a renamed file is not enough.
        val window = ByteArray(64 * 1024)
        var filled = 0
        pending?.let { it.copyInto(window); filled = it.size }
        filled += s.readUpTo(window, filled, window.size - filled)
        var i = 0
        while (i + 4 <= filled) {
            val length = mp3FrameLength(window, i, filled)
            if (length != null && sameMp3Stream(window, i, i + length, filled)) break
            i++
        }
        if (i + 4 > filled) return null
        val b1 = window[i + 1].toInt() and 0xFF
        val b2 = window[i + 2].toInt() and 0xFF
        val b3 = window[i + 3].toInt() and 0xFF
        val mpeg1 = ((b1 shr 3) and 0x3) == 3
        val version = (b1 shr 3) and 0x3
        val bitrate = (if (mpeg1) MPEG1_L3 else MPEG2_L3)[b2 shr 4]
        val rate = mp3Rates(version)[(b2 shr 2) and 0x3]
        val mono = (b3 shr 6) == 3
        val spf = if (mpeg1) 1152 else 576
        val frameStart = audioStart + i

        fun be32At(p: Int): Long =
            ((window[p].toLong() and 0xFF) shl 24) or ((window[p + 1].toLong() and 0xFF) shl 16) or
                ((window[p + 2].toLong() and 0xFF) shl 8) or (window[p + 3].toLong() and 0xFF)

        var frames = -1L
        val xingOffset = i + 4 + if (mpeg1) (if (mono) 17 else 32) else (if (mono) 9 else 17)
        if (xingOffset + 12 <= filled) {
            val id = String(window, xingOffset, 4, Charsets.ISO_8859_1)
            if (id == "Xing" || id == "Info") {
                val f = be32At(xingOffset + 4)
                if (f and 1L != 0L) frames = be32At(xingOffset + 8)
            }
        }
        val vbri = i + 4 + 32
        if (frames < 0 && vbri + 18 <= filled && String(window, vbri, 4, Charsets.ISO_8859_1) == "VBRI") {
            frames = be32At(vbri + 14)
        }
        val audioBytes = (fileSize - frameStart).coerceAtLeast(0)
        val durationMs = when {
            frames > 0 -> frames * spf * 1000 / rate
            bitrate > 0 && fileSize > 0 -> audioBytes * 8 / bitrate
            else -> 0
        }
        val avgKbps = if (frames > 0 && durationMs > 0 && fileSize > 0) (audioBytes * 8 / durationMs).toInt() else bitrate

        return AudioInfo(
            codec = "MP3",
            title = tags["TIT2"] ?: tags["TT2"],
            artist = tags["TPE1"] ?: tags["TP1"],
            albumArtist = tags["TPE2"] ?: tags["TP2"],
            album = tags["TALB"] ?: tags["TAL"],
            track = leadingInt(tags["TRCK"] ?: tags["TRK"]),
            disc = leadingInt(tags["TPOS"] ?: tags["TPA"]),
            year = year(tags["TDRC"] ?: tags["TYER"] ?: tags["TYE"] ?: tags["TDOR"]),
            durationMs = durationMs,
            sampleRate = rate,
            channels = if (mono) 1 else 2,
            bitrateKbps = avgKbps,
            license = tags["WCOP"]?.takeIf { it.isNotBlank() }
                ?: tags["TCOP"]?.takeIf { it.contains("creativecommons", ignoreCase = true) },
            albumGainDb = gain(tags["TXXX:REPLAYGAIN_ALBUM_GAIN"]),
            trackGainDb = gain(tags["TXXX:REPLAYGAIN_TRACK_GAIN"]),
            picture = picture,
        )
    }

    private fun mp3Rates(version: Int): IntArray = when (version) {
        3 -> intArrayOf(44_100, 48_000, 32_000)
        2 -> intArrayOf(22_050, 24_000, 16_000)
        else -> intArrayOf(11_025, 12_000, 8_000)
    }

    /** The length in bytes of the MPEG Layer III frame whose header starts at [p], or null when there isn't one. */
    internal fun mp3FrameLength(b: ByteArray, p: Int, end: Int = b.size): Int? {
        if (p < 0 || p + 4 > end) return null
        val b0 = b[p].toInt() and 0xFF
        val b1 = b[p + 1].toInt() and 0xFF
        val b2 = b[p + 2].toInt() and 0xFF
        if (b0 != 0xFF || (b1 and 0xE0) != 0xE0 || ((b1 shr 1) and 0x3) != 1) return null
        val version = (b1 shr 3) and 0x3 // 3 = MPEG1, 2 = MPEG2, 0 = MPEG2.5
        val bitrateIdx = b2 shr 4
        val rateIdx = (b2 shr 2) and 0x3
        if (version == 1 || bitrateIdx !in 1..14 || rateIdx >= 3) return null
        val kbps = (if (version == 3) MPEG1_L3 else MPEG2_L3)[bitrateIdx]
        val padding = (b2 shr 1) and 0x1
        return (if (version == 3) 144 else 72) * kbps * 1000 / mp3Rates(version)[rateIdx] + padding
    }

    /** Whether a valid frame header at [next] belongs to the same stream (version, rate) as the one at [first]. */
    private fun sameMp3Stream(b: ByteArray, first: Int, next: Int, end: Int): Boolean {
        if (mp3FrameLength(b, next, end) == null) return false
        return (b[first + 1].toInt() and 0x18) == (b[next + 1].toInt() and 0x18) &&
            ((b[first + 2].toInt() shr 2) and 0x3) == ((b[next + 2].toInt() shr 2) and 0x3)
    }

    private fun parseId3(body: ByteArray, major: Int, flags: Int, out: MutableMap<String, String>, wantPicture: Boolean): ByteArray? {
        var p = 0
        if (flags and 0x40 != 0 && major >= 3) {
            val ext = if (major == 4) synchsafe(body, 0) else be32(body, 0) + 4
            p += ext
        }
        var picture: ByteArray? = null
        val idLen = if (major == 2) 3 else 4
        val headLen = if (major == 2) 6 else 10
        while (p + headLen <= body.size) {
            val id = String(body, p, idLen, Charsets.ISO_8859_1)
            if (id[0] == '\u0000') break
            val size = when (major) {
                2 -> ((body[p + 3].toInt() and 0xFF) shl 16) or ((body[p + 4].toInt() and 0xFF) shl 8) or (body[p + 5].toInt() and 0xFF)
                4 -> synchsafe(body, p + 4)
                else -> be32(body, p + 4)
            }
            val start = p + headLen
            if (size <= 0 || start + size > body.size) break
            val frame = body.copyOfRange(start, start + size)
            when {
                id == "TXXX" || id == "TXX" -> {
                    val text = decodeText(frame[0].toInt(), frame, 1)
                    val parts = text.split('\u0000')
                    if (parts.size >= 2) out["TXXX:" + parts[0].uppercase()] = parts[1].trim()
                }
                id[0] == 'T' -> out.putIfAbsent(id, decodeText(frame[0].toInt(), frame, 1).split('\u0000').first().trim())
                id == "WCOP" || id == "WCP" -> out.putIfAbsent("WCOP", String(frame, Charsets.ISO_8859_1).trimEnd('\u0000').trim())
                (id == "APIC" || id == "PIC") && wantPicture && picture == null -> picture = parseApic(frame, major)
            }
            p = start + size
        }
        return picture
    }

    private fun parseApic(f: ByteArray, major: Int): ByteArray? {
        val enc = f[0].toInt()
        var p = 1
        if (major == 2) {
            p += 3 // image format, e.g. "JPG"
        } else {
            while (p < f.size && f[p].toInt() != 0) p++
            p++ // mime terminator
        }
        p++ // picture type
        // description, null-terminated in the frame's encoding
        if (enc == 1 || enc == 2) {
            while (p + 1 < f.size && !(f[p].toInt() == 0 && f[p + 1].toInt() == 0)) p += 2
            p += 2
        } else {
            while (p < f.size && f[p].toInt() != 0) p++
            p++
        }
        return if (p < f.size) f.copyOfRange(p, f.size) else null
    }

    private fun decodeText(enc: Int, b: ByteArray, from: Int): String {
        if (from >= b.size) return ""
        val cs: Charset = when (enc) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return String(b, from, b.size - from, cs).trimEnd('\u0000')
    }

    private fun deUnsync(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            out.write(b[i].toInt())
            if ((b[i].toInt() and 0xFF) == 0xFF && i + 1 < b.size && b[i + 1].toInt() == 0) i++
            i++
        }
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- WAV

    private fun readWav(s: Stream): AudioInfo? {
        val riff = s.bytes(12)
        if (String(riff, 0, 4, Charsets.US_ASCII) != "RIFF" || String(riff, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var rate = 0; var bits = 0; var channels = 0; var byteRate = 0
        var dataSize = 0L
        val tags = mutableMapOf<String, String>()
        repeat(12) {
            val head = s.tryBytes(8) ?: return@repeat
            val id = String(head, 0, 4, Charsets.US_ASCII)
            val size = le32(head, 4).toLong() and 0xFFFFFFFFL
            when (id) {
                "fmt " -> {
                    val f = s.bytes(size.toInt())
                    channels = le16(f, 2)
                    rate = le32(f, 4)
                    byteRate = le32(f, 8)
                    bits = le16(f, 14)
                    if (le16(f, 0) == 0xFFFE && f.size >= 20) {
                        val valid = le16(f, 18)
                        if (valid > 0) bits = valid
                    }
                }
                "data" -> {
                    dataSize = size
                    s.skip(size)
                }
                "LIST" -> {
                    val l = s.bytes(size.toInt())
                    if (String(l, 0, 4, Charsets.US_ASCII) == "INFO") {
                        var p = 4
                        while (p + 8 <= l.size) {
                            val sid = String(l, p, 4, Charsets.US_ASCII)
                            val ssize = le32(l, p + 4)
                            if (ssize < 0 || p + 8 + ssize > l.size) break
                            tags[sid] = String(l, p + 8, ssize, Charsets.UTF_8).trimEnd('\u0000').trim()
                            p += 8 + ssize + (ssize and 1)
                        }
                    }
                }
                else -> s.skip(size)
            }
            if (size and 1L == 1L) s.skip(1)
        }
        if (rate <= 0) return null
        val ms = if (byteRate > 0) dataSize * 1000 / byteRate else 0
        return AudioInfo(
            codec = "WAV",
            title = tags["INAM"],
            artist = tags["IART"],
            album = tags["IPRD"],
            track = leadingInt(tags["ITRK"]),
            year = year(tags["ICRD"]),
            durationMs = ms,
            sampleRate = rate,
            bits = bits,
            channels = channels,
            bitrateKbps = byteRate * 8 / 1000,
        )
    }

    // ---------------------------------------------------------------- helpers

    fun leadingInt(s: String?): Int? = s?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull()
    fun year(s: String?): Int? = s?.trim()?.take(4)?.toIntOrNull()?.takeIf { it in 1000..2999 }
    /** A ReplayGain tag in dB, such as "-6.50 dB"; some localised taggers write "-6,50 dB". */
    fun gain(s: String?): Float? = s?.trim()?.replace(Regex("(?i)db$"), "")?.trim()?.replace(',', '.')?.toFloatOrNull()
        ?.takeIf { it.isFinite() }?.coerceIn(-24f, 12f)

    private fun synchsafe(b: ByteArray, at: Int) =
        ((b[at].toInt() and 0x7F) shl 21) or ((b[at + 1].toInt() and 0x7F) shl 14) or
            ((b[at + 2].toInt() and 0x7F) shl 7) or (b[at + 3].toInt() and 0x7F)

    private fun be32(b: ByteArray, at: Int) =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun le32(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

    private fun le16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    /** A tiny reader with exact reads and reliable skips. */
    private class Stream(private val input: InputStream) {
        fun u8(): Int {
            val v = input.read()
            if (v < 0) throw EOFException()
            return v
        }

        fun u24be(): Int = (u8() shl 16) or (u8() shl 8) or u8()

        fun bytes(n: Int): ByteArray {
            val b = ByteArray(n)
            var read = 0
            while (read < n) {
                val r = input.read(b, read, n - read)
                if (r < 0) throw EOFException()
                read += r
            }
            return b
        }

        fun tryBytes(n: Int): ByteArray? = try { bytes(n) } catch (_: EOFException) { null }

        fun readUpTo(into: ByteArray, off: Int, len: Int): Int {
            var read = 0
            while (read < len) {
                val r = input.read(into, off + read, len - read)
                if (r < 0) break
                read += r
            }
            return read
        }

        fun skip(n: Long) {
            var left = n
            while (left > 0) {
                val skipped = input.skip(left)
                if (skipped <= 0) {
                    if (input.read() < 0) throw EOFException()
                    left--
                } else {
                    left -= skipped
                }
            }
        }
    }
}
