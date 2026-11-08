package app.runout

import android.provider.DocumentsContract
import app.runout.library.StorageAnalyzer
import app.runout.library.StorageAnalyzer.Entry
import app.runout.library.StorageKind
import app.runout.model.Album
import app.runout.model.AudioQuality
import app.runout.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class StorageAnalyzerTest {
    private val authority = "com.android.externalstorage.documents"
    private val tree = DocumentsContract.buildTreeDocumentUri(authority, "primary:Music")

    private fun album(folder: String, title: String, hiRes: Boolean) = Album(
        "a-$title", title, "Artist", null, DocumentsContract.buildDocumentUriUsingTree(tree, folder).toString(),
        listOf(Track("t", "", title, 1, 1, 1000)), if (hiRes) AudioQuality("FLAC", 24, 96_000) else AudioQuality("FLAC", 16, 44_100),
    )

    /** /Music with two records, a stray mp3, a half-finished download and a text file. */
    private val folders = mapOf(
        "primary:Music" to listOf(
            Entry("primary:Music/Air", "Air", true, 0),
            Entry("primary:Music/Vela", "Vela", true, 0),
            Entry("primary:Music/notes.txt", "notes.txt", false, 1_000),
        ),
        "primary:Music/Air" to listOf(
            Entry("primary:Music/Air/01.flac", "01 La Femme.flac", false, 40_000_000),
            Entry("primary:Music/Air/02.flac", "02 Sexy Boy.flac", false, 35_000_000),
            Entry("primary:Music/Air/cover.jpg", "cover.jpg", false, 500_000),
        ),
        "primary:Music/Vela" to listOf(
            Entry("primary:Music/Vela/01.flac", "01 Opening.flac", false, 120_000_000),
            Entry("primary:Music/Vela/02.flac", "02 Lowlight.flac.sonic-part", false, 9_000_000),
            Entry("primary:Music/Vela/bonus.mp3", "bonus.mp3", false, 8_000_000),
        ),
    )

    @Test
    fun `a folder adds up by kind, hi-res by its record, with the largest records first`() {
        val albums = listOf(album("primary:Music/Air", "Moon Safari", hiRes = false), album("primary:Music/Vela", "Night Drive", hiRes = true))
        val f = StorageAnalyzer.measure(tree, albums, { _, id -> folders[id] })
        assertEquals("/Music", f.label)
        assertEquals(3, f.folders)
        assertEquals(7, f.files)
        assertEquals(212_501_000L, f.bytes)
        assertEquals(75_000_000L, f.byKind[StorageKind.FLAC_CD])
        assertEquals(120_000_000L, f.byKind[StorageKind.FLAC_HI_RES])
        assertEquals(9_000_000L, f.byKind[StorageKind.PARTIAL])
        // MP3 is its own kind now, right after the FLAC, and still part of its folder's record.
        assertEquals(8_000_000L, f.byKind[StorageKind.MP3])
        assertEquals(null, f.byKind[StorageKind.OTHER_AUDIO])
        assertEquals(listOf(StorageKind.FLAC_HI_RES, StorageKind.FLAC_CD, StorageKind.MP3), StorageKind.entries.take(3))
        assertEquals(500_000L, f.byKind[StorageKind.PICTURES])
        assertEquals(1_000L, f.byKind[StorageKind.OTHER])
        assertEquals(listOf("Night Drive", "Moon Safari"), f.largest.map { it.title })
        assertEquals(128_000_000L, f.largest.first().bytes)
    }

    @Test
    fun `only a record's own folder can be deleted, never the picked folder or one holding another record`() {
        val tree = mapOf(
            // Loose tracks right in /Music, an artist folder with its own tracks and an album inside it, and a plain album.
            "primary:Music" to listOf(Entry("primary:Music/loose.flac", "loose.flac", false, 5), Entry("primary:Music/Air", "Air", true, 0), Entry("primary:Music/Vela", "Vela", true, 0)),
            "primary:Music/Air" to listOf(Entry("primary:Music/Air/single.flac", "single.flac", false, 10), Entry("primary:Music/Air/Moon Safari", "Moon Safari", true, 0)),
            "primary:Music/Air/Moon Safari" to listOf(Entry("primary:Music/Air/Moon Safari/01.flac", "01.flac", false, 30)),
            "primary:Music/Vela" to listOf(Entry("primary:Music/Vela/01.flac", "01.flac", false, 20)),
        )
        val f = StorageAnalyzer.measure(this.tree, emptyList(), { _, id -> tree[id] })
        val byTitle = f.largest.associateBy { it.title }
        assertFalse("the picked folder itself", byTitle.getValue("Music").deletable)
        assertFalse("holds Moon Safari", byTitle.getValue("Air").deletable)
        assertTrue(byTitle.getValue("Moon Safari").deletable)
        assertTrue(byTitle.getValue("Vela").deletable)
        assertEquals(DocumentsContract.buildDocumentUriUsingTree(this.tree, "primary:Music/Vela").toString(), byTitle.getValue("Vela").folderUri)
    }

    @Test
    fun `a folder that can't be opened says so, and downloads inside music are spotted`() {
        val f = StorageAnalyzer.measure(tree, emptyList(), { _, _ -> null })
        assertNotNull(f.error)
        val inside = DocumentsContract.buildTreeDocumentUri(authority, "primary:Music/Sonic")
        val elsewhere = DocumentsContract.buildTreeDocumentUri(authority, "primary:Download")
        val lookalike = DocumentsContract.buildTreeDocumentUri(authority, "primary:Music2")
        assertTrue(StorageAnalyzer.inside(inside, tree))
        assertTrue(StorageAnalyzer.inside(tree, tree))
        assertFalse(StorageAnalyzer.inside(elsewhere, tree))
        assertFalse(StorageAnalyzer.inside(lookalike, tree))
    }
}
