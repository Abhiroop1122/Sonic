package app.runout.library

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.runout.model.Album
import app.runout.model.AudioQuality
import app.runout.model.Track
import java.io.File
import java.io.InputStream

/**
 * The admission rule for the listener's own music folder: real FLAC always, and real MP3 too when
 * Settings › Flags › MP3 in your folder is on ([mp3]).
 *
 * Only the folder shelf, the deck and the playback service use it. Soulseek (Dig search and
 * downloads), sharing and uploads, share export and Friday Drops stay with [FlacPolicy]. With [mp3]
 * off every call is exactly [FlacPolicy]'s.
 */
class LocalAudioPolicy private constructor(val mp3: Boolean) {

    /** The file extensions the scan treats as music. */
    val extensions: Set<String> = if (mp3) setOf("flac", "mp3") else setOf("flac")

    fun accepts(name: String): Boolean = kindOf(name) != null

    /** "flac" or "mp3" for a name this policy admits, else null. */
    private fun kindOf(name: String): String? = when {
        FlacPolicy.isFlacName(name) -> "flac"
        mp3 && isMp3Name(name) -> "mp3"
        else -> null
    }

    private fun readKind(input: InputStream, kind: String, fileSize: Long, wantPicture: Boolean): AudioInfo? = when (kind) {
        "flac" -> FlacPolicy.read(input, fileSize, wantPicture)
        // TagReader only says MP3 after two consecutive Layer III frames.
        "mp3" -> TagReader.read(input, "mp3", fileSize, wantPicture)?.takeIf { it.codec == "MP3" }
        else -> null
    }

    /** Consumes a stream, without closing it. [name] picks the reader; the bytes must agree. */
    fun read(input: InputStream, name: String, fileSize: Long = -1, wantPicture: Boolean = false): AudioInfo? =
        kindOf(name)?.let { readKind(input, it, fileSize, wantPicture) }

    fun read(file: File, wantPicture: Boolean = false): AudioInfo? {
        if (!mp3) return FlacPolicy.read(file, wantPicture)
        val kind = kindOf(file.name) ?: return null
        return runCatching { file.inputStream().use { readKind(it, kind, file.length(), wantPicture) } }.getOrNull()
    }

    fun read(context: Context, uri: Uri, fileName: String? = null, wantPicture: Boolean = false): AudioInfo? {
        if (!mp3) return FlacPolicy.read(context, uri, fileName, wantPicture)
        return runCatching {
            if (uri.scheme == "file") {
                val file = File(uri.path ?: return null)
                val kind = kindOf(file.name) ?: return null
                if (fileName != null && kindOf(fileName) != kind) return null
                return read(file, wantPicture)
            }
            if (uri.scheme != "content") return null
            var displayName: String? = null
            var size = -1L
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null as android.os.Bundle?, null)?.use { c ->
                if (c.moveToFirst()) {
                    displayName = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
            // The provider's actual name decides, and a cached name has to agree with it.
            val kind = kindOf(displayName ?: fileName ?: return null) ?: return null
            if (fileName != null && kindOf(fileName) != kind) return null
            context.contentResolver.openInputStream(uri)?.use { readKind(it, kind, size, wantPicture) }
        }.getOrNull()
    }

    fun read(context: Context, track: Track): AudioInfo? =
        read(context, Uri.parse(track.uri), track.fileName.takeIf { it.isNotBlank() })

    /** Why a music file was refused, in words a listener can act on. FLAC keeps [FlacPolicy]'s words. */
    fun diagnose(context: Context, uri: Uri, name: String): String {
        if (!mp3 || !isMp3Name(name)) return FlacPolicy.diagnose(context, uri)
        return runCatching {
            val head = context.contentResolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(64 * 1024)
                var n = 0
                while (n < buf.size) { val r = input.read(buf, n, buf.size - n); if (r <= 0) break; n += r }
                buf.copyOf(n)
            } ?: return "Couldn't open the file. Choose the folder again in Settings › Library."
            val text = String(head, Charsets.ISO_8859_1)
            when {
                head.isEmpty() -> "The file is empty."
                text.startsWith("fLaC") -> "A FLAC file renamed to .mp3. Rename it back to .flac."
                text.startsWith("RIFF") -> "A WAV file renamed to .mp3. Sonic plays FLAC, and MP3 from your folder."
                text.contains("ftyp") -> "An M4A/AAC file renamed to .mp3. Sonic plays FLAC, and MP3 from your folder."
                text.startsWith("OggS") -> "An Ogg file renamed to .mp3. Sonic plays FLAC, and MP3 from your folder."
                text.startsWith("ID3") || hasSync(head) -> "Damaged MP3: no two audio frames in a row, or the download was cut off."
                else -> "Not MP3 inside, despite the .mp3 name."
            }
        }.getOrDefault("Couldn't open the file. Choose the folder again in Settings › Library.")
    }

    private fun hasSync(head: ByteArray): Boolean =
        (0 until head.size - 1).any { head[it] == 0xFF.toByte() && (head[it + 1].toInt() and 0xE0) == 0xE0 }

    /** Why this phone can't decode a valid file, or null when it can. Android decodes every MP3. */
    fun unplayableReason(info: AudioInfo, sdk: Int = android.os.Build.VERSION.SDK_INT): String? =
        if (info.codec == "FLAC") FlacPolicy.unplayableReason(info, sdk) else null

    /** Retain the verified tracks of a record while preserving record/track identities. */
    fun filterAlbum(context: Context, album: Album, refreshMetadata: Boolean = true): Album? {
        if (!mp3) return FlacPolicy.filterAlbum(context, album, refreshMetadata)
        val verified = album.tracks.mapNotNull { track ->
            val info = read(context, track) ?: return@mapNotNull null
            (if (refreshMetadata) track.copy(durationMs = info.durationMs.takeIf { it > 0 } ?: track.durationMs,
                durationKnown = info.durationMs > 0, trackGainDb = info.trackGainDb ?: track.trackGainDb) else track) to info
        }
        if (verified.isEmpty()) return null
        return album.copy(tracks = verified.map { it.first }, quality = quality(verified.map { it.second }))
    }

    /**
     * The record's quality, from its lowest codec: one MP3 track makes the whole record MP3, so it is
     * lossy, pressed black and never hi-res. All FLAC is [FlacPolicy.quality].
     */
    fun quality(infos: List<AudioInfo>): AudioQuality {
        val lossy = infos.filter { isLossyCodec(it.codec) }
        if (lossy.isEmpty()) return FlacPolicy.quality(infos)
        return AudioQuality(
            lossy.first().codec, null, infos.mapNotNull { it.sampleRate }.minOrNull(),
            lossy.mapNotNull { it.bitrateKbps }.takeIf { it.isNotEmpty() }?.average()?.toInt(),
        )
    }

    companion object {
        val FLAC_ONLY = LocalAudioPolicy(mp3 = false)
        val WITH_MP3 = LocalAudioPolicy(mp3 = true)

        fun of(mp3: Boolean): LocalAudioPolicy = if (mp3) WITH_MP3 else FLAC_ONLY

        fun isMp3Name(name: String): Boolean =
            name.substringAfterLast('/').substringAfterLast('\\').endsWith(".mp3", ignoreCase = true)

        /** A track's own file name, or the last part of its uri for older records without one. */
        fun nameOf(track: Track): String = track.fileName.ifBlank { Uri.parse(track.uri).lastPathSegment.orEmpty() }

        fun isMp3Track(track: Track): Boolean = isMp3Name(nameOf(track))

        /** What the player is told the track is. */
        fun mimeTypeFor(name: String): String = if (isMp3Name(name)) "audio/mpeg" else "audio/flac"

        fun isLossyCodec(codec: String?): Boolean = codec != null && codec.uppercase() in LOSSY_CODECS

        /** The codec of a compressed stream from Media3's sample MIME type, or null when it isn't a lossy one. */
        fun lossyCodecOfMime(mime: String?): String? = when (mime?.lowercase()) {
            "audio/mpeg" -> "MP3"
            "audio/mpeg-l1", "audio/mpeg-l2" -> "MPEG"
            "audio/mp4a-latm", "audio/aac" -> "AAC"
            "audio/vorbis" -> "OGG"
            "audio/opus" -> "OPUS"
            else -> null
        }

        private val LOSSY_CODECS = setOf("MP3", "AAC", "OGG", "OPUS", "MPEG")
    }
}
