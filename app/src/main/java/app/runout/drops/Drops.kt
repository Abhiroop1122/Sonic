package app.runout.drops

import android.content.Context
import android.net.Uri
import app.runout.library.AlbumJson
import app.runout.library.ArtworkRepository
import app.runout.library.LibraryScanner
import app.runout.library.FlacPolicy
import app.runout.model.Album
import app.runout.model.AudioQuality
import app.runout.model.Source
import app.runout.model.Track
import app.runout.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

data class DropItem(
    val identifier: String,
    val title: String,
    val creator: String,
    val year: Int?,
    val license: String?,
) {
    val coverUrl: String get() = "https://archive.org/services/img/$identifier"
}

/** One FLAC track of a drop, as listed before claiming. */
data class DropTrack(val title: String, val url: String, val lengthMs: Long?)

data class DropWeek(val index: Long, val opens: LocalDate, val closes: LocalDate, val items: List<DropItem>)

sealed interface DropState {
    data object Idle : DropState
    data object Loading : DropState
    data class Ready(val week: DropWeek) : DropState
    data class Failed(val reason: String) : DropState
}

/**
 * Friday Drops: every Friday, ten Creative Commons albums from Internet Archive's netlabel
 * collection, picked from the week number so everyone sees the same ten. You can claim three a
 * week; a claimed album downloads straight from archive.org, so there's no server to run.
 */
class DropsRepository(
    private val context: Context,
    private val http: Http,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val canDownload: () -> Boolean = { false },
    private val publishDownload: suspend (Album, File) -> Album = { _, _ -> error("Choose a download folder first") },
) {
    private val prefs = context.getSharedPreferences("drops", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "drops")
    private val listFile = File(context.filesDir, "drops.json")

    private val _state = MutableStateFlow<DropState>(DropState.Idle)
    val state: StateFlow<DropState> = _state.asStateFlow()

    private val _albums = MutableStateFlow(AlbumJson.readValidatedList(context, listFile))
    private val claimLock = Mutex()
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()

    private val _claimed = MutableStateFlow(claimedThisWeek())
    val claimed: StateFlow<Set<String>> = _claimed.asStateFlow()

    /** This week's drop hasn't been opened yet on this phone: the orange square on the Drops tab. */
    private val _newDrop = MutableStateFlow(!seen(currentIndex()))
    val newDrop: StateFlow<Boolean> = _newDrop.asStateFlow()
    /** Coming back to the app, perhaps on a new Friday. */
    fun checkWeek() { _newDrop.value = !seen(currentIndex()) }
    private fun seen(index: Long) = prefs.contains(weekKey(index))

    val claimsLeft: Int get() = (CLAIMS_PER_WEEK - _claimed.value.size).coerceAtLeast(0)

    fun currentIndex(): Long = weekIndex(Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate())

    suspend fun refresh(): DropState = withContext(Dispatchers.IO) {
        val index = currentIndex()
        _claimed.value = claimedThisWeek()
        prefs.getString(weekKey(index), null)?.let { cached ->
            runCatching { parseCached(index, cached) }.getOrNull()?.let {
                _state.value = DropState.Ready(it)
                _newDrop.value = false
                return@withContext _state.value
            }
        }
        _state.value = DropState.Loading
        val stable = index >= STABLE_FROM
        // The stable pick draws on every album published before this Friday: a pool whose size
        // can't move during the week, so everyone lands on the same page.
        val before = if (stable) opens(index) else null
        val page = if (!stable) pageFor(index) else {
            val pages = poolPages(index)
            if (pages == null) {
                _state.value = DropState.Failed("Couldn't reach Internet Archive. Check your connection.")
                return@withContext _state.value
            }
            // Last week's page, so this week can't land on it again: every one of the ten is new.
            val previous = if (index - 1 >= STABLE_FROM) poolPages(index - 1)?.let { pageFor(index - 1, it) } else null
            pageFor(index, pages, previous)
        }
        val body = http.get(searchUrl(page, stable = stable, before = before))?.toString(Charsets.UTF_8)
        if (body == null) {
            _state.value = DropState.Failed("Couldn't reach Internet Archive. Check your connection.")
            return@withContext _state.value
        }
        val docs = runCatching { parseSearch(body) }.getOrElse {
            _state.value = DropState.Failed("Internet Archive sent something unexpected.")
            return@withContext _state.value
        }
        // An empty page is the archive having a moment, not a week without albums: say so, and
        // keep nothing, so the next try fetches again instead of showing an empty week till Friday.
        if (docs.isEmpty()) {
            _state.value = DropState.Failed("Internet Archive sent an empty list. Try again in a bit.")
            return@withContext _state.value
        }
        val week = DropWeek(index, opens(index), opens(index).plusDays(7), pickFor(index, docs))
        prefs.edit().putString(weekKey(index), JSONArray(week.items.map { it.toJson() }).toString()).apply()
        _state.value = DropState.Ready(week)
        _newDrop.value = false
        _state.value
    }

    /** How many pages of [ROWS] the pool for week [index] fills: albums published before its Friday. */
    private fun poolPages(index: Long): Long? =
        http.get(searchUrl(1, stable = true, before = opens(index), rows = 0))?.toString(Charsets.UTF_8)
            ?.let { runCatching { JSONObject(it).getJSONObject("response").optLong("numFound") }.getOrNull() }
            ?.let { (it + ROWS - 1) / ROWS }

    /**
     * What's on a drop before you spend a claim: its FLAC tracks, each with a streaming address so a
     * few seconds can be heard. The address is the archive's own MP3 of the track when it made one,
     * a fraction of the FLAC's size, so a listen starts quickly; the claim still takes the FLAC.
     * Reads the archive's metadata only; nothing is downloaded.
     */
    suspend fun preview(item: DropItem): Result<List<DropTrack>> = withContext(Dispatchers.IO) {
        runCatching {
            require(item.identifier.matches(Regex("[A-Za-z0-9_.-]+"))) { "Invalid Internet Archive identifier" }
            val meta = http.get("https://archive.org/metadata/${item.identifier}")?.toString(Charsets.UTF_8)
                ?: error("Couldn't load the album from Internet Archive")
            val json = JSONObject(meta)
            planDownload(json).tracks.map { f ->
                DropTrack(f.title ?: f.name.substringAfterLast('/').substringBeforeLast('.'), fileUrl(item.identifier, listenName(json, f.name)), f.lengthMs)
            }
        }.onFailure { if (it is CancellationException) throw it }
    }

    /** Downloads one of this week's albums onto the shelf. */
    suspend fun claim(item: DropItem, onProgress: (Float) -> Unit = {}): Result<Album> = withContext(Dispatchers.IO) {
        claimLock.withLock { runCatching {
            check(canDownload()) { "Sign in to Soulseek to download and keep music from Internet Archive" }
            require(item.identifier.matches(Regex("[A-Za-z0-9_.-]+"))) { "Invalid Internet Archive identifier" }
            val index = currentIndex()
            val week = (state.value as? DropState.Ready)?.week ?: error("This week's drop hasn't loaded yet")
            if (week.index != index) {
                refresh()
                error("A new Friday Drop has opened. Choose an album from this week's selection.")
            }
            val admittedClaims = claimsFor(index)
            _claimed.value = admittedClaims
            require(week.items.any { it.identifier == item.identifier }) { "That album isn't in this week's drop" }
            if (item.identifier !in admittedClaims) check(admittedClaims.size < CLAIMS_PER_WEEK) { "You've claimed all $CLAIMS_PER_WEEK records this week" }

            val meta = http.get("https://archive.org/metadata/${item.identifier}")?.toString(Charsets.UTF_8)
                ?: error("Couldn't load the album from Internet Archive")
            val plan = planDownload(JSONObject(meta))
            require(plan.tracks.isNotEmpty()) { "This album is unavailable in FLAC. No claim was used." }
            require(plan.tracks.map { it.name.substringAfterLast('/') }.distinct().size == plan.tracks.size) { "Album contains conflicting FLAC filenames" }
            val albumDir = File(dir, item.identifier).apply { mkdirs() }
            val totalBytes = plan.tracks.sumOf { it.size }.coerceAtLeast(1)
            var doneBytes = 0L
            val tracks = plan.tracks.mapIndexed { i, f ->
                val target = File(albumDir, f.name.substringAfterLast('/'))
                check(canDownload()) { "Sign in to Soulseek to continue downloading" }
                if (!FlacPolicy.isValid(target) || (f.size > 0 && target.length() != f.size)) {
                    val partial = File(albumDir, target.name + ".part")
                    val ok = http.download(fileUrl(item.identifier, f.name), partial) { b -> onProgress(((doneBytes + b).toFloat() / totalBytes).coerceAtMost(0.99f)) }
                    check(ok) { "Download failed for ${f.title}" }
                    val valid = partial.inputStream().use { FlacPolicy.read(it, partial.length()) } != null
                    check(valid) { partial.delete(); "${f.title ?: target.name} is not valid FLAC audio. The broken download was removed. No claim was used." }
                    check(f.size <= 0 || partial.length() == f.size) { "${f.title ?: target.name} did not download completely. No claim was used." }
                    check(partial.renameTo(target)) { "Couldn't save ${target.name}" }
                }
                doneBytes += f.size
                val info = FlacPolicy.read(target) ?: error("Invalid FLAC audio")
                Track(
                    id = "drop-${item.identifier}/${target.name}",
                    uri = Uri.fromFile(target).toString(),
                    title = f.title ?: info?.title ?: target.nameWithoutExtension,
                    trackNumber = f.track ?: (i + 1),
                    discNumber = 1,
                    durationMs = info?.durationMs?.takeIf { it > 0 } ?: f.lengthMs ?: LibraryScanner.estimateDurationMs(target.length(), plan.codec),
                    fileName = target.name,
                    trackGainDb = info?.trackGainDb,
                )
            }
            val cover = plan.coverName?.let { name ->
                val f = File(albumDir, "cover." + name.substringAfterLast('.', "jpg").lowercase())
                if (f.exists() || http.download(fileUrl(item.identifier, name), f)) f else null
            } ?: File(albumDir, "cover.jpg").takeIf { it.exists() || http.download(item.coverUrl, it) }
            val first = tracks.firstOrNull()?.let { File(Uri.parse(it.uri).path!!) }
            val info = first?.let { FlacPolicy.read(it) }
            val staged = Album(
                id = "drop-${item.identifier}",
                title = item.title,
                artist = item.creator,
                year = item.year,
                folderUri = Uri.fromFile(albumDir).toString(),
                tracks = tracks,
                quality = AudioQuality(plan.codec, info?.bits, info?.sampleRate, info?.bitrateKbps),
                coverPath = cover?.absolutePath,
                tint = cover?.let { ArtworkRepository.averageColor(it) },
                source = Source.DROP,
                license = item.license ?: plan.license,
                notes = "Friday Drop · week of ${week.opens}",
            )
            check(canDownload()) { "Sign in to Soulseek to finish the download" }
            val album = publishDownload(staged, albumDir)
            check(album.tracks.isNotEmpty() && album.tracks.all { FlacPolicy.read(context, it) != null }) { "Couldn't verify the saved FLAC album" }
            _albums.value = _albums.value.filterNot { it.id == album.id } + album
            AlbumJson.writeList(listFile, _albums.value)
            val claimed = admittedClaims + item.identifier
            prefs.edit().putStringSet("claims-$index", claimed).apply()
            _claimed.value = if (currentIndex() == index) claimed else claimedThisWeek()
            onProgress(1f)
            album
        }.onFailure { if (it is CancellationException) throw it } }
    }

    /** Takes a claimed album off the shelf. The claim still counts for the week. */
    fun remove(albumId: String) {
        val album = _albums.value.firstOrNull { it.id == albumId } ?: return
        _albums.value = _albums.value - album
        AlbumJson.writeList(listFile, _albums.value)
        File(dir, albumId.removePrefix("drop-")).deleteRecursively()
    }

    /** A small cover for the drop list, cached so it's only fetched once. */
    suspend fun thumbnail(item: DropItem): File? = withContext(Dispatchers.IO) {
        val f = File(context.cacheDir, "drop-thumbs/${item.identifier}.jpg")
        if (f.exists() && f.length() > 0) return@withContext f
        f.parentFile?.mkdirs()
        if (http.download(item.coverUrl, f)) f else null
    }

    private fun claimedThisWeek(): Set<String> = claimsFor(currentIndex())
    private fun claimsFor(index: Long): Set<String> = prefs.getStringSet("claims-$index", emptySet())?.toSet() ?: emptySet()

    private fun parseCached(index: Long, json: String): DropWeek {
        val arr = JSONArray(json)
        return DropWeek(index, opens(index), opens(index).plusDays(7), (0 until arr.length()).map { dropItemFromJson(arr.getJSONObject(it)) })
    }

    data class PlannedFile(val name: String, val title: String?, val track: Int?, val lengthMs: Long?, val size: Long)
    data class Plan(val codec: String, val tracks: List<PlannedFile>, val coverName: String?, val license: String?)

    companion object {
        const val CLAIMS_PER_WEEK = 3
        const val PER_DROP = 10
        const val PAGES = 20L
        private const val ROWS = 100

        /** Drops open on Fridays: the index counts Fridays since 2 Jan 1970 (a Friday). */
        fun weekIndex(date: LocalDate): Long {
            val friday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.FRIDAY))
            return ChronoUnit.WEEKS.between(LocalDate.of(1970, 1, 2), friday)
        }

        fun opens(index: Long): LocalDate = LocalDate.of(1970, 1, 2).plusWeeks(index)

        /** "flac" in the key: weeks picked before drops were FLAC-only are picked again. */
        fun weekKey(index: Long): String = "week-flac-$index"

        fun seed(index: Long): Long = (index * 2_654_435_761L) and 0x7FFFFFFF

        /**
         * The first week picked the stable way. Before it, a page of the archive's most-downloaded
         * albums was shuffled; but download counts at a page's edges differ by a handful, so the page
         * (and with it the whole ten) could change during the week, and two people opening Drops on
         * different days saw different albums. Earlier weeks keep that pick so nobody's week changes.
         */
        const val STABLE_FROM = 2965L // Friday 30 October 2026
        /** Pages before the pool's size is known; the stable pick asks the archive how many there are. */
        const val STABLE_PAGES = 100L

        /**
         * The first week that walks the pool: each week steps [STRIDE] pages on, a step that shares no
         * factor with the page count, so weeks in a row never read the same page and every page comes
         * round once before any comes back. Pages hold different albums, so all ten are new each week.
         * 30 October keeps the page 1.3.0 gives it, so everyone sees the same ten that Friday.
         */
        const val WALK_FROM = 2966L // Friday 6 November 2026
        private const val STRIDE = 7_919L

        /**
         * The week's page among [pages]: the most-downloaded 20 before [STABLE_FROM], the whole pool
         * after, walked from [WALK_FROM]. [previous] is last week's page: never read twice running.
         */
        fun pageFor(index: Long, pages: Long = if (index >= STABLE_FROM) STABLE_PAGES else PAGES, previous: Int? = null): Int {
            val n = pages.coerceAtLeast(1)
            if (index < WALK_FROM) return 1 + (seed(index) % n).toInt()
            var step = STRIDE % n
            while (n > 1 && gcd(step, n) != 1L) step++
            val page = 1 + ((index * step) % n).toInt()
            return if (page == previous && n > 1) page % n.toInt() + 1 else page
        }

        private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

        fun pickFor(index: Long, docs: List<DropItem>): List<DropItem> =
            if (index >= STABLE_FROM) pickStable(docs, index) else pick(docs, seed(index))

        /**
         * [stable]: by the date each album went public, oldest first, which new uploads don't reorder,
         * so a page holds the same albums all week. Before [STABLE_FROM]: most downloaded first.
         */
        fun searchUrl(page: Int, stable: Boolean = false, before: LocalDate? = null, rows: Int = ROWS): String {
            // Only albums the archive lists with FLAC files, so every drop can be claimed.
            val q = "collection:netlabels AND mediatype:audio AND licenseurl:*creativecommons* AND format:flac" +
                (before?.let { " AND publicdate:[* TO ${it}T00:00:00Z]" } ?: "")
            val sort = if (stable) "publicdate+asc" else "downloads+desc"
            return "https://archive.org/advancedsearch.php?q=" + URLEncoder.encode(q, "UTF-8") +
                listOf("identifier", "title", "creator", "year", "licenseurl").joinToString("") { "&fl%5B%5D=$it" } +
                "&sort%5B%5D=$sort&rows=$rows&page=$page&output=json"
        }

        /**
         * Ten albums for the week whatever order the page arrives in: each album ranked by a hash of
         * the week and its identifier, lowest first. An album joining or leaving the page changes the
         * ten only if it would have been one of them.
         */
        fun pickStable(docs: List<DropItem>, index: Long): List<DropItem> {
            val sha = java.security.MessageDigest.getInstance("SHA-256")
            fun rank(id: String) = sha.digest("$index\u0000$id".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            return docs.distinctBy { it.identifier }.sortedBy { rank(it.identifier) }.take(PER_DROP)
        }

        fun fileUrl(identifier: String, name: String): String =
            "https://archive.org/download/$identifier/" + name.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

        fun parseSearch(body: String): List<DropItem> {
            val docs = JSONObject(body).getJSONObject("response").getJSONArray("docs")
            return (0 until docs.length()).mapNotNull { i ->
                val d = docs.getJSONObject(i)
                val id = d.optString("identifier").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                DropItem(
                    identifier = id,
                    title = textOf(d.opt("title")) ?: id,
                    creator = textOf(d.opt("creator")) ?: "Unknown artist",
                    year = textOf(d.opt("year"))?.take(4)?.toIntOrNull(),
                    license = textOf(d.opt("licenseurl")),
                )
            }
        }

        /** Ten albums for the week, in the same order for everyone. */
        fun pick(docs: List<DropItem>, seed: Long): List<DropItem> =
            docs.distinctBy { it.identifier }.shuffled(java.util.Random(seed)).take(PER_DROP)

        /** FLAC only. Archive format labels never override the filename or downloaded bytes. */
        fun planDownload(meta: JSONObject): Plan {
            val files = meta.getJSONArray("files")
            val all = (0 until files.length()).map { files.getJSONObject(it) }
            val families = listOf("FLAC" to setOf("flac", "24bit flac"))
            for ((codec, formats) in families) {
                val chosen = all.filter { it.optString("format").lowercase() in formats && FlacPolicy.isFlacName(it.optString("name")) }
                    .let { list -> list.filter { it.optString("source") == "original" }.ifEmpty { list } }
                    .sortedWith(compareBy({ trackNo(it.optString("track")) ?: Int.MAX_VALUE }, { it.optString("name") }))
                if (chosen.isNotEmpty()) {
                    val cover = all.firstOrNull { it.optString("format") == "Book Cover" }
                        ?: all.firstOrNull { f ->
                            val n = f.optString("name").lowercase()
                            (n.endsWith(".jpg") || n.endsWith(".png")) && ("cover" in n || "front" in n) && f.optString("source") == "original"
                        }
                        ?: all.firstOrNull { f -> f.optString("format") == "JPEG" && f.optString("source") == "original" }
                    return Plan(
                        codec = codec,
                        tracks = chosen.map { f ->
                            PlannedFile(
                                name = f.getString("name"),
                                title = f.optString("title").takeIf { it.isNotBlank() },
                                track = trackNo(f.optString("track")),
                                lengthMs = lengthMs(f.optString("length")),
                                size = f.optString("size").toLongOrNull() ?: 0L,
                            )
                        },
                        coverName = cover?.optString("name"),
                        license = meta.optJSONObject("metadata")?.let { textOf(it.opt("licenseurl")) },
                    )
                }
            }
            return Plan("NONE", emptyList(), null, null)
        }

        /**
         * The file to stream for a listen to the FLAC [flac]: the MP3 the archive derived from it
         * ("VBR MP3", its `original` naming the FLAC) when there is one, else the FLAC itself.
         */
        fun listenName(meta: JSONObject, flac: String): String {
            val files = meta.optJSONArray("files") ?: return flac
            return (0 until files.length()).map { files.getJSONObject(it) }.firstOrNull { f ->
                "MP3" in f.optString("format").uppercase() && f.optString("original") == flac && f.optString("name").isNotBlank()
            }?.optString("name") ?: flac
        }

        fun trackNo(s: String?): Int? = s?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull()

        /** "240.9" seconds or "4:01" → milliseconds. */
        fun lengthMs(s: String?): Long? {
            val t = s?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (':' in t) {
                val parts = t.split(':').mapNotNull { it.toDoubleOrNull() }
                return (parts.fold(0.0) { acc, v -> acc * 60 + v } * 1000).toLong()
            }
            return t.toDoubleOrNull()?.let { (it * 1000).toLong() }
        }

        private fun textOf(v: Any?): String? = when (v) {
            null, JSONObject.NULL -> null
            is JSONArray -> if (v.length() > 0) v.optString(0) else null
            else -> v.toString()
        }?.trim()?.takeIf { it.isNotEmpty() }
    }
}

private fun DropItem.toJson(): JSONObject = JSONObject().apply {
    put("id", identifier); put("title", title); put("creator", creator)
    year?.let { put("year", it) }
    license?.let { put("license", it) }
}

private fun dropItemFromJson(o: JSONObject) = DropItem(
    o.getString("id"), o.getString("title"), o.getString("creator"),
    if (o.has("year")) o.getInt("year") else null, o.optString("license").takeIf { it.isNotBlank() },
)
