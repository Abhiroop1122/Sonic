package app.runout

import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.test.utils.FakeClock
import app.runout.library.ArtworkRepository
import app.runout.library.AudioInfo
import app.runout.library.FlacPolicy
import app.runout.library.LibraryRepository
import app.runout.library.LibraryScanner
import app.runout.library.LocalAudioPolicy
import app.runout.library.ScanReport
import app.runout.library.ScannedAlbum
import app.runout.library.TagReader
import app.runout.model.Album
import app.runout.model.Pressing
import app.runout.model.Source
import app.runout.playback.Deck
import app.runout.playback.VinylSfx
import app.runout.sharing.ShareRepository
import app.runout.social.SocialRepository
import app.runout.sound.BitPerfect
import app.runout.sound.DspAudioSink
import app.runout.sound.OutputDevice
import app.runout.sound.OutputKind
import app.runout.sound.OutputStatus
import app.runout.sound.PlayerFactory
import app.runout.sound.ReplayGainMode
import app.runout.sound.SoundEngine
import app.runout.sound.SoundSettings
import app.runout.sound.StreamFormat
import app.runout.ui.signalPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration
import java.util.Collections

/**
 * Settings › Flags › MP3 in your folder: MP3 from the listener's own music folder is scanned, shown
 * as black (lossy) records and played, honestly labelled; everything that travels stays FLAC.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class Mp3LocalTest {

    private val context get() = Fixtures.context
    private val paperMoonsDir = "Music/Ines Varga/Paper Moons"
    private val nightDriveDir = "Music/Vela Park - Night Drive (2023)"

    // ------------------------------------------------------------------ reading MP3

    /** An MPEG-1 Layer III, 128 kbps, 44.1 kHz frame: 417 bytes, its header first. */
    private fun frame(): ByteArray = ByteArray(417).also { it[0] = 0xFF.toByte(); it[1] = 0xFB.toByte(); it[2] = 0x90.toByte(); it[3] = 0x64 }

    @Test fun `an MP3 needs two frames in a row, not one plausible header`() {
        val lone = ByteArray(16) + frame() + ByteArray(4_000)
        assertNull(TagReader.read(lone.inputStream(), "mp3", lone.size.toLong(), false))
        val two = ByteArray(16) + frame() + frame() + ByteArray(400)
        val info = TagReader.read(two.inputStream(), "mp3", two.size.toLong(), false)
        assertEquals("MP3", info?.codec)
        assertEquals(44_100, info?.sampleRate)
        // A sync pattern in a cover or junk ahead of the real stream is stepped over.
        val junk = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64, 1, 2, 3) + frame() + frame() + ByteArray(400)
        assertEquals("MP3", TagReader.read(junk.inputStream(), "mp3", junk.size.toLong(), false)?.codec)
    }

    @Test fun `every Paper Moons fixture is real MP3 under the two-frame rule`() {
        listOf("01 Paper Moons.mp3", "02 Glass Hours.mp3", "03 Signal.mp3").forEach { name ->
            val bytes = Fixtures.bytes("$paperMoonsDir/$name")
            val info = TagReader.read(bytes.inputStream(), "mp3", bytes.size.toLong(), false)
            assertEquals(name, "MP3", info?.codec)
            assertTrue(name, info!!.durationMs > 0)
        }
    }

    @Test fun `the folder policy admits MP3 only with the flag on, and FLAC either way`() {
        val mp3 = Fixtures.copyTracks("$paperMoonsDir/01 Paper Moons.mp3").single()
        val flac = Fixtures.copyTracks("$nightDriveDir/01 Opening.flac").single()
        assertNull(LocalAudioPolicy.FLAC_ONLY.read(context, mp3))
        assertNull(FlacPolicy.read(context, mp3))
        assertEquals("MP3", LocalAudioPolicy.WITH_MP3.read(context, mp3)?.codec)
        assertEquals("FLAC", LocalAudioPolicy.WITH_MP3.read(context, flac)?.codec)
        assertEquals("FLAC", LocalAudioPolicy.FLAC_ONLY.read(context, flac)?.codec)
        // The name and the bytes have to agree: MP3 renamed to .flac, or FLAC renamed to .mp3, is refused.
        val dir = File(context.cacheDir, "renamed").apply { mkdirs() }
        val fakeFlac = File(dir, "a.flac").apply { writeBytes(Fixtures.bytes("$paperMoonsDir/01 Paper Moons.mp3")) }
        val fakeMp3 = File(dir, "b.mp3").apply { writeBytes(Fixtures.bytes("$nightDriveDir/01 Opening.flac")) }
        assertNull(LocalAudioPolicy.WITH_MP3.read(fakeFlac))
        assertNull(LocalAudioPolicy.WITH_MP3.read(fakeMp3))
        // A cached name can't turn an MP3 into something else.
        assertNull(LocalAudioPolicy.WITH_MP3.read(context, Uri.parse(mp3.uri), "01 Paper Moons.flac"))
        assertEquals(setOf("flac"), LocalAudioPolicy.FLAC_ONLY.extensions)
        assertEquals(setOf("flac", "mp3"), LocalAudioPolicy.WITH_MP3.extensions)
    }

    @Test fun `a record's quality is its lowest codec, so any MP3 track makes it lossy and never hi-res`() {
        val hiRes = AudioInfo("FLAC", sampleRate = 96_000, bits = 24, bitrateKbps = 2_000)
        val mp3 = AudioInfo("MP3", sampleRate = 44_100, bitrateKbps = 320)
        val q = LocalAudioPolicy.WITH_MP3.quality(listOf(hiRes, mp3, hiRes))
        assertEquals("MP3", q.codec)
        assertFalse(q.isLossless)
        assertFalse(q.isHiRes)
        assertNull(q.bitsPerSample)
        assertEquals(320, q.bitrateKbps)
        assertEquals("MP3 · 320 kbps", q.label)
        // All FLAC: exactly FlacPolicy's answer.
        assertEquals(FlacPolicy.quality(listOf(hiRes)), LocalAudioPolicy.WITH_MP3.quality(listOf(hiRes)))
    }

    // ------------------------------------------------------------------ the scan

    private fun scan(tree: Uri, mp3: Boolean): Pair<ScanReport, List<ScannedAlbum>> = runBlocking {
        val found = Collections.synchronizedList(mutableListOf<ScannedAlbum>())
        val report = LibraryScanner(context).scan(tree, onAlbum = { found += it }, mp3 = mp3)
        report to found.toList()
    }

    @Test fun `with the flag on Paper Moons scans as a black MP3 record beside the FLAC`() {
        val tree = FolderProvider.install(Fixtures.file("Music"))
        val (report, albums) = scan(tree, mp3 = true)
        val moons = albums.single { it.album.title == "Paper Moons" }.album
        assertEquals(3, moons.tracks.size)
        assertEquals("Ines Varga", moons.artist)
        assertEquals("MP3", moons.quality.codec)
        assertFalse(moons.quality.isHiRes)
        assertFalse(moons.quality.isLossless)
        assertEquals(Pressing.BLACK, moons.pressing)
        assertTrue(moons.tracks.all { it.durationKnown && it.durationMs > 0 })
        val night = albums.single { it.album.title == "Night Drive" }.album
        assertTrue(night.quality.isHiRes)
        assertEquals(3, report.mp3Files)
        assertEquals(5, report.audioFiles)
        assertNull(report.otherFiles["mp3"])
        // Off (the default), the same folder is FLAC only and the MP3s are just other files.
        val (off, offAlbums) = scan(tree, mp3 = false)
        assertEquals(setOf("Night Drive"), offAlbums.map { it.album.title }.toSet())
        assertEquals(0, off.mp3Files)
        assertEquals(3, off.otherFiles["mp3"])
    }

    /** An ID3v2.3 tag with plain text frames. */
    private fun id3(vararg frames: Pair<String, String>): ByteArray {
        val body = ByteArrayOutputStream()
        frames.forEach { (id, text) ->
            val data = byteArrayOf(0) + text.toByteArray(Charsets.ISO_8859_1)
            body.write(id.toByteArray(Charsets.ISO_8859_1))
            body.write(byteArrayOf((data.size ushr 24).toByte(), (data.size ushr 16).toByte(), (data.size ushr 8).toByte(), data.size.toByte(), 0, 0))
            body.write(data)
        }
        val b = body.toByteArray()
        val n = b.size
        return byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0,
            ((n shr 21) and 0x7F).toByte(), ((n shr 14) and 0x7F).toByte(), ((n shr 7) and 0x7F).toByte(), (n and 0x7F).toByte()) + b
    }

    /** Glass Hours' audio under new tags. */
    private fun mp3(album: String, track: Int, title: String): ByteArray {
        val src = Fixtures.bytes("$paperMoonsDir/02 Glass Hours.mp3")
        val start = 10 + (((src[6].toInt() and 0x7F) shl 21) or ((src[7].toInt() and 0x7F) shl 14) or ((src[8].toInt() and 0x7F) shl 7) or (src[9].toInt() and 0x7F))
        return id3("TALB" to album, "TRCK" to "$track", "TIT2" to title, "TPE1" to "Vela Park") + src.copyOfRange(start, src.size)
    }

    @Test fun `the same album in FLAC and MP3 keeps the FLAC tracks, and one MP3 track makes it never hi-res`() {
        val dir = File(context.cacheDir, "mixed/Music/Night Drive").apply { deleteRecursively(); mkdirs() }
        Fixtures.file("$nightDriveDir/01 Opening.flac").copyTo(File(dir, "01 Opening.flac"))
        Fixtures.file("$nightDriveDir/02 Lowlight.flac").copyTo(File(dir, "02 Lowlight.flac"))
        File(dir, "01 Opening.mp3").writeBytes(mp3("Night Drive", 1, "Opening"))
        File(dir, "02 Lowlight.mp3").writeBytes(mp3("Night Drive", 2, "Lowlight"))
        val tree = FolderProvider.install(dir.parentFile!!)
        // Only duplicates: the MP3 copies are ignored and the record stays hi-res FLAC.
        val (_, dupes) = scan(tree, mp3 = true)
        val record = dupes.single().album
        assertEquals(listOf("01 Opening.flac", "02 Lowlight.flac"), record.tracks.map { it.fileName })
        assertTrue(record.quality.isHiRes)
        assertEquals("FLAC", record.quality.codec)

        // An MP3 track the FLAC doesn't have joins the record, and the record is lossy.
        File(dir, "03 Afterglow.mp3").writeBytes(mp3("Night Drive", 3, "Afterglow"))
        val (_, mixed) = scan(tree, mp3 = true)
        val m = mixed.single().album
        assertEquals(listOf("01 Opening.flac", "02 Lowlight.flac", "03 Afterglow.mp3"), m.tracks.map { it.fileName })
        assertEquals("MP3", m.quality.codec)
        assertFalse(m.quality.isHiRes)
        assertEquals(Pressing.BLACK, m.pressing)
        // Flag off: the FLAC alone, hi-res again.
        val (_, off) = scan(tree, mp3 = false)
        assertTrue(off.single().album.quality.isHiRes)
        assertEquals(2, off.single().album.tracks.size)
    }

    @Test fun `a broken MP3 is reported in plain words`() {
        val dir = File(context.cacheDir, "brokenmp3/Music/Odd").apply { deleteRecursively(); mkdirs() }
        File(dir, "half.mp3").writeBytes(ByteArray(10) { 0x11 } + frame() + ByteArray(2_000))
        File(dir, "flac.mp3").writeBytes(Fixtures.bytes("$nightDriveDir/01 Opening.flac"))
        val tree = FolderProvider.install(dir.parentFile!!)
        val (report, albums) = scan(tree, mp3 = true)
        assertTrue(albums.isEmpty())
        assertEquals(2, report.unreadable)
        val reasons = report.unreadableFiles.associate { it.path.substringAfterLast('/') to it.reason }
        assertTrue(reasons["half.mp3"], reasons["half.mp3"]!!.startsWith("Damaged MP3"))
        assertTrue(reasons["flac.mp3"], reasons["flac.mp3"]!!.contains("FLAC file renamed to .mp3"))
    }

    // ------------------------------------------------------------------ the shelf and the flag

    private fun CoroutineScope.stop() = coroutineContext[Job]!!.cancel()

    @Test fun `switching the flag off takes MP3 records off the shelf, and a partial scan doesn't bring them back`() = runBlocking {
        val root = File(context.cacheDir, "flagoff/Music").apply { deleteRecursively(); mkdirs() }
        Fixtures.file(nightDriveDir).copyRecursively(File(root, "Night Drive"))
        Fixtures.file(paperMoonsDir).copyRecursively(File(root, "Paper Moons"))
        // A file that can't be read makes every scan a partial one.
        File(root, "Broken").mkdirs(); File(root, "Broken/bad.flac").writeText("not flac")
        val tree = FolderProvider.install(root)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repo = LibraryRepository(context, LibraryScanner(context), ArtworkRepository(context), scope, lookupCoversOnline = false)
        assertFalse("off by default", repo.mp3Local.value)
        assertNull("no folder yet, nothing to scan", repo.setMp3Local(true))
        repo.setFolder(tree)!!.join()
        assertEquals(setOf("Night Drive", "Paper Moons"), repo.albums.value.map { it.title }.toSet())
        assertEquals(Pressing.BLACK, repo.albums.value.single { it.title == "Paper Moons" }.pressing)
        assertEquals(3, repo.report.value!!.mp3Files)

        val repair = repo.setMp3Local(false)
        assertTrue("gone at once", repo.albums.value.none { it.title == "Paper Moons" })
        repair!!.join()
        assertEquals(listOf("Night Drive"), repo.albums.value.map { it.title })
        assertEquals(1, repo.report.value!!.unreadable)
        // A later partial scan keeps unseen albums, but never an MP3 one.
        repo.rescan()!!.join()
        assertEquals(listOf("Night Drive"), repo.albums.value.map { it.title })
        // And the saved shelf agrees on the next launch.
        val again = LibraryRepository(context, LibraryScanner(context), ArtworkRepository(context), scope, lookupCoversOnline = false)
        kotlinx.coroutines.delay(300)
        assertEquals(listOf("Night Drive"), again.albums.value.map { it.title })
        // On again: back on the shelf.
        repo.setMp3Local(true)!!.join()
        assertEquals(setOf("Night Drive", "Paper Moons"), repo.albums.value.map { it.title }.toSet())
        scope.stop()
    }

    @Test fun `a saved shelf with MP3 records loads without them while the flag is off`() = runBlocking {
        val cache = File(context.filesDir, "library.json")
        app.runout.library.AlbumJson.writeList(cache, listOf(Fixtures.paperMoons(), Fixtures.nightDrive()))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val off = LibraryRepository(context, LibraryScanner(context), ArtworkRepository(context), scope, lookupCoversOnline = false)
        kotlinx.coroutines.delay(300)
        assertEquals(listOf("Night Drive"), off.albums.value.map { it.title })
        assertNull(off.albums.value.single().unavailableReason)
        context.getSharedPreferences(LibraryRepository.MP3_PREFS, 0).edit().putBoolean(LibraryRepository.KEY_MP3, true).commit()
        val on = LibraryRepository(context, LibraryScanner(context), ArtworkRepository(context), scope, lookupCoversOnline = false)
        kotlinx.coroutines.delay(300)
        assertEquals(setOf("Night Drive", "Paper Moons"), on.albums.value.map { it.title }.toSet())
        assertTrue(on.albums.value.all { it.unavailableReason == null })
        scope.stop()
    }

    // ------------------------------------------------------------------ the deck

    @Test fun `with the flag on an MP3 folder record plays through the deck as audio-mpeg`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val engine = SoundEngine(null)
        val player: ExoPlayer = PlayerFactory.create(context, engine, handleAudioFocus = false, clock = FakeClock(false)) { ctx, _ -> AudioOutputTest.CapturingOutput(ctx) }
        var allow = true
        val deck = Deck(context, VinylSfx(scope), scope, engine, allowMp3 = { allow }) { ready -> ready(player) }
        fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
        try {
            val moons = Fixtures.paperMoons()
            deck.load(moons)
            settle()
            assertEquals("pm", deck.state.value.album?.id)
            assertTrue(player.mediaItemCount > 0)
            val item = player.getMediaItemAt(0)
            assertEquals(MimeTypes.AUDIO_MPEG, item.localConfiguration?.mimeType)
            deck.startStop()
            settle()
            assertTrue(deck.state.value.needleDown)
            assertTrue(player.playWhenReady)
            assertNull(player.playerError)

            // A record that isn't from the folder never plays MP3, flag or not.
            deck.eject(); settle()
            deck.load(moons.copy(id = "rx", source = Source.RECEIVED))
            settle()
            assertNull(deck.state.value.album)
            // Flag off: the folder record is refused too.
            allow = false
            deck.load(moons)
            settle()
            assertNull(deck.state.value.album)
            assertTrue(runCatching { Deck.mediaItemsFor(moons, app.runout.model.Side(0, moons.tracks)) }.isFailure)
            assertEquals(MimeTypes.AUDIO_MPEG, Deck.mediaItemsFor(moons, app.runout.model.Side(0, moons.tracks), LocalAudioPolicy.WITH_MP3).first().localConfiguration?.mimeType)
        } finally {
            scope.cancel()
            player.release()
        }
    }

    // ------------------------------------------------------------------ honesty

    private fun mp3Source(rate: Int = 44_100, metadata: Metadata? = null) = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_MPEG).setSampleRate(rate).setChannelCount(2).setMetadata(metadata).build()

    private fun pcm(encoding: Int, rate: Int, metadata: Metadata? = null) = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(encoding).setSampleRate(rate).setChannelCount(2).setMetadata(metadata).build()

    @Test fun `an MP3 stream reads as lossy MP3, never exact, never 16-bit lossless`() {
        val engine = SoundEngine(null).apply { update { it.copy(replayGain = ReplayGainMode.OFF) } }
        val sink = DspAudioSink(AudioOutputTest.CapturingOutput(context), engine, floatOutput = false)
        sink.onSourceFormat(mp3Source())
        sink.configure(pcm(C.ENCODING_PCM_16BIT, 44_100), 0, null)
        val st = engine.status.value
        assertEquals("MP3", st.lossyCodec)
        assertTrue(st.lossySource)
        assertEquals(0, st.sourceBits)
        assertFalse(st.dithered)
        assertEquals("MP3 · 44.1 kHz → 16-bit · lossy source", st.label)
        assertFalse(st.label.contains("untouched"))
        // The same samples from a FLAC would be exact; from an MP3 they never are.
        assertTrue(SoundEngine.samplesExact(StreamFormat(16, 16, 44_100, 2, C.ENCODING_PCM_16BIT, deep = false)))
        assertFalse(SoundEngine.samplesExact(StreamFormat(16, 16, 44_100, 2, C.ENCODING_PCM_16BIT, deep = false, lossyCodec = "MP3")))
        assertFalse(SoundEngine.samplesExact(StreamFormat(0, 32, 44_100, 2, C.ENCODING_PCM_FLOAT, deep = false, lossyCodec = "MP3")))
    }

    @Test fun `an MP3 in Pure takes the 16-bit path, never the deep float one`() {
        val engine = SoundEngine(null).apply { update { it.copy(pure = true) } }
        val sink = DspAudioSink(AudioOutputTest.CapturingOutput(context), engine, floatOutput = true)
        sink.onSourceFormat(mp3Source())
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING, sink.getFormatSupport(pcm(C.ENCODING_PCM_FLOAT, 44_100)))
        sink.configure(pcm(C.ENCODING_PCM_16BIT, 44_100), 0, null)
        val st = engine.status.value
        assertFalse(st.floatPath)
        assertFalse(st.dithered)
        assertEquals("MP3", st.lossyCodec)
    }

    @Test fun `the signal path says MP3 and lossy, never lossless or bit-perfect`() {
        val pure = SoundSettings(pure = true)
        // Even with Android reporting an exact USB mode, a lossy file isn't bit-perfect.
        val usb = OutputStatus(0, 44_100, bitPerfect = BitPerfect.ON, decodedBits = 16, lossyCodec = "MP3", device = OutputDevice(OutputKind.USB, "DAC", mixerRate = 44_100))
        val cases = listOf(
            usb to pure,
            usb.copy(bitPerfect = BitPerfect.LOSSY_SOURCE) to pure,
            OutputStatus(0, 44_100, decodedBits = 16, lossyCodec = "MP3", device = OutputDevice(OutputKind.SPEAKER, mixerRate = 44_100)) to SoundSettings(),
            OutputStatus(0, 44_100, decodedBits = 16, lossyCodec = "MP3", device = OutputDevice(OutputKind.WIRED, mixerRate = 48_000)) to SoundSettings(),
            OutputStatus(0, 48_000, decodedBits = 16, lossyCodec = "MP3", replayGainDb = -3f, device = OutputDevice(OutputKind.USB, "DAC", mixerRate = 48_000)) to SoundSettings(),
        )
        cases.forEach { (o, s) ->
            val path = signalPath(o, s)!!
            assertEquals("MP3", path.track.title)
            assertTrue(path.track.spec, path.track.spec.startsWith("MP3 · "))
            assertTrue(path.track.detail, path.track.detail.startsWith("Lossy"))
            assertFalse(path.track.ok)
            assertFalse(path.bitPerfect)
            assertTrue("red lamp", path.lossy)
            assertFalse(path.verdict, path.verdict.contains("bit-perfect", ignoreCase = true))
            assertFalse(path.verdict, path.verdict.contains("lossless", ignoreCase = true))
            assertTrue(path.verdict, path.verdict.contains("lossy", ignoreCase = true))
            assertFalse(path.track.detail.contains("lossless", ignoreCase = true))
            assertNull(path.note)
            assertFalse(path.output.detail, path.output.detail.contains("Bit-perfect to", ignoreCase = true))
            assertFalse(path.output.detail, path.output.detail.contains("for bit-perfect", ignoreCase = true))
        }
        assertEquals("Lossy MP3 source", signalPath(cases[2].first, cases[2].second)!!.verdict)
        assertTrue(signalPath(cases[0].first, cases[0].second)!!.short.startsWith("MP3 "))
        assertTrue(BitPerfect.LOSSY_SOURCE.label.startsWith("Not bit-perfect"))
    }

    @Test fun `ReplayGain is read from an MP3's ID3 TXXX frames`() {
        val engine = SoundEngine(null).apply { update { it.copy(replayGain = ReplayGainMode.TRACK, limiter = false) } }
        val out = AudioOutputTest.CapturingOutput(context)
        val sink = DspAudioSink(out, engine, floatOutput = true)
        val tags = Metadata(
            TextInformationFrame("TXXX", "replaygain_track_gain", com.google.common.collect.ImmutableList.of("-6.0206 dB")),
            TextInformationFrame("TXXX", "REPLAYGAIN_ALBUM_GAIN", com.google.common.collect.ImmutableList.of("-3.00 dB")),
            TextInformationFrame("TIT2", null, com.google.common.collect.ImmutableList.of("Paper Moons")),
        )
        sink.onSourceFormat(mp3Source(metadata = tags))
        sink.configure(pcm(C.ENCODING_PCM_FLOAT, 44_100, metadata = tags), 0, null)
        assertEquals(-6.0206f, engine.replayGainDb(), 1e-4f)
        // The media item's values arrive later and don't override the stream's own.
        engine.setItemGains(null, 0f)
        assertEquals(-6.0206f, engine.replayGainDb(), 1e-4f)
        engine.update { it.copy(replayGain = ReplayGainMode.ALBUM) }
        assertEquals(-3f, engine.replayGainDb(), 1e-4f)
    }

    // ------------------------------------------------------------------ what travels stays FLAC

    @Test fun `with the flag on, share export and the FLAC policy still refuse MP3`() = runBlocking {
        context.getSharedPreferences(LibraryRepository.MP3_PREFS, 0).edit().putBoolean(LibraryRepository.KEY_MP3, true).commit()
        val moons = Fixtures.paperMoons()
        val export = runCatching { ShareRepository(context, SocialRepository(context)).exportAlbum(moons) }
        assertTrue(export.isFailure)
        assertTrue(export.exceptionOrNull()!!.message!!.contains("FLAC"))
        assertFalse(FlacPolicy.isFlacName("01 Paper Moons.mp3"))
        assertNull(FlacPolicy.read(context, moons.tracks.first()))
        assertNull(FlacPolicy.filterAlbum(context, moons))
        assertNotNull(LocalAudioPolicy.WITH_MP3.filterAlbum(context, moons))
    }
}
