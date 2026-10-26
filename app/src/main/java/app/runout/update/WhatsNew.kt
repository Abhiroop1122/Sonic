package app.runout.update

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What's new: the CHANGELOG.md section for the version just installed, the same words the
 * release carries. Shown once, on the first launch after an update (a fresh install meets
 * onboarding instead), and again whenever you ask in Settings › About.
 */
class WhatsNew(
    context: Context,
    private val versionName: String,
    private val versionCode: Long,
    private val changelog: () -> String? = { runCatching { context.assets.open(ASSET).bufferedReader().use { it.readText() } }.getOrNull() },
) {
    private val prefs = context.applicationContext.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val _section = MutableStateFlow<Section?>(null)
    /** The section on show, or null. */
    val section: StateFlow<Section?> = _section.asStateFlow()

    /** On launch: shows this version's notes if the last version seen here was an older one. */
    fun checkOnLaunch(onboarded: Boolean) {
        val seen = prefs.getLong(SEEN, -1L)
        when {
            seen >= versionCode -> Unit
            // A fresh install: nothing was "before", and onboarding says hello instead.
            seen < 0 && !onboarded -> markSeen()
            else -> _section.value = load() ?: run { markSeen(); null }
        }
    }

    /** Settings › About › what's new. */
    fun show() { _section.value = load() }

    fun dismiss() {
        _section.value = null
        markSeen()
    }

    private fun markSeen() { prefs.edit().putLong(SEEN, versionCode).apply() }

    private fun load(): Section? = changelog()?.let { section(it, versionName) }

    /** A line of the notes: [depth] 0 for a bullet, 1 for one under it. */
    data class Item(val text: String, val depth: Int)
    data class Section(val version: String, val date: String?, val lede: String?, val items: List<Item>)

    companion object {
        const val ASSET = "CHANGELOG.md"
        private const val SEEN = "whats_new_seen_code"

        /**
         * The section for [versionName] ("1.3.0", or a dev build "1.3.0-dev.2", which reads its
         * release's section): the "## 1.3.0 (date)" heading, its opening line and its bullets,
         * up to the next heading or rule.
         */
        fun section(changelog: String, versionName: String): Section? {
            val version = versionName.substringBefore('-').trim()
            val lines = changelog.lines()
            val start = lines.indexOfFirst { it.startsWith("## ") && it.removePrefix("## ").trim().split(' ', limit = 2)[0] == version }
            if (start < 0) return null
            val heading = lines[start].removePrefix("## ").trim()
            val date = Regex("\\((\\d{4}-\\d{2}-\\d{2})\\)").find(heading)?.groupValues?.get(1)
            val body = lines.drop(start + 1).takeWhile { !it.startsWith("## ") && it.trim() != "---" }
            val lede = body.takeWhile { !it.trimStart().startsWith("- ") }.map { it.trim() }.filter { it.isNotEmpty() }
                .joinToString(" ").ifBlank { null }
            val items = body.mapNotNull { line ->
                val trimmed = line.trimStart()
                if (!trimmed.startsWith("- ")) return@mapNotNull null
                Item(trimmed.removePrefix("- ").trim(), if (line.length - trimmed.length >= 2) 1 else 0)
            }
            return Section(version, date, lede, items)
        }
    }
}
