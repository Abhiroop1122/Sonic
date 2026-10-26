package app.runout.soulseek

import app.runout.library.FlacPolicy
import java.security.MessageDigest

enum class SoulseekConnection { OFFLINE, CONNECTING, RECONNECTING, CONNECTED, PAUSED, ERROR }
enum class SoulseekDisconnectReason(val label: String) {
    NETWORK_LOST("Connection lost. Reconnecting…"), AUTH_REJECTED("Username or password rejected"),
    DISPLACED("This Soulseek ID connected in another client. Reconnect when you are ready."),
    MANUAL("Disconnected by you"), BACKGROUND_IDLE("Paused while the app is in the background"),
    SERVICE_TIMEOUT("Android stopped background activity. Open Sonic to resume."), PROTOCOL_ERROR("Connection interrupted")
}
data class SoulseekSession(
    val state: SoulseekConnection = SoulseekConnection.OFFLINE,
    val username: String? = null,
    val error: String? = null,
    val authenticated: Boolean = state == SoulseekConnection.CONNECTED,
    val reason: SoulseekDisconnectReason? = null,
) {
    val connected: Boolean get() = state == SoulseekConnection.CONNECTED && !username.isNullOrBlank()
    val signedIn: Boolean get() = connected // Retain the strict transfer/authentication gate.
    val hasAccount: Boolean get() = authenticated && !username.isNullOrBlank()
}
enum class SearchPhase { IDLE, SEARCHING, COMPLETE, CANCELLED, FAILED, INTERRUPTED }
/** [widened]: no one answered the words as typed, so the search went out again with one word left out at a time. */
data class SoulseekSearchStatus(val id: String? = null, val query: String = "", val phase: SearchPhase = SearchPhase.IDLE, val limited: Boolean = false, val error: String? = null, val widened: Boolean = false)
/** A friend's shelf: the FLAC folders they share, from one browse of their whole share. */
data class SoulseekShelf(
    val requestId: String, val username: String, val folders: List<ShelfFolder> = emptyList(), val loading: Boolean = true, val error: String? = null,
    /** Why it failed: OFFLINE, UNREACHABLE (neither side could connect), NO_ANSWER, or null. */
    val reason: String? = null,
    /** Every file they share, FLAC or not; -1 when not known. */
    val total: Int = -1,
) {
    /** What to say instead of folders, or null while loading or when there are folders to show. */
    fun problem(signedIn: Boolean, ours: PortState): String? = when {
        loading -> null
        error != null && !signedIn -> "sign in to see their shelf."
        reason == "OFFLINE" -> "$username is offline. their shelf opens when they're back on soulseek."
        reason == "UNREACHABLE" && ours is PortState.Open ->
            "couldn't connect to $username, and they couldn't connect to you. their router lets no one in, so they need to open a port on their side."
        reason == "UNREACHABLE" ->
            "couldn't connect to $username, and they couldn't connect to you: neither of you has an open port. open yours in settings › soulseek › connection."
        error != null -> "$username didn't answer. they may be busy right now; try again in a minute."
        folders.isNotEmpty() -> null
        total == 0 -> "$username isn't sharing anything right now."
        total > 0 -> "$username shares $total files, but no flac albums."
        else -> "$username shares no flac albums right now."
    }
}
data class ShelfFolder(val directory: String, val tracks: List<SoulseekSearchResult>) {
    val title: String get() = directory.substringAfterLast('/').ifBlank { directory }
    val parent: String get() = directory.substringBeforeLast('/', "")
    val size: Long get() = tracks.sumOf { it.size }
}
data class SoulseekFolderPreview(val requestId: String, val username: String, val directory: String, val files: List<SoulseekSearchResult> = emptyList(), val loading: Boolean = true, val error: String? = null)

data class SoulseekSearchResult(
    val username: String,
    val path: String,
    val size: Long,
    val durationSec: Int? = null,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val bitrate: Int? = null,
    val freeSlots: Int = 0,
    val uploadSpeed: Long = 0,
    val queueLength: Long? = null,
) {
    private val normalizedPath = path.replace('\\', '/')
    val fileName: String = normalizedPath.substringAfterLast('/')
    val directory: String = normalizedPath.substringBeforeLast('/', "")
    val id: String = SoulseekPolicy.remoteId(username, path, size)
}

enum class SoulseekTransferState { QUEUED, DOWNLOADING, PAUSED, VALIDATING, PUBLISHING, COMPLETED, CANCELLED, FAILED }

/** Why a download failed, which decides what Runout does next. */
enum class FailureKind {
    /** The peer went offline or the connection dropped: ask them again in a while. */
    PEER_AWAY,
    /** The peer won't or can't send this file: ask someone else. */
    REFUSED,
    /** The file arrived broken: never take that copy again. */
    BROKEN,
    /** Runout couldn't write to your music folder: only you can fix that. */
    FOLDER,
}

/** What Runout is doing about a failed download by itself. */
enum class Recovery {
    /** Waiting to ask the same peer again, resuming the partial file. */
    RETRY,
    /** Searching Soulseek for the identical file from someone else. */
    SEEK,
}
data class SoulseekTransfer(
    val id: String,
    val username: String,
    val path: String,
    val size: Long,
    val bytes: Long = 0,
    val state: SoulseekTransferState = SoulseekTransferState.QUEUED,
    val queuePosition: Long? = null,
    val speed: Double = 0.0,
    val error: String? = null,
    val provider: String = "Soulseek",
    /** The album on the shelf this file belongs to. */
    val albumId: String? = null,
    val failure: FailureKind? = null,
    val recovery: Recovery? = null,
    /** When [Recovery.RETRY] asks the peer again, in epoch milliseconds. */
    val retryAt: Long? = null,
    /** The peer whose copy failed before this one was found. */
    val replaced: String? = null,
) {
    val directory: String get() = path.replace('\\', '/').substringBeforeLast('/', "")
    val fileName: String get() = path.replace('\\', '/').substringAfterLast('/')
    val progress: Float get() = if (size <= 0) 0f else (bytes.toDouble() / size).toFloat().coerceIn(0f, 1f)
}

data class SoulseekContact(val username: String, val online: Boolean = false)
data class SoulseekUpload(val username: String, val path: String, val bytes: Long, val size: Long, val state: String) {
    val fileName: String get() = path.replace('\\', '/').substringAfterLast('/')
    val progress: Float get() = if (size > 0) (bytes.toDouble() / size).toFloat().coerceIn(0f, 1f) else 0f
}
enum class SoulseekMessageState { PENDING, SENT, FAILED, RECEIVED }
data class SoulseekMessage(val id: String, val username: String, val text: String, val timestamp: Long, val outgoing: Boolean, val state: SoulseekMessageState, val read: Boolean = false)
data class SoulseekConversation(val username: String, val messages: List<SoulseekMessage>, val unread: Int)

/** Stable identities include the complete remote path, expected size and exact peer name. */
object SoulseekPolicy {
    /** How long to wait before asking a peer who went away again: 1, 5, then 15 minutes. */
    val RETRY_DELAYS_MS = listOf(60_000L, 5 * 60_000L, 15 * 60_000L)

    /** What a native download error means for the next step. Unknown errors are treated as passing. */
    fun failureKind(error: String?): FailureKind = when {
        error == null -> FailureKind.PEER_AWAY
        // A full queue at their end ("Too many files", "Too many megabytes", a user limit, a client
        // shutting down) means later, not never: ask the same peer again, as Nicotine+ re-queues.
        busy.any { error.contains(it, ignoreCase = true) } -> FailureKind.PEER_AWAY
        refusals.any { error.contains(it, ignoreCase = true) } -> FailureKind.REFUSED
        else -> FailureKind.PEER_AWAY
    }
    private val busy = listOf("too many files", "too many megabytes", "user limit", "pending shutdown")
    private val refusals = listOf("upload failed on the other side", "other side refused", "declined", "not shared", "denied", "banned", "too many", "file not found", "no such file")

    /** A file as a track key: no folder, saved-name prefix, extension, leading number or punctuation. */
    fun fileKey(path: String): String = path.replace('\\', '/').substringAfterLast('/').substringBeforeLast('.')
        .replace(savedPrefix, "").lowercase(java.util.Locale.ROOT)
        .replace(leadingNumber, "").filter { it.isLetterOrDigit() }
    private val savedPrefix = Regex("^[0-9a-f]{10} ")
    private val leadingNumber = Regex("^[\\s\\d._\\-–()\\[\\]]+(?=\\D)")

    /**
     * What to search Soulseek for to find this exact file elsewhere: its title words with the album's,
     * then its title words alone (peers name folders their own way).
     */
    fun seekQueries(path: String, albumTitle: String?): List<String> {
        fun words(s: String?) = s.orEmpty().lowercase(java.util.Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 && it !in noise }
        val stem = path.replace('\\', '/').substringAfterLast('/').substringBeforeLast('.').replace(savedPrefix, "")
        val title = words(stem.replace(leadingNumber, "")).take(6)
        val album = words(albumTitle).filterNot { it in title }.take(3)
        return listOf(title + album, title).map { it.joinToString(" ") }
            .filter { it.toByteArray(Charsets.UTF_8).size in 2..256 }.distinct()
    }
    private val noise = setOf("flac", "the", "and", "feat", "ft", "mp3", "wav", "24bit", "16bit", "khz")

    /**
     * The identical file from someone not in [skip]: the same size and the same track, the most
     * likely to start soon first (a free slot, then the shortest queue, then the fastest).
     */
    fun pickCopy(results: List<SoulseekSearchResult>, path: String, size: Long, skip: Set<String>): SoulseekSearchResult? {
        val key = fileKey(path)
        return results.filter { it.size == size && it.username !in skip && fileKey(it.path) == key && FlacPolicy.isFlacName(it.fileName) }
            .sortedWith(compareByDescending<SoulseekSearchResult> { it.freeSlots > 0 }.thenBy { it.queueLength ?: Long.MAX_VALUE }.thenByDescending { it.uploadSpeed })
            .firstOrNull()
    }

    /** The native downloader's read was cut by a signal (EINTR), as Android does to paused apps. */
    fun isInterruption(error: String?): Boolean =
        error != null && (error.contains("Interrupted system call", ignoreCase = true) || error.contains("os error 4)"))
    fun remoteId(username: String, path: String, size: Long): String = digest("$username\u0000$path\u0000$size")
    fun accountKey(owner: String, key: String): String = digest("$owner\u0000$key")
    fun albumId(username: String, directory: String): String = "soulseek-" + digest("$username\u0000$directory")
    fun digest(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val digits = "0123456789abcdef"
        return CharArray(bytes.size * 2).also { hex ->
            bytes.forEachIndexed { index, byte ->
                val unsigned = byte.toInt() and 0xff
                hex[index * 2] = digits[unsigned ushr 4]
                hex[index * 2 + 1] = digits[unsigned and 0xf]
            }
        }.concatToString()
    }
    fun validUsername(value: String): Boolean = value.isNotBlank() && value.length <= 30 && value == value.trim() && value.all { it.code in 32..126 }
    fun safeName(value: String, fallback: String = "Music"): String = value.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.').take(120).ifBlank { fallback }
    /**
     * [safeName] for a file: a long name is shortened before its extension, never through it. Cut at
     * 120 characters, a long track name lost its ".flac", and the bridge refused the whole share.
     */
    fun safeFileName(value: String, fallback: String = "Track.flac"): String {
        val at = value.lastIndexOf('.')
        if (at == 0) return fallback // only an extension: no name to keep
        val dot = at.takeIf { it > 0 && value.length - it <= 10 } ?: return safeName(value, fallback)
        val ext = safeName(value.substring(dot + 1), "").lowercase()
        if (ext.isEmpty()) return safeName(value, fallback)
        val base = safeName(value.substring(0, dot), "").take(120 - ext.length - 1)
        return if (base.isBlank()) fallback else "$base.$ext"
    }
    fun messageId(owner: String, serverId: Long): String = accountKey(owner, "incoming:$serverId")
}

/**
 * What a Dig search sends. Punctuation goes (some clients answer nothing when it's there, as
 * Nicotine+ notes), "flac" joins the words so peers send only FLAC files, and the variants leave
 * one word out at a time: the server silently drops searches for some names (any search holding
 * both "frank" and "ocean" reaches no one), and one word less gets past it. Results are still
 * kept only when the folder matches every word typed.
 */
object SearchTerms {
    private val PUNCTUATION = Regex("[!\"#$%&'()*+,\\-./:;<=>?@\\[\\\\\\]^_`{|}~–—‐’“”…]")
    /** Most results a Dig search collects. */
    const val MAX_RESULTS = 15_000

    fun words(text: String): List<String> = text.replace(PUNCTUATION, " ").split(' ').filter { it.isNotBlank() }

    private fun withFlac(words: List<String>): String =
        (if (words.any { it.equals("flac", ignoreCase = true) }) words else words + "flac").joinToString(" ")

    /** The words as typed, cleaned, with "flac". */
    fun network(text: String): String = words(text).ifEmpty { listOf(text.trim()) }.let(::withFlac)

    /** One word left out at a time, longest words kept first; none for a single word. */
    fun variants(text: String): List<String> {
        val ws = words(text).filterNot { it.equals("flac", ignoreCase = true) }
        if (ws.size < 2) return emptyList()
        return ws.indices.sortedBy { ws[it].length }.map { i -> withFlac(ws.filterIndexed { j, _ -> j != i }) }
            .distinct().filter { it != network(text) }.take(4)
    }
}
