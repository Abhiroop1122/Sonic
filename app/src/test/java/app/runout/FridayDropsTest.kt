package app.runout

import android.net.Uri
import app.runout.drops.DropItem
import app.runout.drops.DropState
import app.runout.drops.DropsRepository
import app.runout.model.Source
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Suite 9 — Friday Drops from Internet Archive. Uses responses recorded from archive.org
 * (search and an album's file list) and serves real audio files in place of the downloads.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class FridayDropsTest {

    private val context get() = Fixtures.context
    private val utc: ZoneId = ZoneOffset.UTC
    private fun at(date: String) = LocalDate.parse(date).atStartOfDay(utc).plusHours(12).toInstant().toEpochMilli()

    /** A two-track FLAC album as Internet Archive would describe it. */
    private fun metadataFor(id: String) = JSONObject().apply {
        put("metadata", JSONObject().put("identifier", id).put("licenseurl", "https://creativecommons.org/licenses/by/4.0/"))
        put("files", JSONArray().apply {
            put(JSONObject().put("name", "01 Opening.flac").put("format", "Flac").put("source", "original").put("track", "1").put("title", "Opening").put("length", "2.0").put("size", "66089"))
            put(JSONObject().put("name", "02 Lowlight.flac").put("format", "Flac").put("source", "original").put("track", "2/2").put("title", "Lowlight").put("length", "0:02").put("size", "66915"))
            put(JSONObject().put("name", "01 Opening.mp3").put("format", "VBR MP3").put("source", "derivative"))
            put(JSONObject().put("name", "cover.jpg").put("format", "JPEG").put("source", "original"))
        })
    }.toString().toByteArray()

    private fun http(searchOk: Boolean = true) = FakeHttp(
        mapOf(
            "advancedsearch.php" to { if (searchOk) Fixtures.bytes("ia/search_page1.json") else null },
            "/metadata/" to { metadataFor("x") },
            "01%20Opening.flac" to { Fixtures.bytes("Music/Vela Park - Night Drive (2023)/01 Opening.flac") },
            "02%20Lowlight.flac" to { Fixtures.bytes("Music/Vela Park - Night Drive (2023)/02 Lowlight.flac") },
            "cover.jpg" to { Fixtures.bytes("Music/Untagged/cover.jpg") },
            "services/img" to { Fixtures.bytes("Music/Untagged/cover.jpg") },
        ),
    )

    private fun repo(h: FakeHttp, date: String = "2026-10-16") = DropsRepository(context, h, clock = { at(date) }, zone = utc, canDownload = { true }, publishDownload = { album, _ -> album })

    @Test
    fun `a new drop opens every Friday`() {
        val fri = DropsRepository.weekIndex(LocalDate.parse("2026-10-16"))
        assertEquals(fri, DropsRepository.weekIndex(LocalDate.parse("2026-10-22"))) // next Thursday, same drop
        assertEquals(fri - 1, DropsRepository.weekIndex(LocalDate.parse("2026-10-15"))) // the Thursday before
        assertEquals(fri + 1, DropsRepository.weekIndex(LocalDate.parse("2026-10-23")))
        assertEquals(LocalDate.parse("2026-10-16"), DropsRepository.opens(fri))
        assertEquals(0L, DropsRepository.weekIndex(LocalDate.parse("1970-01-02")))
    }

    @Test
    fun `everyone gets the same ten albums for a week`() {
        val pool = (1..60).map { DropItem("id$it", "Album $it", "Artist", 2020, null) }
        val a = DropsRepository.pick(pool, DropsRepository.seed(2911))
        assertEquals(10, a.size)
        assertEquals(10, a.map { it.identifier }.toSet().size)
        assertEquals(a, DropsRepository.pick(pool, DropsRepository.seed(2911)))
        assertEquals(a, DropsRepository.pick(pool + pool.take(5), DropsRepository.seed(2911))) // duplicates in a page are ignored
        assertNotEquals(a, DropsRepository.pick(pool, DropsRepository.seed(2912)))
        assertTrue(DropsRepository.searchUrl(3).contains("page=3"))
        assertTrue(DropsRepository.searchUrl(3).contains("creativecommons"))
        assertTrue("drops are picked from FLAC albums only", DropsRepository.searchUrl(3).contains("format%3Aflac"))
    }

    @Test
    fun `from 30 October the ten don't depend on how the archive orders its page`() {
        val pool = (1..100).map { DropItem("id$it", "Album $it", "Artist", 2020, null) }
        val week = DropsRepository.STABLE_FROM
        val a = DropsRepository.pickStable(pool, week)
        assertEquals(10, a.map { it.identifier }.toSet().size)
        assertEquals("any order of the same page", a.toSet(), DropsRepository.pickStable(pool.shuffled(java.util.Random(4)), week).toSet())
        assertEquals(a, DropsRepository.pickStable(pool.reversed() + pool.take(7), week))
        // An album that wasn't one of the ten leaving the page changes nothing.
        val outsider = pool.first { it !in a }
        assertEquals(a, DropsRepository.pickStable(pool - outsider, week))
        assertNotEquals(a, DropsRepository.pickStable(pool, week + 1))
        // The archive's oldest-first order, which new uploads don't reshuffle; earlier weeks keep theirs.
        assertTrue(DropsRepository.searchUrl(DropsRepository.pageFor(week), stable = true).contains("publicdate+asc"))
        assertTrue(DropsRepository.searchUrl(DropsRepository.pageFor(week - 1)).contains("downloads+desc"))
        assertEquals(DropsRepository.pick(pool, DropsRepository.seed(week - 1)), DropsRepository.pickFor(week - 1, pool))
        assertTrue(DropsRepository.pageFor(week + 7) in 1..DropsRepository.STABLE_PAGES.toInt())
    }

    @Test
    fun `from 30 October the pool is everything published before that Friday, and an empty answer isn't kept`() = runBlocking {
        val week = DropsRepository.STABLE_FROM
        val url = DropsRepository.searchUrl(3, stable = true, before = DropsRepository.opens(week))
        assertTrue(url, url.contains(java.net.URLEncoder.encode("publicdate:[* TO 2026-10-30T00:00:00Z]", "UTF-8")))
        // The page comes from the pool's size: 12,587 albums are 126 pages.
        assertTrue(DropsRepository.pageFor(week, pages = 126) in 1..126)
        assertEquals(DropsRepository.pageFor(week, pages = 126), DropsRepository.pageFor(week, pages = 126))

        val empty = """{"response":{"numFound":0,"start":0,"docs":[]}}""".toByteArray()
        val h = FakeHttp(mapOf("advancedsearch.php" to { empty }))
        val drops = repo(h, date = "2026-10-30")
        assertTrue(drops.refresh() is DropState.Failed)
        assertTrue("nothing kept: the next try asks again", drops.refresh() is DropState.Failed)
        assertTrue(h.calls.count { "rows=100" in it } >= 2)
    }

    @Test
    fun `the drops tab marks a week until its drop is opened`() = runBlocking {
        val drops = repo(http())
        assertTrue(drops.newDrop.value)
        drops.refresh()
        assertFalse(drops.newDrop.value)
        assertFalse("seen stays seen", repo(http()).newDrop.value)
        assertTrue("next Friday is new", repo(http(), date = "2026-10-23").newDrop.value)
    }

    @Test
    fun `search results from archive_org are read correctly`() {
        val items = DropsRepository.parseSearch(Fixtures.text("ia/search_page1.json"))
        assertEquals(5, items.size)
        assertEquals(DropItem("NS050", "Another Day, Another Way", "No-Source Netlabel", 2012, "http://creativecommons.org/licenses/by-nc-sa/4.0/"), items[0])
        assertNull(items[1].year)
        assertEquals("Unknown artist", items[4].creator)
        assertEquals("https://archive.org/services/img/NS050", items[0].coverUrl)
    }

    @Test
    fun `the download plan picks the best audio and the cover`() {
        val plan = DropsRepository.planDownload(JSONObject(Fixtures.text("ia/metadata_NS050.json")))
        assertEquals("NONE", plan.codec)
        assertTrue(plan.tracks.isEmpty())
        val flac = DropsRepository.planDownload(JSONObject(String(metadataFor("x"))))
        assertEquals("FLAC", flac.codec)
        assertEquals(listOf("01 Opening.flac", "02 Lowlight.flac"), flac.tracks.map { it.name })
        assertEquals(2_000L, flac.tracks[1].lengthMs)
        assertEquals("https://archive.org/download/x/01%20Opening.flac", DropsRepository.fileUrl("x", "01 Opening.flac"))
    }

    @Test
    fun `a listen streams the archive's MP3 of a track and falls back to its FLAC`() = runBlocking {
        val meta = JSONObject().apply {
            put("metadata", JSONObject().put("identifier", "x"))
            put("files", JSONArray().apply {
                put(JSONObject().put("name", "01 Opening.flac").put("format", "Flac").put("source", "original").put("track", "1").put("title", "Opening"))
                put(JSONObject().put("name", "02 Lowlight.flac").put("format", "Flac").put("source", "original").put("track", "2").put("title", "Lowlight"))
                put(JSONObject().put("name", "01 Opening.mp3").put("format", "VBR MP3").put("source", "derivative").put("original", "01 Opening.flac"))
                // An MP3 made from something else isn't a stand-in for track two.
                put(JSONObject().put("name", "02 Other.mp3").put("format", "VBR MP3").put("source", "derivative").put("original", "02 Other.flac"))
            })
        }.toString().toByteArray()
        val r = repo(FakeHttp(mapOf("/metadata/" to { meta })))
        val tracks = r.preview(DropItem("x", "X", "Y", null, null)).getOrThrow()
        assertEquals(listOf("Opening", "Lowlight"), tracks.map { it.title })
        assertEquals("https://archive.org/download/x/01%20Opening.mp3", tracks[0].url)
        assertEquals("https://archive.org/download/x/02%20Lowlight.flac", tracks[1].url)
        // The claim still plans the FLACs only.
        assertEquals(listOf("01 Opening.flac", "02 Lowlight.flac"), DropsRepository.planDownload(JSONObject(String(meta))).tracks.map { it.name })
    }

    @Test
    fun `both sign in and FLAC availability are enforced before a claim is spent`() = runBlocking {
        val deniedHttp = http()
        val denied = DropsRepository(context, deniedHttp, clock = { at("2026-10-16") }, zone = utc)
        val item = (denied.refresh() as DropState.Ready).week.items.first()
        val before = deniedHttp.calls.size
        assertTrue(denied.claim(item).exceptionOrNull()!!.message!!.contains("Sign in"))
        assertEquals(before, deniedHttp.calls.size)
        assertEquals(3, denied.claimsLeft)

        val unavailableHttp = FakeHttp(mapOf(
            "advancedsearch.php" to { Fixtures.bytes("ia/search_page1.json") },
            "/metadata/" to { Fixtures.bytes("ia/metadata_NS050.json") },
        ))
        val unavailable = repo(unavailableHttp)
        unavailable.refresh()
        assertTrue(unavailable.claim(item).exceptionOrNull()!!.message!!.contains("unavailable in FLAC"))
        assertEquals(3, unavailable.claimsLeft)
        assertTrue(unavailable.albums.value.isEmpty())
    }

    @Test
    fun `publication failure does not consume a claim`() = runBlocking {
        val h = http()
        val r = DropsRepository(context, h, clock = { at("2026-10-16") }, zone = utc,
            canDownload = { true }, publishDownload = { _, _ -> error("Folder permission was lost") })
        val item = (r.refresh() as DropState.Ready).week.items.first()
        assertTrue(r.claim(item).isFailure)
        assertEquals(3, r.claimsLeft)
        assertTrue(r.albums.value.isEmpty())
    }

    @Test
    fun `Archive FLAC labels cannot admit renamed MP3 bytes`() = runBlocking {
        val h = FakeHttp(mapOf(
            "advancedsearch.php" to { Fixtures.bytes("ia/search_page1.json") },
            "/metadata/" to { metadataFor("x") },
            ".flac" to { Fixtures.bytes("Music/Ines Varga/Paper Moons/01 Paper Moons.mp3") },
        ))
        val r = repo(h)
        val item = (r.refresh() as DropState.Ready).week.items.first()
        assertTrue(r.claim(item).exceptionOrNull()!!.message!!.contains("not valid FLAC"))
        assertEquals(3, r.claimsLeft)
        assertTrue(r.albums.value.isEmpty())
    }

    @Test
    fun `the week's drop loads once and is cached`() = runBlocking {
        val h = http()
        val r = repo(h)
        val week = (r.refresh() as DropState.Ready).week
        assertEquals(5, week.items.size)
        assertEquals(LocalDate.parse("2026-10-16"), week.opens)
        assertEquals(LocalDate.parse("2026-10-23"), week.closes)
        val calls = h.calls.size
        assertTrue(repo(h).refresh() is DropState.Ready)
        assertEquals("cached, no new request", calls, h.calls.size)
    }

    @Test
    fun `no connection gives a clear message`() = runBlocking {
        val s = repo(http(searchOk = false)).refresh()
        assertTrue(s is DropState.Failed)
        assertTrue((s as DropState.Failed).reason.contains("Internet Archive"))
    }

    @Test
    fun `claiming downloads the album onto your shelf`() = runBlocking {
        val r = repo(http())
        val week = (r.refresh() as DropState.Ready).week
        val item = week.items.first()
        val progress = mutableListOf<Float>()
        val album = r.claim(item) { progress += it }.getOrThrow()
        assertEquals(Source.DROP, album.source)
        assertEquals(item.title, album.title)
        assertEquals(item.license, album.license)
        assertEquals("FLAC · 24-bit · 96 kHz", album.quality.label)
        assertEquals(listOf("Opening", "Lowlight"), album.tracks.map { it.title })
        assertTrue(album.tracks.all { File(Uri.parse(it.uri).path!!).length() > 60_000 })
        assertEquals(2_000L, album.tracks[0].durationMs)
        assertTrue(File(album.coverPath!!).exists())
        assertEquals(1f, progress.last())
        assertTrue(album.audioShareable) // Creative Commons, so friends can be sent it
        assertEquals(setOf(item.identifier), r.claimed.value)
        assertEquals(2, r.claimsLeft)
        // It's still there after a restart.
        val again = repo(http())
        assertEquals(listOf(album.id), again.albums.value.map { it.id })
        assertEquals(setOf(item.identifier), again.claimed.value)
    }

    @Test
    fun `three claims a week, then wait for Friday`() = runBlocking {
        val r = repo(http())
        val items = (r.refresh() as DropState.Ready).week.items
        items.take(3).forEach { assertTrue(r.claim(it).isSuccess) }
        assertEquals(0, r.claimsLeft)
        val fourth = r.claim(items[3])
        assertTrue(fourth.isFailure)
        assertTrue(fourth.exceptionOrNull()!!.message!!.contains("claimed all 3"))
        // Re-claiming one you already have doesn't count again.
        assertTrue(r.claim(items[0]).isSuccess)
        // Next Friday the allowance resets.
        val next = repo(http(), date = "2026-10-23")
        next.refresh()
        assertEquals(3, next.claimsLeft)
    }

    @Test
    fun `stale Friday selection is refreshed and refused before downloading`() = runBlocking {
        var now = at("2026-10-16")
        val h = http()
        val r = DropsRepository(context, h, clock = { now }, zone = utc,
            canDownload = { true }, publishDownload = { album, _ -> album })
        val item = (r.refresh() as DropState.Ready).week.items.first()
        val oldIndex = r.currentIndex()
        now = at("2026-10-23")
        val beforeMetadata = h.calls.count { "/metadata/" in it }
        val result = r.claim(item)
        assertTrue("stale selection must be refused", result.isFailure)
        assertEquals(beforeMetadata, h.calls.count { "/metadata/" in it })
        assertEquals(r.currentIndex(), (r.state.value as DropState.Ready).week.index)
        assertTrue(context.getSharedPreferences("drops", 0).getStringSet("claims-$oldIndex", emptySet())!!.isEmpty())
    }

    @Test
    fun `claim finishing after Friday is charged only to its admitted week`() = runBlocking {
        var now = at("2026-10-16")
        val r = DropsRepository(context, http(), clock = { now }, zone = utc,
            canDownload = { true }, publishDownload = { album, _ -> now = at("2026-10-23"); album })
        val week = (r.refresh() as DropState.Ready).week
        val item = week.items.first()
        r.claim(item).getOrThrow()
        val prefs = context.getSharedPreferences("drops", 0)
        assertEquals(setOf(item.identifier), prefs.getStringSet("claims-${week.index}", emptySet()))
        assertTrue(prefs.getStringSet("claims-${r.currentIndex()}", emptySet())!!.isEmpty())
        assertTrue("visible allowance belongs to current week", r.claimed.value.isEmpty())
    }

    @Test
    fun `claimed drops can be taken off the shelf`() = runBlocking {
        val r = repo(http())
        val item = (r.refresh() as DropState.Ready).week.items.first()
        val album = r.claim(item).getOrThrow()
        r.remove(album.id)
        assertTrue(r.albums.value.isEmpty())
        assertTrue(repo(http()).albums.value.isEmpty())
        assertEquals("the claim still counts", 2, r.claimsLeft)
    }
}
