package app.runout

import app.runout.model.Album
import app.runout.model.AudioQuality
import app.runout.model.Source
import app.runout.model.Track
import app.runout.soulseek.FailureKind
import app.runout.soulseek.SoulseekFolderPreview
import app.runout.soulseek.SoulseekPolicy
import app.runout.soulseek.SoulseekSearchResult
import app.runout.soulseek.SoulseekTransfer
import app.runout.soulseek.SoulseekTransferState
import app.runout.ui.DownloadGroup
import app.runout.ui.FileState
import app.runout.ui.downloadGroup
import app.runout.ui.fileState
import app.runout.ui.shelfMatch
import app.runout.ui.trackKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadsLogicTest {
    private fun file(name: String, user: String = "maya", size: Long = 100) = SoulseekSearchResult(user, "Air/Moon Safari/$name", size)
    private fun preview(vararg names: String, user: String = "maya") = SoulseekFolderPreview("r", user, "Air/Moon Safari", names.map { file(it, user) }, loading = false)
    private fun album(id: String, vararg fileNames: String, title: String = "Moon Safari", artist: String = "Air", source: Source = Source.SOULSEEK) = Album(
        id, title, artist, 1998, "content://x", fileNames.mapIndexed { i, n -> Track("t$i", "content://t$i", n.substringAfter(' ').substringBeforeLast('.'), i + 1, 1, 1000, n) },
        AudioQuality("FLAC", 24, 48_000), source = source,
    )

    @Test fun `a saved file name and the ones peers share compare equal`() {
        val key = trackKey("1a2b3c4d5e 02-New Kid In Town.flac")
        assertEquals("newkidintown", key)
        assertEquals(key, trackKey("02 - New Kid in Town.flac"))
        assertEquals(key, trackKey("02. New Kid In Town.flac"))
    }

    @Test fun `the folder's own download, else the same title and artist, else shared tracks`() {
        val p = preview("01 La Femme d'argent.flac", "02 Sexy Boy.flac", "03 All I Need.flac")
        val own = album(SoulseekPolicy.albumId("maya", "Air/Moon Safari"), "x 01 Other.flac")
        val sameName = album("same-name", "1a2b3c4d5e 05 Talisman.flac")
        val sharesTracks = album("shares", "1a2b3c4d5e 01 La Femme d'argent.flac", "1a2b3c4d5e 02 Sexy Boy.flac", title = "Best Of", artist = "Various")
        assertEquals(own.id, shelfMatch(p, listOf(sameName, sharesTracks, own))?.id)
        assertEquals("same-name", shelfMatch(p, listOf(sharesTracks, sameName))?.id)
        assertEquals("shares", shelfMatch(p, listOf(sharesTracks))?.id)
        // Another artist's album of the same name isn't it; nor is one shared track.
        assertNull(shelfMatch(p, listOf(album("other-artist", "x 09 Song.flac", artist = "Someone Else"))))
        assertNull(shelfMatch(p, listOf(album("lonely", "1a2b3c4d5e 01 La Femme d'argent.flac", title = "Mix", artist = "Various"))))
    }

    @Test fun `a year or tag in the folder name still matches the album`() {
        val p = SoulseekFolderPreview("r", "zoe", "Music/Eagles/1976.12-Hotel California (flac)", listOf(SoulseekSearchResult("zoe", "Music/Eagles/1976.12-Hotel California (flac)/02 - New Kid in Town.flac", 100)), loading = false)
        val shelf = album("hc", "1a2b3c4d5e 02-New Kid In Town.flac", title = "Hotel California", artist = "Eagles")
        assertEquals("hc", shelfMatch(p, listOf(shelf))?.id)
        assertEquals(FileState.HAVE, fileState(p.files.single(), shelf, emptyList()).first)
    }

    @Test fun `each track says if you have it, are getting it, or it broke`() {
        val shelf = album("a", "1a2b3c4d5e 01 La Femme d'argent.flac")
        val have = file("01 La Femme d'argent.flac")
        val broken = file("02 Sexy Boy.flac")
        val getting = file("03 All I Need.flac")
        val missing = file("04 Kelly Watch The Stars.flac")
        val transfers = listOf(
            SoulseekTransfer("1", "maya", broken.path, broken.size, state = SoulseekTransferState.FAILED),
            SoulseekTransfer("2", "maya", getting.path, getting.size, bytes = 50, state = SoulseekTransferState.DOWNLOADING),
        )
        assertEquals(FileState.HAVE, fileState(have, shelf, transfers).first)
        assertEquals(FileState.BROKEN, fileState(broken, shelf, transfers).first)
        assertEquals(FileState.GETTING, fileState(getting, shelf, transfers).first)
        assertEquals(FileState.MISSING, fileState(missing, shelf, transfers).first)
    }

    @Test fun `a failure says whether to ask again or look elsewhere`() {
        assertEquals(FailureKind.PEER_AWAY, SoulseekPolicy.failureKind("The user went offline"))
        assertEquals(FailureKind.PEER_AWAY, SoulseekPolicy.failureKind("Could not connect to the user"))
        assertEquals(FailureKind.PEER_AWAY, SoulseekPolicy.failureKind("Connection reset by peer (os error 104)"))
        assertEquals(FailureKind.REFUSED, SoulseekPolicy.failureKind("The upload failed on the other side"))
        assertEquals(FailureKind.REFUSED, SoulseekPolicy.failureKind("The user declined the download"))
        // The peer's own reason now comes through: a full queue is "later", a ban is "elsewhere".
        assertEquals(FailureKind.PEER_AWAY, SoulseekPolicy.failureKind("The other side refused: Too many files"))
        assertEquals(FailureKind.PEER_AWAY, SoulseekPolicy.failureKind("The other side refused: Too many megabytes"))
        assertEquals(FailureKind.REFUSED, SoulseekPolicy.failureKind("The other side refused: Banned"))
        assertEquals(FailureKind.REFUSED, SoulseekPolicy.failureKind("The other side refused: File not shared."))
    }

    @Test fun `a long file name is shortened before its extension, never through it`() {
        val long = "01 - " + "a very long track title with a featured artist and a remix credit ".repeat(3) + ".flac"
        val safe = SoulseekPolicy.safeFileName(long)
        assertTrue(safe, safe.endsWith(".flac"))
        assertTrue(safe.length <= 120)
        assertEquals("02 - Ivy.flac", SoulseekPolicy.safeFileName("02 - Ivy.flac"))
        assertEquals("a_b.flac", SoulseekPolicy.safeFileName("a/b.FLAC"))
        assertEquals("Track.flac", SoulseekPolicy.safeFileName(".flac"))
    }

    @Test fun `a dig search asks for flac, without punctuation, and can leave one word out`() {
        assertEquals("frank ocean blonde flac", app.runout.soulseek.SearchTerms.network("frank ocean blonde"))
        assertEquals("AC DC Back in Black flac", app.runout.soulseek.SearchTerms.network("AC/DC – Back in Black"))
        assertEquals("air flac", app.runout.soulseek.SearchTerms.network("air flac"))
        val variants = app.runout.soulseek.SearchTerms.variants("frank ocean blonde")
        assertTrue(variants.toString(), "ocean blonde flac" in variants && "frank blonde flac" in variants)
        assertTrue(variants.all { it.split(' ').size == 3 })
        assertEquals(emptyList<String>(), app.runout.soulseek.SearchTerms.variants("blonde"))
    }

    @Test fun `another copy is the same track at the same size from someone new, the quickest first`() {
        val path = "Music/Air/Moon Safari/02 - Sexy Boy.flac"
        assertEquals(SoulseekPolicy.fileKey(path), SoulseekPolicy.fileKey("1a2b3c4d5e 02 Sexy Boy.flac"))
        assertEquals("1999", SoulseekPolicy.fileKey("1999.flac"))
        val results = listOf(
            SoulseekSearchResult("maya", path, 100, freeSlots = 1),
            SoulseekSearchResult("kai", "x/Sexy Boy.flac", 101, freeSlots = 1),
            SoulseekSearchResult("lee", "x/03 Sexy Girl.flac", 100, freeSlots = 1),
            SoulseekSearchResult("ola", "Air - Moon Safari/02 Sexy Boy.flac", 100, freeSlots = 0, queueLength = 4),
            SoulseekSearchResult("zoe", "Rips/02. Sexy Boy.flac", 100, freeSlots = 2),
        )
        assertEquals("zoe", SoulseekPolicy.pickCopy(results, path, 100, setOf("maya"))?.username)
        assertEquals("ola", SoulseekPolicy.pickCopy(results, path, 100, setOf("maya", "zoe"))?.username)
        assertNull(SoulseekPolicy.pickCopy(results, path, 100, setOf("maya", "zoe", "ola")))
        assertEquals(listOf("sexy boy moon safari", "sexy boy"), SoulseekPolicy.seekQueries(path, "Moon Safari"))
    }

    @Test fun `downloads fall into failed, completed and in queue`() {
        assertEquals(DownloadGroup.FAILED, downloadGroup(SoulseekTransferState.FAILED))
        assertEquals(DownloadGroup.FAILED, downloadGroup(SoulseekTransferState.CANCELLED))
        assertEquals(DownloadGroup.COMPLETED, downloadGroup(SoulseekTransferState.COMPLETED))
        listOf(SoulseekTransferState.QUEUED, SoulseekTransferState.DOWNLOADING, SoulseekTransferState.PAUSED, SoulseekTransferState.VALIDATING, SoulseekTransferState.PUBLISHING)
            .forEach { assertEquals(it.name, DownloadGroup.QUEUE, downloadGroup(it)) }
    }
}
